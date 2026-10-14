# Contratos de eventos — versão 1

Contrato inicial da Sprint 2. Implementado: LoanRequested. Os eventos de resultado
de catalog e de compensação continuam previstos para sprints posteriores.
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

Nesta fatia, loans declara a topologia inicial da assinatura para que publicações
confirmadas tenham uma fila durável antes de existir o consumidor. Catalog deverá
reutilizar exatamente o mesmo contrato quando for criado. Réplicas do mesmo
consumidor compartilham fila; outra assinatura independente precisa de sua fila.
Não há alternate-exchange: mensagem sem rota precisa retornar ao publisher.

A fila fonte define x-delivery-limit=3, x-dead-letter-strategy=at-least-once,
x-overflow=reject-publish e os argumentos de DLX/routing key. Isso impede um
ciclo ilimitado de falhas de entrega e mantém mensagens em dead-letter até sua
confirmação pelo destino. A DLQ não reencaminha automaticamente para a origem.
Não há consumidor de negócio nem deduplicação ainda.

No RabbitMQ 4.3, basic.nack com requeue não conta como entrega falhada para esse
limite. O consumidor futuro deve usar basic.reject com requeue=true para falha
retentável e requeue=false para falha permanente, após rollback local. A confirmação
positiva deve ocorrer somente após commit. O limite do broker e sua DLQ não
substituem inbox/deduplicação nem reconciliação da Saga.

Os argumentos de fila são parte do contrato: não alterar tipo ou argumentos
incompatíveis em uma fila existente nem apagá-la para resolver divergência.
Uma evolução deve planejar migração de mensagens. Policies permitem ajustes
operacionais em vários argumentos, mas não substituem o tipo quorum da fila.

## Limites da entrega

Publicação é at-least-once: confirmações não tornam PostgreSQL e RabbitMQ uma
única transação. A janela entre confirmar e marcar SENT permite duplicação com
o mesmo eventId. A inbox de catalog, na Sprint 3, deverá impedir efeitos repetidos.
Um confirm comprova aceitação pelo broker, não processamento pelo catalog.

A topologia é durável, mas o Compose tem um único broker; quorum em um único nó
não fornece alta disponibilidade contra perda definitiva desse nó/disco. Não
apagar volumes. Capacidade/limites de armazenamento e políticas de retenção
precisam ser dimensionados com observabilidade.

Referências oficiais: [confirms e returns no Spring AMQP](https://docs.spring.io/spring-amqp/reference/amqp/template.html),
[CorrelationData](https://docs.spring.io/spring-amqp/docs/current/api/org/springframework/amqp/rabbit/connection/CorrelationData.html)
e [filas quorum e delivery-limit](https://www.rabbitmq.com/docs/quorum-queues).
