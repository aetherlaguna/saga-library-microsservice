# Catalog — títulos, estoque e reserva

Entrega da Sprint 3. Serviço independente em `catalog/`, pacote
`com.library.catalog`, Java 25 e Spring Boot 4.1.1. PostgreSQL 17, migrations
Flyway e transações JDBC locais. Não usa entidades JPA de loans nem acessa seu
banco. O banco de catalog possui role, credenciais e volume próprios.

## Premissa desta fatia

Enquanto a decisão de negócio de estoque continua aberta, a implementação usa
**uma biblioteca única, com quantidade de cópias por título**. É a premissa mínima
comunicada ao usuário, não uma decisão definitiva de múltiplas bibliotecas.
O título tem UUID; exemplares físicos não são identificados separadamente.
O vínculo à biblioteca é o escopo único deste catálogo, sem seleção de unidade.

Cada empréstimo reserva uma cópia de cada título distinto. Todos precisam estar
disponíveis; não há reserva parcial. Não foram criadas regras de elegibilidade,
limites por membro, renovação, prazo máximo ou multas. O evento v1 não possui
libraryId; selecionar unidades ou exemplares exige nova decisão e evolução
explícita do contrato antes de ampliar o domínio.

## API local

Com o serviço configurado, a porta de desenvolvimento é `127.0.0.1:8081`.

```sh
curl -i http://localhost:8081/books \
  -H 'Content-Type: application/json' \
  -d '{"title":"Domain Driven Design","totalCopies":2}'

# Usar o id retornado na criação.
curl -i http://localhost:8081/books/UUID_DO_TITULO
```

POST /books gera UUID no servidor e retorna 201 com id, title, totalCopies e
availableCopies. O estoque inicial pode ser zero, nunca negativo. Título é
obrigatório, com até 255 caracteres. GET /books/{id} retorna o estado atual;
ID ausente retorna 404 por ProblemDetail e UUID inválido retorna 400.
Entradas inválidas de criação retornam 400, sem criar estoque.
Não há endpoint de alteração de estoque ou exclusão nesta sprint.

## Consumo e transação

O consumidor de catalog recebe LoanRequested v1 da fila durável
`catalog.loan-requests.v1`. Corpo é a fonte de verdade; não depende de headers
Java ou de classes compartilhadas. Valida envelope, UUIDs, versão, datas e
livros distintos, não vazios e sem IDs nulos. Campos adicionais desconhecidos
são tolerados.

O listener chama ReservationService através de um proxy transacional separado:

1. Insere inbox por consumer/eventId. Uma inserção concorrente aguarda o commit
   ou rollback da primeira transação; fingerprint divergente é erro permanente.
2. Carrega os títulos com `ORDER BY id FOR UPDATE`, usando a ordem UUID do
   PostgreSQL. Verifica todos antes de descontar qualquer cópia.
3. Decide RESERVED ou REJECTED e tenta inserir uma reserva única por loanId.
   Em conflito, compara o hash do pedido: conteúdo igual vira no-op; divergente
   rejeita a mensagem. Nenhum estado provisório é persistido como resultado.
4. No sucesso, desconta uma cópia por título e grava as associações locais.
   Na recusa, não grava associações, inclusive para títulos inexistentes.
5. Grava BooksReserved ou BooksReservationRejected na outbox local e confirma
   a transação. Qualquer falha reverte inbox, reserva, estoque e evento.
6. Somente após o retorno do commit, o listener envia basicAck.

A restrição do banco garante `0 <= available_copies <= total_copies`.
reservation_books só contém reservas bem-sucedidas. Livro inexistente ou sem
cópias gera recusa `BOOKS_UNAVAILABLE`, não falha de FK nem DLQ de negócio.

| Reentrega | Resultado |
|---|---|
| Mesmo eventId e mesmo envelope normalizado | Ack, sem repetir efeito |
| Mesmo eventId com conteúdo semântico diferente | Rejeição permanente para DLQ |
| Novo eventId, mesmo loanId e mesmo pedido | Inbox adicional, sem nova reserva/outbox |
| Novo eventId, mesmo loanId e pedido diferente | Rejeição permanente; nova inbox revertida |

Hashes usam campos canônicos e livros em ordem textual UUID; ordem recebida,
espaçamento JSON e campos desconhecidos não criam falsa divergência. Falha do
ack após commit permite reentrega: a inbox impede repetir o desconto de estoque.

## Falhas e DLQ

O listener usa ack MANUAL, prefetch=1 e concorrência 2. O retry automático do
container está desativado; não há transação AMQP fingindo atomicidade com JDBC.
JSON/versão/envelope inválido ou reutilização conflitante de ID usam
basicReject(requeue=false). Falhas técnicas aguardam
`catalog.consumer.retry-backoff-ms` (1000ms por padrão), depois usam
basicReject(requeue=true). O quorum delivery-limit=3 limita falhas sucessivas.
No RabbitMQ 4.3, basic.nack não oferece a mesma contagem de falhas; o consumidor
usa basic.reject. O backoff evita esgotar imediatamente o limite em uma queda curta.

Uma indisponibilidade longa pode enviar solicitações válidas à DLQ. Elas
permanecem recuperáveis, sem reserva/inbox parcialmente confirmadas. A mensagem
na DLQ não cancela automaticamente loans: essa integração é da Sprint 4 e a
reconciliação/timeout é da Sprint 5.

### Replay operacional da DLQ

Depois de corrigir a causa, identificar a mensagem-alvo e autorizar a operação:

1. Consumir a mensagem da DLQ sem auto-ack, mantendo-a não confirmada.
2. Republicar o corpo intacto em library.events / loans.requested.v1, com o mesmo
   eventId/messageId, delivery mode persistente e publisher confirms/mandatory.
   Não transportar headers de histórico de entrega como x-death/x-delivery-count.
3. Confirmar a mensagem original da DLQ somente após ack positivo da publicação
   e ausência de return. Se falhar, deixar a original para reentrega.
4. Conferir inbox, reserva e outbox. Uma duplicata não deve descontar estoque.

Não usar "Get messages" com confirmação automática para mover mensagens: a
remoção anterior à confirmação do novo envio cria uma janela de perda. Não há
endpoint ou worker de replay automático nesta sprint. Não editar um evento
inválido e reutilizar seu eventId como se fosse o mesmo evento.

## Resultado e outbox

Catalog declara a mesma fila de solicitações já criada por loans, com argumentos
idênticos. Também declara `loans.catalog-results.v1` e sua DLQ para que os
resultados tenham destino durável antes de existir o consumidor da Sprint 4.
As duas assinaturas usam quorum, limite de falhas e dead-letter at-least-once.

BooksReserved usa `catalog.books-reserved.v1`; BooksReservationRejected usa
`catalog.reservation-rejected.v1`. SagaId/correlationId são preservados da
solicitação, causationId referencia o eventId recebido. O resultado tem um novo
eventId gerado uma única vez e appId= catalog. Payloads e topologia estão em
[EVENTS.md](EVENTS.md).

A outbox própria mantém o comportamento da Sprint 2: confirms, returns,
SKIP LOCKED, backoff e PARKED/replay explícito. As correlações são persistidas,
não recalculadas como loanId. Código do adaptador é local ao serviço; não há
módulo compartilhado de entidades nem dependência de build de loans.

Reserva não significa empréstimo ACTIVE. Até as próximas sprints, o resultado
fica na fila de loans, que continua PENDING. Reservas bem-sucedidas descontam
estoque até futura compensação/devolução; não há liberação automática nesta
entrega. Também não há backfill de eventos para empréstimos antigos criados
antes da outbox de loans.

## Executar com Docker Compose

`compose.catalog.yaml` complementa o arquivo base. Comandos antigos que incluem
somente compose.yaml continuam funcionando sem exigir credenciais de catalog.
RabbitMQ foi fixado em 4.3.6-management, a mesma versão verificada no broker
local; esta entrega não recriou nem atualizou o ambiente de desenvolvimento.

Adicionar ao `.env` existente, sem sobrescrever as configurações anteriores:

```dotenv
CATALOG_DB_PASSWORD=<senha exclusiva do banco catalog>
CATALOG_RABBITMQ_USER=catalog_app
CATALOG_RABBITMQ_PASSWORD=<senha exclusiva do usuário catalog_app>
```

O usuário AMQP precisa existir antes do primeiro início. Em uma configuração
local autorizada, criar interativamente, sem incluir a senha na linha de comando:

```sh
docker compose exec rabbitmq rabbitmqctl add_user catalog_app
catalog_resources='^(library[.]events([.]dlx)?|catalog[.]loan-requests[.]v1([.]dlq)?|loans[.]catalog-results[.]v1([.]dlq)?)$'
docker compose exec rabbitmq rabbitmqctl set_permissions -p / catalog_app \
  "$catalog_resources" "$catalog_resources" "$catalog_resources"
```

Usar no `.env` a senha definida no prompt. Não reutilizar a conta de loans nem
adicionar tag de administrador para a aplicação. Configurar RABBITMQ_DEFAULT_*
não cria novos usuários em um volume já inicializado.

```sh
docker compose -f compose.yaml -f compose.catalog.yaml config --quiet
docker compose -f compose.yaml -f compose.catalog.yaml up -d --build
curl --fail http://localhost:8081/actuator/health
```

Cada serviço tem seu banco e volume. Não apagar volumes ou alterar migrations
aplicadas. Credenciais e contas de desenvolvimento não foram criadas/alteradas
para esta entrega; o teste completo usou somente infraestrutura descartável.

## Verificação

No diretório catalog, usar o Maven Wrapper com Java 25:

```sh
./mvnw clean test
./mvnw -Pintegration clean verify
```

A suíte integration usa PostgreSQL/RabbitMQ descartáveis e testa estoque,
concorrência, deduplicação, rollback, ack após commit, falha de ack e reentrega,
DLQ, queda curta do banco e indisponibilidade do broker. O CI também executa
catalog, preservando o check existente de loans e publicando os dois relatórios.

Fontes: [acknowledgements no Spring AMQP](https://docs.spring.io/spring-amqp/reference/amqp/containerAttributes.html),
[limite de entregas quorum](https://www.rabbitmq.com/docs/quorum-queues)
e [criação interativa de usuário RabbitMQ](https://www.rabbitmq.com/docs/access-control).
