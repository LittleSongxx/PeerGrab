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

[PeerGrab ECS 实测与原版历史评测](peergrab-backend/bench/reports/README.md)按来源分别归档。

## 上线与实测

2026-09-27 首次上线版本为 `2370c45`（压测夹具随后修正为 `7da0a92`）：旧库经备份、独立恢复和迁移演练后升级；一笔虚拟交易从发布到结算完成，资金事件进入 v2 普通消息 Topic、持久通知落库，资金不变量全部通过。当时隔离 MySQL/Redis 的后端测试 **214 项通过、0 跳过**，前端与后端生产包构建通过。

随后在同一台 8 vCPU ECS 停站维护、独立数据卷的条件下，针对简历关键指标加做了[完整 HTTPS 路径复测](peergrab-backend/bench/reports/peergrab-current/report-resume-metrics-20260927.md)：**600 RPS × 180 秒 × 3 轮**，每轮 108,000 次请求全部发出且全部 HTTP 200，三轮 P99 为 183／256／93 ms；650 RPS 长档出现 23 次传输超时，不属于零错误档。2,000 人同单抢 1 个名额重复三轮，均恰好 1 人成功、0 超卖；共享钱包热点结算 200 任务／32 线程两轮约 **60 持久化 TPS**，账务校验通过。[校招／实习简历指标卡](peergrab-backend/docs/校招实习简历性能指标-20260927.md)给出可核验的写法与禁用说法。此前的[SSH 转发及 S1–S5 复测](peergrab-backend/bench/reports/peergrab-current/report-ecs-release-20260927.md)仍保留独立口径：缓存热态减少回源，但未证明吞吐提升；MQ 与扫描独占组各完成 1,000 条自然到期任务。这些是个人演示环境的有限窗口测量，不是生产流量容量承诺。

2026-09-28 已部署优化版 `5ace500`，独立 MySQL/Redis 上的 223 项后端测试全部通过。同条件隔离栈各一轮的[优化与复测报告](peergrab-backend/docs/性能优化与复测-20260928.md)显示：候选入队少做重复查询，S4+S1 合并窗口内的 Redis `ZCARD` 调用约减半；1,000 条同刻到期任务的扫描兜底状态日志 P99 从 39.47 秒降至 23.33 秒，均全部完成。2,000 人抢单的端到端尖峰 P99 基本未变，结算单轮结果更慢，不能将这两项写成已提升；公网偶发超时也尚未复测。旧版简历指标保留为历史基线，不代表当前镜像的同口径容量。

## 快速开始

需要 Docker 与 Compose v2：

```bash
cd peergrab-backend/docker
cp -n .env.example .env
docker compose -f docker-compose.yaml -f docker-compose.full.yaml --env-file .env up -d --build
```

打开 `http://127.0.0.1:25173`。演示身份：发单人 `1001 / demo1001`、跑腿 `2001 / demo2001` 或 `2002 / demo2002`、仲裁员 `9001 / demo9001`。开发与测试命令见[后端](peergrab-backend/README.md)和[前端](peergrab-frontend/README.md)文档；已有旧版 Docker 数据请先看[迁移说明](peergrab-backend/docker/UPGRADE.md)。
