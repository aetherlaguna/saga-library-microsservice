# Sprint 3 — evidências de validação

Data: 14/10/2026. Branch: sprint/3-catalog. Premissa mínima: biblioteca única,
quantidade por título, uma cópia de cada título por empréstimo; decisão de
múltiplas unidades/exemplares permanece aberta.

## Testes automatizados

Executados com Java 25 e PostgreSQL 17/RabbitMQ 4.3.6 em containers descartáveis.
O perfil integration falha se Docker estiver indisponível, sem ignorar testes.

- loans: suíte de regressão aprovada, 42 testes, sem falhas ou testes ignorados.
- catalog: 44 testes aprovados, sem falhas ou testes ignorados; validação de
  envelope/payload e publisher, API e persistência,
  estoque total/disponível e recusa de título inexistente/indisponível.
- Upgrade de V1 para V2 preservou o título e o estoque existentes em schema isolado.
- Fingerprint preservado ao inverter a ordem dos títulos no mesmo evento.
- Diagnóstico Python: 4 testes aprovados.
- Pedidos concorrentes por dois títulos com uma cópia cada: uma reserva e uma
  recusa, sem exceder estoque, mesmo com ordem inversa dos IDs na entrada.
- Mesmo eventId simultâneo e mesmo loanId com novos eventIds: uma reserva e uma
  outbox; conteúdo divergente rejeitado sem confirmar nova inbox.
- Falha da outbox: inbox, reserva e estoque revertidos juntos.
- No momento de basicAck, inbox/reserva/outbox já visíveis fora da transação;
  falha do primeiro ack seguida de reentrega não repetiu efeito.
- Erro antes do commit causou reentrega após backoff e posterior commit completo.
- PostgreSQL pausado por 2,5 segundos: recuperação sem mandar o pedido à DLQ.
- JSON inválido chegou à DLQ sem criar reserva ou inbox.
- Broker indisponível preservou resultado PENDING na outbox; recuperação enviou
  o mesmo evento com correlação e causação corretas.

## Integração por Docker Compose

Projeto aleatório isolado, com portas dinâmicas, volumes novos e credenciais
privadas de teste. As imagens reais dos dois Dockerfiles foram construídas.
Um usuário AMQP próprio de catalog foi criado somente nesse broker descartável.

| Cenário | Resultado |
|---|---|
| Ambos os serviços iniciados | Health de loans e catalog UP |
| Declaração da fila pelos dois serviços | Sem PRECONDITION_FAILED |
| Criar título com uma cópia | HTTP 201, estoque disponível 1 |
| Primeiro POST /loans | PENDING criado, catalog reservou, estoque passou a 0 |
| Segundo POST /loans para o mesmo título | Catalog recusou por indisponibilidade |
| Outboxes | Dois pedidos e dois resultados SENT |
| Inbox de catalog | Dois eventos processados |
| Associações de reserva | Uma associação; sem reserva parcial ou excedente |
| Fila de solicitações | Drenada pelo consumidor |
| Fila de resultados de loans | Resultados persistidos aguardando Sprint 4 |
| Estado dos empréstimos | Continuaram PENDING, conforme escopo da Sprint 3 |
| DLQ de solicitações | Vazia no fluxo válido |

As estatísticas de fila foram verificadas com espera limitada para atualização.
O projeto descartável e somente seus volumes foram removidos ao final. Hash do
`.env` e IDs dos containers de desenvolvimento conferidos antes/depois, inalterados.
Nenhuma migration foi aplicada aos bancos de desenvolvimento.

## Limites

Não valida reserva entre múltiplas bibliotecas, elegibilidade, compensação,
liberação de estoque, ativação/cancelamento de loans, timeout ou reconciliação.
Uma queda prolongada do banco pode esgotar o delivery-limit e exigir replay da
DLQ após correção. O procedimento está documentado em CATALOG.md, sem execução
automática sobre dados de desenvolvimento.

O CI remoto da Sprint 3 depende do envio dos commits e abertura da PR. As PRs
anteriores foram integradas com checks aprovados.
