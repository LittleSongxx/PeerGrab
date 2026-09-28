# S6：隔离栈小流量故障与恢复冒烟

此入口验证 **Redis 暂不可用时一个真实抢单能否由 MySQL 裁决**，以及 **RocketMQ Broker 暂不可用时一笔真实结算能否提交、资金 outbox 能否在恢复后投递并生成持久通知**。它每种故障只做一笔业务，不是稳态负载、主从切换、跨主机高可用或 RPO/RTO 测量。历史 [S6 容量方案](../docs/压测方案与容量评估.md)仍是待执行矩阵；不能把本冒烟说成全部 S6 已完成。

## 建栈与门禁

每种故障各用一个**全新、可销毁**的 `peergrab-bench-*` Compose 项目和新卷。按[压测运行手册](README.md#建立隔离栈)生成 env、启动栈、导出 `PEERGRAB_BENCH_*` 与 `PEERGRAB_TEST_*` 环境变量。应用必须是 JWT 模式、`X-User-Id` 后门关闭，API 与 Worker 的 MQ、Worker 超时扫描都启用。业务表与 `bench_run` 必须为空，发布者和跑腿者使用隔离库内种子钱包。先停生产栈并确认维护窗口；脚本要求 `PEERGRAB_MAINTENANCE_APPROVED=YES`，且 Docker 中没有运行的 `peergrab-prod` 容器。

在 `peergrab-backend` 目录运行。第一次不加 `--execute`，只有只读预检；它会重新核对项目名、Compose 叠加文件、容器与卷标签、回环端口、跨容器网络、数据库 `bench_guard` 和 API 健康。正式执行还要求再次写出完全相同的项目名。

```bash
export PEERGRAB_MAINTENANCE_APPROVED=YES
python3 bench/scripts/run_s6_fault_smoke.py \
  --project "$PEERGRAB_BENCH_PROJECT" --fault redis
python3 bench/scripts/run_s6_fault_smoke.py \
  --project "$PEERGRAB_BENCH_PROJECT" --fault redis \
  --execute --confirm-project "$PEERGRAB_BENCH_PROJECT"
```

Redis 轮完成并导出证据后，**另建全新项目与卷**，再执行 MQ 轮：

```bash
python3 bench/scripts/run_s6_fault_smoke.py \
  --project "$PEERGRAB_BENCH_PROJECT" --fault mq
python3 bench/scripts/run_s6_fault_smoke.py \
  --project "$PEERGRAB_BENCH_PROJECT" --fault mq \
  --execute --confirm-project "$PEERGRAB_BENCH_PROJECT"
```

默认故障窗口 30 秒，可选 `--fault-seconds 20..30`；恢复观察最多 180 秒，可选 `--recovery-seconds 30..600`。只有一个已核对完整 ID 的 Redis 或 Broker 容器被 `docker pause`；脚本不使用 `docker stop`、`compose down`、`-v`、网络规则、生产路由或公网请求。故障内的业务写请求设置 8 秒超时，详情读探针设置 3 秒超时；独立看门狗会在上限前尝试解冻，超时恢复的轮次标为失败。故障期间的工作负载异常会触发立即解冻；正常流量完成后维持剩余故障窗口。`SIGTERM`/`Ctrl-C` 通过清理路径解冻；`SIGKILL`、宿主机断电或 Docker daemon 故障无法被进程内 `finally` 捕获。

## 记录和通过条件

正式执行时，脚本每个阶段打印 UTC 标记，并写入 Git 忽略的 `bench/runs/s6-<project>-<UTC>-<随机值>.json`：预检、业务准备、故障开始、故障内请求、解冻、恢复以及前后资金快照。只读 dry-run 不写运行结果。文件不保存登录口令或 Bearer token。必须保存脚本退出码与该 JSON；故障窗口的 Redis/MQ 可用性、Worker 日志及消费者积压曲线若要进一步分析，需要另采样，不能由一次结束快照推断峰值。

Redis 轮先登录并发布 1 单、读取详情预热缓存；随后在暂停前、暂停期间和恢复后分别发起**固定 10 RPS 的 5 秒／15–25 秒／5 秒详情读**。每段记录 `offered`、实际 `sent`、`completed`、发压器调度漏发、本地在途拒发、HTTP／业务／传输错误及所有完成尝试的 P95；调度落后时不追赶补发，避免形成突发。请求按发起阶段归类，P95 不是 Redis 内部耗时。暂停期间并行执行一次真实抢单。恢复后，数据库必须是 `LOCKED`、`slot_taken=1`、一条 `GRABBED` 记录，详情最终显示新状态。MQ 轮先完成发布→抢单→确认→取货→送达，Broker 暂停后结算；提交时 `fund_event_outbox` 必须为 `PENDING`。恢复后，任务保持 `SETTLED`、恰有 3 条结算流水、outbox 为 `SENT`，发单人和跑腿者各有一条持久通知。两轮前后都检查钱包总额不变、全局借贷平衡、系统账户快照一致、托管状态闭环和名额未超出上限。任何一项失败即 `FAIL`；超时后仍可能已提交，因此以数据库事实为准，不自动重发带新请求 ID 的业务写入。

如果执行进程被 `SIGKILL` 后容器仍暂停，只对终端／JSON 中记录的**完整容器 ID**做人工恢复。先用 `docker container inspect <ID>` 核对 `com.docker.compose.project` 是本轮项目、`com.docker.compose.service` 是本轮 `redis` 或 `rmqbroker`、`org.peergrab.bench.disposable=true` 且 `State.Paused=true`，再执行 `docker unpause <ID>`。身份不一致时不要执行。恢复后重新运行只读预检，并检查前述业务与资金事实；保留失败现场而不是清除整个项目。

通过此冒烟只能表述“在指定隔离栈的一次 30 秒单节点服务暂停中，一笔业务提交和恢复检查通过”。真实 S6 仍需多轮稳态负载、Redis 网络延迟及主从切换、MQ 故障下连续积压与排空曲线、MySQL 连接受限、Worker 强杀、跨主机恢复，并分别计算故障窗口和恢复窗口的延迟与错误率。
