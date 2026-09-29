# S1 独立任务固定到达率抢单

本入口把任务发布、用户 JWT 准备排除在计时窗口外，再对**每个独立任务各发一次抢单**。它回答给定到达率下是否持续完成有效抢单；与“多人争一个名额”的正确性测试分开。一次有限时长通过不代表长期容量。

## 隔离栈与命令

按 [压测运行手册](README.md) 建立**全新** `peergrab-bench-*` 栈并导出 `PEERGRAB_BENCH_PROJECT`、`COMPOSE_PROJECT_NAME`、`PEERGRAB_BENCH_BASE_URL`、`PEERGRAB_TEST_DB_HOST/PORT/PASSWORD`、`PEERGRAB_AUTH_JWT_SECRET`。停生产并声明维护窗口 `PEERGRAB_MAINTENANCE_APPROVED=YES`。API 使用 JWT、关闭 `X-User-Id` 直传身份，MQ 与 Worker 超时扫描开启。脚本会核验隔离 Compose 标签、网络、独立数据卷、回环端口和空白业务表；只访问经核验的本机 API。每一轮用新的栈和新的输出目录。

在 `peergrab-backend` 目录先做只读预检，再执行：

```bash
python3 bench/scripts/run_s1_distinct_fixed.py \
  --project "$PEERGRAB_BENCH_PROJECT" \
  --output bench/runs/s1-fixed-50rps \
  --rate 50 --seconds 15 --max-in-flight 64
python3 bench/scripts/run_s1_distinct_fixed.py \
  --project "$PEERGRAB_BENCH_PROJECT" \
  --output bench/runs/s1-fixed-50rps \
  --rate 50 --seconds 15 --max-in-flight 64 \
  --execute --confirm-project "$PEERGRAB_BENCH_PROJECT"
```

`rate × seconds` 最多 900 单，最大在途 128，请求超时默认 8 秒、上限 10 秒；示例需要 750 单和 75,000 分托管额度，独立种子钱包有 100,000 分。夹具导出器在计时前顺序发布任务并给每单分配独立跑腿用户 JWT 和 request ID；令牌只保存在权限为 0700 的 Git 忽略目录。正式执行期间不自动重试写请求，也不做生产路由或公网请求。

计时客户端使用 Python `urllib` 的独立 HTTP 请求，连接模型与 JMeter 的复用连接批次不同，两者 P99 不直接比较。发压端与目标同机时还应同时记录 CPU、线程数、Hikari 等待和 HTTP 指标，排除发压端资源不足造成的漏发。

## 判定与口径

单调时钟按 `rate` 定点提供请求；调度过晚不会追赶突发，容量满时明确计入本地拒绝。`summary.json` 记录计划到达 `offered`、实际提交 `sent`、完成 `completed`、窗口内完成数及其每秒速率、调度漏发、本地拒绝、线程实际启动滞后、HTTP/业务结果和客户端 P50/P95/P99。`results.json` 保留不含令牌与响应体的逐请求结果。**全部发出与完成、零调度漏发和本地拒绝、全部业务成功**才可通过；同时按 `bench_run_item` 检查每单 `LOCKED`、名额 1/1、恰有一条 `GRABBED` 且抢中者一致，并核对全局钱包总额、借贷平衡与托管状态。

通过时可写“独立任务抢单在 50 RPS 固定到达率、15 秒窗口内全部完成，P99 为实测值”；不可写成“全站稳定 50 TPS”。单轮 15 秒仍不足以证明长时间稳定性，最高可靠档位应在新隔离栈重复多轮并增加时长；受最多 900 个预付费任务的上限约束，高到达率的单轮时长相应变短。
