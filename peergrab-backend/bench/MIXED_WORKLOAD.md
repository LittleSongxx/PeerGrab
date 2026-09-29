# 多用户持续读+发布混合压测

`MixedWorkloadClient` 在固定到达率下同时访问四类真实 HTTP 接口：游标首屏 20%、第 6/21/101 页的后续游标 20%、100 个任务的详情 50%、发布 10%。使用 32 个读用户和 16 个独立发单钱包；热身、造数、获取游标不计入正式采样。客户端记录每类接口的发送/完成、HTTP 状态、业务码、P50/P95/P99、传输错误，以及调度落空、发压端容量拒绝和采样窗口内完成率。结束后核对已确认发布数、持久任务数、全局钱包守恒及借贷平衡。详情缓存计数取自采样窗口，包含 `dbLoads`、`staleReturns` 和 `degradedReads`。

从 ECS 的 `peergrab-backend` 目录执行。先按 [压测运行手册](README.md)创建**全新的** `peergrab-bench-*` 独立栈、导出环境变量并运行 `preflight.py`。发压前设置 `PEERGRAB_MAINTENANCE_APPROVED=YES`，客户端会再次用 Docker 检查 `peergrab-prod` 没有运行容器；生产同机运行时必须先进入已授权的停站维护窗口。不得对 `peergrab-local`、公开站或生产库运行。此场景须独占新的数据卷，因为 S2 种子入口要求任务表与 `bench_run` 为空：

```bash
python3 bench/scripts/seed_bench.py s2 --count 10000
export PEERGRAB_MAINTENANCE_APPROVED=YES
mvn -q -ntp -pl peergrab-bench -am install -DskipTests
mvn -q -ntp -pl peergrab-bench exec:java \
  -Dexec.mainClass=com.peergrab.bench.MixedWorkloadClient \
  -Dexec.args="$PEERGRAB_BENCH_BASE_URL 100 30 1800 256 5000"
```

参数依次为回环 HTTP origin、目标 RPS、热身秒数、采样秒数、最大在途请求和单请求超时毫秒。至少造 2,500 条 S2 任务才能覆盖后续游标。样例是目标 100 RPS、30 秒热身、30 分钟采样；这是**待执行方案**，不是已有实测。程序限制每阶段最多 100 万次计划请求，故较高 RPS 时须缩短采样或分档测量。每轮使用不同的新压测栈与卷，因为测试会创建钱包和任务数据。

同时运行 `python3 bench/scripts/collect_metrics.py --project "$PEERGRAB_BENCH_PROJECT" --duration 1850 --output bench/runs/<new-dir>/resources.jsonl`，并采集 `/actuator/prometheus` 中的 HTTP、Hikari、GC、抢单阶段和鉴权 Timer。正式报告至少记录 Git 提交、镜像、发压端 CPU/线程/网络、实际发送率、每接口业务码和分位数、数据库与 Redis 资源；客户端 JSON 中的 `completedWithinWindowRps` 才是采样窗口内完成率。超过采样窗口才完成的请求仍计入延迟和最终完成数，但不计入该完成率。

`bench_run.summary` 保存不含 JWT 的 JSON 汇总，私有环境口令不会输出。`PASS` 要求热身和采样均没有本地拒绝、传输/业务错误，所有已确认发布都持久化，资金守恒且借贷平衡。若 `sentRps` 低于目标，应先检查发压端 `schedulerMissed`、`capacityRejected` 和资源，不能把目标 RPS 当作实发容量。本场景只覆盖列表、详情和发布的持续混合流量；抢单、履约、结算以及故障恢复须使用各自的专用场景。
