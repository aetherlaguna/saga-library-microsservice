#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { printf 'ERRO: %s\n' "$1" >&2; exit "${2:-1}"; }
for tool in docker curl jq; do
  command -v "$tool" >/dev/null || fail "Pré-requisito ausente: $tool" 2
done
docker info >/dev/null 2>&1 || fail 'Daemon Docker indisponível.' 2
docker compose config --quiet || fail 'Configuração Compose inválida; confira .env.' 2
running=$(docker compose ps --status running --services) || fail 'Não foi possível consultar os serviços.' 2
for service in loans rabbitmq; do
  grep -Fxq "$service" <<< "$running" || fail "Serviço parado: $service" 2
done

# Não imprimir o JSON: detalhes podem conter dados operacionais sensíveis.
body=$(curl --silent --show-error --max-time 15 \
  "${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}") || fail 'Health inacessível.'
jq -e '.status == "UP" and .components.db.status == "UP" and .components.rabbit.status == "UP"' \
  >/dev/null <<< "$body" || fail 'Health requer status global, db e rabbit UP, com componentes visíveis (profile local).'
printf 'OK: health global, db e rabbit UP.\n'

# Ler somente o usuário, nunca a senha ou o ambiente completo.
user=$(docker compose exec -T loans printenv SPRING_RABBITMQ_USERNAME) || fail 'Usuário AMQP indisponível no loans.'
[ -n "$user" ] || fail 'Usuário AMQP vazio.'
connections=$(docker compose exec -T rabbitmq rabbitmqctl -q list_connections \
  --no-table-headers user vhost state) || fail 'Não foi possível consultar conexões AMQP.'
jq -Rse --arg user "$user" \
  'split("\n") | any(.[] | split("\t"); .[0] == $user and .[1] == "/" and .[2] == "running")' \
  >/dev/null <<< "$connections" || fail 'Nenhuma conexão running do usuário configurado no vhost /.'
printf 'OK: conexão AMQP running do usuário configurado em /.\n'
