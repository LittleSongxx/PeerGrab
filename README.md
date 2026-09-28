# PeerGrab

校园跑腿交易平台：发布悬赏、资金托管、并发抢单、履约结算、退款与仲裁。

[在线体验：www.peergrab.cn](https://www.peergrab.cn)

![PeerGrab 任务广场](docs/assets/screenshots/square.png)

## 核心流程

| 发布与托管 | 抢单与履约 | 结算与争议 |
| --- | --- | --- |
| 发单人发布任务，悬赏同步托管 | 多人竞争一个名额；跑腿确认、取货、送达，超时可递补 | 发单人确认结算；取消则退款，争议交由仲裁员处理 |

## 功能实拍

|  |  |
| --- | --- |
| [![任务广场](docs/assets/screenshots/square.png)](docs/assets/screenshots/square.png) 任务广场 | [![发布任务](docs/assets/screenshots/publish.png)](docs/assets/screenshots/publish.png) 发布与托管 |
| [![任务详情](docs/assets/screenshots/detail.png)](docs/assets/screenshots/detail.png) 履约进度 | [![钱包流水](docs/assets/screenshots/wallet.png)](docs/assets/screenshots/wallet.png) 钱包流水 |
| [![信用分](docs/assets/screenshots/credit.png)](docs/assets/screenshots/credit.png) 信用分 | [![消息通知](docs/assets/screenshots/notifications.png)](docs/assets/screenshots/notifications.png) 实时通知 |
| [![争议仲裁](docs/assets/screenshots/arbitration.png)](docs/assets/screenshots/arbitration.png) 争议仲裁 |  |

## 架构

```mermaid
flowchart LR
    U[React / TypeScript] --> N[Nginx]
    N -->|REST / WebSocket| P[Spring Boot API]
    P --> A[应用用例]
    A --> D[领域模型与状态规则]
    A --> I[基础设施适配器]
    I --> M[(MySQL)]
    I --> R[(Redis)]
    I --> Q[RocketMQ]
    Q --> W[独立 Worker]
    W --> A
```

API 与 Worker 复用应用用例；领域层定义状态规则和端口，基础设施层实现 MySQL、Redis、MQ 适配。ArchUnit 在测试中检查依赖边界。当前资金链路基于单 MySQL 本地事务。

- **发布即托管**：任务、托管单、账户扣款和复式分录在同一事务提交；金额以整数分保存，`X-Request-Id` 防止发布重试重复扣款。
- **并发抢单**：鉴权后先做热点限流，再校验信用与在途资格；Redis Lua 原子预占名额，MySQL 状态与版本 CAS 加唯一索引最终裁决；Redis 不可用时仍可由数据库裁决。
- **状态机驱动交互**：领域层约束任务流转，后端按状态和当前身份计算 `availableActions`；前端据此显示按钮，不另写一套状态规则。
- **资金防重与对账**：结算以任务状态 CAS、托管单状态 CAS、流水业务号唯一约束防重复入账；账户按固定顺序更新，后台核对借贷平衡、余额快照和托管闭环。
- **可靠异步**：超时递补和自动结算同事务登记本地消息，再交给 RocketMQ 定时投递；发送失败重试、Worker 扫描兜底，版本与轮次拦住过期消息。资金事件与账务同事务写入 outbox，MQ 恢复后由 Worker 投递。
- **缓存与通知**：详情采用 Cache Aside、逻辑过期和互斥回填；可选布隆判否仍核实 MySQL，默认关闭以减少冷查询开销；缓存不参与抢单裁决。WebSocket 经 Redis Pub/Sub 跨 API 副本分发，持久消息与定期轮询补齐实时事件的丢失。

## 技术栈

| 部分 | 技术 |
| --- | --- |
| 前端 | React 18 · TypeScript · Vite 5 · WebSocket |
| 后端 | Java 21 · Spring Boot 3.5.8 · Maven |
| 数据与消息 | MySQL 8 · Redis 7 · RocketMQ 5 |

[当前镜像 JMeter ECS 复测](peergrab-backend/bench/reports/peergrab-current/report-ecs-jmeter-20260928.md)、[工具迁移前 ECS 六类场景实测](peergrab-backend/bench/reports/peergrab-current/report-ecs-spectrum-20260928.md)及[按版本归档的历史评测](peergrab-backend/bench/reports/README.md)分别说明工具、负载和结果。

## 上线与实测

2026-09-27 首次上线版本为 `2370c45`（压测夹具随后修正为 `7da0a92`）：旧库经备份、独立恢复和迁移演练后升级；一笔虚拟交易从发布到结算完成，资金事件进入 v2 普通消息 Topic、持久通知落库，资金不变量全部通过。当时隔离 MySQL/Redis 的后端测试 **214 项通过、0 跳过**，前端与后端生产包构建通过。

2026-09-28 已部署优化版 `5ace500`，独立 MySQL/Redis 上的 223 项后端测试全部通过。[工具迁移前 ECS 六类场景实测](peergrab-backend/bench/reports/peergrab-current/report-ecs-spectrum-20260928.md)由 Vegeta、Java 客户端和 Python 探针取得，包含公网偶发超时、热点抢单的秒级尾延迟、MQ／扫描到期流转以及 Broker 暂停时“结算已提交、客户端却超时”的故障反例；保留原工具和计时口径。

随后在同一业务镜像上完成[独立的 JMeter ECS 复测](peergrab-backend/bench/reports/peergrab-current/report-ecs-jmeter-20260928.md)：广场游标首屏经异机公网 HTTPS，在 **500 目标 RPS × 180 秒 × 3 轮**中实际启动 **269,608 次，全部通过 HTTP 和业务断言**，最差轮 P99 **255 ms**；600 目标 RPS 长档第二轮出现 **1 次连接超时**，按门禁停止第三轮。2,000 个 JMeter 用户线程同单抢 1 名额恰好 1 人成功、0 超卖，但实际请求开始横跨 2.228 秒、P99 **8.735 秒**；共享钱包热点结算 200 单／32 线程短批次为 **34.72 持久化 TPS**，资金校验通过。不同工具的连接复用与到达模型不同，不能用两份报告直接计算优化收益或全站容量。

[JMeter 校招／实习简历指标卡](peergrab-backend/docs/校招实习简历性能指标-JMeter-20260928.md)给出新轮次的可追溯表述。[原工具指标卡](peergrab-backend/docs/校招实习简历性能指标-20260928.md)、[2026-09-27 简历专项复测](peergrab-backend/bench/reports/peergrab-current/report-resume-metrics-20260927.md)与[旧版指标卡](peergrab-backend/docs/校招实习简历性能指标-20260927.md)保留为历史基线；代码、路径、工具和负载窗口不同，不能直接计算优化增益。这些有限窗口实验不是全站容量或生产 SLA 承诺。

当前 HTTP 发压入口是 [JMeter 5.6.3 场景计划](peergrab-backend/bench/jmeter/README.md)；S5 的 Worker 定时事件仍由专用探针测量。[可观测性现状评估](peergrab-backend/docs/可观测性现状评估-20260928.md)区分了已有 Micrometer 指标与尚未部署的持续看板、告警和链路追踪。

## 快速开始

需要 Docker 与 Compose v2：

```bash
cd peergrab-backend/docker
cp -n .env.example .env
docker compose -f docker-compose.yaml -f docker-compose.full.yaml --env-file .env up -d --build
```

打开 `http://127.0.0.1:25173`。演示身份：发单人 `1001 / demo1001`、跑腿 `2001 / demo2001` 或 `2002 / demo2002`、仲裁员 `9001 / demo9001`。开发与测试命令见[后端](peergrab-backend/README.md)和[前端](peergrab-frontend/README.md)文档；已有旧版 Docker 数据请先看[迁移说明](peergrab-backend/docker/UPGRADE.md)。
