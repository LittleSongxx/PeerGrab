# PeerGrab 压测运行手册

现有 HTTP 场景主要使用 [Apache JMeter 5.6.3 计划](jmeter/README.md)；持续的[读+发布混合负载](MIXED_WORKLOAD.md)使用新 Java 固定到达率客户端。两种工具的到达分布、连接模型和结果必须分别标注。[ECS JMeter 实测](reports/peergrab-current/report-ecs-jmeter-20260928.md)与[旧工具报告](reports/peergrab-current/README.md)分开归档。S5 测量的是 Worker 到期事件，仍由专用探针造数和核验。预检、SQL 资金核对和故障解冻脚本不属于 HTTP 发压器，继续保留。旧 Vegeta／Java／Python 发压命令集中在[历史运行手册](LEGACY_RUNBOOK.md)，不得把旧数字重标为 JMeter 结果。

压测只允许使用**全新、可销毁的 `peergrab-bench-*` Compose 项目和独立数据卷**。`run_id` 用于追溯，不提供数据隔离；[`cleanup.sh`](scripts/cleanup.sh)仅删除重新核验身份的整套测试栈，不按轮次删业务行。正式 ECS 加压安排停站维护窗口或同规格独立目标机，禁止对演示库或生产数据发压。

## 建立隔离栈

需要 Java 21、Maven、Python 3、Docker Compose v2 及已核验 SHA-512 的[JMeter 官方发行包](https://jmeter.apache.org/download_jmeter.cgi)。以下在仓库根目录执行；端口如被占用，生成 env 时改用新的四个未占用回环端口。

```bash
python3 peergrab-backend/bench/scripts/new_bench_env.py \
  --project peergrab-bench-local01 \
  --output peergrab-backend/docker/.env.bench.local01
set -a
source peergrab-backend/docker/.env.bench.local01
set +a
export PEERGRAB_BENCH_DISPOSABLE=YES
export PEERGRAB_BENCH_PROJECT="$COMPOSE_PROJECT_NAME"
export PEERGRAB_BENCH_BASE_URL="http://127.0.0.1:$PEERGRAB_API_PORT"
export PEERGRAB_TEST_DB_HOST=127.0.0.1
export PEERGRAB_TEST_DB_PORT="$PEERGRAB_MYSQL_PORT"
export PEERGRAB_TEST_DB_PASSWORD="$PEERGRAB_MYSQL_PASSWORD"
export PEERGRAB_TEST_MQ_PORT="$PEERGRAB_RMQ_PROXY_PORT"
export JMETER_BIN=/path/to/apache-jmeter-5.6.3/bin/jmeter
cd peergrab-backend/docker
docker compose --env-file .env.bench.local01 \
  -f docker-compose.yaml -f docker-compose.full.yaml -f docker-compose.bench.yaml \
  up -d --wait --build
cd ../..
python3 peergrab-backend/bench/scripts/preflight.py
```

预检核对项目、容器和卷标签、回环端口、数据库 `bench_guard` 及 API 健康；任何一项不符就拒绝发压。环境文件包含测试口令，权限必须为 `0600`，不得提交。不同的缓存开关、Worker 模式或资金对照要创建**不同的新项目和卷**，而不是只换 `run_id`。

## 执行与核验

以下入口均从 `peergrab-backend` 目录运行；每次使用新的 `bench/runs/` 子目录存放私有结果。

| 场景 | 当前入口 | 结果必须核验的事实 |
| --- | --- | --- |
| S1 抢单 | [JMeter 业务计划](jmeter/business/README.md) `run.py s1`／`s1-distinct` | 同单竞争验证零超卖；独立任务批次验证有效成功数和短批次 TPS，不能互相混算 |
| S1 独立任务固定到达率 | [受保护的固定到达率入口](S1_DISTINCT_FIXED.md) | 计划／实际发出／窗口内完成率、零本地漏发、客户端 P99 和每单数据库唯一抢中 |
| S2 广场列表 | [异机 JMeter HTTPS 计划](jmeter/s2/README.md) | 真实游标首屏、目标／实际开始率、全部失败、发压端与 ECS 资源；临时路由仅限维护窗口 |
| S3 缓存 | [JMeter 业务计划](jmeter/business/README.md) `s3-read`／`s3-mixed` | 读对照先用 `python3 bench/scripts/seed_bench.py s3` 造 100 个 ID；混合轮在新栈自行发布，冷热态、详情回源与 MySQL 总查询分别计数 |
| 持续读写混合 | [多用户固定到达率客户端](MIXED_WORKLOAD.md) | 全新栈先造至少 2,500 条 S2 数据；32 读用户、16 发单用户，首屏/多层游标/详情/发布分别统计，核对持久发布与资金守恒 |
| S4 结算 | [JMeter 业务计划](jmeter/business/README.md) `run.py s4` | HTTP 履约准备不计入结算批次；按数据库持久成功数算短批次 TPS，并核对复式流水、托管与重复结算 |
| S5 到期事件 | [专用时间轴探针](S5_TIMELINE.md) | MQ 与扫描各用新栈，测到期至事务内状态日志；JMeter 的 HTTP P99 不能替代 Worker 到期延迟 |
| S6 故障 | [受限故障冒烟](S6_FAULT_SMOKE.md) | Redis 详情 HTTP 读负载用 JMeter；暂停／解冻、单笔业务与资金核对由有 watchdog 的脚本控制 |

S2 的固定 JMeter 用户池只能**尝试**目标 RPS；服务端变慢、发压器排队或连接耗尽时，实际发出率会偏离目标，必须以 JTL 为分母。S1 的 Synchronizing Timer 放行线程也不保证请求同刻抵达应用。JMeter 使用 CLI `-n` 模式，原始 JTL 只保存字段白名单；业务正确性以响应断言加 MySQL 真值为准。[Apache JMeter 官方最佳实践](https://jmeter.apache.org/usermanual/best-practices.html)。

## 证据与清理

每轮记录业务镜像／提交、JMX 哈希、数据规模、用户或 Key 分布、采样窗口、目标和实际请求、P50／P95／P99、传输／HTTP／业务错误、客户端与服务器资源，以及名额或资金 SQL 校验。历史数字保留原版本和工具标签，失败轮与修正后的有效轮分开。私有归档、SHA-256、保留与清理规则见[评测资产目录](ASSET_CATALOG.md)；公开报告只包含脱敏汇总。

导出并核验需要保留的证据后，在仓库根目录运行：

```bash
peergrab-backend/bench/scripts/cleanup.sh \
  --env-file="$PWD/peergrab-backend/docker/.env.bench.local01" \
  --destroy --confirm="$PEERGRAB_BENCH_PROJECT"
```

脚本再次执行实时预检，只销毁身份匹配的隔离项目与三个专属卷。ECS 维护窗口还必须恢复 Nginx 原配置、生产容器、资金校验与公网健康；清理隔离栈不等于完成生产恢复。
