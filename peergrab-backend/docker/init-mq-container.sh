#!/bin/sh
# Compose 一次性作业：在 broker 注册完成后创建延迟/事务 topic 和消费组。
set -eu

MQADMIN=""
for candidate in /home/rocketmq/rocketmq-*/bin/mqadmin; do
    if [ -f "$candidate" ]; then
        MQADMIN="$candidate"
        break
    fi
done
if [ -z "$MQADMIN" ]; then
    echo "mqadmin not found in RocketMQ image" >&2
    exit 1
fi

NAMESRV="${NAMESRV:-rmqnamesrv:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"
admin() {
    sh "$MQADMIN" "$@"
}
attempt=0
until admin clusterList -n "$NAMESRV" 2>/dev/null | grep -q "$CLUSTER"; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
        echo "RocketMQ broker did not register within 120 seconds" >&2
        exit 1
    fi
    sleep 2
done

admin updateTopic -n "$NAMESRV" -c "$CLUSTER" -t errand-confirm-timeout -a +message.type=DELAY
admin updateTopic -n "$NAMESRV" -c "$CLUSTER" -t errand-auto-settle -a +message.type=DELAY
admin updateTopic -n "$NAMESRV" -c "$CLUSTER" -t errand-fund-event -a +message.type=TRANSACTION
admin updateTopic -n "$NAMESRV" -c "$CLUSTER" -t errand-cache-evict -a +message.type=DELAY

for group in peergrab-timeout-consumer peergrab-autosettle-consumer peergrab-fund-event-consumer peergrab-cache-evict-consumer peergrab-fund-event-push; do
    admin updateSubGroup -n "$NAMESRV" -c "$CLUSTER" -g "$group"
done
