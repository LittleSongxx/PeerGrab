# PeerGrab JMeter 压测入口

当前 **HTTP 发压**统一使用 Apache JMeter 5.6.3 CLI；原 Vegeta 与 Java/Python HTTP 发压器保留作历史报告复现，不能把旧轮次结果改标为 JMeter 成绩。所有会写业务数据的计划只允许[可销毁隔离栈](../README.md)，原始 JTL、临时 JWT CSV 和服务器证据留在 Git 忽略的 `bench/runs/`。详情见 [S2 公网入口](s2/README.md)、[S1/S3/S4 业务负载](business/README.md)、[S6 故障读负载](../S6_FAULT_SMOKE.md)。

| 场景 | JMeter 承担的负载 | 仍由外部验证的部分 |
| --- | --- | --- |
| S1 抢单 | 一人一 JWT／请求 ID，同单单名额同步起跑 | 任务创建、发压前身份校验、数据库名额与抢单行数 |
| S2 广场 | 异机 HTTPS 游标首屏，固定 JMeter 用户池与吞吐定时器控制目标 RPS | 隔离数据与临时路由身份、目标／实际请求数分母、发压端资源 |
| S3 缓存 | 详情均匀／90% 热 Key、发布后读同一任务九次 | 冷热栈准备、MySQL 总查询、详情回源与抽样一致性 |
| S4 结算 | 多任务并发结算及同任务重复结算 | 完整履约准备、持久化账本／托管校验和资金守恒 |
| S5 到期 | **无 HTTP 发压替代物**：这是 Worker 定时消息／扫描的到期实验 | 专用 Java 探针造同刻到期事件并计算状态日志时差，不能拿 JMeter 的 HTTP P99 代替 |
| S6 故障 | Redis 暂停前／中／后详情读由 JMeter 发起 | Python 仅负责受限容器暂停／解冻、单笔业务探针、恢复与资金校验 |

JMeter 的线程数、目标 RPS、已发送请求数是不同概念。S2 的固定用户池可复用连接，但当全部线程等待响应时会低于目标速率，**不等同于严格开放到达模型**；与旧 Vegeta 的连接和定时模型不同，不能直接计算 P99 改善。S1 的 Synchronizing Timer 释放线程并不等于网络请求在同一时刻进入应用；S3/S4 的短批次完成率不叫长期稳态 TPS。每轮同时披露预热、样本窗口、JTL 业务断言、传输错误、客户端资源和数据库真值。

安装二进制时从[Apache 官方下载页](https://jmeter.apache.org/download_jmeter.cgi)获取并核验 SHA-512；负载测试按[Apache 官方建议](https://jmeter.apache.org/usermanual/best-practices.html)使用 `-n` CLI，关闭 GUI 大监听器并只保存必要的 JTL 字段。[当前镜像的 JMeter ECS 实测](../reports/peergrab-current/report-ecs-jmeter-20260928.md)与[先前 Vegeta／Java 的 ECS 报告](../reports/peergrab-current/report-ecs-spectrum-20260928.md)分开归档，不能因为替换了工具就计算性能收益。JMeter 的静态 HTML 测试报告也不等于生产 Grafana 指标看板。
