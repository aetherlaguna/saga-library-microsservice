# Outbox de loans

A criação de um empréstimo PENDING grava LoanRequested em outbox_events na mesma
transação PostgreSQL. LoanService faz flush do empréstimo antes do INSERT JDBC
para satisfazer a FK local. OutboxStore exige transação existente; uma falha em
qualquer escrita reverte empréstimo, livros e evento. A criação não publica AMQP.
V1/V2 permanecem imutáveis; V3 cria a outbox e seu índice de pendências.

## Publicação

Um scheduler executa lotes com até 20 eventos. Cada evento usa uma transação
independente, selecionando uma linha vencida com FOR UPDATE SKIP LOCKED. O lock
é mantido até a tentativa de publicação e atualização. Réplicas podem trabalhar
sem selecionar a mesma linha simultaneamente; isso não garante ordem global
nem ordem por agregado para futuros tipos de evento.

O publisher envia JSON persistente com mandatory, correlated publisher confirms
e returns habilitados. Só marca SENT após ack e ausência de return. Nack,
timeout e conexão indisponível não são sucesso. O identificador interno do
confirm muda por tentativa, enquanto eventId/messageId/corpo permanecem estáveis.
O JSONB normaliza a ordem e o espaçamento; não há assinatura sobre bytes JSON.

Se ocorrer falha entre publicação e commit de SENT, a transação reverte e outra
tentativa pode gerar duplicata. Não existe garantia exactly-once. Consumidores
precisam deduplicar por eventId na mesma transação do efeito de negócio.

## Estados e retries

| Estado | Comportamento |
|---|---|
| PENDING | Elegível quando next_attempt_at chega; nunca removido em falha |
| SENT | Broker confirmou publicação roteada; não significa Saga ACTIVE |
| PARKED | Limite de falhas específicas da mensagem atingido; evento preservado para intervenção |

attempts conta todas as tentativas confirmadas ou falhas registradas. Uma
transação abortada pode ter publicado sem incrementar esse contador.
message_failures conta retornos sem rota e nacks reais do broker.

Falhas de conexão, timeout, future excepcional, nack sintético do cliente e
interrupção não consomem o limite de message_failures. Permanecem PENDING com
backoff exponencial de 1s até 60s, sem esgotamento por indisponibilidade longa.
O lote para no primeiro erro de infraestrutura. O backoff não usa jitter nesta
versão; avaliar jitter quando houver várias réplicas.

Sem rota ou nack real: mesma agenda de retry, com estacionamento após 5 falhas
por padrão. Corrigir a causa antes de fazer replay. PARKED é um estado do banco,
não a DLQ do RabbitMQ: um evento sem rota ainda não chegou à fila de consumo.
last_error armazena somente código fixo, sem corpo nem mensagens de exceção.

## Configuração

| Propriedade | Padrão |
|---|---|
| library.outbox.enabled | true |
| library.outbox.poll-interval-ms | 1000 |
| library.outbox.batch-size | 20 |
| library.outbox.max-message-failures | 5 |
| library.outbox.confirm-timeout-ms | 5000 |
| library.outbox.retry-delay-ms | 1000 |
| library.outbox.max-retry-delay-ms | 60000 |
| spring.rabbitmq.connection-timeout | 3s |

O timeout transacional é 45s, maior que os limites padrão de conexão/confirm.
A publicação segura uma conexão JDBC durante I/O. Há um scheduler por instância;
limitar réplicas/workers em relação ao pool do banco. Não elevar timeouts sem
reavaliar esse orçamento. O timeout transacional não é um cancelamento absoluto
de todas as operações de rede do cliente AMQP.

A readiness inclui readinessState e db. RabbitMQ continua na saúde geral, mas
sua queda não retira a aplicação do tráfego de criação. Assim POST /loans pode
retornar 201/PENDING com evento persistido mesmo durante indisponibilidade do
broker. Liveness continua restrita ao estado da aplicação.

## Inspeção e replay

Consultar status, attempts, message_failures, next_attempt_at e last_error pelo
banco do serviço, evitando imprimir body ou dados sensíveis. Não há endpoint
administrativo de replay nesta sprint.

OutboxDispatcher.replayParked(eventId) oferece replay transacional explícito,
restrito a PARKED. Preserva o evento, o corpo e o total de attempts, reinicia
message_failures e agenda para o Clock atual. Eventos SENT não são reabertos.
O método é testado e pode ser integrado a uma operação administrativa futura.

Como alternativa operacional, após revisar a causa e o evento-alvo, um operador
pode aplicar uma atualização restrita (substituir o UUID de exemplo pelo alvo):

```sql
UPDATE outbox_events
SET status = 'PENDING', message_failures = 0,
    next_attempt_at = CURRENT_TIMESTAMP, last_error = NULL
WHERE event_id = '00000000-0000-0000-0000-000000000000'::uuid
  AND status = 'PARKED';
```

Não fazer replay em massa nem modificar payloads para contornar erros. Replays
podem duplicar entrega, portanto a deduplicação do consumidor é obrigatória.
Retenção e limpeza de SENT/PARKED ficam pendentes; nenhuma linha é apagada
automaticamente nesta versão.

## Verificações

A suíte integration usa somente PostgreSQL/RabbitMQ descartáveis. Testa:

- contrato JSON versionado, persistência, roteamento e confirmação;
- rollback do evento com o empréstimo e rollback do empréstimo se a outbox falhar;
- return mesmo com ack, nack, timeout, interrupção e falha do future;
- broker indisponível: HTTP 201 e readiness UP, evento preservado além do limite
  de falhas de mensagem, recuperação pelo scheduler de um novo contexto;
- falha no UPDATE após ack, seguida de duas entregas com o mesmo eventId;
- workers concorrentes usando SKIP LOCKED;
- mensagem persistente após restart da aplicação RabbitMQ no mesmo container;
- rejeição e limite de reentregas levando à DLQ;
- estacionamento e replay explícito sem trocar eventId.

Reinício do contexto Spring e da aplicação RabbitMQ não equivale a testar perda
definitiva de disco ou morte abrupta de todo o host. O restart físico com volume
preservado foi validado separadamente na Sprint 1.
