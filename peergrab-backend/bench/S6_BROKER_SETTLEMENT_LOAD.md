# S6：Broker 暂停期间的持续结算与 Outbox 恢复

此入口只测一台 ECS 上、已核验的全新 `peergrab-bench-*` 隔离 Compose 栈。它在 RocketMQ Broker 暂停时，用固定到达率对**不同已送达任务**发起结算，观察客户端成功率与尾延迟、MySQL 实际提交、`fund_event_outbox` 待发送峰值及最老事件年龄、Broker 恢复后的排空时间和通知端到端延迟。故障控制复用 [S6 冒烟](S6_FAULT_SMOKE.md)的完整容器 ID 核验与独立看门狗。

## 前置条件与命令

必须先按 [压测运行手册](README.md#建立隔离栈)建立**空业务表、新数据卷**的隔离项目并通过 `preflight.py`。App 使用 JWT，头部身份注入关闭，App/Worker 均启用 MQ。维护窗口已有批准、`peergrab-prod` 全部停机；入口会在预检、准备任务期间及暂停前反复确认。只允许主机回环 HTTP 和项目专属数据库，不接受公网 URL。不能在已做过 S6 或其他业务负载的栈重跑，因为入口要求业务表和 `bench_run` 全空。

在 ECS 的 `peergrab-backend` 目录，沿用建栈时导出的 `PEERGRAB_BENCH_PROJECT`、`COMPOSE_PROJECT_NAME`、`PEERGRAB_BENCH_DISPOSABLE`、`PEERGRAB_BENCH_BASE_URL`、`PEERGRAB_TEST_DB_HOST`、`PEERGRAB_TEST_DB_PORT`、`PEERGRAB_TEST_MQ_PORT`，并提供隔离用户 1001、2001 的 `PEERGRAB_AUTH_DEMO_PASSWORD_1001` 和 `PEERGRAB_AUTH_DEMO_PASSWORD_2001`。不要把口令直接写在命令行或日志里。

```bash
export PEERGRAB_MAINTENANCE_APPROVED=YES
python3 bench/scripts/run_s6_broker_settlement_load.py \
  --project "$PEERGRAB_BENCH_PROJECT"
python3 bench/scripts/run_s6_broker_settlement_load.py \
  --project "$PEERGRAB_BENCH_PROJECT" \
  --execute --confirm-project "$PEERGRAB_BENCH_PROJECT"
```

首条命令只读。正式执行前需输入完全相同的项目名；默认先发布、抢单并送达 32 个任务，再让 Broker 暂停最多 30 秒，16 秒内按每秒 2 次请求结算，客户端每次等待最多 8 秒，解冻后最多观察 180 秒。可调参数：`--rate 1..5`、`--load-seconds 8..18`、`--request-timeout 3..8`、`--fault-seconds 25..30`、`--recovery-seconds 30..600`；脚本要求负载时长 + 客户端超时 + 3 秒不大于暂停上限。最高 90 单、每单 100 分，低于初始发布者钱包额度。改变参数要同时记录到报告。

故障只执行 `docker pause`／`docker unpause` 已核验的隔离 Broker 完整 ID；不停止容器、不删卷、不改变生产路由。正常路径和 `SIGTERM`／`Ctrl-C` 都调用解冻；非守护线程看门狗在暂停上限前独立重试。进程被 `SIGKILL`、Docker daemon 停止或机器断电仍需要按 [S6 冒烟中的人工步骤](S6_FAULT_SMOKE.md#记录和通过条件)检查并恢复。脚本不会在客户端超时后自动重试结算，避免把“已提交但未收到应答”误当失败业务重做。

## 结果口径

私有结果写入 Git 忽略的 `bench/runs/s6-broker-load-<project>-<UTC>-<随机值>.json`；不保存口令和 Bearer token。客户端记录**计划请求数、实际开始与完成数、漏发／本地拒绝、HTTP／业务／传输失败、P50／P95／P99**。一次传输超时后仍以 MySQL 查明是否已结算，并单列“超时但已提交”。通过要求每次请求成功应答、所有任务恰好结算一次、资金总额和复式流水等不变式成立、暂停期间确实看到 `PENDING` 积压，并在恢复期限内全部变为 `SENT` 且每单出现两条持久通知。

`outboxPendingPeak` 和 `oldestPendingPeakMs` 是每秒左右查询一次 MySQL 的**采样峰值**，不是无遗漏的真实最大值。`drainAfterUnpauseMs` 从脚本完成解冻后开始计时，以数据库中所有本轮 Outbox 已发送且通知齐备为终点；`notificationEndToEndP99Ms` 从 Outbox 行创建到该任务最后一条持久通知创建，包含整个故障等待窗口。客户端 P99 只统计实际发出的 HTTP 请求，不等于全站容量。单轮通过也不代表跨主机高可用或长时间 Broker 故障 SLA。

运行前可用 `python3 bench/scripts/test_run_s6_broker_settlement_load.py` 执行离线门禁测试。运行后还需保存退出码和 JSON，复核生产启动与公网健康，独立栈清理遵循 [运行手册](README.md#证据与清理)。
