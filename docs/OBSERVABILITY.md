# Observabilidade local

## Execução

Preencher `GRAFANA_PASSWORD` no .env e usar as credenciais AMQP já provisionadas
para loans/catalog. O bootstrap do broker não altera usuários de um volume antigo.

```sh
docker compose -f compose.yaml -f compose.catalog.yaml -f compose.observability.yaml config --quiet
docker compose -f compose.yaml -f compose.catalog.yaml -f compose.observability.yaml up --build -d
```

Esse comando é uma operação de desenvolvimento a executar conscientemente:
o complemento habilita o plugin Prometheus no broker e pode recriá-lo. Preserva
hostname, volumes e credenciais do Compose base. Não foi executado pelo agente
sobre os dados de desenvolvimento; o aceite usa um projeto descartável.

- Grafana: http://localhost:3000, usuário admin e senha configurada.
- Prometheus: http://localhost:9090, coleta e regras de alerta.
- Alertmanager: http://localhost:9093, alertas agrupados.
- Tempo: http://localhost:3200, API de traces; consultar via Explore/Tempo no Grafana.
- Aplicações: /actuator/prometheus; detalhes de health restritos ao profile local.

O dashboard Biblioteca é provisionado automaticamente, assim como as fontes
Prometheus e Tempo. As imagens de observabilidade estão fixadas por digest do
aceite; atualizar deliberadamente e repetir os testes. Retenção local: sete dias
para métricas e traces. Grafana/Prometheus/Tempo/Alertmanager têm volumes próprios.
É uma stack de desenvolvimento em nó único, sem alegação de alta disponibilidade.

## Logs e traces

Logs JSON Logstash no stdout, com traceId/spanId e contexto loanId/sagaId/eventId
nos consumidores. Não usar IDs como labels de métricas. Não registrar payloads,
credenciais ou dados pessoais em logs de aplicação. Mensagens de bibliotecas
podem conter detalhes técnicos: controlar acesso ao coletar/exportar logs.

OpenTelemetry/Micrometer propaga W3C no HTTP e AMQP. O contexto original é salvo
em trace_headers da outbox na mesma transação, somente traceparent/tracestate,
sem baggage arbitrário. O worker restaura o pai antes de publicar; portanto uma
publicação assíncrona/retry continua ligada à operação original. Contexto de
tracing não define autorização nem substitui eventId/correlationId.

Fora do complemento, exportação fica desativada por padrão; tracing/contexto
continuam disponíveis. `TRACING_EXPORT_ENABLED=true` habilita exportação e
`management.opentelemetry.tracing.export.otlp.endpoint` define o receptor. No
complemento, exporta para Tempo via HTTP/OTLP e sampling 100% para aprendizado.
Amostragem padrão é 10%; dimensionar antes de usar fora do ambiente local.

## Métricas e alertas

Dashboard: taxa/latência HTTP, memória JVM, conexões de banco, filas RabbitMQ,
quantidade/idade da outbox, tentativas, eventos estacionados, Sagas vencidas e
duração por resultado. Duração/conclusão são registradas após commit; séries
contadoras/timers reiniciam com o processo, enquanto Prometheus guarda o histórico.
Gauges operacionais consultam o banco; indisponibilidade produz NaN, não falso zero.

Prometheus avalia alertas de serviço indisponível, outbox >120s, PARKED, Saga
vencida e DLQ. Alertmanager recebe e agrupa; o receiver local não envia email,
Slack ou notificações externas. Configurar um destino somente com autorização
e credenciais próprias. Não apresentar UI de alertas como notificação externa.
RabbitMQ usa /metrics/per-object para identificar as filas fixas da aplicação.

## Aceite reproduzível

```sh
python3 scripts/check-saga-e2e.py
```

Cria projeto Compose aleatório, portas dinâmicas, credenciais privadas e bancos
novos; usa as imagens reais. Verifica sucesso/recusa, timeout com consumidores
pausados, restauração do estoque, resultado atrasado, reinício e telemetria.
Remove somente a infraestrutura descartável criada, mesmo em falha, e confere
que .env de desenvolvimento não foi alterado. Nunca roda contra bancos existentes.
Requer downloads iniciais e recursos suficientes no Docker Desktop.
