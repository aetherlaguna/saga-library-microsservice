# Recuperação da Saga e compensação

## Premissas técnicas

Timeout configurável por `loans.saga.timeout` (padrão 5 minutos). É um prazo de
coordenação distribuída, não o prazo de devolução. Não cria multa, renovação,
limite por membro ou política de elegibilidade. Biblioteca única, estoque por
título e uma unidade por título permanecem as premissas da entrega.

`loan_sagas` separa estado técnico de negócio:

- WAITING: aguardando reserva até deadline persistido.
- WAITING_RELEASE: timeout ocorrido e liberação solicitada; Loan continua PENDING.
- COMPLETED: decisão normal concluída ou liberação confirmada.
- MANUAL_REVIEW: registro legado sem LoanRequested; exige revisão explícita.

Não concluir o cancelamento técnico sem confirmação do acervo. Uma indisponibilidade
prolongada pode manter WAITING_RELEASE; ela fica visível nas métricas/alertas.
Não existe garantia de conclusão enquanto infraestrutura ou processamento
continuarem indisponíveis. O prazo de devolução não dispara a compensação.

## Eventos adicionais v1

`BooksReleaseRequested`, routing key `loans.release-requested.v1`:

- envelope v1 com loanId=sagaId=correlationId;
- causationId referencia o LoanRequested original;
- payload: `{ "requestEventId": "UUID do LoanRequested" }`.

`BooksReleased`, routing key `catalog.books-released.v1`:

- novo eventId, loanId/sagaId/correlationId preservados;
- causationId referencia BooksReleaseRequested;
- mesmo payload requestEventId.

Filas `catalog.release-requests.v1` e `loans.release-results.v1`, cada uma com
`.dlq`, são quorum/duráveis, com o mesmo contrato delivery-limit e DLX das filas
anteriores. Ambos os serviços declaram a topologia com argumentos idênticos.

## Consistência e reordenação

O reconciliador de loans seleciona até 50 empréstimos vencidos com bloqueio de
linha/SKIP LOCKED, grava a solicitação na outbox e muda o estado técnico na mesma
transação. O consumidor de resultados bloqueia a mesma linha antes de decidir.
Um BooksReserved que chega após o prazo não ativa o empréstimo; inicia/aguarda
liberação. Um resultado atrasado após a confirmação não reativa CANCELLED.

Catalog serializa reserva/liberação por loanId usando advisory lock transacional.
A liberação registra uma barreira persistida, restaura estoque reservado uma vez
e grava confirmação na outbox na mesma transação. Se a liberação chegar antes
do pedido original, a barreira impede a reserva posterior. A confirmação pode
existir sem uma linha em reservations; por isso a outbox de catalog deixa de ter
FK para essa tabela em V3. Isso não cria acesso a banco remoto.

Repetição da solicitação de liberação preserva identidade/fingerprint e não soma
estoque novamente. loans possui inbox de confirmação persistida. EventId
reutilizado com conteúdo divergente é rejeitado. A reserva passa a RELEASED;
a associação histórica dos livros é preservada.

## Recuperação operacional

A cada `loans.saga.recovery-interval-ms` (padrão 5000), o reconciliador verifica
prazos e republica o mesmo evento de liberação se ele foi enviado há mais de
60 segundos sem conclusão. Catalog pode republicar sua mesma confirmação após
reentrega da solicitação. Nada é excluído automaticamente.

Eventos PARKED e falhas permanentes na DLQ exigem correção e replay explícito.
Não desestacionar automaticamente um evento inválido. Inspecionar contratos,
causação, credenciais e logs; preservar eventId, corpo e routing key no replay.
O alerta identifica operações vencidas, mas não corrige contratos incompatíveis.

`loans.saga.recovery-enabled=false` desativa o agendamento; o estado continua
persistido para retomada. Reiniciar serviços não reinicia deadlines nem elimina
barreiras/inboxes. Não remover volumes para resolver erros.

## Migrations

Loans V5 adiciona estado técnico e inbox de confirmação, preservando dados e
inicializando empréstimos antigos PENDING como WAITING. Em uma atualização, os
PENDING antigos podem estar vencidos e gerar compensação: inspecionar o backlog
e usar recovery-enabled=false durante uma manutenção planejada, se necessário.
Catalog V3 adiciona barreiras e RELEASED. Migrations anteriores são imutáveis.

## Consulta

`GET /loans/{id}` retorna a representação do empréstimo e 404 ProblemDetail para
ID inexistente. Estado técnico pode ser investigado em loan_sagas no banco de
loans e nas métricas; nenhuma API administrativa de mutação foi aberta.

V7 isola empréstimos antigos que nunca tiveram LoanRequested em MANUAL_REVIEW.
Eles não bloqueiam o batch de recuperação nem são cancelados/reservados por
suposição. Permanecem preservados e visíveis no alerta de Sagas vencidas.
