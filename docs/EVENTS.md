# Contratos de eventos — versão 1

Contratos das Sprints 2 e 3. Implementados: LoanRequested, BooksReserved e
BooksReservationRejected. Eventos de compensação continuam nas próximas sprints.
Não compartilhar entidades JPA nem acessar bancos de outros serviços.

## Envelope de LoanRequested

```json
{
  "eventId": "4dbeef6f-6736-480e-a569-8ee59b2eb9db",
  "eventType": "LoanRequested",
  "schemaVersion": 1,
  "occurredAt": "2026-10-14T12:00:00Z",
  "loanId": "3615f22c-1155-41d7-8354-08691e92d524",
  "sagaId": "3615f22c-1155-41d7-8354-08691e92d524",
  "correlationId": "3615f22c-1155-41d7-8354-08691e92d524",
  "causationId": null,
  "payload": {
    "memberId": "31fb9a91-b5e9-47ed-9a5e-f5167146fc7c",
    "bookIds": ["3c76da82-00e0-4dad-9175-5842a944e4e3"],
    "dueAt": "2026-10-21T12:00:00Z"
  }
}
```

- `eventId`: UUID gerado uma vez, preservado em retries e replay.
- `eventType` e `schemaVersion`: nome e versão do contrato; a routing key acompanha v1.
- `occurredAt`: instante UTC da criação do empréstimo, pelo Clock da aplicação.
- `loanId`: referência ao empréstimo; sagaId e correlationId usam esse mesmo UUID
  nesta primeira versão. CorrelationId externo poderá ser introduzido futuramente.
- `causationId`: null no evento inicial; eventos derivados deverão referenciar o
  evento causador quando seus contratos forem implementados.
- `bookIds`: títulos distintos, sem IDs nulos, ordenados na serialização; quantidade
  de cada título igual a 1. Não representa existência, autorização ou disponibilidade.
- `dueAt`: instante UTC; nenhuma política adicional de prazo foi inventada.

Não incluir dados pessoais nem registeredBy nesse evento. A fonte de verdade é
o corpo JSON. Consumidores devem tolerar campos adicionais desconhecidos;
mudanças incompatíveis exigem nova versão e estratégia de migração.

Propriedades AMQP: JSON UTF-8, mensagem persistente, messageId=eventId,
correlationId=loanId, type=LoanRequested, appId=loans; headers schemaVersion e
sagaId. A correlação interna de publisher confirm é exclusiva por tentativa,
separada do eventId. Propagação de tracing será concluída na sprint de observabilidade.

## Topologia inicial

| Recurso | Nome / configuração |
|---|---|
| Exchange de eventos | library.events, topic, durável |
| Routing key inicial | loans.requested.v1 |
| Fila da assinatura de catalog | catalog.loan-requests.v1, quorum, durável |
| Exchange de dead letters | library.events.dlx, topic, durável |
| Routing key de dead letter | catalog.loan-requests.v1.dead |
| DLQ | catalog.loan-requests.v1.dlq, quorum, durável |

Loans e catalog declaram a mesma assinatura, com argumentos idênticos,
verificados em integração com os dois serviços no mesmo broker. Loans conserva
a declaração para publicar mesmo com catalog fora do ar. Réplicas do mesmo
consumidor compartilham fila; outra assinatura independente precisa de sua fila.
Não há alternate-exchange: mensagem sem rota precisa retornar ao publisher.

A fila fonte define x-delivery-limit=3, x-dead-letter-strategy=at-least-once,
x-overflow=reject-publish e os argumentos de DLX/routing key. Isso impede um
ciclo ilimitado de falhas de entrega e mantém mensagens em dead-letter até sua
confirmação pelo destino. A DLQ não reencaminha automaticamente para a origem.
Catalog possui consumidor e inbox transacional. O consumidor de resultados em
loans permanece na Sprint 4.

No RabbitMQ 4.3, basic.nack com requeue não conta como entrega falhada para esse
limite. O consumidor futuro deve usar basic.reject com requeue=true para falha
retentável e requeue=false para falha permanente, após rollback local. A confirmação
positiva deve ocorrer somente após commit. O limite do broker e sua DLQ não
substituem inbox/deduplicação nem reconciliação da Saga.

Os argumentos de fila são parte do contrato: não alterar tipo ou argumentos
incompatíveis em uma fila existente nem apagá-la para resolver divergência.
Uma evolução deve planejar migração de mensagens. Policies permitem ajustes
operacionais em vários argumentos, mas não substituem o tipo quorum da fila.

## Resultados publicados por catalog

Os resultados usam o mesmo envelope v1, com novo eventId, loanId da solicitação,
sagaId/correlationId preservados e causationId igual ao eventId de LoanRequested.
OccurredAt vem do Clock UTC de catalog. MessageId é o novo eventId e appId é catalog.

BooksReserved, routing key `catalog.books-reserved.v1`:

```json
{
  "reservationId": "8f385191-579f-42c3-b482-a2a31cb87a36",
  "bookIds": ["3c76da82-00e0-4dad-9175-5842a944e4e3"]
}
```

BooksReservationRejected, routing key `catalog.reservation-rejected.v1`:

```json
{
  "reasonCode": "BOOKS_UNAVAILABLE",
  "unavailableBookIds": ["3c76da82-00e0-4dad-9175-5842a944e4e3"]
}
```

Os JSONs acima representam apenas payload; os demais campos ficam no envelope.
Título inexistente e sem estoque são recusas de negócio com a mesma razão estável.
Não há reserva parcial nem dados pessoais no resultado. Reutilização conflitante
de eventId/loanId é erro de protocolo enviado à DLQ, sem um segundo resultado.

A fila `loans.catalog-results.v1` liga as duas routing keys ao exchange library.events.
É quorum/durável, com x-delivery-limit=3, x-dead-letter-strategy=at-least-once,
x-overflow=reject-publish, DLX library.events.dlx e key loans.catalog-results.v1.dead.
A DLQ `loans.catalog-results.v1.dlq` também é quorum/durável. Catalog declara essa
assinatura inicialmente; loans deverá manter os mesmos argumentos na Sprint 4.

Premissa de estoque da Sprint 3: uma biblioteca única, quantidade por título,
sem identificação de exemplares. LibraryId não foi adicionado silenciosamente ao
v1. Múltiplas unidades/exemplares exigem decisão e evolução explícita de versão.
Detalhes de reserva, inbox, conflitos e replay: [CATALOG.md](CATALOG.md).

## Limites da entrega

Publicação é at-least-once: confirmações não tornam PostgreSQL e RabbitMQ uma
única transação. A janela entre confirmar e marcar SENT permite duplicação com
o mesmo eventId. A inbox e a reserva única por loanId em catalog impedem repetir o desconto
de estoque. A deduplicação de resultados em loans permanece na Sprint 4.
Um confirm comprova aceitação pelo broker, não processamento pelo catalog.

A topologia é durável, mas o Compose tem um único broker; quorum em um único nó
não fornece alta disponibilidade contra perda definitiva desse nó/disco. Não
apagar volumes. Capacidade/limites de armazenamento e políticas de retenção
precisam ser dimensionados com observabilidade.

Referências oficiais: [confirms e returns no Spring AMQP](https://docs.spring.io/spring-amqp/reference/amqp/template.html),
[CorrelationData](https://docs.spring.io/spring-amqp/docs/current/api/org/springframework/amqp/rabbit/connection/CorrelationData.html)
e [filas quorum e delivery-limit](https://www.rabbitmq.com/docs/quorum-queues).

## Consumo em loans (Sprint 4)

Loans também declara a assinatura de resultados com argumentos idênticos aos de
catalog. Inbox/decisão local e mudança de estado são transacionais; confirmação
AMQP ocorre após commit. Ver [consumo de resultados](LOAN-RESULTS.md).
As referências anteriores à Sprint 4 como futura descrevem o limite da Sprint 3.

## Liberação e timeout (Sprint 5)

BooksReleaseRequested e BooksReleased, filas e regras de reordenação estão em
[recuperação da Saga](RECOVERY.md). O contexto W3C é persistido na outbox sem
alterar o envelope v1; ver [observabilidade](OBSERVABILITY.md).
