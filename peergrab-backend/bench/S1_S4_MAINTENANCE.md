# ECS S1 / S4 维护窗口补测

在 **S2 极限轮和前端补测结束、结果已导出后**，使用现有 `peergrab-bench-max0926` 独立栈补测 S4 资金结算与 S1 抢单尖峰。公开生产栈必须保持停机。脚本不改 Compose、不重置数据；只向已通过 `preflight.py` 的回环 API 发压。它先测 S4，后测 S1，因为 S1 会给演示跑腿人留下未履约的抢单记录，可能触发其进行中任务上限。

## 执行

在 ECS 的 `/data/peergrab-bench-max-20260926/peergrab-backend` 执行；先确认此脚本和对应 Maven 类已经部署、编译。不要把 `.env` 内容或口令贴进报告。

```bash
set -a
source docker/.env.bench.max0926
set +a
export PEERGRAB_BENCH_DISPOSABLE=YES
export PEERGRAB_BENCH_PROJECT="$COMPOSE_PROJECT_NAME"
export PEERGRAB_BENCH_BASE_URL="http://127.0.0.1:$PEERGRAB_API_PORT"
export PEERGRAB_TEST_DB_HOST=127.0.0.1
export PEERGRAB_TEST_DB_PORT="$PEERGRAB_MYSQL_PORT"
export PEERGRAB_TEST_DB_PASSWORD="$PEERGRAB_MYSQL_PASSWORD"
export PEERGRAB_TEST_MQ_PORT="$PEERGRAB_RMQ_PROXY_PORT"
export PEERGRAB_MAINTENANCE_APPROVED=YES
python3 bench/scripts/run_s1_s4_maintenance.py --dry-run
python3 bench/scripts/run_s1_s4_maintenance.py
```

也可用 `--only s4` 或 `--only s1` 单独执行。每次启动都会重新采集本次基线；不要把前一次失败后的环境当作全新、同质数据集。结果目录为忽略提交的 `bench/runs/s1-s4-maint-<UTC>/`，包括每档 Maven 日志、`runId`、`bench_run.summary`、`verify_run.sql` 和 `verify_fund.sql` 输出及 `manifest.json`。归档前保留该目录。

## 档位与判读

| 场景 | 档位 | 有效指标 |
| --- | --- | --- |
| S4 | 20 个任务 / 4 线程；50 / 8；100 / 16 | 数据库持久化结算 TPS、请求 P99、系统错误、同任务幂等拒绝；结算、退款、借贷和托管状态全通过 |
| S1 | 16、64、128 人抢同一任务的 1 个名额 | 抢中恰好 1、超卖 0、系统错误 0、拒绝分类和 P99；瞬时 `请求数 ÷ 耗时` 不算稳态 QPS |

每档前后重新核对生产容器停机、压测栈身份与容器 ID、可用内存至少 3 GiB、Docker 数据盘至少 5 GiB、S2 原有 10,000 条任务的完整行哈希与状态、任务／抢单／托管单／账本行数、全局钱包总额和流水借贷差额。压测期间每 5 秒重查环境。任一 Maven 退出异常、超时、SQL 校验失败、`bench_run.status != PASS`、S2 种子变化或生产容器重新启动，均终止后续档位。脚本不会自动清理压测栈，也不会恢复生产；测完按维护总流程导出结果、清理隔离栈并恢复生产。

这些客户端是有限并发的封闭循环/瞬时竞争，能给出本 ECS 上相应路径的正确性和结算吞吐，不能单独确定 S1/S4 固定到达率的容量拐点。需要拐点时，另写开放到达率负载并持续记录 offered/completed、排队和错误。
