# 自动结算批量到期探针

此探针独立测量 `DELIVERED → SETTLED`。S5 测量的是 `LOCKED` 确认超时，两者不能合并统计。造数直接写入一次性隔离库：每单有 `DELIVERED` 任务、`HELD` 托管单、配平的发布托管流水和独立跑腿钱包；因此测量范围是**到期触发、Worker 结算及 MySQL 状态日志**，不包含 HTTP 发布／履约或应用本地消息投递。任务集中在同一时间到期，托管系统账户仍然是共享热点。

在服务器维护窗口使用[压测运行手册](README.md)创建**全新** `peergrab-bench-*` Compose 栈，导出环境变量，并设置 `PEERGRAB_MAINTENANCE_APPROVED=YES`。脚本在造数前核验独立容器、卷、回环端口、数据库标记、Worker MQ 模式，并通过 Docker 确认 `peergrab-prod` 无运行容器。数据库的任务、托管、流水、压测轮次、信用事件和资金 Outbox 必须为空；一个新栈只跑一轮，跑完导出证据并销毁该栈。不要对生产库或其他已有压测数据的栈执行。

从服务器的 `peergrab-backend` 目录构建：

```bash
mvn -pl peergrab-bench -am install -DskipTests
```

**只测扫描：** 新栈的环境文件设置 `PEERGRAB_BENCH_MQ_ENABLED=false`，启动并通过 `preflight.py` 后运行：

```bash
python3 bench/scripts/run_auto_settle_probe.py scan 1000 60 120
```

**MQ 与扫描共同运行：** 另建全新栈，设置 `PEERGRAB_BENCH_MQ_ENABLED=true`，启动并通过预检后运行：

```bash
python3 bench/scripts/run_auto_settle_probe.py combined 1000 180 120
```

参数依次为模式、任务数、到期前准备秒数、到期后最大观察秒数。任务数限 1–2,000；大量定时消息需留足发送时间，若准备期不够，本轮失败且不能选取其中的部分完成数据作为结果。`combined` 在核验过的隔离网络内启动一次性 Maven runner，结束后删除该 runner；扫描仍始终运行，因此其结果**不能归因成纯 MQ**。`scan` 关闭 Worker MQ，仅由扫描推进。记录 Worker 容器实际 `PEERGRAB_SETTLE_SCAN_INTERVAL_MS`，不要把源码默认值当成运行配置。

输出和 `bench_run.summary` 保存到期时间、完成数、未完成／提前／重复／错误状态数，以及到期至 `errand_status_log.created_at` 的 P50／P95／P99／最大值。正确性闸门还逐单检查 `SETTLED` 任务、`RELEASED` 托管、结算三笔流水、信用事件、资金 Outbox，检查全局借贷平衡、系统钱包余额快照和总钱包金额守恒。`peakBusinessBacklog` 是每秒采样的业务待处理任务数，**不是** RocketMQ consumer lag；`combined` 结束时的 `mqadmin consumerProgress` 是单次快照。状态日志时间在结算事务内产生，并非精确的事务提交时间。若报告需要运行中 MQ lag 峰值，应单独持续采样消费进度。

`bench_run.status=PASS` 表示上述正确性闸门通过，不代表延迟达成任何事先未声明的性能目标。运行环境、镜像与提交、服务器 CPU／MySQL 锁等待、原始输出和清理恢复记录应与同一轮结果一并归档。
