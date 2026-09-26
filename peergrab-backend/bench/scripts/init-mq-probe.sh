#!/bin/sh
# Runs only in the disposable benchmark Compose stack. The probe never joins a business topic/group.
set -eu

MQADMIN=""
for candidate in /home/rocketmq/rocketmq-*/bin/mqadmin; do
    if [ -f "$candidate" ]; then
        MQADMIN="$candidate"
        break
    fi
done
[ -n "$MQADMIN" ] || { echo "mqadmin not found" >&2; exit 1; }

NAMESRV="${NAMESRV:-rmqnamesrv:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"
attempt=0
until sh "$MQADMIN" clusterList -n "$NAMESRV" 2>/dev/null | grep -q "$CLUSTER"; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 60 ] || { echo "RocketMQ broker registration timeout" >&2; exit 1; }
    sleep 2
done

sh "$MQADMIN" updateTopic -n "$NAMESRV" -c "$CLUSTER" \
    -t peergrab-bench-delay-probe -a +message.type=DELAY
sh "$MQADMIN" updateSubGroup -n "$NAMESRV" -c "$CLUSTER" \
    -g peergrab-bench-delay-probe-consumer
