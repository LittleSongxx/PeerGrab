# ECS 维护窗口：S3 与 S5 独立栈复测

本手册在 S2/S1/S4 隔离项目已经导出结果并清理、生产容器仍停机时执行。每个变体使用全新 Compose 项目和卷；同一时刻只运行一个隔离项目。以下 1000 条 S5 是首档，不代表 S5 极限。

## 先同步工具和编译

在本机仓库根目录同步本轮尚未推送的工具文件，不覆盖 ECS 环境文件：

```bash
rsync -az --relative \
  -e "ssh -i ${PEERGRAB_BENCH_SSH_KEY:?Set the benchmark SSH key} -o BatchMode=yes" \
  peergrab-backend/bench/scripts/preflight.py \
  peergrab-backend/bench/scripts/run_mq_probe.py \
  peergrab-backend/bench/scripts/summarize_s5.py \
  peergrab-backend/peergrab-bench/src/main/java/com/peergrab/bench/BenchSafety.java \
  peergrab-backend/peergrab-bench/src/main/java/com/peergrab/bench/CacheLoadClient.java \
  peergrab-backend/peergrab-bench/src/main/java/com/peergrab/bench/S5TimelineProbe.java \
  peergrab-backend/docker/docker-compose.bench.ecs-images.yaml \
  "${PEERGRAB_BENCH_SSH_HOST:?Set user@host}:/data/peergrab-bench-max-20260926/"
```

之后在 ECS 的 `/data/peergrab-bench-max-20260926/peergrab-backend` 执行。先核对生产与其他压测容器都停机、所需镜像已在本机，再构建客户端。2026-09-26 只读检查发现 Maven runner 镜像尚未缓存，需在前几项压测结束后先拉取；不得因镜像缺失跳过安全预检。

```bash
set -euo pipefail
test -z "$(docker ps -q --filter label=com.docker.compose.project=peergrab-prod)"
test -z "$(docker ps -q --filter label=org.peergrab.bench.disposable=true)"
docker pull maven:3.9-eclipse-temurin-21
docker image inspect peergrab-prod-app:latest peergrab-prod-worker:latest \
  mysql:8.0.40 redis:7.4-alpine apache/rocketmq:5.3.1 \
  maven:3.9-eclipse-temurin-21 >/dev/null
mvn -B -ntp -pl peergrab-bench -am install -DskipTests
```

## 每个变体共同的建栈步骤

以下使用相同的四个回环端口，**仅在上一变体通过 `cleanup.sh` 清理后**复用。每次更换 `project`，令 `mode` 为 `s3a`、`s3b`、`s5mq` 或 `s5fallback`；这些例子使用 2026-09-26 的唯一项目名。`cache`、`mq`、`scan` 的取值见下表。

| mode | project | cache | mq | scan | confirm |
| --- | --- | --- | --- | --- | --- |
| s3a | `peergrab-bench-s3a0926` | false | true | true | 300 |
| s3b | `peergrab-bench-s3b0926` | true | true | true | 300 |
| s5mq | `peergrab-bench-s5mq0926` | true | true | false | 5 |
| s5fallback | `peergrab-bench-s5fallback0926` | true | false | true | 5 |

```bash
set -euo pipefail
project=peergrab-bench-s3a0926
cache=false
mq=true
scan=true
confirm=300
env_file="docker/.env.$project"
python3 bench/scripts/new_bench_env.py --project "$project" --output "$env_file" \
  --api-port 38110 --mysql-port 33310 --redis-port 36310 --mq-port 38111 \
  --mq-enabled "$mq"
sed -i \
  -e "s/^PEERGRAB_BENCH_CACHE_ENABLED=.*/PEERGRAB_BENCH_CACHE_ENABLED=$cache/" \
  -e "s/^PEERGRAB_BENCH_TIMEOUT_SCAN_ENABLED=.*/PEERGRAB_BENCH_TIMEOUT_SCAN_ENABLED=$scan/" \
  -e "s/^PEERGRAB_BENCH_CONFIRM_SECONDS=.*/PEERGRAB_BENCH_CONFIRM_SECONDS=$confirm/" \
  "$env_file"
test "$(stat -c '%a' "$env_file")" = 600
set -a
source "$env_file"
set +a
export PEERGRAB_BENCH_DISPOSABLE=YES
export PEERGRAB_BENCH_PROJECT="$COMPOSE_PROJECT_NAME"
export PEERGRAB_BENCH_BASE_URL="http://127.0.0.1:$PEERGRAB_API_PORT"
export PEERGRAB_TEST_DB_HOST=127.0.0.1
export PEERGRAB_TEST_DB_PORT="$PEERGRAB_MYSQL_PORT"
export PEERGRAB_TEST_DB_PASSWORD="$PEERGRAB_MYSQL_PASSWORD"
export PEERGRAB_TEST_MQ_PORT="$PEERGRAB_RMQ_PROXY_PORT"
compose=(docker compose --env-file "$env_file" \
  -f docker/docker-compose.yaml -f docker/docker-compose.full.yaml \
  -f docker/docker-compose.bench.yaml \
  -f docker/docker-compose.bench.ecs-images.yaml)
"${compose[@]}" config >/dev/null
"${compose[@]}" up -d --wait --no-build --pull never
python3 bench/scripts/preflight.py --require-mq-enabled "$mq" \
  --require-timeout-scan-enabled "$scan" \
  --require-confirm-seconds "$confirm"
```

若某一步失败，停止发压，先确认项目容器状态；不能把现有 `.env.bench.max0926` 改成新项目，也不能复用旧卷。

## S3：缓存关/开

分别在 `s3a`、`s3b` 全新栈运行以下步骤。建栈后 `seed_bench.py s3` 会插入 100 条已发布任务并通过 API 重建 Bloom。为了分开看冷态和热态，每栈连续跑两次相同模式；比较第二轮时，a/b 的 JVM 与连接已预热，b 的缓存已预热。结果来自各栈 `bench_run.summary`；同时保留客户端日志及资源采样。

```bash
set -euo pipefail
python3 bench/scripts/seed_bench.py s3
mkdir -p "bench/runs/$project"
set -o pipefail
python3 bench/scripts/collect_metrics.py --project "$project" --duration 60 \
  --interval 2 --output "bench/runs/$project/resources.jsonl" &
metrics_pid=$!
client_mode=a  # s3b 栈改为 b
mvn -o -q -pl peergrab-bench exec:java \
  -Dexec.mainClass=com.peergrab.bench.CacheLoadClient \
  -Dexec.args="$client_mode $PEERGRAB_BENCH_BASE_URL" \
  | tee "bench/runs/$project/cold.log"
mvn -o -q -pl peergrab-bench exec:java \
  -Dexec.mainClass=com.peergrab.bench.CacheLoadClient \
  -Dexec.args="$client_mode $PEERGRAB_BENCH_BASE_URL" \
  | tee "bench/runs/$project/warm.log"
wait "$metrics_pid"
mysql_id=$(docker ps -q --filter "label=com.docker.compose.project=$project" \
  --filter label=com.docker.compose.service=mysql)
test -n "$mysql_id"
docker exec -i "$mysql_id" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B peer_grab' \
  > "bench/runs/$project/runs.tsv" <<'SQL'
SELECT run_id,status,summary FROM bench_run WHERE scenario LIKE 'S3-%' ORDER BY started_at;
SQL
```

每轮必须是 5000/5000 完成、`fail=0`、`bench_run.status=PASS`。a 应全部回源；b 比较 `cacheHits`、`dbLoads`、P99 和 CPU。两个模式均为封闭循环 50 并发、5000 次请求，`RPS` 是这段请求的完成速率，不能称为稳态极限 QPS。

## S5：MQ 独占/扫描独占

各自重新建全新栈。`s5mq` 设 `mq=true, scan=false, confirm=5`；`s5fallback` 设 `mq=false, scan=true, confirm=5`。两种模式均使用 1000 条任务、提前 90 秒造数、确认超时 5 秒、到期后最长观察 120 秒。预检在探针入口再次验证 Worker 模式和确认超时。

```bash
set -euo pipefail
# 仅 s5mq 项目：先核验独立探针 Topic 的定时消息链路
python3 bench/scripts/run_mq_probe.py delay 2
mkdir -p "bench/runs/$project"
set -o pipefail
python3 bench/scripts/run_mq_probe.py s5 1000 90 5 120 \
  | tee "bench/runs/$project/s5.log"
```

```bash
set -euo pipefail
# 仅 s5fallback 项目：探针不发送 MQ 消息
mkdir -p "bench/runs/$project"
set -o pipefail
mvn -o -q -pl peergrab-bench exec:java \
  -Dexec.mainClass=com.peergrab.bench.S5TimelineProbe \
  -Dexec.args='fallback 1000 90 5 120' \
  | tee "bench/runs/$project/s5.log"
```

每轮结束后，在该栈仍健康时导出到期后每秒完成数：

```bash
set -euo pipefail
mysql_id=$(docker ps -q --filter "label=com.docker.compose.project=$project" \
  --filter label=com.docker.compose.service=mysql)
run_id=$(docker exec -i "$mysql_id" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B peer_grab' <<'SQL'
SELECT run_id FROM bench_run WHERE scenario LIKE 'S5-%' ORDER BY started_at DESC LIMIT 1;
SQL
)
test -n "$run_id"
python3 bench/scripts/summarize_s5.py "$run_id" \
  --output "bench/runs/$project/s5-completions.json"
```

确认 `expected=completed=1000`，`unprocessed=duplicate=premature=wrongState=0`，再比较 P50/P95/P99、到期后逐秒完成数与峰值；MQ 轮同时保存 `consumerProgress` 结束快照。扫描轮有默认 2 秒宽限和每 5 秒一次、每批最多 200 条的调度特性，不能把其延迟直接解释成 CPU 饱和。1k 首档通过后，若要找吞吐拐点，使用**新的项目和卷**逐级做 5k/10k，给 MQ 同步发送足够的 `leadSeconds`（10k 建议至少 300 秒）。

## 清理与恢复顺序

每个变体导出 `bench/runs/$project` 后，在相同导出环境下执行：

```bash
set -euo pipefail
bench/scripts/cleanup.sh --env-file="$PWD/$env_file" \
  --destroy --confirm="$project"
test -z "$(docker ps -q --filter "label=com.docker.compose.project=$project")"
```

若预检拒绝清理，先排查该栈，不要对生产项目运行 `down --volumes`。最后一个隔离项目清理完，再执行私有生产恢复脚本并核对先前的任务行数、钱包总额、MQ 状态与 HTTPS 健康。
