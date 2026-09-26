# S5 自然到期评测

`S5TimelineProbe` 在独立、可销毁压测栈中造 `LOCKED` 任务，设定统一的未来到期时刻。它通过数据库 `errand_status_log.created_at` 记录每条任务首次持久化流转的时间，并计算 `实际流转时间 − (locked_at + confirm-seconds)` 的 P50/P95/P99、最大值、提前处理数、重复事件数和卡单数。负值表示提前处理。进度输出的 `locked` 是**业务待处理量**，不是 RocketMQ 消费堆积。

## 运行条件

- 使用 `peergrab-bench-*` 独立 Compose 项目和全新数据卷；先执行该栈的安全预检。绝不连接公开演示站或生产数据库。工具启动时还会调用 `BenchSafety.requireDisposableStack()` 检查实时容器、端口和数据库归属。
- 先建完数据库及业务 Topic；独立的 MQ 探针 Topic/消费组由压测栈的 `mq-probe-init` 服务运行 `scripts/init-mq-probe.sh` 创建。
- Worker 的 `peergrab.timeout.confirm-seconds` 必须与命令里的 `confirmSeconds` 一致。压测机、MySQL 和 RocketMQ 宿主机时间须同步；工具在本机与数据库偏差超过 1 秒时拒绝运行，Broker 偏差需另外监控。
- `mq` 轮：Worker 设置 `PEERGRAB_MQ_ENABLED=true`、`PEERGRAB_TIMEOUT_SCAN_ENABLED=false`，只由定时消息推进。`fallback` 轮：Worker 设置 `PEERGRAB_MQ_ENABLED=false`、`PEERGRAB_TIMEOUT_SCAN_ENABLED=true`，只由兜底扫描推进。压测叠加层通过 `PEERGRAB_BENCH_MQ_ENABLED` 和 `PEERGRAB_BENCH_TIMEOUT_SCAN_ENABLED` 传入这两个变量，运行前会检查容器里的实际值。两轮在**不同全新栈**进行，以免旧消息、旧消费位点或已降低的信用分影响比较。

MQ 轮先在 env 文件中设置 `PEERGRAB_BENCH_MQ_ENABLED=true`、`PEERGRAB_BENCH_TIMEOUT_SCAN_ENABLED=false`，重建全新隔离栈；兜底轮在另一份 env 文件中设 `false`、`true`。构建后在 `peergrab-backend` 目录运行，沿用 [运行手册](README.md)导出的隔离栈环境变量：

```bash
mvn -pl peergrab-bench -am install -DskipTests
python3 bench/scripts/run_mq_probe.py delay 2
python3 bench/scripts/run_mq_probe.py s5 1 20 5 30
```

`run_mq_probe.py` 先在宿主机完整核对 Compose 项目、卷、端口、数据库标记和 Worker 模式，再启动一次性 Maven 容器并核对其只连接该项目的网络；容器内再次校验自身 ID、IP、数据库标记和固定内网目标。RocketMQ 客户端从代理取得容器私网地址，因此 MQ 轮必须在该网络内运行；VPN TUN 改写宿主机路由时，本机映射端口不足以完成 gRPC 路由。脚本结束会删除自己创建的临时 runner，不会清理压测栈。

兜底轮使用宿主机命令 `mvn -pl peergrab-bench exec:java -Dexec.mainClass=com.peergrab.bench.S5TimelineProbe -Dexec.args='fallback 1 20 5 30'`。`count=1` 是连通性试跑，不构成性能结果。正式档位可用 `1000`、`10000`；万条定时消息需要为发送留足 `leadSeconds`，否则工具会将本轮记为失败，不能拿到期后的部分发送数据计算 P99。完整排空后检查并导出 `bench_run.summary`、`bench_run_item` 和状态日志，再销毁压测卷；未处理、提前处理、重复事件或最终状态错误任一非零即失败。`bench_run.status=PASS` 只表示上述正确性检查通过，P99 是否达到实验目标需单独判定。旧 `scripts/seed_s5.sql` 曾把任务直接设置成已过期一小时，只能反映历史兜底清理吞吐；该脚本现已禁用，不能用于新评测。

摘要同时保存 `leadSeconds`、`confirmSeconds`、`timeoutAfterDueSeconds` 和 `dueEpochMs`。若运行中的 Worker 容器显式设置了 `PEERGRAB_TIMEOUT_SCAN_INTERVAL_MS`，还保存其数值和 `worker-env` 来源；未显式暴露时写 `null`，不把源码默认值当作已验证的运行配置。比较两轮时先核对这些参数与 Worker 镜像、配置一致。

此工具测量 Worker 的定时消费或扫描处理至数据库状态落盘，不覆盖抢单 HTTP、应用本地消息表的重发、Redis 候选队列流转。MQ/扫描来源由**单独运行配置**隔离，不能只凭最终数据库状态归因。MQ runner 结束后会只读输出一次 `mqadmin consumerProgress`，包含每队列 `Diff`、`Inflight` 和总量；这是结束快照，不代表运行期间峰值。若要报告积压峰值，正式轮还需按固定间隔采样该命令；进度输出里的 `locked` 仅是业务待处理量。Broker 投递误差可用独立探针 Topic 的 `DelayMessageProbe` 检查，与业务端到端延迟分开报告。

RocketMQ 5.x 定时消息使用毫秒 Unix 时间戳，默认最远 24 小时；消费者组内实例分摊消息，因此探针不能加入业务组：[官方定时消息说明](https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/)、[官方消费者组说明](https://rocketmq.apache.org/docs/domainModel/08consumergroup/)、[官方消费进度说明](https://rocketmq.apache.org/docs/featureBehavior/09consumerprogress/)。
