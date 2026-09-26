# PeerGrab 压测运行手册

这些工具只用于**可销毁的独立压测栈**。同机客户端要求本地 Docker 预检；异机 S2 客户端先通过 SSH 核对 ECS 的隔离栈，再走回环隧道或经身份校验的临时 HTTPS 白名单路由。公开演示库与旧项目卷绝不能作为压测目标。

原版历史报告与 PeerGrab 当前实测分开存放，见[评测报告索引](reports/README.md)。

## 建立隔离栈

需要 Java 21、Maven、Python 3、Docker Compose 2.24.4+。以下命令在仓库根目录执行。每一轮使用新的 `peergrab-bench-*` 项目名和新的 env 文件；生成器会创建随机口令、独立端口和权限为 `0600` 的文件。

```bash
python3 peergrab-backend/bench/scripts/new_bench_env.py \
  --project peergrab-bench-trial01 \
  --output peergrab-backend/docker/.env.bench.trial01
set -a
source peergrab-backend/docker/.env.bench.trial01
set +a
export PEERGRAB_BENCH_DISPOSABLE=YES
export PEERGRAB_BENCH_PROJECT="$COMPOSE_PROJECT_NAME"
export PEERGRAB_BENCH_BASE_URL="http://127.0.0.1:$PEERGRAB_API_PORT"
export PEERGRAB_TEST_DB_HOST=127.0.0.1
export PEERGRAB_TEST_DB_PORT="$PEERGRAB_MYSQL_PORT"
export PEERGRAB_TEST_DB_PASSWORD="$PEERGRAB_MYSQL_PASSWORD"
export PEERGRAB_TEST_MQ_PORT="$PEERGRAB_RMQ_PROXY_PORT"

cd peergrab-backend/docker
docker compose --env-file .env.bench.trial01 \
  -f docker-compose.yaml -f docker-compose.full.yaml -f docker-compose.bench.yaml \
  up -d --wait --build
cd ../..
python3 peergrab-backend/bench/scripts/preflight.py
```

预检核对运行中的容器、Compose 叠加文件、独立卷、回环端口、数据库标记及 API 健康；任何一项不符就拒绝发压。`docker-compose.bench.yaml` 不启动网页服务，也不向宿主机发布 RocketMQ remoting 端口。首次构建后，编译客户端：

```bash
cd peergrab-backend
mvn -B -ntp -pl peergrab-bench -am install -DskipTests
```

## 场景与最小试跑

以下命令都在 `peergrab-backend` 目录执行，沿用上面的环境变量。每个场景单独创建全新栈；`seed_bench.py` 要求任务表和 `bench_run` 表为空。试跑数值只检查链路，不是容量结果。

| 场景 | 工作负载和记录口径 | 数据准备 |
| --- | --- | --- |
| S1 | 同一任务尖峰抢单；检查名额、重复抢单和拒绝分类；瞬时完成数不能当稳态 QPS | 客户端准备任务；无需 SQL 种子 |
| S2 | 任务广场阶梯并发（封闭循环）或固定到达率 `S2-OPEN`；分别记录完成率、P99、错误和客户端拒载 | `python3 bench/scripts/seed_bench.py s2 --count 100`；正式轮按目标结果集规模造数 |
| S3 | 任务详情缓存消融：无缓存/有缓存、读写混合和热 Key；比较相同请求量与缓存冷热状态 | `python3 bench/scripts/seed_bench.py s3`，自动重建 Bloom |
| S4 | HTTP 发布、抢单、履约后测结算；对同一任务重复结算，核对资金流水和托管闭环 | 客户端准备任务和账户；独立 JWT 栈 |
| S5 | MQ 定时消息与扫描兜底的自然到期延迟；以预定到期时刻到数据库落盘计算 P50/P95/P99 | 每种模式各用一个全新栈；见 [S5 专项说明](S5_TIMELINE.md) |

优先消融：S1 比较限流开/关及 Sentinel/本地实现；S3 分别比较缓存开/关、分片数 1/4；S4 比较 MQ 开/关对结算响应与资金正确性的影响；S5 分别测独占 MQ 与独占扫描。连接池与 Tomcat 线程数仅在资源采样显示对应瓶颈后逐项调整。每个对照使用新卷和相同种子；S5 的两条独占路径属于不同机制对比，应同时报告模式配置，不宣称只改了一个变量。生产默认的双通道恢复路径仍需另做故障注入测量。

S2 固定到达率最小试跑示例（`1 RPS`、预热 1 秒、采样 2 秒）：

```bash
python3 bench/scripts/seed_bench.py s2 --count 100
mvn -B -ntp -pl peergrab-bench exec:java \
  -Dexec.mainClass=com.peergrab.bench.OpenLoopLoadClient \
  -Dexec.args="$PEERGRAB_BENCH_BASE_URL 1 1 2 8 5000"
```

ECS 同机首轮可在**全新独立栈完成至少 10,000 条 S2 造数和 Maven 编译后**运行：

```bash
python3 bench/scripts/seed_bench.py s2 --count 10000
python3 bench/scripts/run_first_round.py --dry-run
python3 bench/scripts/run_first_round.py
# 完成低档位后，可在同样的安全门禁下单独运行有限的更高档位：
python3 bench/scripts/run_first_round.py --rates 40,80,160
```

执行器默认只向预检通过的回环 API 发 5/10/20 offered RPS；`--rates` 仅允许从 5/10/20/40/80/160/320 中选择不超过三个递增档位。客户端 `--max-in-flight` 默认 32，仅可选 32/64/128；对照轮必须保持同一设置。每档预热 10 秒、采样 30 秒；并行采集容器与宿主机资源。每档前后以低频 GET 检查公开站 `/api/health`；公开站异常或响应明显变慢、宿主机可用内存低于 3 GiB、连续两次 CPU 超过 85%、iowait 超过 10%、资源采集失效或 `bench_run.status` 非 `PASS` 均停止后续档位。独立目录 `bench/runs/first-round-*` 保存每档日志、资源 JSONL、run ID、状态、摘要和停止原因。此轮是同机有限档位试跑，不能当作异机容量结论。

需要找 S2 固定到达率的性能拐点时，使用独立的逐秒采样执行器（仍只允许已预检的回环隔离栈）：

```bash
python3 bench/scripts/run_s2_knee.py --dry-run
python3 bench/scripts/run_s2_knee.py --rates 50,100,200,400,800 \
  --warmup-seconds 30 --sample-seconds 60 --max-in-flight 128
```

每档保存逐秒发起／完成／错误／本地拒载、所有已发送请求的 P50/P95/P99、MySQL 活跃连接与执行线程、app/MySQL/Worker cgroup CPU 限流、容器及宿主机 CPU／内存／磁盘／网络采样，还单独记录发压进程 CPU。在线模式约每 10 秒检查公开站健康；宿主机连续两次 CPU 高于 85%、iowait 高于 10%、可用内存低于 3 GiB、Docker 数据盘可用空间低于 5 GiB、公开站异常或采集失败立即停机。若维护窗口已获授权且 `peergrab-prod` 所有容器确已停机，可显式设置 `PEERGRAB_MAINTENANCE_APPROVED=YES` 并加 `--maintenance`；此模式允许宿主 CPU 达到 100%，但继续检查隔离栈健康、数据库标记、内存、磁盘、iowait、容器身份及生产容器停机状态。测量档错误率高于 5% 或任何客户端本地拒载后停止升档；失败轮写入 `FAIL`。`PASS` 只表示该档未触发停机门槛，零错误情况需看 `zeroError`。`schedulerMissed`、`capacityRejected` 表示同机发压器跟不上，发生时该档不能解释为服务端容量；同机结果仍不能代表公网极限。

S2 是只读场景，线程池消融可在**同一个已验证的隔离项目**保留相同数据库、Worker 和种子，仅重建隔离 app；脚本会核对所有 Compose 叠加文件与 CPU/内存配额，失败时恢复原始配置。ECS 专用资源限制叠加文件属于本机文件，不提交到仓库：

```bash
python3 bench/scripts/run_s2_ablations.py \
  --env-file "$PWD/docker/.env.bench.trial01" \
  --compose-overlay "$PWD/docker/docker-compose.bench.ecs.yaml" --rate 80 --dry-run
python3 bench/scripts/run_s2_ablations.py \
  --env-file "$PWD/docker/.env.bench.trial01" \
  --compose-overlay "$PWD/docker/docker-compose.bench.ecs.yaml" --rate 80 --no-warm-round
```

默认顺序为 Hikari/Tomcat `20/200 → 8/200 → 20/200 → 20/64 → 20/200`；每次先做 20/80 RPS 预热爬坡，再测两轮 80 RPS、`maxInFlight=64`。若实际栈没有第四个 Compose 文件，省略 `--compose-overlay`。S1、S3、S4、S5 的写入或缓存状态对照仍需新卷。

其他入口：`SpikeLoadClient <baseUrl> <concurrency> <slotTotal>`、`RampLoadClient <baseUrl> <concurrencyCsv> <stageSeconds>`、`CacheLoadClient <a|b|c|d> <baseUrl>`、`FundsHttpLoadClient <baseUrl> <distinctCount> <concurrency> [sameTaskAttempts] [timeoutMillis]`。S4 和 S5 的客户端均自行造数；旧 `seed.sql`、`seed_s4.sql`、`seed_s5.sql` 已禁用。S5 的 Worker 到期配置必须与探针命令一致。

ECS 维护窗口的独立执行与正确性门槛见 [S1/S4 手册](S1_S4_MAINTENANCE.md)、[S3/S5 手册](S3_S5_ECS_RUNBOOK.md)。

## 采集与对比

发压时并行运行只读采样器，输出放在忽略目录 `bench/runs/`：

```bash
python3 bench/scripts/collect_metrics.py \
  --project "$PEERGRAB_BENCH_PROJECT" --duration 30 --interval 2 \
  --output bench/runs/trial01/resources.jsonl
```

每轮保存 `bench_run` 的 `run_id`、汇总、逐秒数据和 JSONL；同时记录 Git SHA、镜像、CPU/内存限制、发压机位置、种子规模及配置。[报告模板](reports/TEMPLATE.md)列出正确性与资源字段。`scripts/compare_runs.sql` 替换两个 `run_id` 后可输出**同场景且关键参数一致**的速率和 P99 差值；不同场景、失败轮或 S5 MQ/兜底轮只并列展示，不能直接算提升。消融只改一个配置，使用等量新种子与相同预热、采样时间，至少重复三轮。当前同机发压只能提供同机基线，不能推断公网或异机容量。

封闭循环并发会随服务变慢而自动降低请求到达率；要测固定流量下的排队、拒载和拐点，使用 `S2-OPEN` 并同时报告 `offered/s`、`completed/s` 和 `dropped`。[k6 对开放/封闭模型的说明](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/)可作为实验口径参考。

## 异机 S2 固定到达率

目标 ECS 的资源以每轮报告为准：[4 vCPU／50 Mbps](reports/peergrab-current/report-ecs-max-20260926.md)与[升级后的 8 vCPU／100 Mbps](reports/peergrab-current/report-ecs-8cpu-20260927.md)分开记录。先建立、预检和造数至少 10,000 条的**独立栈**；本机需有 Python 3、[Python 发压依赖](requirements.txt)、SSH 私钥及已核对的 ECS host key。先设置 `PEERGRAB_BENCH_SSH_HOST=user@host` 与 `PEERGRAB_BENCH_SSH_KEY=/path/to/key`，不要把个人主机或密钥路径写进仓库。以下命令在**发压工作站**执行，远端路径换成实际 checkout；`--dry-run` 只读预检和造数，不建立隧道、不登录、不发压。

```bash
python3 peergrab-backend/bench/scripts/run_remote_s2.py \
  --project peergrab-bench-max0926 \
  --remote-backend /data/peergrab-bench-max-20260926/peergrab-backend \
  --remote-env .env.bench.max0926 --rates 20,40,80 --dry-run
python3 peergrab-backend/bench/scripts/run_remote_s2.py \
  --project peergrab-bench-max0926 \
  --remote-backend /data/peergrab-bench-max-20260926/peergrab-backend \
  --remote-env .env.bench.max0926 --rates 20,40,80
```

客户端在每档之前、预热之后、档位之后及运行中每 10 秒重验远端容器、卷、数据库标记与回环绑定；检测到身份变化即停止。SSH `-L` 只把本机随机回环端口转给经验证的 ECS 回环 API，**无需新增安全组端口**。`--direct --direct-base-url https://www.peergrab.cn/__bench_<随机前缀>/` 可在有备份、仅允许发压机 IP、仅开放登录和列表两个精确路径的临时 HTTPS 代理上发压；脚本会先把公网列表 ID 与经 SSH 核对的隔离库 ID 比较。该路由测后必须删除，生产配置不能指向压测库。逐档升压；首档出现 5xx、业务错误、超时、网络异常、发压端在途名额拒载或采样窗口完成率低于 95% 即停止。结果保存每秒原始计数、`okPerSecInWindow`（成功 API QPS）、成功请求及全部尝试的 P50/P95/P99、超时阈值、Python 与 SSH 进程 CPU、近似响应体 Mbps 到 `bench/runs/`。密码和令牌只存在内存中，不写入结果。

判读拐点时，`schedulerMissed` 是客户端未按时送出的请求，`capacityRejected` 是本地 `maxInFlight` 拒载，两者都不能算服务端失败。只有按时送出的请求仍持续积压，才能把吞吐平台和 P99 上升作为服务端拐点。同步采 ECS 的整机 CPU、网卡、容器 CPU、数据库活跃连接与线程；与当轮带宽上限比较，`responseMbpsInWindowApprox` 只是响应体近似值。SSH 加密会占用 ECS CPU，因此 SSH 隧道轮与直连 HTTPS 轮不得混算容量。[2026-09-26 ECS 极限轮](reports/peergrab-current/report-ecs-max-20260926.md)记录当日配置和结果。

`run_remote_s2_vegeta.py` 提供另一个固定到达率发压器，默认只做隔离栈和临时路由的身份预检；真正发压还要求 `--execute --confirm-project <隔离项目名>`。它只保存汇总 JSON，`status=0` 统计为无 HTTP 响应，不能算 HTTP 200 或应用 5xx。长档应同时报告采样秒数和全部错误数。

正式极限测量应安排公开站可承受的维护窗口，或使用同规格独立目标机；生产站与隔离栈同驻时，结果必须写明资源配额及公开站的并发负载。[Docker Compose 项目隔离说明](https://docs.docker.com/compose/how-tos/project-name/)用于确认容器、网络和卷的命名边界。

## 清理

导出所需结果后，在仓库根目录执行下列命令。脚本再次执行实时预检，只删除确认过的项目容器、网络和三个专属卷；删除后该轮数据不可恢复。

```bash
peergrab-backend/bench/scripts/cleanup.sh \
  --env-file="$PWD/peergrab-backend/docker/.env.bench.trial01" \
  --destroy --confirm="$PEERGRAB_BENCH_PROJECT"
```
