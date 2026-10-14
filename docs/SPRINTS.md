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

Evidência local atual: 24 testes Java e 4 Python aprovados, sem testes ignorados.
Commits iniciais da suíte e CI: `cb41d04` e `adcd7d2`. O usuário confirmou
o CI aprovado no GitHub. A conexão AMQP local também foi validada pelo script.
Eventos de negócio, catalog, outbox/inbox e Saga distribuída ainda não existem.

## Sprint 1 — Consolidar a base

Estado: em andamento; implementação HTTP e rollback concluídos, com duas
validações operacionais ainda pendentes.

- [x] Conferir o resultado do workflow no GitHub e corrigir falhas, se houver.
- [x] Concluir configuração AMQP local com credenciais e permissões adequadas.
- [ ] Inspecionar os nós existentes e validar persistência da identidade RabbitMQ.
- [ ] Validar o diagnóstico AMQP com credencial correta, incorreta e recuperação.
- [x] Definir e testar contratos para JSON malformado e UUID inválido.
- [x] Definir quando se aplica 404 e documentar que hoje só existe POST /loans.
- [x] Testar rollback transacional sem tocar o banco de desenvolvimento.

Aceite: CI verde, ambiente conectado ao broker, erros HTTP consistentes e
validações operacionais registradas sem expor segredos. Mudanças de contas ou
credenciais existentes exigem autorização específica quando ainda não concedida.

Evidências:

- CI aprovado no GitHub, conforme confirmação do usuário; os novos commits
  precisam passar novamente quando enviados.
- `.env` local ignorado pelo Git; `loans_app` configurado com permissões em `/`
  e acesso ao painel sem tag de administrador. Senha do PostgreSQL preservada.
- `./scripts/check-amqp.sh`: health global/db/rabbit UP e conexão AMQP running.
- Dois diretórios de nós encontrados no volume existente; identidade ativa
  preservada. Banco e broker não foram recriados durante a configuração.
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

Pendências: comprovar persistência da identidade/dados após recriação controlada
sem apagar volumes; validar credencial incorreta e recuperação do diagnóstico
em ambiente de teste. A execução positiva já foi validada no ambiente local.
Não marcar a sprint como encerrada antes dessas verificações.

Commit de encerramento: ainda não definido.

## Sprint 2 — Contratos e outbox de loans

Estado: planejada. Depende da sprint 1 e das decisões necessárias ao contrato.

- [ ] Definir envelope versionado, payloads, correlationId, causationId e sagaId.
- [ ] Confirmar topologia: exchange durável library.events e routing keys versionadas.
- [ ] Definir filas consumidoras, mensagens persistentes, retry limitado e DLQ.
- [ ] Gravar LoanRequested na outbox na mesma transação do empréstimo PENDING.
- [ ] Implementar publicação com confirms e tratamento de mensagens não roteadas.
- [ ] Marcar envio apenas após confirmação e permitir recuperação após falha.
- [ ] Testar broker indisponível, reinício e possibilidade de publicação duplicada.

Aceite: uma indisponibilidade do broker não perde eventos; publicação recupera
sem assumir atomicidade entre transação JPA e RabbitMQ. Deduplicação de efeitos
será concluída nos consumidores.

Evidências / commit de encerramento / pendências: a preencher.

## Sprint 3 — Acervo e reserva atômica

Estado: planejada. Depende da sprint 2 e da definição de estoque/reserva.

- [ ] Criar catalog com banco, credenciais, migrations e serviço Compose próprios.
- [ ] Modelar títulos, cópias disponíveis e vínculo com biblioteca conforme decisão.
- [ ] Consumir LoanRequested com inbox transacional e reserva única por loanId.
- [ ] Reservar todos os títulos atomicamente ou recusar sem reserva parcial.
- [ ] Gravar BooksReserved ou BooksReservationRejected via outbox local.
- [ ] Confirmar entrega AMQP somente após commit e testar reentrega/duplicação.
- [ ] Testar disputa simultânea pelo estoque com PostgreSQL real.

Aceite: não há estoque excedido, reserva parcial silenciosa ou reserva duplicada.
Nenhum serviço acessa diretamente tabelas do outro.

Evidências / commit de encerramento / pendências: a preencher.

## Sprint 4 — Saga ponta a ponta

Estado: planejada. Depende da sprint 3.

- [ ] Consumir resultados de catalog em loans com inbox e deduplicação.
- [ ] Aplicar ACTIVE quando todas as pré-condições forem atendidas, ou CANCELLED na recusa.
- [ ] Tratar conflito otimista com releitura e retry limitado.
- [ ] Validar roteamento, ack após commit, retry e DLQ nos dois serviços.
- [ ] Testar sucesso, recusa, mensagens duplicadas e reinício dos serviços.

Aceite: o fluxo pelo Compose converge para ACTIVE ou CANCELLED nos cenários
cobertos, sem repetir efeitos. Timeout e compensação ainda dependem da sprint 5;
a entrega desta sprint não representa a Saga completa para falhas tardias.

Evidências / commit de encerramento / pendências: a preencher.

## Sprint 5 — Compensação e recuperação

Estado: planejada. Depende da sprint 4 e das regras de timeout/compensação.

- [ ] Implementar solicitação e confirmação idempotentes de liberação de reserva.
- [ ] Representar estados técnicos de compensação sem concluir antes da confirmação.
- [ ] Implementar timeout persistido e reconciliação de operações pendentes.
- [ ] Liberar reservas bem-sucedidas recebidas após timeout ou cancelamento.
- [ ] Testar eventos atrasados, duplicados, perda temporária do broker e reinícios.

Aceite: cancelamento não deixa reserva presa; não há PENDING indefinido;
compensações pendentes são identificáveis e recuperáveis.

Evidências / commit de encerramento / pendências: a preencher.

## Sprint 6 — Observabilidade e aceite da fatia mínima

Estado: planejada. Depende da sprint 5.

- [ ] Implementar logs estruturados com contexto da Saga e sem dados sensíveis.
- [ ] Adicionar Prometheus/Grafana para métricas históricas e dashboards.
- [ ] Medir idade da outbox, duração/resultados da Saga, retries e DLQ sem labels UUID.
- [ ] Escolher backend de tracing e propagar contexto HTTP/AMQP.
- [ ] Configurar alertas para outbox atrasada, DLQ e Saga expirada.
- [ ] Executar testes de falhas e o aceite completo pelo Docker Compose.

Aceite: acompanhar uma Saga entre serviços e detectar/recuperar falhas; confirmar
sucesso, recusa, concorrência, deduplicação, compensação e eventos atrasados.

Evidências / commit de encerramento / pendências: a preencher.

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
