# PeerGrab 设计演进记录

> 工程决策与踩坑档案。记录“当时为什么这样做、后来被什么事实推翻、最后怎么修”，面向后续维护者，不承担教程和业务介绍职责。
>
> 口径：本文只写仓库里能核对到的代码、配置、提交与报告。**不新造性能数字**；引用到的数字都注明来源文件。无法确证“原始设计稿”的地方，只写落地事实与原因，不补写设计意图。
>
> 基线：Git `HEAD = 7499174`（2026-10-18），工作区仍有未提交改动（前端、文档与 `txmsg-lab` 等）。仓库公开历史从 2026-09-26 的 `4e7729d` 开始；更早的 P1–P7 只能从源码注释、模块结构和文档修复轮次归纳，**不是官方路线图，也不能按本仓库提交时间排序**。

## 1. 文档定位：四份文档各回答什么

| 文档 | 回答的问题 | 不回答的问题 |
| --- | --- | --- |
| [根 README](../../README.md) | 项目是什么、怎么跑、当前上线与实测状态 | 为什么这样设计、踩过哪些坑 |
| [全面深度分析报告](../analysis/PeerGrab-全面深度分析报告.md) | 独立审计发现了什么、H/M/L 问题与修复记录 | 分期演进、基础设施替换方法 |
| [架构设计与技术选型](../architecture/架构设计与技术选型.md) | 当前代码的模块、链路、选型与边界 | 历史决策顺序、失败方案 |
| [压测方法论](../benchmark/methodology.md) / [压测设计](../architecture/design-benchmark.md) | 怎么设计实验、怎么读数字、怎么报告 | 业务链路为什么这么写 |
| 本文 | 分期交付、关键取舍、踩坑与门禁缺口 | 完整教程、逐行代码讲解 |

本文与 [不变式与对照实验](../architecture/invariants-and-control-experiments.md) 的分工：本文讲“为什么变成现在这样”，不变式文档讲“现在必须始终成立什么、用什么实验证明”。

## 2. 一句话定位与技术底座

PeerGrab 是校园跑腿悬赏交易平台：发布即托管、多人抢一个名额、履约后结算、取消退款、争议仲裁，外加信用分、缓存、可靠异步与实时通知。业务链路短，但每个环节都落在并发与一致性的硬点上：抢单超卖可以直接数出来，资金借贷不平可以直接查出来。

运行形态是**模块化单体 + 双进程**：

- 10 个 Maven 子模块（工作区当前 pom；`txmsg-lab` 尚未提交）：`shared`、`domain`、`application`、`infrastructure`、`presentation`、`bootstrap`、`worker` 进入运行包；`bench` 是压测客户端，`sharding-lab` 是离线分片实验，`txmsg-lab` 是事务消息协议仿真，三者都不进入 API/Worker。
- API（`peergrab-bootstrap`，8080）与 Worker（`peergrab-worker`，8082）复用 `application`/`domain`，只装配不同的适配器与调度器。
- 单 MySQL 库承载任务、托管、账户、复式流水、本地消息、资金 outbox、通知与 JWT 会话；Redis 与 RocketMQ 都是可重建或可补偿的派生状态。

## 3. 技术栈：落地与原因

> 仓库里没有可核对的“原始设计稿”。下表因此不设“设计稿”列，只写**实际落地**与**能核对到的原因**。

| 项 | 实际落地 | 为什么这样落地 | 代价 / 边界 |
| --- | --- | --- | --- |
| 运行时 | Java 21；Spring Boot 3.5.8 | 父 POM 固定 `java.version=21`、`spring.boot.version=3.5.8`；Java 21 的虚拟线程用于集成测试与压测客户端 | 生产仍以平台线程为主，虚拟线程不是全站加速器 |
| 架构 | 六边形分层；`domain` 只依赖 JDK | ArchUnit 三条规则守“领域无框架、端口在 domain、model 不依赖 ports” | 边界靠测试与评审，不靠编译期模块系统 |
| 模块 | 运行包 7 个 + `bench` + `sharding-lab` + `txmsg-lab` | 压测、分片与事务消息仿真不污染运行包；API/Worker 复用同一套用例 | 多模块构建比单模块重 |
| 持久层 | JdbcTemplate，手写 SQL | 抢单/结算核心是带条件 CAS，`affectedRows` 语义直接；POM 注释明确“MyBatis-Plus 留到需要替换仓储 API 时再引入” | SQL 与方言绑定，换库要改适配器 |
| 事务 | 应用层 `@Transactional` / `TransactionTemplate`；事务步骤单独成 Bean | 同类自调用会绕过 Spring 代理；Redis 补偿必须在事务外 | 事务边界分散在用例与 Step 类，需要约定 |
| Redis 客户端 | Lettuce + Redisson | Lua/EVAL、ZSET、String 用 Lettuce；布隆重建锁仍用 Redisson；详情重建锁已改为 `SET NX PX + token Lua compare-delete` | 两套客户端增加连接池与观测复杂度 |
| 消息 | `rocketmq-client-java 5.2.0`，Broker 5.3.1 | 只有 5.x gRPC 客户端支持任意时间定时消息；4.x 固定 delay level 做不到“5 分钟后” | 单 Broker；故障时靠 DB 扫描兜底 |
| 一致性 | 同库本地消息表 + 资金事件 outbox + DB 扫描 | 延迟消息与业务事务要同库提交；不引入分布式事务 | 扫描有延迟；outbox 有积压与老化 |
| 鉴权 | 本机默认 Redis Session；生产 `peergrab.auth.mode=jwt`，MySQL 会话为真值 | Redis 故障时仍能校验已签发 JWT；refresh token 一次性轮换、只存 SHA-256 | 每请求查 MySQL，用 `resolve-cache-ms` 本地正结果缓存缓解 |
| 缓存 | 单 Redis、`shards=1`、逻辑过期 + 互斥重建 + 空值 + Bloom + 延迟双删 | 当前单 Redis 实例，多分片只增写放大；缓存不参与抢单裁决 | 冷态 P99 可能高于无缓存；双删只缩小窗口 |
| 分片 | ShardingSphere 5.5.2 只在 `peergrab-sharding-lab` | 运行链路仍是单库；分片规则与算法先离线验证 | 未进生产，不能宣称已分片 |
| 限流 | Sentinel 1.8.8，按 `errandId` 热点参数限流；可切本地固定窗口 | 在 Redis Lua 与 DB CAS 之前挡掉过热流量 | 限流不决定谁抢中；实例级阈值，多实例需重新定参 |
| 金额 | `long` 分；佣金用 `BigDecimal` 十进制乘、`RoundingMode.DOWN` | 避免二进制浮点在 0.29 等费率上少 1 分；余数归跑腿保证“跑腿 + 佣金 == 托管” | 仍无多币种、无部分结算 |
| 前端 | React 18.3 / TypeScript / Vite 5.4 | 后端下发 `role/availableActions`，前端不写第二份状态机 | 前端仍要处理 WS 降级与轮询 |
| 可观测 | Micrometer/Prometheus；Compose observability overlay + 11 条告警 | 指标覆盖 JVM、HTTP、Hikari、业务结果、outbox、延迟消息、对账差异、双删失败 | 无 tracing、无日志聚合、无 Alertmanager |
| 部署 | 单 ECS、Docker Compose；生产只发布前端端口到宿主回环 | 演示与教学定位，单机成本最低 | 无中间件高可用、无自动故障转移 |
| 时区 | app/worker 容器 `TZ=Asia/Shanghai` | 对账、信用校准、保留清理 cron 按东八区解释 | 代码内仍应避免隐式依赖 JVM 默认时区 |

## 4. P1–P10：分期、取舍与验证

> **分期来源说明**：P1–P7 的编号来自源码注释（例如 `GrabErrandUseCase` 写“P7 引入”、`GrabRecord` 写“P1 索引没带 round、P2 暴露”、`DelayTaskPolicy` 写“P2/P3/P4”、`ErrandQueryPort` 写“P6 改造计划”），但本仓库公开提交从 P8 前后才开始，无法用 `git log` 还原 P1–P7 的交付顺序。P8–P10 才是可按提交与报告逐项核对的分期。下面的“验证”既引用当期报告，也引用今天仍可复跑的测试；两者会明确区分。

### P1：骨架与发布托管

**交付内容**

- Maven 多模块与六边形分层；`domain` 零框架依赖，ArchUnit 三条规则进测试。
- 发布即托管：`publish_request` 先占幂等位，钱包按账户 ID 排序锁，复式过账，托管单 `HELD`，任务 `DRAFT→PUBLISHED` CAS，状态日志同事务。
- 提交后才做 Redis 名额初始化、Bloom 登记与缓存失效；Redis/Bloom 失败只告警，DB 是唯一真值。

**关键取舍**

- **模块化单体，不拆微服务**：托管、账本、状态、outbox 都在同一 MySQL 本地事务内闭环，拆开就要引入分布式事务。
- **领域层不知道事务**：事务边界画在应用层，领域对象只表达规则；这让同一套规则能被 API 与 Worker 复用。
- **Redis 是提示，不是账本**：发布提交后写 Redis；写失败不影响发布结果，后续由 DB CAS 兜底。

**验证与数字**

- 现行 `PublishIdempotencyIT.samePublisherAndKeyCreateAndChargeExactlyOnce`：12 个并发同键请求只产生 1 个任务 ID、2 条 `escrow:{id}` 流水，发单人余额只扣一次（10,000→9,900 分）。
- 现行 `ArchitectureRuleTest`：领域无框架依赖、端口位于 domain、model 不依赖 ports，三条规则通过。
- P1 当期的测试数量在仓库文档中没有单独记录，本文不补数字。

### P2：抢单闭环

**交付内容**

- 四层防护：L1 热点限流 → L2 信用/在途资格 → L3 Redis Lua 原子预占 → L4 MySQL `status+version` CAS + `grab_record` 双唯一索引终裁。
- 失败回补 Redis 名额；失败者进候选队列；抢中后登记首轮确认超时消息。
- `grab_record` 同时用 `uk_errand_round_seq`、`uk_errand_round_user` 管名额与同用户重复，另有 `uk_errand_request` 管请求幂等。

**关键取舍**

- **Lua + DB CAS 双保险，而不是分布式锁**：要的是“名额守恒”，不是“互斥”；锁丢失时 CAS 与唯一索引仍能兜住。
- **Lua 成功但 DB 失败必须回滚名额**：补偿放在事务外，事务已回滚，补偿是独立动作；不回滚就会名额泄漏。
- **唯一索引必须带 round**：同一任务回退/递补后 round 自增，索引语义从“同一名额只能占一次”精确为“同一轮内同一名额只能占一次”。
- **候选队列是失败后的路径，不是第五层防护**：它不参与同步裁决，不能拿来把四层说成五层。

**验证与数字**

- 现行 `GrabConcurrencyIT.spike_2000_concurrent_only_one_wins`：2,000 并发抢 1 个名额，应用层成功数 = 1，数据库 `GRABBED` 记录数 = 1，超卖 0，Redis 剩余名额与 DB 一致。
- 现行 `OversellControlExperimentIT` 用错误实现做反向验证：实验 A 把 Lua 拆成 GET/判断/DECR，实验 B 再把 DB 改成无条件 UPDATE；数字已在隔离环境回填（Redis 通过 14、名额余 -13；无条件 UPDATE 成功 22），见 [不变式文档实测表](../architecture/invariants-and-control-experiments.md#4-实测记录2026-10-19隔离-mysqlredis)。

### P3：资金与异步

**交付内容**

- 结算、退款、仲裁三条资金路径；结算佣金向下取整、余数归跑腿。
- 资金三道幂等闸门：任务状态 CAS、托管单状态 CAS、账本业务号唯一（`uk_biz_direction`）。
- 本地消息表 + RocketMQ 延迟消息主通道 + DB 扫描兜底；资金事件 outbox 与账务同事务。
- 三层对账：L1 借贷平衡、L2 系统户/用户户快照、L3 托管闭环；差异落 `recon_diff`。
- 审计日志 `REQUIRES_NEW`；批量自动结算把一页到期任务合并过账。

**关键取舍**

- **同库事务优先于“先进架构”**：资金正确性靠本地事务、唯一键与对账，不靠分布式事务框架。
- **延迟消息与 outbox 分工**：需要延迟的用本地消息表 + 定时消息；不需要延迟的资金事件用 outbox 普通 Topic。
- **批量结算换吞吐，但放大锁持有**：单页批量减少事务数，却让共享 ESCROW/COMMISSION 行锁持有更久；这是当前明确边界。
- **提交后副作用逐项隔离**：缓存、榜单、审计、推送失败只计数告警，不回滚已提交资金。

**验证与数字**

- 现行 `SettleConcurrencyIT.concurrent_settle_same_errand`：200 并发结算同一任务，恰好 1 次成功，`settle:{id}` 流水恰好 3 条，跑腿余额只增加 1900 分。
- 现行 `AutoSettleBatchIT`：2 个到期任务批量结算，2/2 成功；重复调用返回 `(0, 2)`，不重复入账；L1/L2/L3 全绿。
- 当前镜像 JMeter S4：200 个不同任务 / 32 线程短批次，**34.72 持久化 TPS**，P50/P95/P99 = 604/983/1,081 ms，201 个任务持久结算、201 个托管释放，资金校验通过（[JMeter 报告](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-jmeter-20260928.md)）。

### P4：鉴权与实时

**交付内容**

- 双鉴权实现：本机 Redis Session，生产 JWT + MySQL `auth_token_session` 真值；refresh token 256 位随机、DB 只存 SHA-256、一次一用轮换。
- WebSocket：握手 query token + Origin 白名单；每次推送前复核会话；本机连接推送 + Redis Pub/Sub 跨副本；有界队列丢事件只降级。
- `availableActions` 由服务端按身份与状态计算；前端不写第二份状态机。
- 仲裁员角色策略：不能发布、不能抢单、不能裁决自己是当事人的争议。

**关键取舍**

- **JWT 保持服务端状态**：换取“Redis 故障仍能鉴权、退出即吊销”；代价是每请求查 MySQL，用本地正结果缓存缓解。
- **没有用户表**：身份来自 token，仲裁员由配置指定；这让演示简单，也让生产必须关闭 demo 并接真实身份。
- **实时推送可丢，持久通知不丢**：WS 只是体验层，持久消息与轮询兜底才是事实来源。
- **角色校验放在用例层，不只靠按钮隐藏**：前端隐藏按钮不是权限控制。

**验证与数字**

- 现行 `AuthModeIT`、`AuthRefreshIT`、`BackdoorDisabledIT`、`JwtRedisOutageIT` 分别覆盖双模式、刷新轮换、直传身份关闭与 Redis 故障下的 JWT 校验。
- 现行 `ArbitratorSeparationIT` 三条断言：仲裁员不能发布、不能抢单、不能裁决自己是当事人的争议。
- 本节没有可引用的性能数字；前端构建通过属于 P8/P10 的验证。

### P5：缓存与信用

**交付内容**

- 详情缓存：Cache Aside + 逻辑过期 + 互斥重建 + 空值缓存 + TTL 抖动；提交后删除 + 延迟双删；一致性抽样校验含负值缓存核对。
- Bloom 判否短路：未就绪 fail-open；发布登记 3 次退避重试；30 分钟全量校准；重建期间撤销 READY；1% 判否抽样审计。
- 信用分：30 天滑动窗口事件回放，结算 +2、超时未确认 -5、争议败诉 -8；抢单门槛 40；每日校准；校区排行榜。
- Redis 故障时 1 秒进程内详情副本，只用于展示降级，不参与资金与名额裁决。

**关键取舍**

- **缓存一致性只承诺“缩小窗口”**：双删不能消除“慢读在第二次删除之后回填”的窗口，只能降低概率；TTL 与校验 job 是最终兜底。
- **身份相关字段不进共享缓存**：`role/availableActions` 按请求计算，避免缓存串号。
- **布隆判否必须可回源**：判否短路的前提是索引可信；索引未就绪或重建期必须 fail-open，否则真实任务会被误判 404。
- **信用分与资金动作同事务**：结算成功与 +2 分没有中间态；事件消息只用于通知，不用于计分。

**验证与数字**

- 现行 `CacheDoubleDeleteControlIT`：发布登记 1 次双删，抢单再登记 1 次；第二次删除延迟落在 400–700 ms。
- 现行 `CacheStaleReaderWindowIT`：模拟慢读在提交与失效之后回填，缓存仍为 `PUBLISHED`、DB 已是 `LOCKED`，证明窗口真实存在。
- 当前镜像 JMeter S3：缓存关/开各 5,000 次详情读，`Com_select` 增量 **30,460 → 15,526（约 -49.0%）**；冷态 P99 **246 → 479 ms**，热态第三批 129 → 116 ms；90% 热 Key 5,000 次全部命中、P99 105 ms；正式读写混合 500 发布 + 4,500 读，5,000/5,000 成功，P99 1,193 ms，抽样差异 0（[JMeter 报告](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-jmeter-20260928.md)）。

### P6：分片前置、游标与刷新令牌

**交付内容**

- `grab_record`、`escrow_order`、`errand_status_log` 补 `campus_id`，为同片路由准备。
- ShardingSphere JDBC 规则与取模算法放进 `peergrab-sharding-lab`，不进入运行包。
- 任务广场与钱包改游标分页：`created_at DESC, id DESC` 稳定排序；保留旧 `page/size` 兼容路径。
- JWT refresh token；信用分每日校准 job。

**关键取舍**

- **先补分片键语义，再接分片框架**：抢单唯一性依赖 `grab_record`、托管闭环依赖 `escrow_order`、状态时间线依赖 `errand_status_log`；这些表不带校区维度，分片后“同一任务内唯一”很难验证。
- **游标是增量兼容，不是一刀切**：旧前端与旧测试仍走 `page/size`，传 `cursor` 才返回 `{items,nextCursor}`。
- **分片实验不冒充生产能力**：运行链路仍是单库，不能因为 lab 里有规则就说“已分片”。

**验证与数字**

- 现行 `CursorPaginationIT` 覆盖游标翻页与兼容路径。
- 本地深分页对照（100,000 条任务、20,000 条跑腿历史、100,000 条钱包流水）：广场第 90,001 行 `OFFSET 90000` 约 **102 ms**，同位置 `(created_at,id)` 游标约 **0.6 ms**；钱包首屏旧索引全表 100,000 行 + 排序约 **26.8 ms**，新索引 20 行约 **0.5 ms**；跑腿历史覆盖索引仍保留回表与排序，未加入生产 DDL（[query-paging 报告](../../peergrab-backend/bench/reports/peergrab-current/query-paging-local-20260927.md)）。
- `peergrab-sharding-lab` 的规则/算法测试通过，但不属于运行链路验证。

### P7：热点限流与 S2 容量观察

**交付内容**

- `GrabRateLimiterPort` 抽象；Sentinel 热点参数限流与本地固定窗口两种实现，按 `errandId` 限流。
- 限流放在所有下游调用之前；默认单实例单任务每秒 500 次放行；`GrabPhaseMetrics` 记录读任务、信用、在途、名额预占、DB 事务、候选入队等阶段。
- S2 固定到达率/固定线程两种模型的压测资产与场景分工。

**关键取舍**

- **限流只保护下游，不裁决赢家**：谁抢中仍由 L3/L4 决定；限流拒绝是预期业务结果，不是系统错误。
- **默认参数来自压测，不是拍脑袋**：`per-second: 500` 的注释写明“S2 阶梯加压后定参”；但报告没有单独做“限流开/关”的消融，不能把 S2 数字全部归因于 Sentinel。
- **S2 只测稳态读路径**：抢单正确性由 S1 独立验证，不把尖峰数字混进吞吐。

**验证与数字**

- 4 vCPU ECS、生产并行、隔离栈带 CPU 配额：S2 固定到达率 **700 offered QPS × 60 秒**两轮均 42,000/42,000 HTTP 200；**720 QPS** 一轮 43,192 成功 + 8 无响应、重跑 43,200 全成功；**750 QPS** 44,980 成功 + 20 无响应，P99 1,991.2 ms；800 QPS 短档未通过。报告把实用上限记为约 700 QPS、拐点约 700–750 QPS（[4 vCPU 报告](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-max-20260926.md)）。
- 完整公网路径（Host Nginx → 前端 Nginx → App）：**450 QPS × 180 秒** 81,000/81,000 零错误，P99 169.4 ms；**500 QPS × 180 秒** 89,994/90,000，6 次无响应；525 QPS P99 331.1 ms；550 QPS P99 949.4 ms。报告结论是“450 QPS 是经 3 分钟复核的保守持续档位”，不是容量承诺。
- 这些数字是 P7 之后持续压测的结果，不能反过来证明“Sentinel 带来了 700 QPS”。

### P8：生产部署与上线

**交付内容**

- `docker-compose.yaml` + `docker-compose.full.yaml`（本机全栈）与 `docker-compose.prod.yaml`（生产）；Nginx 同源提供前端、`/api`、`/ws`。
- 生产 API 使用 JWT；只发布前端端口到宿主回环，MySQL/Redis/MQ/API 不暴露宿主端口；`/api/internal/` 在前端 Nginx 直接 404。
- 备份、独立恢复、迁移演练；全新卷跑 `init.sql`，已有卷只跑 `migrate-runtime.sql` / `migrate-query-indexes.sql`。

**关键取舍**

- **单 ECS 先上线，不先堆高可用**：演示定位下，单机成本最低；代价是中间件单点、无自动故障转移，文档必须如实写。
- **生产关闭 demo 登录**：演示口令与 9001 只用于本机/公开演示；生产必须接真实身份。
- **迁移不重跑 init**：`init.sql` 只用于全新卷；旧库必须显式执行迁移，避免“重建镜像就以为升级了”。
- **可观测先做指标，不做 tracing**：Prometheus 指标与告警能覆盖资金/异步关键信号；tracing、日志聚合、Alertmanager 明确列为边界。

**验证与数字**

- 2026-09-27 首次上线 `2370c45`：旧卷备份、隔离 MySQL 恢复、迁移演练后停写升级；隔离 MySQL/Redis 上 **214 项测试通过、0 跳过**；前端与后端生产包构建通过（[README](../../README.md)、[后端 README](../../peergrab-backend/README.md)）。
- 2026-09-28 部署优化版 `5ace500`：隔离 MySQL/Redis 上 **223 项测试、0 失败、0 错误、0 跳过**。
- 维护结束生产 7 个核心服务健康，公网 `/api/health` 返回 200；生产核心计数为任务 2、托管单 2、抢单 1、钱包账户 5、流水 9、钱包总额 110,000 分、资金 outbox 1、通知 3（[JMeter 报告](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-jmeter-20260928.md)）。

### P9：压测体系与证据治理

**交付内容**

- 隔离栈工具链：`new_bench_env.py`、`preflight.py`、`seed_bench.py`、`collect_metrics.py`、`cleanup.sh`、`verify_fund.sql`。
- HTTP 压测统一到 JMeter 5.6.3：`bench/jmeter/{business,s2,s6}` 共 7 个现行 JMX；S1/S3/S4 由 `run.py` 驱动，S2 支持异机公网与本机 HTTPS，S6 由 JMeter 发详情读、Python 控制暂停。
- Java 客户端与探针：`SpikeLoadClient`、`CacheLoadClient`、`FundsHttpLoadClient`、`OpenLoopLoadClient`、`MixedWorkloadClient`、`S5TimelineProbe`、`DelayMessageProbe`。
- 报告分层：`peergrab-current`（当前版本）与 `original-project`（迁入前原版）分开；失败轮与有效轮分开；私有证据哈希进 `ASSET_CATALOG.md` 与 `private-evidence-index.json`。

**关键取舍**

- **换工具不重标历史数字**：JMeter 结果与 Vegeta/Java/Python 结果分开归档，不计算跨工具增益。
- **隔离优先于方便**：只允许全新 `peergrab-bench-*` 项目和独立卷；`run_id` 不提供隔离；清理脚本按项目身份销毁，不按轮次删业务行。
- **失败轮公开**：600 RPS 的连接超时、Broker 暂停的 8 秒客户端超时、首个 Redis 故障轮被看门狗判无效，都保留在报告中。
- **公开摘要、私有原始证据**：JTL、JWT CSV、生产备份不入库；公开报告只写脱敏汇总与哈希。

**验证与数字**

- JMeter S2：500 目标 RPS × 180 秒 × 3 轮，实际开始 **269,608** 次、全部 HTTP 200，最差轮 P99 **255 ms**；600 目标 RPS 长档第二轮出现 **1 次连接超时**，按门禁停止第三轮。
- JMeter S1：2,000 线程同单抢 1 名额，成功 1、名额满 1,994、冲突 5、系统错误 0；P99 **8,735 ms**，请求开始跨度 **2,228 ms**，整批 8.869 秒。
- JMeter S4：200 单 / 32 线程短批次 **34.72 TPS**；S5（专用探针）：1,000 单同刻到期，MQ P99 **9.512 s**、扫描兜底 P99 **25.100 s**；S6（同镜像原工具轮）：Redis 暂停期间详情读 P95 **2,013.163 ms**，Broker 暂停时默认配置结算客户端 **8 秒超时**、应用侧 8.204 秒后返回 200 且数据库已提交。
- CI 的 `benchmark-assets` Job 断言 7 个 JMX 可解析，并校验 `generate_plans.py` 可重现四个业务计划。

### P10：三轮审计修复

**交付内容**

- 第一轮（[分析报告 §9](../analysis/PeerGrab-全面深度分析报告.md)）：H 7 项、M 20 项、L 6 类，共 33 条记录；28 条“已修复”、5 条“已缓解并标注边界”。
- 第二轮（§9.4）：登录限速表清理、重发/outbox 预算耗尽释放租约、校区列表空结果缓存、`AutoSettleBatchIT`、旧库升级路径、运行时启动、保留期 SQL 验证。
- 同轮补齐（§9.3）：可观测 overlay，Prometheus `127.0.0.1:29090`、Grafana `127.0.0.1:23000`，11 条告警规则。
- 第三轮（§10）：XFF 伪造绕过、跨轮幂等重放、开户锁序、worker 分账配置、自动结算冲突、递补写抓单记录、confirm/deliver 归属与幂等、消费者毒消息 ACK、`accountType` NULL、`page*size` 溢出、JWT 缓存上限、雪花回拨、佣金精度、退款/仲裁副作用、发布重放真实状态、`recon_diff` 保留、截止时间 NULL 修复、WS 1013、`crypto.randomUUID` 降级、bench 口令分离、文档口径。

**关键取舍**

- **修资金正确性优先于修体验**：H1/H2/H3/H5/H6/H7 先修；M2 共享钱包热点明确“已缓解并标注边界”，不做账务拓扑重构；自动结算 `CONFLICT` 登记 durable retry，不再当幂等成功。
- **账户开通移出结算事务**：消除 wallet→escrow 与 escrow→wallet 的锁序环；抢单事务内仍在 CAS 后开通账户，因为此时锁序是 errand→wallet。
- **Bloom 全量校准每 30 分钟一次**：修复“已就绪但漏登记”，代价是周期性全量扫描；重建期间撤销 READY，读路径 fail-open，避免误杀。
- **保留证据与毒消息处置**：`recon_diff` 不再自动删除，`publish_request` 保留 30 天；消费者对不可恢复状态分类 ACK，避免无限重投。

**验证与数字**

- README 当前口径：`mvn -Dpeergrab.it=true test` 合计 **298 项、0 失败、0 错误、0 跳过，其中 78 项集成测试真实执行**；另验证 API/Worker readiness、保留期 SQL、迁移脚本幂等性。
- 第二轮记录为 **289 项、0 失败/0 跳过**；第一轮分析报告记录为 **267 项、1 跳过（73 项 IT）**；更早的 2026-09-25 冒烟为 141 项、2026-09-27 上线为 214 项、2026-09-28 为 223 项。这些是不同时间点的快照，不是互相矛盾。
- 旧库升级路径：用改动前 `init.sql` 建库 → 执行两个迁移脚本 → 全量 289 项测试通过，校验 `transfer_count`、9001 账户、5 个新索引。
- 当前 HEAD `7499174` 的修复**没有在 README 中声明已部署**；生产仍记录为 `5ace500`。

## 5. 基础设施替换指引

### 5.1 装配方式

项目没有集中式 `@Configuration` 手工 `new` 所有适配器。装配靠两件事：

1. **组件扫描**：API 的 `PeerGrabApplication` 位于 `com.peergrab`，默认扫描 `com.peergrab.**`；Worker 的 `WorkerApplication` 显式扫描 `com.peergrab.worker`、`com.peergrab.application`、`com.peergrab.infrastructure`，**不扫描 presentation**（所以 Worker 不装配 WebSocket）。
2. **条件装配**：适配器用 `@ConditionalOnProperty` 按配置二选一；`@Primary` 只用于 Sentinel 适配器。

因此“替换基础设施”不是改用例，而是：**在 domain 找到/补端口 → 在 infrastructure 写适配器 → 用配置或条件选中 → 跑 ArchUnit 与对应 IT**。`application`/`domain` 不需要改。

### 5.2 端口 → 适配器 → 装配位置

| 能力 | 端口（domain） | 现行适配器 | 选择方式 | 装配位置 |
| --- | --- | --- | --- | --- |
| 鉴权 | `auth/ports/AuthPort` | `RedisAuthAdapter` / `JwtAuthAdapter` | `peergrab.auth.mode=session`（默认）/`jwt` | infrastructure 组件扫描；生产 compose 设 `PEERGRAB_AUTH_MODE=jwt` |
| 刷新令牌 | `auth/ports/RefreshTokenPort` | `JwtAuthAdapter`（`RefreshTokenPort extends AuthPort`） | 仅 `peergrab.auth.mode=jwt` | infrastructure 组件扫描 |
| 延迟消息 | `errand/ports/DelayMessagePort` | `RocketMqDelayMessageAdapter` / `NoopDelayMessageAdapter` | `peergrab.mq.enabled=true/false` | infrastructure 组件扫描 |
| 缓存失效延迟 | `errand/ports/CacheEvictDelayPort` | `RocketMqCacheEvictAdapter` / `NoopCacheEvictAdapter` | 同上 | infrastructure 组件扫描 |
| 详情缓存 | `errand/ports/ErrandCachePort` | `RedisErrandCacheAdapter` / `NoopErrandCacheAdapter` | `peergrab.cache.enabled=true/false` | infrastructure 组件扫描 |
| 名额槽位 | `grab/ports/GrabSlotPort` | `RedisGrabSlotAdapter` | 无开关；Redis 异常时用例降级到 DB CAS | infrastructure 组件扫描 |
| 候选队列 | `grab/ports/CandidateQueuePort` | `RedisCandidateQueueAdapter` | 无开关 | infrastructure 组件扫描 |
| 抢单限流 | `grab/ports/GrabRateLimiterPort` | `SentinelGrabRateLimiterAdapter` / `LocalGrabRateLimiterAdapter` | `peergrab.grab.limit.type=sentinel/local` | infrastructure 组件扫描；Sentinel 加 `@Primary` |
| 实时通知 | `notify/ports/RealtimeNotifier` | `RealtimePushService`（API）/ `RedisFanoutRealtimeNotifier`（Worker） | `peergrab.ws.enabled=true/false` | presentation / infrastructure 组件扫描 |
| 信用排行 | `credit/ports/CreditRankingPort` | `RedisCreditRankingAdapter` | 无开关 | infrastructure 组件扫描 |
| 任务仓储/查询 | `errand/ports/ErrandRepository`、`ErrandQueryPort` | `JdbcErrandRepository`、`JdbcErrandQueryAdapter` | 无开关 | infrastructure 组件扫描 |
| 发布幂等 | `errand/ports/PublishRequestRepository` | `JdbcPublishRequestRepository` | 无开关 | infrastructure 组件扫描 |
| 本地消息 | `errand/ports/LocalMessageRepository` | `JdbcLocalMessageRepository` | 无开关 | infrastructure 组件扫描 |
| 跑腿额度 | `errand/ports/RunnerQuotaPort` | `JdbcRunnerQuotaAdapter` | 无开关 | infrastructure 组件扫描 |
| 钱包/钱包查询 | `wallet/ports/WalletRepository`、`WalletQueryPort` | `JdbcWalletRepository`、`JdbcWalletQueryAdapter` | 无开关 | infrastructure 组件扫描 |
| 资金审计/事件 | `wallet/ports/FundAuditPort`、`FundEventPort` | `JdbcFundAuditAdapter`、`JdbcFundEventOutboxAdapter` | 无开关 | infrastructure 组件扫描 |
| 对账 | `recon/ports/ReconRepository` | `JdbcReconRepository` | 无开关 | infrastructure 组件扫描 |
| 缓存差异 | `errand/ports/SyncDiffRepository` | `JdbcSyncDiffRepository` | 无开关 | infrastructure 组件扫描 |

### 5.3 换实现时的检查清单

1. 端口是否在 `peergrab-domain` 的 `ports` 包？ArchUnit 会检查端口位置。
2. 新适配器是否只依赖 domain 端口与基础设施库？不要在适配器里反向依赖 application 用例。
3. 条件是否互斥？`mq.enabled` 为 false 时 `Noop` 的 `matchIfMissing=true` 会接管；两个实现同时被扫描会启动失败。
4. Worker 是否需要这个适配器？Worker 不扫描 presentation；WebSocket 相关实现只在 API 生效。
5. 是否补了对应 IT？至少覆盖“正常路径 + 外部依赖失败降级 + 幂等重放”。
6. 是否改了 `SharedConfigConsistencyTest` 的共享键？API/Worker 的同名配置必须同值，否则会静默漂移。

## 6. 踩坑档案（18 条）

> 记录格式：**现象 → 根因 → 修法 → 教训（含哪层测试/门禁没抓住）**。素材来自分析报告 §4、§9、§10 与源码；同类项已合并，按“对后续维护最有复用价值”选 18 条，不追求覆盖全部 M/L 项。未单列的修复见 §9/§10，例如业务结果 UI 语义（M8）、递补/回退推送（M10）、钱包流水归属（M11）、JWT 缓存（M13）、数据生命周期（M14）、死状态清理（M15）、批量结算副作用（M16）、限流器清理（M18）、WS 防护（M19）、前端 token 卫生（M20），以及第三轮的 confirm 归属、deliver 幂等、自动结算消费者 ACK、WS 1013、JWT 缓存上限等。

### 1. 仲裁员可以先抢单、再裁决自己的争议
- **现象**：9001 能被前端引导抢单，成为任务跑腿；随后对涉及自己的争议做裁决，资金去向由当事人自己决定。
- **根因与修法**：只校验 `operatorId == arbitratorId`，没有“仲裁员不得为发单人/当前跑腿”的校验；`ErrandActionResolver` 给仲裁员下发 `GRAB`，`GrabErrandUseCase` 也不限制角色。 新增 `ActorRolePolicy`，发布与抢单入口拒绝仲裁员；`ArbitrateErrandUseCase` 二次校验仲裁员不是当事人；`availableActions` 不再给仲裁员 `GRAB`；补 `ArbitratorSeparationIT`。
- **教训**：权限不能只靠“按钮不显示”。当时没有一条“仲裁员走完整交易生命周期”的集成测试，角色矩阵也没有被当成一等测试对象。

### 2. 无钱包账户的身份抢单后，结算必然中毒
- **现象**：9001 抢单、确认、取货、送达都成功；发单人确认或自动结算时找不到 9001 的钱包账户，抛 `NoSuchElementException`，接口 500；自动结算消费者反复重试，任务卡在 `DELIVERED`、托管资金 `HELD`。
- **根因与修法**：`init.sql` 只给 1001/2001/2002 建了 USER 账户；结算用 `accountOrFallback(...).orElseThrow()`；抢单前没有账户存在性校验。 `WalletRepository.ensureUserAccount` 幂等开通；抢单事务内 CAS 成功后开通；结算/退款/仲裁前也开通；迁移补 9001 零余额账户。第三轮又把结算/退款/仲裁的开户移到事务外，发布侧只做存在性校验，消除 wallet→escrow 与 escrow→wallet 的锁序环；补 `SettlementAccountAutoProvisionIT`。
- **教训**：种子数据假设“所有身份都有钱包”，但演示身份是配置出来的，不在种子清单里。测试只覆盖 1001/2001 的正常生命周期，没有覆盖“特权身份也能被 UI 引导抢单”的异常路径；锁序也没有专门的并发测试。

### 3. 仲裁 favor 宽松解析，非法值默认判给跑腿
- **现象**：`favor=null`、空串、`"PUBLISHER "` 拼写错误都会落到 `RUNNER` 分支，直接决定托管资金去向；请求体为 null 还会 NPE→500。
- **根因与修法**：控制器写的是 `"PUBLISHER".equalsIgnoreCase(req.favor()) ? PUBLISHER : RUNNER`，把“非 PUBLISHER”当成“支持跑腿”；非法参数也没有统一异常映射。 `ArbitrationFavor.fromWire` 严格解析 `RUNNER/PUBLISHER`，null、空串、其他值抛 `INVALID_ARGUMENT`；`GlobalExceptionHandler` 把 `IllegalArgumentException` 映射为 `INVALID_ARGUMENT`。
- **教训**：资金动作的线上字符串必须在领域边界收敛，不能“默认给一方”。当时没有枚举 wire 格式测试，也没有 null body 测试；`default` 分支在资金场景是危险信号。

### 4. Bloom 判否短路的安全网与注释/README 不符
- **现象**：注释说 `BloomRebuildJob` “每 5 分钟整体校准”，实际只在 `existenceIndexReady()==false` 时重建；发布后 Redis 抖动导致 Bloom 登记失败时，真实任务可能在一段时间内被详情请求判 404。
- **根因与修法**：把“未就绪重建”误当成“周期全量校准”；发布 afterCommit 的 `registerExisting` 失败只 warn；布隆只加不减，漏登记不会自愈。 发布登记 3 次退避重试；新增 `periodicRefresh` 每 30 分钟全量校准；重建期间撤销 READY，读路径 fail-open；1% 判否抽样审计；补冷启动/故障测试。
- **教训**：注释和 README 把安全网写成了事实，但没有任何测试验证“Redis 丢 key 后真实任务仍可读”。这类“文档承诺 > 实现”的问题，靠读代码才能发现；门禁应至少有一个“外部索引丢失”的 IT。

### 5. Broker 长故障时 MQ 发送占满调度池，outbox 还空占租约
- **现象**：`LocalMessageRetryJob` 与 `FundEventOutboxJob` 都跑在 fast 池（3 线程），逐条同步 `producer.send`；Broker 黑洞时单轮可阻塞十几分钟，与超时/自动结算扫描争线程。预算耗尽后，未处理消息仍被 120 秒租约占住。
- **根因与修法**：MQ 发送与兜底扫描共用调度池；`DelayMessagePort.available()` 在 Rocket 适配器里默认 true，重试任务不会提前退出；领取 100 条后只处理了少量就退出，剩余租约没释放。 新增 `mqSendScheduler`（默认 2 线程）；单轮预算 10 秒；`RocketMqDelayMessageAdapter.available()` 失败冷却 5 秒；预算结束/异常时逐条 `releaseClaim`；补单测。
- **教训**：S6 只验证了“资金最终正确”，没有断言“Broker 故障时兜底扫描仍在推进”。故障注入如果不看调度器指标，就会漏掉“正确但慢到不可用”的退化。

### 6. 容器时区缺失，对账/信用校准落在业务高峰
- **现象**：`ReconciliationJob` cron 写 `0 0 2 * * ?`、`CreditCalibrationJob` 写 `0 0 3 * * ?`，文档说“凌晨”；容器 JVM 默认 UTC，实际在北京时间 10:00/11:00 执行，对账的全表扫描落在业务时段。
- **根因与修法**：compose 没设 `TZ`，cron 按 JVM 默认时区解释；部署检查只看健康，没人核对“任务实际几点跑”。 `docker-compose.full.yaml`/`prod.yaml` 的 app/worker 设 `TZ=Asia/Shanghai`；CI 增加 compose 断言，检查 `TZ` 与 Redis `allkeys-lru`。
- **教训**：cron 正确性没有被测试；`config-reference.md` 也只能提醒“按容器 TZ 解释”。部署门禁必须把“运行时语义”纳入，而不只是 `config --quiet`。

### 7. 延迟消息 Topic 配置没接线：发到 A、订阅 B
- **现象**：发送端用 `DelayTaskPolicy.TOPIC_*` 常量，消费端读 `${peergrab.mq.topic.*}`；运维改配置后主通道静默失效，只剩扫描兜底。
- **根因与修法**：配置项写进了 yaml，但没有真正驱动发送端；生产者和消费者各有一套“真相”。 四个 Topic 配置同时驱动发送端与消费端：`TimeoutTransferStep`、`DeliverErrandUseCase`、`RocketMqCacheEvictAdapter`、`FundEventOutboxJob` 都读配置；`SharedConfigConsistencyTest` 要求 API/Worker 同名键同值。
- **教训**：配置键“存在”不等于“生效”。当时没有 producer/consumer 契约测试；CI 的 MQ contract 只验证资金 Topic，不覆盖四个延迟 Topic。

### 8. 递补轮次只增不减，达到上限后候选永久失效
- **现象**：`round` 只在 transfer/revert 自增、从不重置；达到 `maxTransferRounds=5` 后，每次确认超时都直接回退，不再尝试候选队列；“让所有人重新抢”的语义走样。
- **根因与修法**：把“任务生命周期轮次”与“连续递补次数”混为一个字段；回退没有归零。 新增 `errand.transfer_count` 记录连续递补，回退时归零；达到上限回退重开；迁移补列；`TimeoutTransferIT.max_rounds_reached_triggers_revert` 覆盖。
- **教训**：测试只验证“单次流转对不对”，没有验证“流转会不会没完没了/永久失效”。生命周期类缺陷要靠多轮测试与运行日志，不是单轮断言。

### 9. 共享 ESCROW/COMMISSION 行锁压制结算吞吐
- **现象**：所有结算都锁同一对系统钱包行；200 单 / 32 线程短批次只有 **34.72 TPS**（原工具 39.52/36.09），P99 秒级；批量结算锁持有更久。
- **根因与修法**：账务模型固定使用共享 `ESCROW(-1)`/`COMMISSION(-2)` 账户；Hikari 扩容、加线程都无法突破两个数据库行锁串行点。 已做低风险优化——批量 owner 查询、按主键排序的 `IN (... ) ORDER BY id FOR UPDATE`、页内单事务批量过账、自动结算多页扫描；**未做**影子账户/缓冲记账/每任务托管账户，明确标注为容量边界。
- **教训**：压测正确指出了瓶颈，但“TPS 低”不是 bug，是账务拓扑的代价。没有跨多轮、真实用户分布的收益证据，就不应贸然改账本模型；这条边界必须和性能数字一起披露。

### 10. Redis 预占后进程崩溃，残留锁人；回退又不重建名额键
- **现象**：Lua 预占成功、DB 落库前进程崩溃，`grabbed` 集合 TTL 跟随 slot key（7 天），该跑腿重试被 `SISMEMBER` 判为 `ALREADY_GRABBED`，最长 7 天无法再抢该单；任务回退 `PUBLISHED` 后只 delete 名额键，二次开放的热点任务后续全部走 `SLOT_MISSING`→DB CAS，失去 Redis 削峰。
- **根因与修法**：补偿依赖应用层 `rollback`，崩溃路径没有执行；回退只删不建。 DB 仍为 `PUBLISHED` 时对 `ALREADY_GRABBED`/`GRAB_CONFLICT` 清理陈旧 `grabbed`/idem 快照（有 pending 预占时不动）；回退提交后 `initSlot` 重建名额键；补对应 IT。
- **教训**：补偿测试只覆盖“Lua 成功、DB 失败”的正常异常，没有覆盖“进程在两步之间死掉”。这类缺陷只能靠故障注入或崩溃窗口分析发现。

### 11. 前端抢单不带幂等键，非安全上下文还没有 `crypto.randomUUID`
- **现象**：前端 `api.grab` 不传 `X-Request-Id`，服务端每次生成新 UUID，与 README“重试须复用同一 requestId”的契约不一致；HTTP/IP 直连 demo 时 `crypto.randomUUID` 为 undefined，直接 TypeError。
- **根因与修法**：幂等键被当成“后端能力”，前端没有生成与复用；浏览器 API 可用性假设了安全上下文。 新增 `utils/requestId.ts`，优先 `crypto.randomUUID`，否则时间戳 + 随机串降级；抢单携带 `X-Request-Id`，网络失败重试复用，收到业务响应后清理。
- **教训**：README 的契约没有前端测试兜底；`npm run build` 不会发现运行时的浏览器 API 缺失。跨端契约要有端到端或浏览器冒烟。

### 12. 雪花时钟回拨：先直接拒绝，后变成持锁忙等
- **现象**：NTP 小幅回拨会让 `nextId` 直接抛异常，发布/抢单/结算瞬时 500；第一次修复允许 100ms 内等待，但等待是纯忙等，持锁空转。
- **根因与修法**：对时钟回拨零容忍；等待逻辑没有退避与上限。 允许 100ms 内小幅回拨，`waitNextMillis` 先 `Thread.onSpinWait()`，超过 64 次后 `parkNanos(100µs)`，并设等待上限；配合 MySQL 节点租约与持久时间栅栏，租约丢失可配置关进程。
- **教训**：没有时钟注入测试，也没有 NTP 回拨演练；“时间”被当成环境常量，而不是可故障的依赖。

### 13. 登录限速被伪造 `X-Forwarded-For` 绕过
- **现象**：限速按 `X-Forwarded-For` 的第一个 hop 计数；攻击者每次伪造不同头，就能无限尝试登录。
- **根因与修法**：无条件信任转发头，没有区分可信代理与公网直连。 只对 loopback/私网可信代理解析转发头；从右往左取第一个非可信 hop；公网直连只用 `remoteAddr`；补伪造头单测。
- **教训**：安全属性没有集成测试；限速的单测只验证计数，没有验证“身份维度不可伪造”。任何来自请求头的安全维度都要先问“谁可以写这个头”。

### 14. 抢单幂等重放跨轮次误报“已抢中”
- **现象**：任务回退/递补后 round 自增，旧轮次的 `requestId` 记录仍能命中 `findGrabbedByRequestId`，把旧成功结果回放给当前轮，前端显示“已抢中”。
- **根因与修法**：幂等查询只按 `errandId + requestId`，没有带 round；幂等键的作用域少了一个生命周期维度。 `findGrabbedByRequestId(campusId, errandId, requestId, round)` 带 round；应用层再校验 `record.round() == round` 与 `runnerId`；补跨轮重放单测。
- **教训**：幂等测试只覆盖“同一轮重复提交”，没有覆盖“回退/递补后重放”。幂等键的语义必须和状态机轮次一起定义。

### 15. `page * size` 整型溢出，负 OFFSET 导致 500
- **现象**：大页码时 `page * size` 先按 `int` 计算溢出为负数，SQL `OFFSET` 变负，查询报错/接口 500。
- **根因与修法**：分页参数是 `int`，乘法没有提升为 `long`。 三个查询适配器与通知查询统一 `(long) page * size`；保留参数上界 clamp 为后续产品决策。
- **教训**：分页测试只覆盖正常页，没有边界值（大页码、超大 size）。参数校验不能只做“非空/正数”，还要做溢出与上界。

### 16. `recon_diff` 被保留期任务误删，审计证据丢失
- **现象**：`DataRetentionJob` 把 `recon_diff` 当普通差异表按保留期删除；对账差异是“有差异即 bug”的审计证据，删掉后无法追查。
- **根因与修法**：把所有“差异/日志表”都归入可清理派生数据，没有区分审计事实与可重建投影。 `recon_diff` 不再自动删除，保留到人工确认处理完毕；`publish_request` 默认保留 30 天并在文档标注幂等窗口。
- **教训**：保留期测试只断言“旧数据被删”，没有断言“受保护数据必须保留”。数据生命周期测试需要正向与反向两类断言。

### 17. 佣金 `Math.floor(double)` 在 0.29 等费率上少 1 分
- **现象**：`Math.floor(totalCents * commissionRate)` 在二进制浮点表示偏小的费率上会少 1 分，破坏“跑腿 + 佣金 == 托管”的恒等。
- **根因与修法**：金额用 `long` 分，但费率乘法退化为 `double`；`0.29` 不能精确表示。 改为 `BigDecimal.valueOf(totalCents).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.DOWN)`；余数归跑腿。
- **教训**：仓库内可见的结算测试配置基本是 `0.05`，没有覆盖 `0.29` 这类边界费率。金额计算的测试参数要覆盖“二进制不友好”的费率，而不只是常见值。

### 18. 截止时间为 NULL 的任务永不扫描，资金静默冻结
- **现象**：`confirm_deadline_at`/`auto_settle_deadline_at` 为 NULL 的 `LOCKED`/`DELIVERED` 任务永远不会被超时扫描或自动结算扫描捞到；托管资金一直 `HELD`，对账也发现不了“该结算但没结算”。
- **根因与修法**：新增时间列时没有数据回填路径；扫描 SQL 只按时间比较，NULL 天然不匹配；readiness 只看进程与中间件，不看业务截止时间完整性。 新增 `ErrandDeadlineRepairJob`，按 `locked_at/delivered_at + 配置时限` 回填；仍无法修复的行打 ERROR 日志提示人工排查；迁移回填历史正常数据。
- **教训**：schema 迁移只验证“列存在/索引存在”，没有验证“旧数据被修复”；健康检查没有业务不变量探针。数据修复任务应和迁移一起交付。

## 7. 进度快照

> 口径以 README 为准；分析报告正文的数字是写作时快照，不覆盖 README。

| 项 | 当前状态 | 证据 |
| --- | --- | --- |
| 代码基线 | `7499174`（2026-10-18） | `git rev-parse --short HEAD` |
| 工作区 | 有未提交的前端改动（`App.tsx`、`api.ts`、`ws.ts` 等） | `git status --short` |
| 后端测试 | **298 项、0 失败、0 错误、0 跳过；78 项 IT 真实执行** | [README](../../README.md) |
| 前端构建 | `npm run build` 通过 | 分析报告 §7；README 上线记录 |
| 生产版本 | `5ace500`（2026-09-28 部署） | [后端 README](../../peergrab-backend/README.md) |
| 当前 HEAD 是否上线 | README 未声明 `7499174` 已部署 | README 上线段落 |
| HTTP 压测工具 | JMeter 5.6.3，7 个现行 JMX | [JMeter 入口](../../peergrab-backend/bench/jmeter/README.md) |
| S5 到期测量 | 专用 Java 探针，无 HTTP 替代物 | [S5 说明](../../peergrab-backend/bench/S5_TIMELINE.md) |
| 可观测 | Prometheus/Grafana overlay + 11 条告警；无 tracing/日志聚合/Alertmanager | [后端 README](../../peergrab-backend/README.md) |
| 容量结论 | 只有有限窗口；无全站容量/SLA | [评测报告索引](../../peergrab-backend/bench/reports/peergrab-current/README.md) |

### 测试数为什么有多个版本

- **141**：2026-09-25 隔离栈冒烟（[smoke 报告](../../peergrab-backend/bench/reports/peergrab-current/smoke-20260925-current.md)）。
- **214**：2026-09-27 首次上线版本。
- **223**：2026-09-28 优化版。
- **267 / 1 跳过 / 73 IT**：分析报告 §0 的实跑快照。
- **289 / 0 跳过**：第二轮审计后的快照（§9.4）。
- **298 / 78 IT**：README 当前口径，第三轮审计后。

这些数字对应不同代码与测试集，不能拿来算“测试增长”或“覆盖率提升”。

## 8. 已知边界与后续路线

### 8.1 诚实边界

1. **单 ECS、单 MySQL/Redis/Broker**：无主从、无多活、无自动故障转移；容器重启靠 Compose `restart`。
2. **共享系统钱包行锁**：结算短批次 32–39 TPS；批量结算放大锁持有；未做影子账户/缓冲记账。
3. **单名额 + 分片未进运行链路**：公开 API 限制 `slotTotal=1`；ShardingSphere 只在 lab，当前仍是单库，不能宣称已分片。
4. **无外部渠道对账**：只有内部 L1/L2/L3；没有支付/银行流水、T+1 差错处置、资金熔断。
5. **纯抢单 + 风控薄**：无智能派单/ETA/顺路/背单量；只有“不能抢自己任务”和在途限制，无设备/IP/关系图谱、刷单识别、名单与处罚。
6. **仲裁是单仲裁员二元裁决**：无证据链、申诉、时限、众包评审。
7. **可观测不闭环**：有指标与 11 条告警，无 tracing、日志聚合、Alertmanager；生产未部署持续看板。
8. **演示身份不是生产身份**：没有用户表，9001 与四个 demo 口令只用于演示；生产必须关闭 demo。
9. **S6 默认配置的 Broker 故障仍未修**：客户端 8 秒超时、应用 8.204 秒后返回 200 且已提交；隔离对照关闭双删后约 94.856 ms，但该设置未部署生产。
10. **无长期稳态容量**：S2 最长 180 秒；S1/S4 是短批次/尖峰；不能外推全天候 QPS/TPS。

### 8.2 后续路线（按收益/风险排序）

1. **先修 S6 默认配置的提交后阻塞**：把延迟双删/首轮超时消息全部改为有界后台投递（部分已做），用 Broker 暂停 + 持续结算复测，直到客户端不再超时。
2. **结算热点专项**：在独立压测栈对比“共享系统户 / 分片系统户 / 每任务托管户”，同时改 `escrow_order` 映射、迁移、对账与资金校验；没有跨多轮证据前不动账务拓扑。
3. **长稳态与混合负载**：按 [多用户混合负载](../../peergrab-backend/bench/MIXED_WORKLOAD.md) 跑 30 分钟，补多游标页、多用户、读写混合；当前样例是“待执行方案”，不是已实测。
4. **S6 连续故障窗口**：Broker 故障下持续结算 + outbox 积压/排空曲线；Redis 网络延迟与主从切换；MySQL 连接受限；Worker 强杀。
5. **测试基础设施**：IT 仍依赖固定端口/常驻中间件，无 Testcontainers；本地 `mvn test` 会跳过 IT，必须靠 CI 的 `-Dpeergrab.it=true` 与报告断言。
6. **可观测闭环**：tracing、日志聚合、Alertmanager；把“超卖计数/资金差异/到期未处理”做成可告警指标。
7. **身份与风控**：真实用户表/角色服务、登录限速多实例化、设备/IP 风控；生产禁用 demo。
8. **部署当前 HEAD**：`7499174` 通过隔离测试，但 README 未声明已上线；上线前走独立发布窗口、迁移演练与回滚。

### 8.3 不应该再出现的表述

- 把四层防护说成“五层”（候选队列、失败回补、扫描兜底都不是同步裁决层）。
- 把 `2,000 ÷ 8.869` 说成“抢单 TPS”。
- 把 600 RPS 长档说成“三轮零错误”（实际第二轮 1 次连接超时，第三轮未跑）。
- 把 S4 短批次 34.72 TPS 说成“稳定结算容量”。
- 把 S5 的 HTTP P99 或 MQ 消费进度当成 Worker 到期延迟。
- 把不同工具、不同链路、不同窗口的数字直接算“优化提升百分比”。

## 9. 核对入口

```bash
# 代码基线
git log --oneline --stat

# 当前测试（需要隔离 MySQL/Redis；会改测试库数据）
cd peergrab-backend
mvn -B -ntp -Dpeergrab.it=true test

# 只跑某一个 IT
mvn -B -ntp -Dpeergrab.it=true -Dtest=GrabConcurrencyIT test
```

延伸阅读：

- [全面深度分析报告](../analysis/PeerGrab-全面深度分析报告.md)
- [架构设计与技术选型](../architecture/架构设计与技术选型.md)
- [配置参考](../architecture/config-reference.md)
- [不变式与对照实验](../architecture/invariants-and-control-experiments.md)
- [压测方法论](../benchmark/methodology.md)
- [评测资产目录](../../peergrab-backend/bench/ASSET_CATALOG.md)
