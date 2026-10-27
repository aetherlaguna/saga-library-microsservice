# Sprints 5 e 6 — aceite do backend

Data: 26/10/2026. Branch: sprint/5-6-recovery-observability, baseada na Sprint 4
local. Nenhum push, merge, deploy ou migration em banco de desenvolvimento.

## Entrega

- Timeout técnico configurável (padrão 5m), persistido e separado de dueAt.
- Reconciliação em batches e solicitação de liberação via outbox.
- Estado WAITING_RELEASE antes do cancelamento; conclusão só após confirmação.
- Barreira de cancelamento no acervo, inclusive antes da solicitação original.
- Liberação idempotente, estoque/outbox/barreira na mesma transação.
- Resultados atrasados não reativam empréstimos cancelados.
- Reenvio da mesma liberação após confirmação perdida; PARKED exige operação explícita.
- Migração de legados sem evento para MANUAL_REVIEW, sem bloquear novas Sagas.
- GET /loans/{id}, 404 ProblemDetail e carregamento da coleção dentro da transação.
- Logs JSON, contexto no consumidor, traces persistidos através da outbox.
- Métricas operacionais, duração após commit, dashboard e alertas locais.
- Prometheus, Grafana, Tempo 3 e Alertmanager provisionados com imagens por digest.

## Evidências

- Loans: 65 testes aprovados, zero falhas/erros/ignorados, em PostgreSQL 17 e
  RabbitMQ 4.3.6 descartáveis. Contextos de integração fechados após cada classe.
- Catalog: 48 testes aprovados, zero falhas/erros/ignorados.
- Diagnóstico Python: quatro testes aprovados.
- Após V7, suíte LoanIntegrationIT novamente aprovada (16 testes), incluindo
  upgrade de V1 com preservação dos dados e isolamento de legado em MANUAL_REVIEW.
- Infraestrutura dos testes e dados de desenvolvimento não se misturaram.

Testes adicionados: liberar reserva duas vezes sem duplicar estoque; liberar
antes de reservar; rollback por falha na outbox de confirmação; corrida entre
reserva/liberação; timeout não cancela prematuramente; resultado atrasado antes
e depois da confirmação; reenvio preservando identidade; consulta HTTP e 404.

## Compose e telemetria

`scripts/check-saga-e2e.py` executado em projeto aleatório com portas dinâmicas,
bancos novos, credenciais privadas e imagens construídas pelos Dockerfiles.

1. Criação HTTP convergiu para ACTIVE; segundo pedido sem estoque, para CANCELLED.
2. Contexto W3C de entrada preservado na outbox de loans e na outbox de catalog.
3. Consumidores de resultados pausados: catalog reservou; timeout solicitou
   liberação; estoque voltou a 1 enquanto loans permaneceu PENDING/WAITING_RELEASE.
4. Ao retomar consumidores, empréstimo convergiu para CANCELLED; reiniciar os
   serviços preservou o resultado. Portas dinâmicas foram redescobertas.
5. Prometheus coletou ambos os serviços; Grafana e Alertmanager saudáveis;
   trace consultável no Tempo pelo mesmo traceId.
6. Projeto descartável removido, hash do .env de desenvolvimento preservado.

A primeira execução detectou incompatibilidade da configuração de retenção com
Tempo 3 e uma falha do teste ao reutilizar portas dinâmicas após restart. Ambos
corrigidos; execução posterior completa aprovada. Métricas OTLP push desativadas
para evitar exportação paralela ao receptor padrão; Prometheus é o backend de métricas.

## Limites explícitos

Esta entrega conclui a fatia de backend das Sprints 5 e 6 sob as premissas
registradas. Frontend Angular, cadastro completo de membros/autores/bibliotecas,
autenticação, devolução, renovação e multas continuam nas trilhas futuras e
regras de negócio pendentes. Não foram inventadas políticas financeiras.

Alertas chegam ao Alertmanager local; não há notificações externas configuradas.
O CI existente mantém testes Java/Python; o aceite completo é reproduzível
localmente pelo script. Não foi adicionado upload de logs de aceite ao GitHub:
a revisão automática bloqueou essa publicação por possível exposição de dados.
Nenhum log foi enviado pelo agente e não houve push desta branch.

PARKED, DLQ por contrato inválido e MANUAL_REVIEW exigem investigação. Uma falha
permanente não é mascarada por retries infinitos. A stack é local, de nó único;
não representa alta disponibilidade de produção, política de backups ou retenção
de dados de negócio. Logs ficam no stdout; não foi adicionado armazenamento Loki.
