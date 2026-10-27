# Planejamento das sprints — Biblioteca

Plano registrado em 14/10/2026 UTC. As sprints representam entregas, sem duração
estimada até definirmos disponibilidade e capacidade. Este documento não
autoriza implementar automaticamente todo o backlog.

## Como acompanhar

- Implementar uma sprint por vez, conforme autorização do usuário.
- Marcar `[x]` somente após implementar e verificar o item.
- Registrar decisões de negócio antes de implementar regras dependentes delas.
- Ao concluir, preencher evidências, commit de entrega e pendências; atualizar
  este documento em um commit de documentação.
- Separar commits por funcionalidade, com mensagens semânticas e sem coautoria.
- Preservar migrations aplicadas, volumes, dados e configurações locais.

Cada sprint terá uma referência Git registrada ao final. Se quisermos fazer
checkout de entregas completas, podemos criar tags `sprint-1`, `sprint-2` etc.
no commit de encerramento, quando isso for solicitado. Nenhuma tag de sprint
foi criada apenas pela publicação deste plano. Para inspecionar uma entrega
marcada: `git switch --detach sprint-1`. Para voltar: `git switch main`.
Antes de trocar referências, preserve alterações locais e pare os serviços;
checkout de código não reverte migrations nem torna um banco atual compatível
com versões antigas. Use infraestrutura isolada para executar entregas antigas.

## Base já implementada

- [x] Serviço loans com criação HTTP de empréstimo PENDING e PostgreSQL.
- [x] Validação, DTOs, ProblemDetail e controle otimista com migration V2.
- [x] Configuração AMQP por ambiente e identidade explícita do broker.
- [x] Actuator com detalhes no profile local e probes separados.
- [x] Testes de domínio, HTTP, concorrência, migrations e conexão AMQP isolada.
- [x] Workflow GitHub Actions para push e pull request.

Evidência local atual: 65 testes Java de loans, 48 de catalog e 4 Python
aprovados, sem testes ignorados.
Commits iniciais da suíte e CI: `cb41d04` e `adcd7d2`. O usuário confirmou
o CI aprovado no GitHub. A conexão AMQP local também foi validada pelo script.
A Sprint 2 adicionou LoanRequested e outbox de loans. A Sprint 3 adicionou
catalog, reserva, inbox e outbox de resultados. Consumo de resultados em loans
e Saga distribuída completa continuam nas próximas sprints.

## Sprint 1 — Consolidar a base

Estado: concluída, integrada pela PR #1 em main (`df142e4`), com CI aprovado.

- [x] Conferir o resultado do workflow no GitHub e corrigir falhas, se houver.
- [x] Concluir configuração AMQP local com credenciais e permissões adequadas.
- [x] Inspecionar os nós existentes e validar persistência da identidade RabbitMQ.
- [x] Validar o diagnóstico AMQP com credencial correta, incorreta e recuperação.
- [x] Definir e testar contratos para JSON malformado e UUID inválido.
- [x] Definir quando se aplica 404 e documentar que hoje só existe POST /loans.
- [x] Testar rollback transacional sem tocar o banco de desenvolvimento.

Aceite: CI verde, ambiente conectado ao broker, erros HTTP consistentes e
validações operacionais registradas sem expor segredos. Mudanças de contas ou
credenciais existentes exigem autorização específica quando ainda não concedida.

Evidências:

- CI da PR #1 aprovado: os dois checks de testes retornaram SUCCESS,
  verificados antes da implementação da Sprint 2.
- `.env` local ignorado pelo Git; `loans_app` configurado com permissões em `/`
  e acesso ao painel sem tag de administrador. Senha do PostgreSQL preservada.
- `./scripts/check-amqp.sh`: health global/db/rabbit UP e conexão AMQP running.
- Dois diretórios de nós encontrados no volume existente; identidade ativa
  preservada. Na validação final, apenas o broker foi recriado, com a mesma
  imagem, hostname, nó e volume. PostgreSQL não foi recriado.
- `e3eee79`: handler 400 com ProblemDetail fixo e nove casos HTTP de corpo/formato
  inválido, sem exposição do valor inválido ou detalhes do parser; nenhuma linha
  criada em loans ou loan_books nesses cenários.
- `dbd431c`: rollback após criação pelo serviço e flush; empréstimo e livros são
  visíveis dentro da transação e ausentes após a falha.
- `./mvnw -B -o -Pintegration clean verify`: 24 testes Java aprovados com banco e
  broker descartáveis; testes Python: 4 aprovados. Nenhum teste ignorado.
- Contrato 404: ainda não existe GET /loans/{id} nem consulta por ID. Uma rota
  inexistente pode retornar 404 do framework; isso não representa uma busca
  de empréstimo ausente. O contrato de recurso ausente será definido com a consulta.

- Senha incorreta aplicada somente em loans via override temporário: HTTP 503,
  db UP, rabbit DOWN e diagnóstico com saída 1. Após restauração: HTTP 200 e
  diagnóstico aprovado. `.env` e container do broker inalterados nesse teste.
- Snapshot consistente do volume com broker parado, protegido fora do repositório;
  mesma imagem usada na recriação. Usuários, permissões, vhosts, exchanges,
  bindings e filas comparados antes/depois sem divergências.
- Fila durável e mensagem persistente de teste sobreviveram à recriação;
  payload e delivery_mode=2 conferidos. Somente a fila temporária foi removida.
- Diagnóstico final aprovado; hash do `.env` e ID do container PostgreSQL inalterados.

Procedimento e evidências: [validação operacional da Sprint 1](SPRINT1-VALIDATION.md).
Referência de encerramento local: commit que adiciona esse relatório e marca
esta checklist como concluída. Nenhuma tag foi criada.

Publicação concluída pela PR #1. O usuário autorizou a Sprint 2 separadamente.

## Sprint 2 — Contratos e outbox de loans

Estado: concluída, integrada pela PR #2 em main (`346a6a6`), com os dois checks
de testes aprovados. Nenhum deploy em desenvolvimento foi realizado.

- [x] Definir envelope versionado, payloads, correlationId, causationId e sagaId.
- [x] Confirmar topologia: exchange durável library.events e routing keys versionadas.
- [x] Definir filas consumidoras, mensagens persistentes, retry limitado e DLQ.
- [x] Gravar LoanRequested na outbox na mesma transação do empréstimo PENDING.
- [x] Implementar publicação com confirms e tratamento de mensagens não roteadas.
- [x] Marcar envio apenas após confirmação e permitir recuperação após falha.
- [x] Testar broker indisponível, reinício e possibilidade de publicação duplicada.

Aceite local: uma indisponibilidade do broker não perde eventos; publicação
recupera sem assumir atomicidade entre transação JPA e RabbitMQ. Deduplicação
de efeitos será concluída nos consumidores. Retry de infraestrutura permanece
ativo com backoff limitado; falhas específicas da mensagem podem ir para PARKED,
com replay explícito. Nada é removido automaticamente da outbox.

Decisões técnicas: sagaId=loanId, correlationId=loanId no primeiro evento e
causationId=null; filas quorum; loans declara inicialmente a assinatura de
catalog com contrato de argumentos fixo, para não depender da Sprint 3 para rotear.
Readiness verifica aplicação/banco, sem depender do broker para receber PENDING.
Nenhuma política de estoque, prazo, autenticação ou multa foi criada.

Evidências e commits:

- `5e7144c`: envelope LoanRequested, topologia durável, quorum/DLQ e contrato.
- `7f78fcb`: V3 e escrita transacional da outbox; rollback conjunto verificado.
- `a1b66cb`: publisher com confirms/returns, worker agendado, retry, estacionamento
  e replay, além dos testes de falhas/concorrência.
- `./mvnw -B -o -Pintegration clean verify`: 42 testes Java aprovados, sem testes
  ignorados, com PostgreSQL/RabbitMQ descartáveis. Python: 4 testes aprovados.
- Criação HTTP com broker indisponível retornou 201; readiness 200 e health geral
  503. Mais falhas de infraestrutura que o limite de mensagem não estacionaram
  o evento. Um contexto novo com scheduler ativo recuperou o envio.
- Falha no UPDATE de SENT após ack produziu duas mensagens com mesmo eventId/corpo.
- Testes de worker concorrente, mensagem após restart do broker, rejeição/limite
  de reentregas, DLQ e replay preservando eventId aprovados.

Documentos: [contratos de eventos](EVENTS.md) e [outbox/replay](OUTBOX.md).

Limites: consumidor, inbox, reserva do acervo, transição para ACTIVE/CANCELLED,
compensação, timeout e reconciliação continuam nas próximas sprints. A aplicação
local de desenvolvimento permanece na imagem anterior; V3/topologia novas foram
aplicadas apenas nos ambientes descartáveis de teste. A retenção da outbox e a
propagação de tracing ainda precisam ser evoluídas.

## Sprint 3 — Acervo e reserva atômica

Estado: concluída e validada localmente na branch `sprint/3-catalog`, sob a
premissa mínima abaixo. Push, PR e CI remoto desta sprint continuam pendentes.
A Sprint 2 foi integrada pela PR #2 (`346a6a6`), com checks aprovados.

Premissa mínima comunicada enquanto a decisão de estoque continua aberta:
uma biblioteca única, estoque agregado por título e quantidade 1 por título
em cada empréstimo. Book identifica título; cópias físicas não recebem IDs.
O vínculo à biblioteca é o escopo único da instância de catalog, sem seleção de
unidade no evento v1. Isso não encerra as decisões de múltiplas bibliotecas ou
exemplares. A evolução exige nova decisão e contrato explícito.

- [x] Criar catalog com banco, credenciais configuráveis, migrations e Compose próprios.
- [x] Modelar títulos e cópias disponíveis no escopo de biblioteca única, sob a premissa acima.
- [x] Consumir LoanRequested com inbox transacional e reserva única por loanId.
- [x] Reservar todos os títulos atomicamente ou recusar sem reserva parcial.
- [x] Gravar BooksReserved ou BooksReservationRejected via outbox local.
- [x] Confirmar entrega AMQP somente após commit e testar reentrega/duplicação.
- [x] Testar disputa simultânea pelo estoque com PostgreSQL real.

Aceite local: não há estoque excedido, reserva parcial silenciosa ou reserva
duplicada nos cenários testados. Nenhum serviço acessa tabelas do outro.
Estoque reservado só será liberado com futura compensação/devolução. Não foram
criadas regras de elegibilidade, limites por membro, multas ou prazo máximo.

Evidências e commits:

- `606d356`: serviço catalog, Maven Wrapper/Dockerfile próprios, API de títulos
  e banco com restrições de estoque.
- `53210c4`: inbox, reserva all-or-nothing, outbox e consumidor com ack após commit;
  testes de falhas, concorrência, reentrega e contratos.
- `50012a7`: compose.catalog.yaml, variáveis próprias e RabbitMQ fixado em 4.3.6,
  versão já observada no ambiente local. O arquivo base continua independente.
- `d25492a`: CI executa catalog e loans, com cache e relatórios de ambos.
- `./mvnw -B -o -Pintegration clean verify`: 44 testes de catalog e 42 de loans
  aprovados, sem falhas ou testes ignorados. Diagnóstico Python: 4 aprovados.
- Smoke com os dois Dockerfiles e Compose descartável: primeiro pedido reservado,
  segundo recusado por falta de estoque, resultados persistidos na fila de loans,
  outboxes SENT e empréstimos ainda PENDING conforme escopo da Sprint 3.
- Credenciais/volumes dos testes eram exclusivos e descartáveis. Hash do `.env`
  e IDs dos containers de desenvolvimento preservados; sem deploy ou migration
  nos bancos de desenvolvimento.

Documentos: [catalog e operação](CATALOG.md), [contratos](EVENTS.md) e
[evidências de validação](SPRINT3-VALIDATION.md).

Pendências para uso local: configurar as três variáveis de catalog, provisionar
seu usuário AMQP próprio e iniciar os dois arquivos Compose. Não foram criadas
contas nem alteradas credenciais existentes. Quedas longas do banco podem levar
solicitações válidas à DLQ; o runbook de replay preserva eventId e exige
confirmação da republicação antes de confirmar a mensagem original.

Consumo de resultados em loans, ACTIVE/CANCELLED e deduplicação desses resultados
permanecem na Sprint 4. Compensação, timeout e reconciliação permanecem na Sprint 5.

## Sprint 4 — Saga ponta a ponta

Estado: concluída e validada localmente na branch `sprint/4-loan-results`.
Sem push/PR/CI remoto; baseada na entrega local da Sprint 3.

- [x] Consumir resultados de catalog em loans com inbox e deduplicação.
- [x] Aplicar ACTIVE quando todas as pré-condições forem atendidas, ou CANCELLED na recusa.
- [x] Tratar conflito otimista com releitura e retry limitado.
- [x] Validar roteamento, ack após commit, retry e DLQ nos dois serviços.
- [x] Testar sucesso, recusa, mensagens duplicadas e reinício dos serviços.

Aceite: o fluxo pelo Compose converge para ACTIVE ou CANCELLED nos cenários
cobertos, sem repetir efeitos. Timeout e compensação ainda dependem da sprint 5;
a entrega desta sprint não representa a Saga completa para falhas tardias.

Evidências: [validação da Sprint 4](SPRINT4-VALIDATION.md) e
[consumo de resultados](LOAN-RESULTS.md). Loans: 61 testes; catalog: 44;
diagnóstico Python: 4, todos aprovados. Smoke Compose isolado convergiu para
ACTIVE/CANCELLED. Implementação em 61b41c0, 340f3e6 e 4e73524; encerramento no
commit deste relatório. Timeout e compensação permanecem na Sprint 5.

## Sprint 5 — Compensação e recuperação

Estado: concluída e validada localmente em `sprint/5-6-recovery-observability`.
Sem push/PR/CI remoto. Timeout técnico configurável, premissas e limites no relatório.

- [x] Implementar solicitação e confirmação idempotentes de liberação de reserva.
- [x] Representar estados técnicos de compensação sem concluir antes da confirmação.
- [x] Implementar timeout persistido e reconciliação de operações pendentes.
- [x] Liberar reservas bem-sucedidas recebidas após timeout ou cancelamento.
- [x] Testar eventos atrasados, duplicados, perda temporária do broker e reinícios.

Aceite: cancelamento não deixa reserva presa; não há PENDING indefinido;
compensações pendentes são identificáveis e recuperáveis.

Evidências: [aceite das Sprints 5 e 6](SPRINT5-6-VALIDATION.md).
Operação: [recuperação](RECOVERY.md) e [observabilidade](OBSERVABILITY.md).
Commits de implementação separados por responsabilidade; encerramento no commit
de documentação que atualiza esta checklist. Alertas externos não configurados.

## Sprint 6 — Observabilidade e aceite da fatia mínima

Estado: concluída e validada localmente em `sprint/5-6-recovery-observability`.
Sem push/PR/CI remoto. Timeout técnico configurável, premissas e limites no relatório.

- [x] Implementar logs estruturados com contexto da Saga e sem dados sensíveis.
- [x] Adicionar Prometheus/Grafana para métricas históricas e dashboards.
- [x] Medir idade da outbox, duração/resultados da Saga, retries e DLQ sem labels UUID.
- [x] Escolher backend de tracing e propagar contexto HTTP/AMQP.
- [x] Configurar alertas para outbox atrasada, DLQ e Saga expirada.
- [x] Executar testes de falhas e o aceite completo pelo Docker Compose.

Aceite: acompanhar uma Saga entre serviços e detectar/recuperar falhas; confirmar
sucesso, recusa, concorrência, deduplicação, compensação e eventos atrasados.

Evidências: [aceite das Sprints 5 e 6](SPRINT5-6-VALIDATION.md).
Operação: [recuperação](RECOVERY.md) e [observabilidade](OBSERVABILITY.md).
Commits de implementação separados por responsabilidade; encerramento no commit
de documentação que atualiza esta checklist. Alertas externos não configurados.

## Decisões de negócio pendentes

- [ ] Confirmar Book como título e definir cópias/estoque por Library.
- [ ] Definir quando reservar e liberar estoque.
- [ ] Definir elegibilidade do membro e necessidade de validação na fatia mínima.
- [ ] Definir timeout e comportamento durante compensação.
- [ ] Definir limites, prazos, renovação e repetição de título entre empréstimos ativos.
- [ ] Definir identidade/autorização do operador.
- [ ] Definir devolução parcial, cancelamento após ACTIVE e perdas/danos.
- [ ] Definir multas, calendário, arredondamento e pagamento.
- [ ] Definir requisitos de múltiplas bibliotecas e fusos.

## Evoluções posteriores

Planejar separadamente membros e autenticação; consulta e devolução; renovação;
e multas. Não criar um microsserviço por entidade ou inventar regras financeiras.
Essas entregas dependem das decisões acima e de novo escopo autorizado.

## Trilha separada — Frontend Angular

O frontend tem numeração e critérios próprios, sem alterar as Sprints 1–6 do
backend. O plano está em [sprints do frontend Angular](FRONTEND-SPRINTS.md).
As dependências de APIs e da Saga estão indicadas em cada entrega. A criação
desse plano não inicia automaticamente a implementação.
