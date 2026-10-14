# Biblioteca

Microsserviço `loans` com Java 25, Spring Boot, PostgreSQL e RabbitMQ.

O [planejamento das sprints](docs/SPRINTS.md) reúne backlog, checklists, critérios
de aceite e referências Git para acompanhar as entregas.

## Execução local

Se não houver `.env`, copie `.env.example` para `.env` e preencha as variáveis. Em volumes
existentes, informe as credenciais já configuradas: as variáveis de bootstrap
não alteram senhas armazenadas no PostgreSQL ou usuários existentes no RabbitMQ.
Não remova volumes para corrigir autenticação.

```sh
cp .env.example .env
# Preencha .env antes de iniciar.
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

O health global deve apresentar `db` e `rabbit` como `UP`. O healthcheck do
broker verifica apenas o processo e não comprova autenticação AMQP de loans.
Após modificar variáveis, recrie os containers com `docker compose up -d`.

Para diagnóstico sem imprimir senhas:

```sh
docker compose exec rabbitmq rabbitmqctl list_users
docker compose exec rabbitmq rabbitmqctl list_permissions -p /
docker compose exec rabbitmq rabbitmqctl list_connections user vhost state
docker compose logs --tail=100 loans
```

Se houver divergência em um volume existente, ajuste usuário e permissões no
RabbitMQ Management em http://localhost:15672 usando uma conta administrativa
existente. Use as mesmas credenciais no `.env` e recrie loans. Preserve os dados.
Evite compartilhar logs ou saídas de configuração que possam conter segredos.

## Estado desta etapa

A configuração conecta loans ao host `rabbitmq:5672` pela rede Compose e exige
credenciais externas. A migration V2 e `@Version` adicionam controle otimista;
a concorrência entre transações independentes foi validada com PostgreSQL
descartável nos testes de integração.
O Compose ativa explicitamente o profile `local`, que expõe detalhes do
Actuator e métricas. Fora desse profile, somente health é exposto, sem detalhes.
Liveness verifica o estado da aplicação; readiness inclui banco e estado da
aplicação. RabbitMQ fica no health geral, permitindo criar solicitações com
outbox durante indisponibilidade do broker. Não use o health agregado como
liveness.

LoanRequested, outbox transacional e publicação com confirmações estão
implementados. Consulte os [contratos de eventos](docs/EVENTS.md) e o
[funcionamento e replay da outbox](docs/OUTBOX.md). O worker publica na fila
durável de solicitações do catálogo, com retries e DLQ configurados.

Catalog possui API de títulos, estoque, consumidor de LoanRequested e inbox/outbox
transacionais, com reserva de todos os livros ou recusa sem efeito parcial.
Consulte [modelo, API e execução de catalog](docs/CATALOG.md). Para iniciar o par,
inclua `-f compose.catalog.yaml` junto com `-f compose.yaml`, após configurar as
credenciais exclusivas de catalog. O arquivo base continua funcionando isoladamente.

A premissa mínima é uma biblioteca única, com cópias por título. 201/PENDING
continua significando solicitação criada: loans ainda não consome os resultados.
Os resultados ficam em `loans.catalog-results.v1` até a Sprint 4. Compensação,
timeout, liberação de estoque e Saga distribuída completa permanecem nas próximas
sprints. Nenhuma migration foi aplicada aos bancos de desenvolvimento nesta entrega.
A migration V3 cria a outbox; V1/V2 não foram alteradas. A topologia é declarada
aditivamente quando a aplicação conecta ao broker; não apagar filas/volumes para
resolver uma declaração incompatível.

## Contrato de erros HTTP

A aplicação expõe `POST /loans` para criação. Erros tratados retornam
`application/problem+json`:

| Situação | Status | Resposta |
|---|---|---|
| JSON malformado, corpo vazio/null, UUID ou data ilegível, formato incompatível | 400 | Título `Requisição inválida`, detalhe fixo `Corpo da requisição inválido ou malformado` |
| Campos que violam as validações do DTO | 400 | Título `Dados inválidos`, lista `errors` com `field` e `message` |
| Regra de domínio inválida | 400 | Título `Empréstimo inválido`, descrição da regra |
| Conflito otimista | 409 | Título `Conflito de atualização`, orientação para recarregar e tentar novamente |

Erros de leitura não devolvem valores recebidos nem mensagens internas do parser.
Não existe `GET /loans/{id}`: 404 de uma rota inexistente não significa que houve
consulta por um empréstimo ausente. Esse contrato será definido quando a consulta
for implementada. O handler 409 prepara futuras atualizações; seu teste HTTP usa
um controller exclusivo de teste.

## Identidade persistente do RabbitMQ

Antes de recriar um broker existente, identifique seu nó e diretório de dados:

```sh
docker compose exec rabbitmq hostname
docker compose exec rabbitmq rabbitmqctl eval 'node().'
docker compose exec rabbitmq rabbitmqctl eval 'application:get_env(rabbit, mnesia_dir).'
docker compose exec rabbitmq ls /var/lib/rabbitmq/mnesia/
```

Configure `RABBITMQ_HOSTNAME` com o hostname do nó que contém os dados válidos,
sem o prefixo `rabbit@`. Para um volume novo, use `rabbitmq`. Se houver vários
diretórios de nós, investigue qual contém os dados antes de iniciar o broker.
Faça backup do volume antes de qualquer mudança de identidade. Não use um nome
novo para um volume existente: isso pode iniciar um nó vazio no mesmo volume.
O DNS usado pela aplicação continua sendo o nome do serviço `rabbitmq`.

Após uma recriação planejada, confirme a mesma identidade, usuários, filas e
conexão AMQP. Não combine essa operação com atualização da imagem do broker.

## Verificação da conexão AMQP

Com Docker, curl e jq instalados, ambiente configurado e serviços em execução:

```sh
./scripts/check-amqp.sh
```

O script consulta o health antes das conexões, exige global/db/rabbit UP e uma
conexão `running` do usuário configurado no container loans, no vhost `/`.
Não imprime senhas nem o JSON de diagnóstico. Retorna 0 em sucesso, 1 para
falha de verificação e 2 para pré-requisito/configuração/serviço indisponível.
O profile local deve estar ativo para os componentes ficarem visíveis.
Quando o usuário é compartilhado com o bootstrap ou outros clientes, a lista do
broker isoladamente não identifica a origem da conexão; o health `rabbit`
confirma o acesso de loans.

Validação operacional concluída: credencial correta, incorreta somente em
loans e recuperação, além de persistência após recriação do broker com backup.
Veja as [evidências da Sprint 1](docs/SPRINT1-VALIDATION.md). O teste não alterou
as senhas do broker ou do PostgreSQL. Novas execuções com falhas deliberadas
devem ser planejadas, pois interrompem temporariamente o acesso da aplicação.

## Testes

No diretório `loans`, com Java 25:

```sh
./mvnw clean test
./mvnw -Pintegration clean verify
```

Esses comandos também estão disponíveis no diretório `catalog`, com Maven Wrapper
próprio. O primeiro comando executa testes sem infraestrutura: criação, validação de
campos e datas, proteção das coleções, transições, conflito HTTP 409 e tratamento
de confirms, returns, nack, timeout e interrupção no publisher.
O segundo executa `LoanIntegrationIT` e `OutboxIntegrationIT`, com PostgreSQL 17 e RabbitMQ 4
descartáveis via Testcontainers, portas dinâmicas e credenciais de teste.
Não usa `.env`, credenciais, containers ou volumes de desenvolvimento.
Docker é obrigatório para integração; indisponibilidade provoca falha, não
um teste ignorado.

A integração cobre criação HTTP 201/PENDING e associação dos livros, rejeição
400 por campo e entradas ilegíveis sem persistência, rollback após flush de
empréstimo e livros, liveness/readiness e conexão AMQP real, criação
do schema via Flyway, atualização V1 → V3 preservando dados e controle otimista
entre transações independentes. A transação perdedora não sobrescreve o estado,
a versão nem os livros do vencedor. O teste HTTP 409 usa um controller de teste:
ainda não há endpoint de atualização na aplicação.

A suíte de outbox também cobre criação durante broker indisponível, recuperação
automática em novo contexto, duplicação após confirmação sem marcação de envio,
workers concorrentes, estacionamento/replay, persistência após restart do broker
e DLQ. Readiness segue UP durante a falha do broker; o health geral fica DOWN.

Na raiz, com Python 3 e jq, execute os testes do diagnóstico sem Docker/rede:

```sh
python3 -B -m unittest discover -s scripts/tests -v
```

Validação local da Sprint 3: 42 testes Java em loans, 44 em catalog e 4 testes
Python passaram, sem falhas ou testes ignorados. O smoke test com os dois
Dockerfiles e Compose descartável também passou. A validação operacional local também foi concluída e registrada
separadamente nos relatórios da Sprint 1.

## Testes a cada push

O workflow [Tests](.github/workflows/tests.yml) executa em todo push, pull request
e também manualmente pela aba Actions do GitHub. Usa Ubuntu, Java Temurin 25,
cache Maven e o Docker do runner para os containers descartáveis. Não requer
secrets do repositório nem o arquivo `.env`.

Executa os testes Python do diagnóstico e `./mvnw -B -ntp -Pintegration clean verify`
em loans e em catalog.
Se um teste falhar, o job falha. Os relatórios Surefire ficam disponíveis no
artefato `test-reports` por sete dias. Docker e jq são verificados antes da suíte
para impedir sucesso aparente por ausência de pré-requisitos.

A execução no GitHub começa após enviar o commit do workflow ao repositório,
com GitHub Actions habilitado. O workflow não publica a aplicação nem faz deploy.
Para impedir merges com testes falhando, configure separadamente a proteção da
branch exigindo o check `Java 25, PostgreSQL e RabbitMQ`.

Referências: [Maven no GitHub Actions](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)
e [configuração do Java e cache Maven](https://github.com/actions/setup-java).
