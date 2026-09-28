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

内置的 [Open Model Thread Group](https://jmeter.apache.org/usermanual/component_reference#Open_Model_Thread_Group) 仍被 Apache 标为实验性；`random_arrivals` 是随机到达，**不等同于**此前 Vegeta 的均匀固定间隔。JMX 先留 3 秒无流量预备，再在同一个 JMeter 进程中连续执行预热和采样，避免另起线程组时把全局时间表集中到边界。每个请求校验 HTTP 200、业务 `code=OK`、20 条列表、非空游标以及至少一个种子 ID。登录和路由核验不计入压测样本。

忽略目录 `bench/runs/jmeter-s2-*/` 保存原始 JTL、JMeter 运行日志和 `summary.json`；均仅供私有复核，不提交。JTL 使用字段白名单，不保存 URL、请求头、响应体或错误消息；Bearer 只经子进程环境传递。`summary.json` 用实际启动的请求数作分母，区分 HTTP 非 200、业务断言失败与非 HTTP 错误，并记录各秒启动数及发压端 CPU、内存、FD。只看 JMeter 的目标 RPS 不足以确认实际发出速率；若每秒启动数出现明显空档或补发突刺，结果标记为恶化。JMeter 的 HTTP elapsed 从实际采样开始计时，不包含目标调度时刻到实际发送之间的等待。

可复用的脱敏 JTL 解析器是 [`jtl_summary.py`](jtl_summary.py)。本目录的单元测试可运行 `python3 -m unittest discover -s peergrab-backend/bench/jmeter/s2 -p 'test_*.py'`。JMX 已用本机假 HTTP 服务完成 600 RPS、3 秒预热与 8 秒采样的功能验证；该结果**不代表 ECS 性能**，ECS 指标必须重新执行并单独发布。
