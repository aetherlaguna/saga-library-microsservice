# Consumo de resultados do acervo — Sprint 4

Loans consome `loans.catalog-results.v1`, com os mesmos argumentos quorum/DLQ
já declarados por catalog. `BooksReserved` ativa o empréstimo pendente;
`BooksReservationRejected` cancela a solicitação pendente. As regras de estoque,
biblioteca única e quantidade por título permanecem as da Sprint 3.

## Validação e transação

O corpo JSON é a fonte de verdade. O codec exige envelope v1, causationId,
identificadores e payload válido; o listener exige routing key correspondente.
Campos desconhecidos são tolerados e a ordem dos livros não altera o fingerprint.
A causação/correlação precisa corresponder ao LoanRequested durável na outbox
local. Uma confirmação precisa conter exatamente os livros solicitados; a
recusa precisa indicar um subconjunto não vazio, com BOOKS_UNAVAILABLE.

V4 adiciona `catalog_result_inbox`, com unicidade por consumidor/eventId, e
`catalog_result_decisions`, com decisão única por loanId. A decisão preserva o
identificador de reserva ou motivo de recusa para futura recuperação. Inbox,
decisão e estado do empréstimo são confirmados na mesma transação.

- Mesmo eventId e mesmo conteúdo: DUPLICATE, sem nova alteração.
- Mesmo eventId e conteúdo divergente: erro permanente EVENT_ID_REUSED.
- Novo eventId e mesmo resultado: inbox registrada, sem repetir efeito.
- Resultados contraditórios ou reservas diferentes para o mesmo loanId: erro
  permanente CONFLICTING_RESULT; nunca sobrescrever a primeira decisão.
- Falha local: rollback de inbox/decisão/estado, permitindo reentrega.

A atualização JPA usa @Version. Conflitos otimistas são tentados até três vezes,
sempre em nova transação com releitura. Após o limite, a falha retorna ao listener.

## Entrega e operação

O listener confirma basicAck apenas quando o serviço retorna após commit.
Protocolo inválido recebe basicReject(requeue=false); falha técnica recebe
basicReject(requeue=true), com backoff e delivery-limit da fila. Não usar nack
como substituto para o limite de entregas do RabbitMQ 4.3. A DLQ não faz replay
automático. Corrigir a causa antes de reenviar uma mensagem, preservando corpo,
eventId, routing key e persistência. Nunca apagar fila/volume para limpar erros.

Configuração: loans.consumer.enabled (true), concurrency (2), retry-backoff-ms
(1000, intervalo permitido 100–10000). Prefetch 1; retry de listener desativado.
`loans.consumer.enabled=false` permite interromper consumo, por exemplo durante
investigação, mantendo eventos na fila. Publisher de outbox é independente.

O log de resultado é emitido após o commit e contém loanId/eventId/outcome,
sem payload nem dados pessoais. Isso ainda não substitui tracing distribuído.

## Limites

Esta sprint não implementa timeout, devolução ou compensação. Resultado
contraditório/estado inesperado vai à DLQ para investigação, não dispara
liberação automaticamente. A Sprint 5 deve recuperar reservas tardias e não
concluir cancelamentos técnicos antes de confirmar a liberação remota.
Não há validação de membro/autorização nova nem mudança de contrato HTTP.
