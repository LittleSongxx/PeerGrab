# S2 广场游标首屏：JMeter 5.6.3

本计划仅发送 `GET /api/errands?campusId=1&cursor=&size=20`。包装器先通过 SSH 核验独立 Compose 项目、卷和数据库守卫、至少 10,000 条种子数据，并在临时 HTTPS 路由上验证返回的 20 条任务 ID 与游标。默认只执行这些只读预检；只有额外提供 `--execute --confirm-project <相同项目名>` 才启动负载。临时路由及 ECS 维护窗口由现有运维流程创建和恢复，本脚本不修改 Nginx 或生产数据。

安装 [Apache JMeter 5.6.3](https://jmeter.apache.org/download_jmeter.cgi)，按下载页校验 SHA-512 或 PGP 签名。在**独立发压机**运行，且按 [官方建议](https://jmeter.apache.org/usermanual/get-started.html#running)使用 CLI 模式。先运行预检：

```bash
python3 peergrab-backend/bench/jmeter/s2/run_s2_jmeter.py \
  --project peergrab-bench-example \
  --remote-backend /data/peergrab-bench-example/peergrab-backend \
  --remote-env .env.bench.example \
  --ssh-host USER@ECS_HOST --key ~/.ssh/KEY \
  --direct-base-url https://www.peergrab.cn/__bench_REPLACE_WITH_TEMP_ROUTE/ \
  --jmeter /path/to/apache-jmeter-5.6.3/bin/jmeter
```

核验通过后，以相同参数增加 `--execute --confirm-project peergrab-bench-example --rates 450,600,650 --warmup 20 --sample 60` 做阶梯测试。600 RPS 长稳态三轮可单独指定 `--rates 600 --warmup 20 --sample 180 --repeat 3`。`--rates` 和 `--repeat` 都有上限，异常或恶化会停止后续轮次。

当前默认的 `cursor-first-pooled.jmx` 使用 **128 个可复用连接的 JMeter 用户线程**和 [Constant Throughput Timer](https://jmeter.apache.org/usermanual/component_reference#Constant_Throughput_Timer) 控制目标 RPS。它先等待 3 秒、进入预热，再取采样窗口；末尾另留 10 秒非采样流量，防止停机时中断采样窗口的在途请求。每个请求校验 HTTP 200、业务 `code=OK`、20 条列表、非空游标以及至少一个种子 ID。登录和路由核验不计入压测样本。**定时器只能尝试目标速率，线程数不足或服务变慢时实际到达率会下降**；结果必须同时报告目标、实际开始数、每秒开始数和发压端资源，不能仅凭目标值声称开放模型容量。

最初的 [Open Model 诊断计划](../archive/s2-open-model-invalid.jmx) 已移出当前执行目录。ECS 校准时它为每次请求建立新连接，并在时间表结束时中断在途样本；该轮只能作为**发压模型失配的无效诊断**，不能当应用 100 RPS 失稳证据，也不能与旧 Vegeta 复用连接的结果计算增益。Apache 将 Open Model 标为[实验性](https://jmeter.apache.org/usermanual/component_reference#Open_Model_Thread_Group)。

忽略目录 `bench/runs/jmeter-s2-*/` 保存原始 JTL、JMeter 运行日志和 `summary.json`；均仅供私有复核，不提交。JTL 使用字段白名单，不保存 URL、请求头、响应体或错误消息；Bearer 只经子进程环境传递。`summary.json` 用实际启动的请求数作分母，区分 HTTP 非 200、业务断言失败与非 HTTP 错误，并记录各秒启动数、`Connect > 0 ms` 样本数及发压端 CPU、内存、FD；该字段只是连接建立样本的近似下界，**不是精确新建 TCP 连接数**。预热初秒可能因线程启动低于目标，单独披露；正式采样窗口若实际开始数明显低于目标、出现空档或补发突刺，则标记为恶化。JMeter 的 HTTP elapsed 从实际采样开始计时，不包含目标定时器等待。

可复用的脱敏 JTL 解析器是 [`jtl_summary.py`](jtl_summary.py)。本目录的单元测试可运行 `python3 -m unittest discover -s peergrab-backend/bench/jmeter/s2 -p 'test_*.py'`。本机假服务只验证脚本行为；[当前镜像 ECS JMeter 实测](../../reports/peergrab-current/report-ecs-jmeter-20260928.md)另记真实请求、失败档、资源和恢复证据。

## ECS 本机发压入口

如需 JMeter **运行在目标 ECS 本机**，使用 [`run_s2_jmeter_ecs.py`](run_s2_jmeter_ecs.py)。它不依赖 SSH 或 `aiohttp`，只使用本机 Docker 预检、数据库种子与首屏 ID 交叉校验；要求停产维护窗口、`PEERGRAB_MAINTENANCE_APPROVED=YES`、独立 `peergrab-bench-*` 项目与至少 10,000 条 S2 种子任务。需要导出 [运行手册](../../README.md#建立隔离栈)中的隔离环境变量及隔离用户 1001 的 `PEERGRAB_AUTH_DEMO_PASSWORD_1001`。首次不加 `--execute` 只读验证，不启动 JMeter：

```bash
export PEERGRAB_MAINTENANCE_APPROVED=YES
export PEERGRAB_JMETER_BIN=/path/to/verified/apache-jmeter-5.6.3/bin/jmeter
python3 bench/jmeter/s2/run_s2_jmeter_ecs.py \
  --project "$PEERGRAB_BENCH_PROJECT" --rates 500 --warmup 20 --sample 180 --repeat 3
python3 bench/jmeter/s2/run_s2_jmeter_ecs.py \
  --project "$PEERGRAB_BENCH_PROJECT" --rates 500 --warmup 20 --sample 180 --repeat 3 \
  --execute --confirm-project "$PEERGRAB_BENCH_PROJECT"
```

默认 `loopback-api` 发往 ECS 本机隔离 App 回环 HTTP，适合看应用容量，**不含 Nginx/TLS**。若维护流程已安装精确的临时 Nginx HTTPS 路由，可在两条命令都加 `--transport local-https --https-route "/__bench_<本轮令牌>/"`。此模式的 JMeter 仍在 ECS 上运行，TCP 连接固定到 `127.0.0.1:443`，TLS SNI 和 HTTP Host 保持 `www.peergrab.cn`；JMeter 子进程使用结果目录内仅映射该域名的私有 Java hosts 文件。脚本不会安装、修改或恢复 Nginx 路由。HTTPS 预检会检查证书、回环路由、任务 ID 和游标；每轮负载前后重复验证。JMeter 子进程环境只传最少的 Java 运行变量、Bearer 和路由前缀，不继承隔离库口令。

结果在 Git 忽略的 `bench/runs/jmeter-s2-ecs-*/`，保留 JTL、日志及脱敏 `summary.json`。报告必须写明“**发压器与目标服务同 ECS、共享 CPU/网络资源**”以及 `loopback-api` 或 `local-https`，不能把同机结果与本页异机 JMeter 的数字拼成一条容量曲线。入口在样本失败、采样窗口明显欠发或秒级节奏异常时停止后续轮次；信号中断和异常通过独立进程组清理 JMeter 的 shell 与 Java 子进程。离线安全测试：`python3 bench/jmeter/s2/test_run_s2_jmeter_ecs.py`。
