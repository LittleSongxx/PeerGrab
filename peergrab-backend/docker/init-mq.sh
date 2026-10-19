#!/usr/bin/env bash
# RocketMQ 初始化：创建 DELAY 类型 topic 与消费组。
#
# 为什么要显式建 topic：5.x 的定时消息要求 topic 的 message.type=DELAY，
# 自动创建的普通 topic 会让 broker 拒收定时消息，报错信息还不太直观。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE=(docker compose -f "$SCRIPT_DIR/docker-compose.yaml")
if [ -n "${COMPOSE_ENV_FILE:-}" ]; then
  COMPOSE+=(--env-file "$COMPOSE_ENV_FILE")
elif [ -f "$SCRIPT_DIR/.env" ]; then
  COMPOSE+=(--env-file "$SCRIPT_DIR/.env")
fi
BROKER_CONTAINER="${BROKER_CONTAINER:-$("${COMPOSE[@]}" ps -q rmqbroker)}"
if [ -z "$BROKER_CONTAINER" ]; then
  echo "RocketMQ broker container not found; start rmqbroker with Docker Compose first" >&2
  exit 1
fi
NAMESRV="${NAMESRV:-rmqnamesrv:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"
TOPIC="${TOPIC:-errand-confirm-timeout}"
# P3：送达后 24h 自动结算（DELAY 类型，与超时流转同类）
TOPIC_AUTO_SETTLE="${TOPIC_AUTO_SETTLE:-errand-auto-settle}"
# 资金事件改为同库 outbox + 普通消息。使用新 Topic，保留旧 TRANSACTION Topic 的历史与位点。
TOPIC_FUND_EVENT="${TOPIC_FUND_EVENT:-errand-fund-event-v2}"
# P5：延迟双删（DELAY 类型）
TOPIC_CACHE_EVICT="${TOPIC_CACHE_EVICT:-errand-cache-evict}"
GROUP="${GROUP:-peergrab-timeout-consumer}"

admin() {
  docker exec "$BROKER_CONTAINER" sh -c "cd /home/rocketmq/rocketmq-*/bin && sh mqadmin $*"
}

# Broker 进程 healthy 不等于已向 nameserver 注册完成；初始化脚本必须等路由可用，
# 否则第一次 updateTopic 会报 "specified clusterName exists" 且 topic 创建不完整。
wait_for_broker() {
  local i out
  for i in $(seq 1 30); do
    out=$(admin "clusterList -n $NAMESRV" 2>&1 || true)
    if echo "$out" | grep -q 'DefaultCluster'; then
      echo "broker 已注册（等待 ${i} 次探测）"
      return 0
    fi
    sleep 3
  done
  echo "broker 未在 nameserver 注册，初始化失败" >&2
  echo "$out" | tail -5 >&2
  return 1
}

wait_for_broker || exit 1

retry_admin() {
  local i out
  for i in $(seq 1 15); do
    out=$(admin "$*" 2>&1)
    if ! echo "$out" | grep -qiE 'error|exception|not exist|failed'; then
      echo "$out" | tail -1
      return 0
    fi
    sleep 3
  done
  echo "命令重试失败: $*" >&2
  echo "$out" | tail -5 >&2
  return 1
}

wait_for_topic_route() {
  local topic="$1" i
  for i in $(seq 1 20); do
    if admin "topicRoute -n $NAMESRV -t $topic" >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done
  echo "topic 路由不可用: $topic" >&2
  return 1
}

echo "创建 DELAY topic: $TOPIC"
retry_admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC -a +message.type=DELAY" | tail -2

echo "创建 DELAY topic: $TOPIC_AUTO_SETTLE"
retry_admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_AUTO_SETTLE -a +message.type=DELAY" | tail -1

echo "创建 NORMAL topic（资金 outbox 普通消息）: $TOPIC_FUND_EVENT"
retry_admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_FUND_EVENT -a +message.type=NORMAL" | tail -1

echo "创建 DELAY topic（延迟双删）: $TOPIC_CACHE_EVICT"
retry_admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_CACHE_EVICT -a +message.type=DELAY" | tail -1

echo "创建消费组: $GROUP"
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g $GROUP" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g peergrab-autosettle-consumer" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g peergrab-fund-event-consumer" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g peergrab-cache-evict-consumer" | tail -1
# 资金事件实时推送已并入 worker 的落库消费者（经 Redis fanout 到 API），不再创建独立 push 组。

# 创建成功不代表 nameserver 立刻有路由；等 4 个 topic 都可路由后再交付。
for topic in "$TOPIC" "$TOPIC_AUTO_SETTLE" "$TOPIC_FUND_EVENT" "$TOPIC_CACHE_EVICT"; do
  wait_for_topic_route "$topic"
done
echo "topic 路由已就绪: $TOPIC $TOPIC_AUTO_SETTLE $TOPIC_FUND_EVENT $TOPIC_CACHE_EVICT"

echo "集群状态:"
admin "clusterList -n $NAMESRV" | tail -3
