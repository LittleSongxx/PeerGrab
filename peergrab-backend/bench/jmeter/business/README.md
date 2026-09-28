# JMeter S1 / S3 / S4 业务负载

这四个 JMX 使用 Apache JMeter 5.6.3 的 HTTP 采样器发压；`JMeterFixtureExporter` 在**经 `preflight.py` 核验的独立 Compose 栈**中创建 JWT session、任务和私有 CSV。`run.py` 负责 JMeter CLI 执行、JTL 业务结果分类及数据库验收。需要新轮次时新建隔离项目和卷；CSV 中含 JWT，仅存于被 `.gitignore` 排除的 `bench/runs/`，目录权限 0700、CSV 权限 0600。

在独立压测栈的 Docker 宿主机，从 `peergrab-backend` 目录运行，先按 `bench/S3_S5_ECS_RUNBOOK.md` 或维护窗口总流程建立隔离栈，设置并核验以下变量：

```bash
export PEERGRAB_BENCH_DISPOSABLE=YES
export PEERGRAB_BENCH_PROJECT="$COMPOSE_PROJECT_NAME"
export PEERGRAB_BENCH_BASE_URL="http://127.0.0.1:$PEERGRAB_API_PORT"
export PEERGRAB_TEST_DB_HOST=127.0.0.1
export PEERGRAB_TEST_DB_PORT="$PEERGRAB_MYSQL_PORT"
export PEERGRAB_TEST_DB_PASSWORD="$PEERGRAB_MYSQL_PASSWORD"
export PEERGRAB_TEST_MQ_PORT="$PEERGRAB_RMQ_PROXY_PORT"
export JMETER_BIN=/path/to/apache-jmeter-5.6.3/bin/jmeter
python3 bench/scripts/preflight.py
```

每次运行使用新的输出目录。S3 读计划必须先在全新栈执行一次 `python3 bench/scripts/seed_bench.py s3`，它会生成 100 个任务并重建 Bloom。缓存关、开分别使用不同新栈；热 Key 在缓存开启且已预热的独立轮次运行。以下是可执行入口示例：

```bash
python3 bench/jmeter/business/run.py s1 --users 2000 \
  --output bench/runs/jmeter-s1-round1
python3 bench/jmeter/business/run.py s3-read --threads 50 --iterations 100 \
  --distribution uniform --cache-enabled false --output bench/runs/jmeter-s3-off
python3 bench/jmeter/business/run.py s3-read --threads 50 --iterations 100 \
  --distribution uniform --cache-enabled true --output bench/runs/jmeter-s3-on
python3 bench/jmeter/business/run.py s3-read --threads 50 --iterations 100 \
  --distribution hot90 --cache-enabled true --output bench/runs/jmeter-s3-hot
python3 bench/jmeter/business/run.py s3-mixed --threads 50 --iterations 10 \
  --output bench/runs/jmeter-s3-mixed
python3 bench/jmeter/business/run.py s4 --tasks 200 --threads 32 --same-attempts 8 \
  --output bench/runs/jmeter-s4
```

S1 先通过 30 个不计时任务预热应用发布/抢单路径，再给每个压测线程不同跑腿用户 JWT 和 request ID，等待 Synchronizing Timer 后抢同一个名额；结果要求恰好 1 次成功且数据库 `slot_taken=grabbed_rows=1`。JMeter 的线程释放无法保证真正同时到达，JMeter 连接也没有预建；尤其是 2,000 线程时须同时记录发压端 CPU、内存、线程数和网络，不能把 `2000 / 批次耗时` 称作稳态 QPS。

S3 读计划以固定请求数封闭循环读取预置 ID，`hot90` 精确生成约 90% 的同一任务 ID；S3 混合计划每轮先发布再读同一 ID 九次。响应检查 HTTP 200、`code=OK`、任务 ID 和混合场景状态；跑完核对详情缓存计数，并对混合场景执行抽样缓存检查。`dbLoads` 只计详情回源，并非全站 MySQL 查询数。冷热态和缓存配置不可在同一污染栈中混为同质对照。

S4 在计时前通过真实 HTTP 路径完成发布、抢单、确认、取件、送达，然后由 JMeter 结算不同任务，再对同一任务并发重复结算。JTL 断言要求不同任务全部返回 `SETTLED`、重复任务恰好一次 `SETTLED`，其余只接受幂等/冲突拒绝。`run.py` 复核持久化状态、托管释放、全局钱包守恒、借贷平衡和 `verify_fund.sql` 五条不变量。此处的完成速率是短批次结果，不能称为长期稳定 TPS。

输出含 `manifest.json`、`results.jtl`、`jmeter.log`、`jmeter-console.txt` 和 `summary.json`。JTL 只保留时间、HTTP 状态及脚本生成的有限业务结果枚举，不保存 URL、Bearer 头、响应正文或任意错误信息；解析器拒绝多余列。`summary.json` 只在 HTTP、业务断言及数据库核对全部通过后写入；失败时 `bench_run` 标记 FAIL。`generate_plans.py` 可重新生成四个 JMX。**这些是新压测计划，之前 ECS 上通过 Vegeta/Java 客户端测得的数据不能改标成 JMeter 结果；必须真正运行后才能记录 JMeter 实测值。**

`run.py` 的安全边界是 Docker 宿主机回环接口。如果将 JMeter 发压端移到异机，应通过只连这条经核验回环绑定的 SSH 隧道传输请求，并在宿主机执行相同的 `preflight.py`、造数、JTL/SQL 后验。公开入口压测还需要与宿主机代理路由和维护窗口总控配合，不能直接把 JMX 的 `host` 属性改成生产域名运行。
