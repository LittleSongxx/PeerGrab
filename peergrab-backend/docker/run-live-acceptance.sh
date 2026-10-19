#!/usr/bin/env bash
# 本机/CI 的"真实栈"验收后端编排。
#
# 为什么 API/Worker 跑在容器里而不是直接 java -jar：
# RocketMQ 5.x 的 broker 注册地址是容器网络地址，宿主机进程通过 127.0.0.1:8081
# 的 proxy 无法可靠拿到可用的 topic 路由；把 API/Worker 放进 compose 网络后
# 与 docker-compose.full.yaml 的部署形态一致，MQ 主通道才能真正被验证。
#
# 用法：
#   bash peergrab-backend/docker/run-live-acceptance.sh up    # 起中间件 + API + worker
#   bash peergrab-backend/docker/run-live-acceptance.sh down  # 停并删除可丢弃栈
#
# 依赖：docker、本机已执行 `mvn -DskipTests package` 生成两个可执行 jar。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT="${PEERGRAB_ACCEPTANCE_PROJECT:-peergrab-live}"
NETWORK="${PROJECT}_default"
MYSQL_PASSWORD="${PEERGRAB_MYSQL_PASSWORD:-peergrab123456}"
APP_IMAGE="${PEERGRAB_RUNTIME_IMAGE:-eclipse-temurin:21-jre}"
# 不写死版本号：jar 名随 Maven 版本变化，glob 保证与 pom 的单一版本源一致。
APP_JAR="$(ls "$BACKEND_DIR"/peergrab-bootstrap/target/peergrab-bootstrap-*.jar 2>/dev/null | head -1 || true)"
WORKER_JAR="$(ls "$BACKEND_DIR"/peergrab-worker/target/peergrab-worker-*.jar 2>/dev/null | head -1 || true)"
COMPOSE=(docker compose -p "$PROJECT" -f "$SCRIPT_DIR/docker-compose.yaml")

usage() {
  echo "usage: $0 {up|down|logs}" >&2
  exit 1
}

stop_containers() {
  docker rm -f "${PROJECT}-app" "${PROJECT}-worker" >/dev/null 2>&1 || true
}

up() {
  for jar in "$APP_JAR" "$WORKER_JAR"; do
    [ -f "$jar" ] || { echo "缺少 $jar；先在 peergrab-backend 执行 mvn -B -ntp -DskipTests package" >&2; exit 1; }
  done
  stop_containers
  "${COMPOSE[@]}" --env-file /dev/null down -v >/dev/null 2>&1 || true
  "${COMPOSE[@]}" --env-file /dev/null up -d --wait mysql redis rmqnamesrv rmqbroker
  COMPOSE_PROJECT_NAME="$PROJECT" COMPOSE_ENV_FILE=/dev/null "$SCRIPT_DIR/init-mq.sh"
  docker image inspect "$APP_IMAGE" >/dev/null 2>&1 || docker pull "$APP_IMAGE"

  local common_env=(
    -e TZ=Asia/Shanghai
    -e "SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/peer_grab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true"
    -e SPRING_DATASOURCE_USERNAME=root
    -e "SPRING_DATASOURCE_PASSWORD=$MYSQL_PASSWORD"
    -e SPRING_DATA_REDIS_HOST=redis
    -e SPRING_DATA_REDIS_PORT=6379
    -e PEERGRAB_MQ_ENDPOINTS=rmqbroker:8081
    -e PEERGRAB_MQ_ENABLED=true
    -e PEERGRAB_AUTH_DEMO_ENABLED=true
    -e PEERGRAB_AUTH_DEMO_PASSWORD_1001="${PEERGRAB_AUTH_DEMO_PASSWORD_1001:-demo1001}"
    -e PEERGRAB_AUTH_DEMO_PASSWORD_2001="${PEERGRAB_AUTH_DEMO_PASSWORD_2001:-demo2001}"
    -e PEERGRAB_AUTH_DEMO_PASSWORD_2002="${PEERGRAB_AUTH_DEMO_PASSWORD_2002:-demo2002}"
    -e PEERGRAB_AUTH_DEMO_PASSWORD_9001="${PEERGRAB_AUTH_DEMO_PASSWORD_9001:-demo9001}"
    -e PEERGRAB_TIMEOUT_CONFIRM_SECONDS="${PEERGRAB_LIVE_CONFIRM_SECONDS:-3}"
    -e PEERGRAB_SETTLE_AUTO_SETTLE_SECONDS="${PEERGRAB_LIVE_AUTO_SETTLE_SECONDS:-8}"
    -e PEERGRAB_TIMEOUT_SCAN_INTERVAL_MS=600000
    -e PEERGRAB_SETTLE_SCAN_INTERVAL_MS=600000
    -e PEERGRAB_TIMEOUT_SCAN_ENABLED=false
  )

  docker run -d --name "${PROJECT}-app" --network "$NETWORK" -p 127.0.0.1:8080:8080 \
    "${common_env[@]}" -e PEERGRAB_WORKER_ID=1 \
    -v "$APP_JAR:/app/app.jar:ro" "$APP_IMAGE" java -Xmx320m -jar /app/app.jar >/dev/null
  docker run -d --name "${PROJECT}-worker" --network "$NETWORK" -p 127.0.0.1:8082:8082 \
    "${common_env[@]}" -e PEERGRAB_WORKER_ID=2 \
    -v "$WORKER_JAR:/app/worker.jar:ro" "$APP_IMAGE" java -Xmx320m -jar /app/worker.jar >/dev/null

  local i
  for i in $(seq 1 60); do
    if curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1 \
       && curl -fsS http://127.0.0.1:8082/actuator/health/readiness >/dev/null 2>&1; then
      echo "API(8080) + worker(8082) ready; compose project=$PROJECT"
      return 0
    fi
    sleep 2
  done
  echo "API/worker 未就绪；日志：" >&2
  docker logs --tail 80 "${PROJECT}-app" >&2 || true
  docker logs --tail 80 "${PROJECT}-worker" >&2 || true
  exit 1
}

down() {
  stop_containers
  "${COMPOSE[@]}" --env-file /dev/null down -v --remove-orphans >/dev/null 2>&1 || true
  echo "acceptance stack $PROJECT removed"
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  logs) docker logs --tail 120 "${PROJECT}-app"; docker logs --tail 120 "${PROJECT}-worker" ;;
  *) usage ;;
esac
