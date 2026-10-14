# Biblioteca

Microsserviço `loans` com Java 25, Spring Boot, PostgreSQL e RabbitMQ.

## Execução local

Copie `.env.example` para `.env` e preencha as três variáveis. Em volumes
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
A exposição de detalhes do Actuator é destinada ao diagnóstico local.

Eventos, outbox/inbox, catalog e Saga distribuída ainda não estão implementados.
