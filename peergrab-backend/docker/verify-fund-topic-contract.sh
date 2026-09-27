#!/usr/bin/env bash
# Run only against a newly created, disposable CI MQ Compose project.
set -euo pipefail

project="${1:?Pass the isolated Compose project name}"
if [[ "$project" != peergrab-ci-mq* && "$project" != peergrab-mq-contract* ]] ||
   [[ "${PEERGRAB_MQ_CONTRACT_DISPOSABLE:-}" != YES ]]; then
  echo 'Refusing to send a contract event outside an isolated CI MQ project' >&2
  exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose=(docker compose -p "$project" -f "$script_dir/docker-compose.yaml" -f "$script_dir/docker-compose.full.yaml")
broker="$("${compose[@]}" ps -q rmqbroker)"
if [[ -z "$broker" ]]; then
  echo 'Isolated RocketMQ broker is not running' >&2
  exit 1
fi

init_output="$("${compose[@]}" run --rm --no-deps mq-init 2>&1)"
if [[ "$init_output" != *'topicName=errand-fund-event-v2'* ||
      "$init_output" != *'attributes={+message.type=NORMAL}'* ]]; then
  printf '%s\n' "$init_output" >&2
  echo 'Fund event topic is not NORMAL' >&2
  exit 1
fi

key="ci-fund-contract-$(date +%s)-$$"
send_output="$(docker exec "$broker" sh -c '
  for admin in /home/rocketmq/rocketmq-*/bin/mqadmin; do
    exec sh "$admin" sendMessage -n rmqnamesrv:9876 -t errand-fund-event-v2 \
      -k "$1" -c SETTLED \
      -p "{\"bizNo\":\"$1\",\"type\":\"SETTLED\",\"errandId\":1,\"publisherId\":1,\"runnerId\":2,\"amountCents\":100,\"commissionCents\":5}"
  done' _ "$key")"
read -r broker_name queue_id < <(awk '$3 == "SEND_OK" {print $1, $2}' <<< "$send_output")
if [[ -z "${broker_name:-}" || ! "${queue_id:-}" =~ ^[0-9]+$ ]]; then
  printf '%s\n' "$send_output" >&2
  echo 'Fund event ordinary send did not return SEND_OK' >&2
  exit 1
fi

consume_output="$(docker exec "$broker" sh -c '
  for admin in /home/rocketmq/rocketmq-*/bin/mqadmin; do
    exec sh "$admin" consumeMessage -n rmqnamesrv:9876 -t errand-fund-event-v2 \
      -g peergrab-fund-event-consumer -b "$1" -i "$2" -o 0 -c 1
  done' _ "$broker_name" "$queue_id")"
if [[ "$consume_output" != *'Consume ok'* || "$consume_output" != *"\"bizNo\":\"$key\""* ]]; then
  printf '%s\n' "$consume_output" >&2
  echo 'Fund event could not be read back from its consumer group' >&2
  exit 1
fi
printf 'PASS: %s is NORMAL; SEND_OK and consumer read-back for %s\n' \
  'errand-fund-event-v2' "$key"
