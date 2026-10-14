# Planejamento do frontend Angular

Estado: planejado. Esta trilha é separada das Sprints 1–6 do backend e não
renumera nem altera seus critérios de aceite. O registro do plano não autoriza
implementar automaticamente as entregas. Cada sprint será iniciada por pedido
do usuário, com commits semânticos separados e sem coautoria.

## Objetivo e limites

Painel responsivo para o operador da biblioteca, organizado por acervo e
empréstimos. O frontend usa somente APIs HTTP; não acessa bancos ou RabbitMQ.
Não criar regras de elegibilidade, multas, autenticação ou devolução sem os
respectivos contratos e decisões de negócio.

APIs observadas ao planejar: POST /loans, POST /books e GET /books/{id}.
Listagens, busca e consulta de empréstimos ainda precisam ser implementadas.
As alterações necessárias no backend devem ter escopo e commits próprios.

Proposta técnica a confirmar no início da implementação: aplicação em
frontend/, formulários reativos, organização por funcionalidade e origem HTTP
única com proxy para /api/catalog e /api/loans. Escolher versões compatíveis de
Angular, Node e biblioteca visual nessa etapa, verificando a documentação oficial.
Não pressupor que essas escolhas já estão implementadas.

## Front 1 — Estrutura e navegação

Estado: planejada.

- [ ] Criar aplicação Angular em frontend/ e definir versões e biblioteca visual.
- [ ] Criar layout responsivo e navegação para acervo e empréstimos.
- [ ] Definir proxy HTTP de desenvolvimento e execução via Docker Compose.
- [ ] Padronizar estados de carregamento, vazio e falha e tratamento de ProblemDetail.
- [ ] Configurar build e testes no CI a cada push e pull request.

Aceite: aplicação executável por Docker, navegação funcional e verificações do
frontend no CI. Não apresentar funcionalidades futuras como disponíveis.

## Front 2 — Acervo

Estado: planejada. Depende da Front 1 e de listagem/busca em catalog.

- [ ] Definir e implementar no backend listagem paginada e busca de títulos.
- [ ] Criar listagem com busca, paginação e estoque disponível.
- [ ] Criar formulário de cadastro e detalhe de título.
- [ ] Exibir validações por campo, ausência de resultado e falhas de comunicação.
- [ ] Testar os fluxos de cadastro e consulta.

Aceite: cadastrar e encontrar um título por meio da interface com dados reais.
O estoque exibido é informativo; a reserva continua sendo validada atomicamente
pelo backend. Manter explícita a premissa atual de biblioteca única e estoque
agregado por título.

## Front 3 — Solicitação e acompanhamento de empréstimo

Estado: planejada. Depende da Front 2, da Sprint 4 do backend e de GET /loans/{id}.

- [ ] Definir e implementar consulta de empréstimo por ID no backend.
- [ ] Selecionar títulos distintos e informar membro e data/hora de vencimento.
- [ ] Usar IDs de membro e operador como campos provisórios de desenvolvimento,
      até existirem cadastro e autenticação; não tratar isso como autorização.
- [ ] Converter data/hora local para instante UTC e indicar o fuso na interface.
- [ ] Criar solicitação e mostrar PENDING como aguardando reserva.
- [ ] Consultar periodicamente o resultado, com intervalo e limite de espera definidos,
      interrompendo as consultas ao sair da tela ou receber resultado terminal.
- [ ] Mostrar ACTIVE como confirmação e CANCELLED como recusa.
- [ ] Testar sucesso, recusa, falha HTTP e solicitação ainda pendente.

Aceite: acompanhar uma solicitação real até ACTIVE ou CANCELLED nos cenários
cobertos. HTTP 201 não significa reserva concluída. Esgotar a espera na interface
não cancela o empréstimo nem representa timeout da Saga; permitir consultar o ID
novamente. Timeout e compensação do backend permanecem na Sprint 5.

## Front 4 — Histórico e operação

Estado: planejada. Depende da Front 3 e dos contratos de consulta abaixo.

- [ ] Definir e implementar pesquisa paginada de empréstimos por membro e estado.
- [ ] Expor pela API motivo estável de recusa, quando disponível, sem detalhes internos.
- [ ] Criar histórico com filtros e detalhe do empréstimo.
- [ ] Permitir atualização do estado de solicitações pendentes.
- [ ] Testar paginação, filtros e apresentação de recusas.

Aceite: localizar empréstimos e compreender seu estado sem acessar ferramentas
de infraestrutura. Não inventar um motivo quando a API não o informar.

## Front 5 — Acesso e devolução

Estado: planejada, com escopo condicionado às decisões de negócio.

- [ ] Definir autenticação, permissões e integração com o principal do backend.
- [ ] Substituir o operador informado manualmente pela identidade autenticada.
- [ ] Implementar login e apresentação das operações autorizadas.
- [ ] Definir e implementar contrato de devolução e liberação idempotente do acervo.
- [ ] Criar fluxo de devolução conforme as regras aprovadas.
- [ ] Testar acesso autorizado, acesso negado e devolução.

Aceite: autorização efetiva no backend e devolução consistente com o acervo.
Não assumir devolução parcial, multas ou cancelamento após ACTIVE sem decisão.
Esta sprint pode ser dividida após a definição desses contratos.

## Ordem sugerida

Iniciar Front 1 e Front 2 em paralelo à evolução do backend. Iniciar Front 3
quando a Sprint 4 do backend e a consulta HTTP estiverem disponíveis. A execução
com falhas tardias depende também de compensação, timeout e reconciliação.

Registrar ao concluir cada sprint os commits, a PR, as evidências de testes e
as pendências. Nenhuma implementação, publicação ou implantação foi realizada
pela criação deste planejamento.
