# Biblioteca

Microsserviço `loans` com Java 25, Spring Boot, PostgreSQL e RabbitMQ.

## Execução local

Copie `.env.example` para `.env` e preencha as variáveis. Em volumes
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
a concorrência real ainda precisa de teste entre transações independentes.
O Compose ativa explicitamente o profile `local`, que expõe detalhes do
Actuator e métricas. Fora desse profile, somente health é exposto, sem detalhes.
Liveness verifica o estado da aplicação; readiness inclui banco e RabbitMQ.
Não use o health agregado como liveness.

Eventos, outbox/inbox, catalog e Saga distribuída ainda não estão implementados.

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

Validação operacional pendente: executar com credenciais corretas e depois com
uma credencial incorreta somente em loans, confirmando falha do diagnóstico.
Restaure a configuração e confirme recuperação. Faça isso em ambiente de teste,
sem interromper o broker nem alterar senhas do volume de desenvolvimento.

## Testes

No diretório `loans`, com Java 25:

```sh
./mvnw clean test
./mvnw -Pintegration clean verify
```

O primeiro comando executa testes sem infraestrutura: criação, validação de
campos e datas, proteção das coleções, transições de estado e conflito HTTP 409.
O segundo também executa `LoanIntegrationIT`, com PostgreSQL 17 e RabbitMQ 4
descartáveis via Testcontainers, portas dinâmicas e credenciais de teste.
Não usa `.env`, credenciais, containers ou volumes de desenvolvimento.
Docker é obrigatório para integração; indisponibilidade provoca falha, não
um teste ignorado.

A integração cobre criação HTTP 201/PENDING e associação dos livros, rejeição
400 por campo sem persistência, liveness/readiness e conexão AMQP real, criação
do schema via Flyway, atualização V1 → V2 preservando dados e controle otimista
entre transações independentes. A transação perdedora não sobrescreve o estado,
a versão nem os livros do vencedor. O teste HTTP 409 usa um controller de teste:
ainda não há endpoint de atualização na aplicação.

Na raiz, com Python 3 e jq, execute os testes do diagnóstico sem Docker/rede:

```sh
python3 -B -m unittest discover -s scripts/tests -v
```

Validação local: 14 testes Java e 4 testes Python passaram, sem falhas ou testes
ignorados. A validação dos containers de desenvolvimento e do script com suas
credenciais continua separada desses testes isolados.

## Testes a cada push

O workflow [Tests](.github/workflows/tests.yml) executa em todo push, pull request
e também manualmente pela aba Actions do GitHub. Usa Ubuntu, Java Temurin 25,
cache Maven e o Docker do runner para os containers descartáveis. Não requer
secrets do repositório nem o arquivo `.env`.

Executa os testes Python do diagnóstico e `./mvnw -B -ntp -Pintegration clean verify`.
Se um teste falhar, o job falha. Os relatórios Surefire ficam disponíveis no
artefato `test-reports` por sete dias. Docker e jq são verificados antes da suíte
para impedir sucesso aparente por ausência de pré-requisitos.

A execução no GitHub começa após enviar o commit do workflow ao repositório,
com GitHub Actions habilitado. O workflow não publica a aplicação nem faz deploy.
Para impedir merges com testes falhando, configure separadamente a proteção da
branch exigindo o check `Java 25, PostgreSQL e RabbitMQ`.

Referências: [Maven no GitHub Actions](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)
e [configuração do Java e cache Maven](https://github.com/actions/setup-java).
