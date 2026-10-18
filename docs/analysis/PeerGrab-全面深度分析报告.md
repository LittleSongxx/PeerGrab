# PeerGrab 全面深度分析报告

> 分析对象：当前工作区（HEAD `b1e6d57` + 15 个未提交修改）  
> 分析方式：全量源码阅读（258 个 Java 文件、18 个前端文件、SQL/Compose/CI/压测资产）、联网调研、单元测试与集成测试实跑、构建验证  
> 结论口径：只评价代码与文档事实；公开演示定位下的已知限制与真正缺陷分开表述。

---

## 0. 结论摘要

1. **项目定位**：PeerGrab 是一个"校园跑腿悬赏交易平台"的**模块化单体**教学/展示级系统，核心是"发布即资金托管 + 并发抢单 + 履约状态机 + 结算/退款/仲裁 + 信用分 + 可靠异步 + 缓存/实时通知"，技术栈为 Java 21 / Spring Boot 3.5 / MySQL 8 / Redis 7 / RocketMQ 5 / React 18。
2. **设计对齐度**：在**交易与资金内核**上，与国内大厂（支付宝担保交易、闲鱼、美团配送、有赞账务）的公开设计思想**高度对齐**：先托管后履约、复式记账+快照+对账、唯一索引幂等、本地消息表/outbox、Redis 原子预占+DB CAS 终裁、状态机事件驱动、Cache Aside 三件套、长连接推送。对齐的是"共识做法"，不是规模与合规能力。
3. **未对齐项集中在规模化与合规**：智能派单（本项目是纯抢单+超时递补）、资金持牌托管/分账（本项目自建虚户，存在二清边界）、热点账户（共享 ESCROW/COMMISSION 行锁，实测结算 32–39 TPS）、外部渠道对账、风控反作弊、多活高可用、可观测性闭环、仲裁证据链/申诉、运营后台。
4. **工程质量高于一般个人项目**：ArchUnit 守分层、IT 有"必须执行、不许跳过"门禁、压测有证据哈希与失败轮披露、README 主动声明指标边界。实跑：`mvn -Dpeergrab.it=true test` **267 项、0 失败、1 跳过（其中 73 项真集成测试，连独立 MySQL/Redis）**；`npm run build` 通过。
5. **发现的问题**：没有发现"会立即导致资金错账"的 P0 缺陷；但发现 **7 个高优先级问题、20 个中优先级问题**及若干工程质量问题。最值得优先修的是：**仲裁权限/角色分离缺失**、**仲裁员身份可抢单但无钱包账户导致结算必失败**、**仲裁 favor 参数宽松解析**、**Bloom 判否短路的安全网与实现不符**、**MQ 长故障下 worker 调度池被阻塞**、**容器时区导致对账/校准落在业务高峰**、**Topic 配置项未接线**。

---

## 1. 主要功能

| 域 | 能力 |
| --- | --- |
| 账户与资金 | 演示钱包、托管账户、佣金账户；金额整数"分"；复式记账流水；余额快照；审计日志；三层对账 |
| 发布 | 发布即托管（同事务扣款+托管单+任务）；`X-Request-Id` 幂等；Redis 名额初始化；布隆登记 |
| 抢单 | 热点限流 → 信用/在途资格 → Redis Lua 原子预占 → MySQL CAS + 唯一索引终裁 → 失败回补 → 候选排队 |
| 履约 | 确认接单 → 取货 → 送达；状态机严格流转；`availableActions` 由服务端按身份+状态计算 |
| 结算 | 发单人确认或送达后 24h 自动结算；任务/托管单/账本三道幂等闸门；佣金向下取整、跑腿得余数；批量自动结算 |
| 退款/取消 | 无人接单可取消并全额退款；仲裁支持发单人退款 |
| 争议仲裁 | 双方可发起争议，资金原地冻结；仲裁员二元裁决（支持跑腿结算/支持发单人退款） |
| 超时治理 | 确认超时 5min：MQ 延迟消息主通道 + DB 扫描兜底；候选递补，候选空则回退 PUBLISHED；最大流转轮次保护 |
| 信用 | 30 天滑动窗口事件回放；结算 +2、超时未确认 -5、争议败诉 -8；抢单门槛 40 分；校区排行榜 |
| 通知 | 站内消息（资金事件落库）+ WebSocket 实时推送（状态/通知/信用）+ Redis Pub/Sub 跨副本 + 轮询兜底 |
| 认证 | Redis Session 与 MySQL 真值 JWT 双实现；refresh token 一次性轮换（SHA-256 存储）；退出即吊销 |
| 可观测 | Micrometer/Prometheus 指标、Actuator readiness（db/redis/雪花租约/调度器）、审计与对账差异表 |
| 工程配套 | Docker Compose 三套（本地/全栈/生产）、单 ECS 部署文档、备份与迁移脚本、CI 5 个 Job、JMeter/Vegeta 压测资产 |

规模：Java 258 个文件约 1.14 MB；前端 18 个文件约 140 KB；单体 10 个 Maven 模块（含离线分片实验模块）。

---

## 2. 各部分设计实现思想

### 2.1 架构风格：模块化单体 + 六边形/洋葱
- `peergrab-domain`：纯 JDK，聚合根、状态机、端口（Repository/Port），ArchUnit 强制"领域不依赖框架"。
- `peergrab-application`：用例编排，事务边界画在应用层；`@Transactional` 事务步骤单独成 Bean 以规避自调用失效。
- `peergrab-infrastructure`：JdbcTemplate 适配器、Redis Lua、RocketMQ 适配、鉴权、雪花租约。
- `peergrab-presentation`：REST/WS/拦截器/DTO/异常映射；`bootstrap` 与 `worker` 两个进程复用 domain/application，分别装配在线 API 与后台任务。
- 设计取态：**不拆微服务**。理由充分——资金托管、状态、账本、outbox 都在同一 MySQL 本地事务内闭环，不需要分布式事务；同时用 `peergrab-sharding-lab` 保留分片规则实验（不进运行包）。

### 2.2 领域模型
- `Errand` 聚合根：状态字段无 setter，唯一流转入口 `transitTo(target, expectedVersion)`；`LOCKED -> LOCKED` 自环 + `round` 表达"超时改派"。
- `EscrowOrder`：`HELD` 是唯一可变态，`RELEASED/REFUNDED` 为终态，保证一笔托管只有一个去向。
- `WalletAccount`：只做"转移"不做"冻结"，明确区分担保交易（转移）与预授权（冻结）——注释里记录了 P3 修正过的模型缺陷。
- `Money`：long 分；`CreditEvent`：只增不减的事件事实。
- 状态落 `errand_status_log` 做事件溯源展示；领域变更另以 `changes()` 暴露。

### 2.3 发布与托管（同库事务）
`publish_request` 先占幂等位（唯一键 + `FOR UPDATE`）→ 钱包按账户 ID 排序 `SELECT ... FOR UPDATE` → `WalletPosting` 复式过账 → 托管单 HELD → 任务 DRAFT→PUBLISHED CAS → 状态日志 → 提交后：Redis 名额 `SETNX`、布隆登记、缓存失效。**Redis/Bloom 都是提示，失败只告警**，DB 是唯一真值。

### 2.4 抢单链路（项目核心）
- **L1** Sentinel 热点参数限流（按 `errandId`）。
- **L2** 资格前置：信用分读后端 `credit_score`（不信任客户端传分）+ 在途任务数。
- **L3** Redis Lua：hash tag 同槽；requestId 幂等；名额守恒；同用户防重复；pending 窗口标记；返回码与 `SlotOutcome` 一一对应。
- **L4** MySQL：`runner_quota_lock` 串行化同一跑腿额度判定 → `UPDATE errand SET status='LOCKED', slot_taken+1, version+1 WHERE status='PUBLISHED' AND version=? AND slot_taken<slot_total` → `grab_record` 双唯一索引兜底 → 状态日志。
- 失败用 Lua 回滚（幂等、条件 INCR）；提交后异步登记超时消息、失效缓存、发通知。
- 关键取舍：**Redis 只做预占与削峰，DB CAS 才是最终裁决**，与业界共识一致。

### 2.5 超时改派与自动结算
- 本地消息表（`local_message`）与业务同事务登记，后台有界队列即时发送，失败/停机/队列满保留 PENDING 由 worker 重发；DB 扫描是兜底通道。
- 幂等三件套：version CAS、round、`msg_key` 唯一索引。
- 候选队列：Redis ZSET + 租约 token + Lua CAS（ack/release/claim），score = 时间戳 − 信用分×10ms（上限 1s 加权）。
- 自动结算：送达时同事务登记 `autosettle:{id}` 延迟消息；结算以任务 CAS + 托管单 CAS + 账本 biz_no 唯一三道闸门防重；批量事务把一页到期任务合并过账（v5 优化）。

### 2.6 资金、对账与审计
- 复式记账：每笔转移借贷平衡；`account_version` 唯一约束保证"账户版本-流水"一一对应；余额快照只增可追溯。
- 审计 `fund_audit_log` 用 `REQUIRES_NEW`：资金回滚也要留痕；文档还专门复现了 REQUIRES_NEW 与连接池的经典死锁。
- 对账三层：L1 全局借贷平衡、L2 系统户快照=流水净额（用户户用"最新流水 vs 快照+版本"）、L3 托管闭环；差异落 `recon_diff`，"有差异即 bug"。
- 资金事件 outbox：与账务同事务写 `fund_event_outbox`，worker 投递普通 Topic，双消费组（落库 / 实时推送），消费幂等靠 `notification(msg_key,user_id)` 唯一索引。

### 2.7 缓存体系
Cache Aside + 逻辑过期（物理 TTL 约为逻辑 TTL 两倍）+ SET NX token 互斥重建 + 空值缓存 + TTL 抖动 + 可选多分片 + Redis 故障时 1 秒进程内兜底副本 + 布隆判否（当前工作区未提交改动已改为"判否短路 + 1% 抽样审计"）+ 提交后删除与延迟双删（MQ）+ 随机抽样一致性校验 job。身份相关的 `role/availableActions` 不进共享缓存，避免缓存串号。

### 2.8 实时通知
WebSocket（握手 query token + 浏览器 Origin 白名单）→ 每次推送前复核会话有效性（撤回即时生效）→ 本机连接推送 + Redis Pub/Sub 广播其它 API 副本（instanceId 防回环）→ 有界队列丢事件只降级不阻塞业务 → 前端 WS + 10/30 秒轮询双保险。

### 2.9 认证与安全
- Session 模式（Redis）用于本机；JWT 模式用于生产：JWT 只携带 sid，服务端 `auth_token_session` 是撤销真值；refresh token 256 位随机、DB 只存 SHA-256、一次一用轮换。
- 演示登录白名单式策略、口令常量时间比较、四身份密码必须不同；`allow-header-identity` 生产默认关闭且 IT 有专用断言。
- 内部运维端点要求仲裁员身份，前端 Nginx 对 `/api/internal/` 直接 404；生产只发布前端端口到宿主回环，MySQL/Redis/MQ/API 不暴露宿主端口。

### 2.10 可靠性细节
- 雪花 ID：MySQL 节点租约 + 持久时间栅栏（`safe_after_ms`）+ 本地 2/3 租期截止，防止节点复用产生重复 ID；租约丢失可配置"关进程"。
- 扫描重试表 `worker_scan_retry`：游标推进前先持久登记失败项，租约+退避，避免"游标越过失败任务"。
- 调度分池（fast/maintenance/mq），扫描多页+时间预算，批量事务失败整体回滚后逐条幂等回退。

### 2.11 前端
后端下发 `role/availableActions`，前端不实现第二份状态机；游标分页；发布用 sessionStorage 复用 `X-Request-Id`；雪花 ID 全程字符串；统一 `BizError` 按 code 分支；WS 指数退避重连并降级轮询。

---

## 3. 与国内大厂同类业务的对齐分析

### 3.1 对齐良好的部分（共识做法）

| 维度 | 本项目做法 | 行业共识 / 参考 |
| --- | --- | --- |
| 担保交易 | 发布即托管；确认或到期才放行；退款冲正；争议冻结资金 | 支付宝担保交易状态机、"先托管后履约"；闲鱼"待结算在途资金" |
| 账务 | 复式记账 + 余额快照 + 以流水为准 + 对账兜底 | 支付系统三户模型/双表；有赞"百万分之一差错率"以对账兜底 |
| 幂等 | 唯一索引/唯一业务号作为最终落点（publish_request、grab_record、wallet_ledger.biz_no、local_message.msg_key、notification、credit_event、fund_event_outbox） | 业界共识"幂等本质是唯一约束"，Redis 只做前置去重 |
| 最终一致 | 同库本地消息表/outbox + 定时投递 + 扫描兜底 + 消费幂等 | 阿里/腾讯/有赞 outbox 实践；RocketMQ 事务消息的替代路径（因需延迟消息） |
| 抢单并发 | 限流→资格→Redis Lua 预占→DB CAS+唯一索引终裁→失败回补→候选递补 | 腾讯云"Redis 原子预扣 + DB 条件更新终裁 + 补偿 + 对账" |
| 状态机 | 枚举转移表 + 聚合唯一入口 + 每次流转留痕 + Action 下发前端 | 京东物流/闲鱼交易链路"事件驱动 + Action 化" |
| 缓存 | Cache Aside + 逻辑过期 + 互斥重建 + 空值 + 布隆 + 抖动 + 延迟双删 + 校验 job | 阿里云/美团缓存三件套；延迟双删属争议手段，本项目也自认"缩小而非消除"窗口 |
| 实时推送 | 长连接 + 服务端主动推 + 跨副本 Pub/Sub + 心跳 + 轮询兜底 | 美团 Pike、腾讯云长连接网关的简化形态 |
| 信用分 | 30 天滑动窗口事件回放 + 每日校准 + 门槛 + 排行榜 | 芝麻信用/闲鱼鱼力值的极简版：分数→资格 |
| 资金安全 | 借贷平衡、快照核对、托管闭环、审计日志、REQUIRES_NEW | 有赞资损防控"事前/事中/事后"的骨架 |

### 3.2 未对齐 / 有明确差距的部分

| 维度 | 差距 | 说明 |
| --- | --- | --- |
| 分配模型 | 纯"抢单+超时递补"，无智能派单/分配抢单 | 行业已从抢单转向派单（全局最优、ETA、顺路、背单量），本项目候选排序仅为"时间戳−信用分×10ms" |
| 资金合规 | 自建虚户、平台中间户 | 真实业务需持牌机构托管/分账，否则有"二清"风险；本项目是演示记账，无充值/提现/渠道回单 |
| 热点账户 | 共享 ESCROW/COMMISSION 单行锁 | 行业有汇总明细、缓冲记账、子账户拆分、异步汇总；本项目实测共享钱包结算 32–39 TPS |
| 对账 | 仅内部账账对账 | 缺外部渠道 T+1/T+0 对账、差错自动处置、资金熔断 |
| 风控 | 仅"不能抢自己任务"+在途限制 | 缺设备/IP/关系图谱、刷单识别、名单与处罚、灰度规则引擎 |
| 仲裁 | 单仲裁员、二元裁决、无证据/申诉/时限 | 行业是众包评审（闲鱼小法庭）或受理-核查-处理运营流程 |
| 高可用 | 单 ECS、单 MySQL/Redis/MQ，无主从/多活 | 文档已诚实声明；容器重启靠 Compose `restart`，无自动故障转移 |
| 可观测 | 有指标/健康/审计，无 tracing/看板/告警 | 行业资金链路要求 TraceId 全链路 + 金额一致性指标 + 告警分级 |
| 业务纵深 | 单名额、无部分结算、无改派人工干预、无异常订单/赔付 | 行业跑腿有改派、补时、补贴、保险、申诉等完整运营闭环 |

**总体判断**：*交易与资金内核的设计思想基本对齐国内大厂同类业务（约 80% 的行业共识做法有落地），显著超出一般教学项目；但"对齐"发生在方法论层面，规模、合规、风控、可观测性与运营闭环仍是演示深度，不能按真实资金生产系统标准验收。*

---

## 4. 问题与 Bug 清单

> 分级：**H 高**（可能导致资金方向错误、功能不可用、数据错乱或安全边界问题）｜**M 中**（一致性/性能/体验/可维护性风险）｜**L 低**（工程质量与文档）。  
> 说明：项目 README/压测报告已主动披露的"已知限制/失败轮"不计入缺陷，只作为边界事实引用。

### 4.1 H 级（建议优先修复）

**H1. 仲裁权限模型缺失：仲裁员可成为当事人并"自裁"**
- 位置：`ArbitrateErrandUseCase`、`RefundErrandUseCase.arbitrateRefund`、`ErrandActionResolver`。
- 机制：只校验 `operatorId == 配置的 arbitratorId`；没有"仲裁员不得为发单人/当前跑腿"的校验；同时 `ErrandActionResolver` 会给仲裁员下发 `GRAB` 动作，`GrabErrandUseCase` 也不限制角色——9001 可以先抢单成为当事人，再对涉及自身的争议做裁决。
- 影响：演示环境中任何访客（生产 `.env.prod` 使用公开演示口令 `demo9001`，见 `docker/ECS_DEPLOY.md`）都能左右任何争议的资金去向，且可自导自演；真实业务属严重越权。
- 建议：引入角色/权限服务；`arbitrate` 校验 operator 不是 publisher/grabber；仲裁员不展示/不允许 GRAB；生产禁用演示特权身份或改为只读演示。

**H2. 无钱包账户的身份抢单后，结算必然失败（毒任务）**
- 位置：`init.sql` 仅给 1001/2001/2002 建了 USER 账户；`SettleErrandUseCase.accountOrFallback(...).orElseThrow()`。
- 机制：9001（仲裁员）可被 UI 引导抢单（`availableActions` 含 GRAB，且无角色校验、信用默认 60、在途 0），确认/取货/送达都成功；但送达后发单人确认或自动结算时找不到 9001 的钱包账户 → `NoSuchElementException` → 接口 500；自动结算消费者返回 FAILURE 反复重试、扫描任务反复登记。任务卡在 DELIVERED、托管资金 HELD，只能靠人工发起争议再仲裁退款解围。
- 验证：`init.sql` 中 USER 账户仅 3 个；前端 `ErrandActionResolver` 对 9001 返回 `GRAB`。
- 建议：抢单前校验跑腿账户存在（或注册时开通钱包）；对无账户身份隐藏 GRAB；结算失败转人工/死信告警而非无限重试。

**H3. 仲裁 favor 参数宽松解析，非法值默认判给跑腿**
- 位置：`ErrandController.arbitrate`：`"PUBLISHER".equalsIgnoreCase(req.favor()) ? PUBLISHER : RUNNER`。
- 机制：`favor=null`、空串、拼写错误（如 `"PUBLISHER "`）都会被解析为支持跑腿——直接决定托管资金去向。
- 影响：客户端 bug/误操作即可造成资金裁决方向错误；请求体为 null 还会 NPE → 500。
- 建议：严格枚举校验（`RUNNER|PUBLISHER`），非法返回 `INVALID_ARGUMENT`；对资金动作加二次确认/幂等 requestId。

**H4. 布隆判否短路（当前未提交改动）的安全网与注释/README 不符，存在真实任务被判 404 的窗口**
- 位置：`GetErrandDetailUseCase`（未提交改动开启 `bloom-negative` 短路）、`PublishErrandUseCase` afterCommit 的 `registerExisting` 失败仅 warn、`BloomRebuildJob.ensureReady`。
- 机制：`BloomRebuildJob` **只在 `existenceIndexReady()==false` 时重建**（就绪后每 5 分钟直接 return），并不是注释所称的"每 5 分钟整体校准"；发布后 Redis 抖动导致布隆登记失败时，唯一自愈路径是读路径 1% 抽样审计 + 每 5 分钟随机抽 100 条的巡检，可能让真实任务在一段时间内被 99% 的详情请求判为 404（尤其冷门任务）。
- 建议（三选一或组合）：布隆登记纳入本地消息表/outbox 重试；把 `bloom-rebuild` 改成真正的周期全量校准；对判否请求提高审计率或"回源一次后补登记"。
- 备注：该改动在 git 中未提交，说明作者也在评估阶段；建议确认后再上线。

**H5. Broker 长故障时 worker 快调度池可能被同步 MQ 发送长期占用，拖慢超时/结算扫描**
- 位置：`WorkerSchedulers.fastTaskScheduler`（池 3）、`LocalMessageRetryJob`、`FundEventOutboxJob`、`RocketMqDelayMessageAdapter`。
- 机制：`LocalMessageRetryJob` 与 `FundEventOutboxJob` 都跑在 fast 池上，且逐条同步 `producer.send`；gRPC 请求超时配置 10s，单轮最多 100 条；Broker 处于"挂起/黑洞"状态时单轮可阻塞十几分钟，与 `TimeoutScanJob`/`AutoSettleScanJob` 争抢 3 个线程。`DelayMessagePort.available()` 在 Rocket 适配器中未实现（默认 true），重试任务不会提前退出。
- 影响：MQ 故障期间到期流转/自动结算的兜底 SLA 明显退化，且 `WorkerSchedulerHealthIndicator` 可能把 readiness 判 down。
- 建议：MQ 发送任务独立调度池或加时间预算；实现 `available()` 探测；缩短请求超时；失败即止（本批次剩余留待下轮）。

**H6. 容器时区缺失：对账/信用校准实际跑在北京时间上午而非凌晨**
- 位置：`docker-compose.prod.yaml`/`full.yaml`（app/worker 未设 `TZ`）、`ReconciliationJob`（cron `0 0 2 * * ?`）、`CreditCalibrationJob`（`0 0 3 * * ?`）。
- 机制：JVM 默认 UTC，cron 按 UTC 解释 → 对账在 10:00、信用校准在 11:00（北京时间），与文档"凌晨"不符，且对账包含全表扫描/关联查询，落在业务时段。
- 建议：容器 `TZ=Asia/Shanghai` 或 Java `-Duser.timezone`，或在 cron 中显式写时区语义。

**H7. 延迟消息 Topic 配置未接线：发送端用常量、消费端读配置**
- 位置：`TimeoutTransferStep.enqueueTimeout` / `DeliverErrandUseCase` 使用 `DelayTaskPolicy.TOPIC_*` 常量；`TimeoutTransferConsumer`/`AutoSettleConsumer` 读 `${peergrab.mq.topic.confirm-timeout/auto-settle}`；`CacheEvictDelayPort.TOPIC_CACHE_EVICT` 同理。
- 机制：配置项被写进 yaml 但没有真正驱动发送；一旦运维改配置（或未来引入环境差异化 Topic），会静默出现"发到 A、订阅 B"，主通道失效、只剩扫描兜底，问题很难被发现。
- 建议：发送端也读取配置；或删除这些无效配置项，统一常量并注释说明。

### 4.2 M 级

| 编号 | 问题 | 位置/机制 | 影响 | 建议 |
| --- | --- | --- | --- | --- |
| M1 | `maxTransferRounds` 达到上限后候选递补永久失效 | `TimeoutTransferUseCase.handleTimeout`：`round` 只在 transfer/revert 自增、从不重置；达到 5 后每次确认超时直接 revert | 该任务之后所有超时都不再尝试候选，"让所有人重新抢"语义走样；round 无界增长，展示"第 N 轮"失真 | revert 时重置/引入独立 transferCount，或冷却后允许候选 |
| M2 | 共享系统账户热点 | 结算固定锁 ESCROW(1)/COMMISSION(2) 两行；v5 批量结算 200 单单事务锁账户更久 | 实测结算 32–39 TPS，P99 秒级；批量时在线结算/退款可能长时间等锁（默认 50s 超时）与连接池放大 | 单笔/小批事务、对账异步化、影子账户或缓冲记账；长稳态压测 |
| M3 | Redis `noeviction` + 512MB 与业务混用 | prod/local compose；缓存、名额、会话共用同一实例 | 内存打满后写命令报错：缓存失效、Lua 预占回退 DB（性能）、Session 模式登录失效 | 缓存与状态分离实例；缓存类数据用 `allkeys-lru`；监控内存水位 |
| M4 | 一致性校验盲区：空值缓存不核对 | `CacheConsistencyCheckUseCase` 对 `cached.isEmpty()/cached.get().isEmpty()` 直接 `continue` | "DB 有、缓存空值"的不一致无法被巡检发现（TTL 60s 兜底但期间 404） | 对 empty 抽样回源核对 |
| M5 | Redis 预占后进程崩溃的残留锁人 | `grab.lua` 失败路径依赖应用 `rollback`；崩溃则 `grabbed` 集合 TTL 跟随 slot key（7 天），pending 仅 10s | 该 runner 重试被 SISMEMBER 判为 ALREADY_GRABBED → 前端 GRAB_CONFLICT，最长 7 天无法再抢该单（他人可触发 DB 兜底自愈） | DB 状态为 PUBLISHED 时对 ALREADY_GRABBED/GRAB_CONFLICT 分支执行 invalidate/rollback；缩短 grabbed TTL |
| M6 | 回退 PUBLISHED 后不重建 Redis 名额键 | `TimeoutTransferUseCase.revertAndReturnSlot` 只 delete；`initSlot` 只在发布时调用 | 二次开放的热点任务后续抢单全部走 SLOT_MISSING→DB CAS，失去 Redis 削峰（正确性不受影响） | revert 提交后 `initSlot` 重建 |
| M7 | 前端抢单不带 `X-Request-Id` | `api.grab` 未传，服务端生成新 UUID | 与 README"重试须复用同一 requestId"的契约不一致；网络丢响应后用户重试只能靠 ALREADY_GRABBED 兜底 | 前端生成并复用 requestId |
| M8 | 业务结果被 UI 当成成功 | `ErrandController.settle/cancel/arbitrate` 对 ALREADY/CONFLICT 也返回 `code=OK`；`ActionButtons` 只要不抛错就提示"成功" | 用户看到"确认完成成功"但 `result=CONFLICT`；二次结算等幂等场景显示误导 | 业务码透传前端，按 result 展示；或这些结果返回非 OK code |
| M9 | 非法参数返回 500 | `status` 非法枚举、非法游标、非法 favor、null body 等抛 `IllegalArgumentException/NPE` | 客户端应得 400/INVALID_ARGUMENT，却得到 HTTP 500/INTERNAL_ERROR，影响排障与监控 | 增加 `@ExceptionHandler(IllegalArgumentException)` 等 |
| M10 | 递补/回退通知链路不全 | `TimeoutTransferStep` 写 TRANSFERRED/REVERTED 站内消息，但无 WS 推送；前端 `Notifications.TYPE_TEXT` 无此二类型；`FundEventPushConsumer` 失败即 ACK | 递补/回退只能靠轮询发现；推送失败静默丢失（持久消息仍在） | 边界补 WS 推送；前端补文案；失败计数告警 |
| M11 | 钱包流水归属语义混淆 | 托管转账两条腿都把 `user_id` 记为发单人（`Publish/Refund/Settle` 的 escrow leg） | 用户账单出现"托管 +X/-X""结算 -X"等平台账户动作，净额正确但"入账/转出"标签误导 | 区分"用户账户流水"与"业务资金轨迹"，或账单只展示用户账户腿 |
| M12 | 登录/接口无频控，演示特权身份公开 | 无失败次数限制、无验证码；`ECS_DEPLOY.md` 公开 demo 口令（含 9001） | 任何人可操作仲裁；暴力破解风险（演示定位下可接受，但必须显式阻断生产复用） | 登录限速/锁定；演示环境 9001 只读或禁用资金裁决 |
| M13 | JWT 实为有状态会话，每请求查 MySQL | `JwtAuthAdapter.resolve` 每次请求 `SELECT COUNT(*) auth_token_session` | 名为 JWT 但无状态收益；MySQL 成为鉴权热点与单点（换来"Redis 故障仍可鉴权"） | 文档如实说明；可加短 TTL 本地缓存或 Caffeine |
| M14 | 数据只增不删 | `publish_request`、`local_message`(SENT)、`fund_event_outbox`(SENT)、`errand_status_log`、`notification` 无归档/清理 | 长期运行表膨胀，索引与备份成本上升 | 定期归档/分区/SENT 清理任务（对账与审计表按合规保留） |
| M15 | 状态机与信用规则存在"声明了但没接线" | `CLOSED` 不可达；`LOCKED/ACCEPTED→CANCELLED` 在枚举允许但聚合/CAS 禁止；`CANCEL_AFTER_GRAB`、`DELIVERY_LATE` 无触发点；`GrabRecord.CANDIDATE/EXPIRED` 从不写入；`SlotOutcome.NOT_GRABBABLE(-3)` Lua 从不返回 | 文档/枚举与行为不一致，易误读；部分业务规则缺实现 | 删除死状态/死事件或补实现；用状态机测试锁死可达性 |
| M16 | 批量结算 post-commit 副作用可中断 | `SettleErrandUseCase.settleAutoBatch` 提交后循环 audit/ranking/notifier，任一异常会中断后续行的副作用 | 已提交任务缺审计/通知（资金正确） | 逐项 try/catch + 计数告警，或统一 outbox |
| M17 | 雪花 ID 对时钟回拨零容忍 | `SnowflakeIdGenerator.nextId` 回拨直接抛异常 | NTP 小幅回拨会让发布/抢单/结算瞬时 500 | 允许小窗口等待/告警；大回拨再拒绝 |
| M18 | 本地限流器清理条件苛刻 | `LocalGrabRateLimiterAdapter` 仅当请求恰好落在 `second%64==0` 时 prune | 长期运行 counters 只增不减（内存轻微泄漏） | 按周期定时清理 |
| M19 | WebSocket 防护偏弱 | 无消息频率限制、单用户连接数无上限；`revalidateSessions` 每 60s 每连接做一次鉴权 DB 查询 | 恶意连接消耗线程/DB；大量连接时定时任务放大 DB 压力 | 连接/消息限频、批量复核、JWT 本地缓存 |
| M20 | 前端 WS 与身份切换细节 | token 过期后 WS 用旧 token 循环重连直到下次 REST 刷新；`Detail` 的 `onIdentityChange` 未使用；`Square.spikeGrab` 部分签发的 demo token 未 revoke | 体验与 token 卫生问题 | 401/关闭码触发 refresh 或停止重连；清理未用 prop/token |

### 4.3 L 级（工程质量/文档/可维护性）

- **文档死链**：`peergrab-backend/README.md` 指向的 `docs/架构设计与技术选型.md` 已被删除，且 `.gitignore` 忽略了整个 `peergrab-backend/docs/`（"Remove private recruiting materials"提交导致公开仓库**没有架构设计文档**），与 README"完整取舍见架构设计"不符。建议把可公开的架构设计文档恢复。
- **注释与代码不一致**：`JdbcErrandRepository.casSettle` 注释称"不带 version 条件而只带 status，是有意的"，实际 SQL 带 `version = ?`；`GetErrandDetailUseCase` 注释称 BloomRebuildJob"每 5 分钟整体校准一次"，实际只在未就绪时重建；`findConfirmTimeout(timeoutSeconds)` 参数实为"宽限期"而非超时。
- **配置无效项**：`peergrab.mq.topic.*` 对发送端无效（见 H7）；`peergrab.cache.check-interval-ms` 等散落在 worker yaml，API 与 worker 存在配置漂移风险（如 `max-ongoing`、`commission-rate` 双份）。
- **死代码/未用端口**：`GrabRecord.candidate/expired`、`CancelAfterGrabBizNo`、`ErrandRepository.findAutoSettleDue`（仅游标版本使用）、`LocalMessageRepository.findPending`、`CreditRepository.windowDelta`、`ReconRepository.countDiffs`、`GrabSlotPort.remainingSlot`（仅测试）等。
- **建表/索引规模化隐患**：`ORDER BY RAND()` 抽样、每 60s `SELECT DISTINCT campus_id FROM errand`、`listByRunner` 的 `DISTINCT` 子查询、对账 SQL 的相关子查询，在十万级以上都会变重（其中随机采样、校区列表与 `listByRunner` 已在 §9 修复，对账相关子查询保留并按校园量级设计）。
- **驱动行为放宽校验**：`JdbcWalletRepository.applyAccountUpdates` 容忍 JDBC `SUCCESS_NO_INFO(-2)`，理论上会掩盖"版本 CAS 失败"（有对账兜底，但破坏了"锁定后必成功"的断言）。
- **测试门禁**：本地 `mvn test` 会静默跳过约 70 个 IT（只有 CI 用 `-Dpeergrab.it=true` + 报告断言兜底），容易误判"全绿"；IT 依赖固定端口/常驻中间件，无 Testcontainers，环境耦合。
- **仓库卫生**：`bench/runs/tools/apache-jmeter-5.6.3` 等压测工具与产物体量大；`架构图/`、`.zcodeignore` 未跟踪；当前工作区有 15 个未提交修改（Bloom 短路、缓存降级等），存在"线上镜像 / 仓库 / 工作区"三份状态不一致风险。
- **文案与实现一致性**：README 中"当前资金链路基于单 MySQL 本地事务""不是 SLA"等边界写得很好，但个别文案（如"缓存失效链路已闭环""全量重建每 5 分钟校准"）略高于实现，建议统一复核。

---

## 5. 值得肯定的工程实践（不因问题清单而忽略）

1. **边界诚实**：压测报告明确区分"目标 RPS/实际发出/完成"，公开失败轮与 4 次传输超时、8.2 秒阻塞反例；不宣称全站 SLA。
2. **正确性设计成体系**：唯一索引/业务号/版本 CAS/状态机/对账/审计/outbox 互相咬合，且每个关键决策有注释解释"为什么这样"（REQUIRES_NEW 与连接池死锁、@Transactional 自调用、REPEATABLE READ、MQ Topic 类型、Redisson 启动、时钟栅栏等）。
3. **故障注入与证据链**：S6 单节点暂停、Redis 熔断回源、Broker 暂停下资金最终正确、outbox 恢复排空，均给出可复核的 manifest/hash。
4. **架构可维护性**：ArchUnit 守边界、端口-适配器清晰、API/Worker 复用用例、读写模型分离、prewarm/一致性校验/对账都可独立触发。
5. **工程化配套**：CI 5 个 Job（后端 IT 必须执行断言、前端构建、MQ 契约、Compose 模型校验、压测资产校验）、备份与迁移演练、演示身份与生产鉴权分离。

---

## 6. 改进优先级建议

**P0（先做，收益/风险比最高）**
1. 修 H1/H2/H3：角色与权限（仲裁员不得为当事人、不允许 GRAB）、抢单前账户存在性校验、favor 严格枚举 + 资金动作确认。
2. 修 H5：MQ 发送与扫描分池/时间预算/`available()` 探测，避免故障时兜底 SLA 崩塌。
3. 修 H6：显式时区 + 对账/校准错峰。
4. 对 H4 的 Bloom 短路：先补"登记失败重试 + 周期全量校准"，再开启短路；否则保持"判否必回源"。

**P1（一个迭代内）**
5. H7/M7/M8/M9：配置接线、前端 requestId、业务码语义、参数校验。
6. M2/M3：热点账户与 Redis 内存策略（先长稳态压测再定方案）。
7. M4/M5/M6：缓存、预占残留、回退后名额键的边界自愈。
8. M12/M13：登录限速、演示特权收敛、JWT 鉴权缓存与文档说明。

**P2（持续治理）**
9. M1/M10/M11/M14/M15/M16——业务语义与数据生命周期治理。
10. 恢复公开架构文档；建立"线上/仓库/工作区"三态一致性检查；IT 本地一键隔离环境。

---

## 7. 验证记录

| 验证项 | 命令/环境 | 结果 |
| --- | --- | --- |
| 后端单测 | `mvn -B test`（WSL，JDK 21 / Maven 3.9.16） | 全绿；无中间件时 IT 自动跳过（约 70 项） |
| 后端全量（单测+IT） | 临时隔离 Compose 项目 `peergrab-analysis-it`（MySQL 8.0.40 / Redis 7.4，独立卷），`mvn -B -Dpeergrab.it=true test` | **267 项，0 失败，0 错误，1 跳过；其中 73 项真实集成测试执行**；验证后 `down -v` 清理 |
| 前端 | `npm run build`（WSL，Node 25 / Vite 5.4） | 通过；主包 gzip ≈53 KB |
| 文档链接 | 脚本扫描 README 相对链接 | 1 处死链（`peergrab-backend/docs/架构设计与技术选型.md`） |
| 架构边界 | ArchUnit 测试 | 通过（领域无框架依赖、端口位置、model 不依赖 ports） |

---

## 8. 参考来源（联网调研）

- 支付宝担保交易接口/状态机：https://help.alipay.com/support/help_detail.htm?help_id=491081
- 闲鱼支付服务说明（待结算在途资金）：https://terms.alicdn.com/legal-agreement/terms/b_end_product_protocol/20250909184130672/20250909184130672.html
- 担保交易的工程实现（资金指令幂等/状态机/对账双通道）：https://developer.aliyun.com/article/1768898
- 闲鱼交易链路架构演进（多业务多状态 + Action 化）：https://developer.aliyun.com/article/727645
- 美团配送资金安全治理之对账体系建设：https://tech.meituan.com/2018/03/21/balance-accounts.html
- 有赞资损防控体系：https://developers.youzanyun.com/article/1557135494803
- 有赞业务对账平台：https://tech.youzan.com/practice-of-business-check-platform-in-youzan/
- 微信支付分账常见问题（冻结/分账/回退边界）：https://pay.weixin.qq.com/doc/v3/partner/4014547107
- 美团 Pike 长连接消息投递：https://tech.meituan.com/2021/07/22/Pike-2.0.html
- 美团订单分配算法：https://tech.meituan.com/2018/03/29/ai-con.html
- 美团"分配抢单"与骑手治理（钛媒体）：https://www.tmtpost.com/7828216.html
- 腾讯云：Redis 预扣+DB 终裁防超卖：https://cloud.tencent.cn/developer/article/2741240
- 腾讯云：双履约状态机与延时消息超时治理：https://cloud.tencent.cn/developer/article/2745506
- 阿里云：缓存击穿/雪崩/穿透三件套：https://developer.aliyun.com/article/1763294
- dbaplus：延迟双删的争议与边界：https://dbaplus.cn/news-160-7269-1.html
- 京东物流状态机实践：https://m.sohu.com/a/707919144_121438385/
- RocketMQ 消费幂等（业务唯一键而非 MessageID）：https://help.aliyun.com/zh/apsaramq-for-rocketmq/cloud-message-queue-rocketmq-4-x-series/use-cases/consumption-idempotence
- 支付系统账户体系（三户/双表/复式记账）：https://mpaypass.com.cn/news/201808/22102541.html

---

## 9. 修复实施记录（2026-10）

> 状态口径：**已修复**＝代码、配置或文档已改，可由 diff 核对；**已缓解并标注边界**＝风险已降低但仍有明确残留，边界写在"修复要点"里。本表只做索引，**以合入 diff 与测试结果为准**，不替代验收；原报告正文不改动。

### 9.1 H 级

| 编号 | 状态 | 对应模块 | 修复要点 |
| --- | --- | --- | --- |
| H1 | 已修复 | application `ActorRolePolicy`、`ArbitrateErrandUseCase`、`ErrandActionResolver` | 新增平台角色策略：仲裁员禁止发布与抢单；仲裁用例二次校验仲裁员不得为当事人；`availableActions` 不再给仲裁员下发 GRAB |
| H2 | 已修复 | domain `WalletRepository.ensureUserAccount`；application 抢单事务、结算、退款、仲裁；docker `migrate-runtime.sql` | 抢单成功事务内及结算/退款/仲裁前幂等开通用户账户；迁移补 9001 零余额账户行，避免无账户身份结算中毒 |
| H3 | 已修复 | domain `ArbitrationFavor.fromWire`、presentation `ErrandController` | 严格解析 RUNNER/PUBLISHER，null、空串、拼写错误返回 INVALID_ARGUMENT，不再默认判给跑腿 |
| H4 | 已修复 | application `PublishErrandUseCase`、`GetErrandDetailUseCase`；worker `BloomRebuildJob` | 发布登记 3 次退避重试；每 30 分钟全量校准（`bloom-refresh-interval-ms`）；重建期间撤销 READY，读路径 fail-open；1% 判否抽样审计 |
| H5 | 已修复 | worker `WorkerSchedulers`、`LocalMessageRetryJob`、`FundEventOutboxJob`；infrastructure `RocketMqDelayMessageAdapter` | MQ 同步发送独立 `mqSendScheduler`；重发/投递单轮预算 10 秒；`available()` 失败冷却 5 秒，Broker 故障不再拖垮兜底扫描 |
| H6 | 已修复 | docker `docker-compose.full.yaml`、`docker-compose.prod.yaml` | app/worker 容器设 `TZ=Asia/Shanghai`，对账、信用校准、保留清理 cron 按东八区执行 |
| H7 | 已修复 | application `TimeoutTransferStep`、`DeliverErrandUseCase`；infrastructure `RocketMqCacheEvictAdapter` | 四个 Topic 配置真正驱动发送端，与消费端同源，消除"发到 A、订阅 B"的常量漂移 |

### 9.2 M 级

| 编号 | 状态 | 对应模块 | 修复要点 |
| --- | --- | --- | --- |
| M1 | 已修复 | domain `Errand.transferCount`；application `TimeoutTransferUseCase`；docker `migrate-runtime.sql` | 新增 `errand.transfer_count` 记录连续递补，回退归零；达到上限回退重开，不再永久失效 |
| M2 | 已缓解并标注边界 | 账务模型未变 | 共享 ESCROW/COMMISSION 行锁仍在；README 实测短批次结算 32–39 TPS，作为已知容量边界披露，未做影子账户或缓冲记账 |
| M3 | 已修复 | docker compose Redis 配置 | `noeviction` 改为 `allkeys-lru`（512MB，samples 10）；缓存、名额、布隆、榜单可重建，名额键淘汰后回退数据库 CAS |
| M4 | 已修复 | application `CacheConsistencyCheckUseCase` | 抽样校验新增负值缓存核对，"DB 有、缓存空值"可被发现 |
| M5 | 已修复 | application `GrabErrandUseCase` | 数据库仍为 PUBLISHED 时清理陈旧的 grabbed/idem 快照，进程崩溃不再导致长期误判 ALREADY_GRABBED |
| M6 | 已修复 | application `TimeoutTransferUseCase.revertAndReturnSlot` | 回退提交后重建 Redis 名额键，二次开放仍走 Redis 削峰 |
| M7 | 已修复 | frontend `api.ts`、`ActionButtons.tsx` | 抢单携带 `X-Request-Id`，网络失败重试复用同一幂等键，收到业务响应后清理 |
| M8 | 已修复 | presentation `ErrandController`；frontend `ActionButtons.tsx` | 结算/退款冲突返回 SETTLE_CONFLICT/REFUND_CONFLICT；前端按 `result` 展示"已完成，无需重复处理" |
| M9 | 已修复 | presentation `GlobalExceptionHandler` | 非法参数统一映射 INVALID_ARGUMENT，不再以 HTTP 500 暴露 |
| M10 | 已修复 | application `TimeoutTransferStep`；shared `RealtimeEvents`；infrastructure `RedisFanoutRealtimeNotifier`；worker `FundEventConsumer` | 递补/回退接入实时推送与前端文案；资金事件推送并入落库消费者，推送失败只记日志，由持久通知与轮询兜底 |
| M11 | 已修复 | presentation `WalletController`；frontend `Wallet.tsx` | 流水返回 `accountType`（USER/ESCROW/COMMISSION），前端标注平台账户动作不代表个人余额变动 |
| M12 | 已缓解并标注边界 | presentation `LoginAttemptGuard`、`DemoLoginPolicy`、`AuthController` | 登录失败按 IP+userId 限速（默认 5 次/60 秒窗口、锁 300 秒）；边界：公开演示口令仍保留，生产须关闭 demo 并接真实身份 |
| M13 | 已修复 | infrastructure `JwtAuthAdapter` | `auth.resolve-cache-ms` 本地正结果缓存，跨实例登出的最坏延迟写进配置注释 |
| M14 | 已修复 | worker `DataRetentionJob`、`retention.*` | 按保留期清理 SENT 本地消息、已发送资金事件、发布登记、已读通知与差异表；资金流水与状态日志不删 |
| M15 | 已修复 | domain `ErrandStatus`、`CreditEventType`、`GrabRecord`、`SlotOutcome`；`ErrandStatusMachineTest` | 删除不可达 CLOSED、未接线的 CANCEL_AFTER_GRAB/DELIVERY_LATE、GrabRecord.CANDIDATE/EXPIRED、NOT_GRABBABLE；状态机测试锁可达性 |
| M16 | 已修复 | application `SettleErrandUseCase` | 批量结算提交后的审计、榜单、缓存、推送逐项隔离，失败仅计数告警 |
| M17 | 已修复 | shared `SnowflakeIdGenerator` | 允许 100ms 内小幅回拨等待越过，超过窗口才拒绝发号 |
| M18 | 已修复 | infrastructure `LocalGrabRateLimiterAdapter` | 改为每 60 秒按时间触发 prune，计数器不再只增不减 |
| M19 | 已修复 | presentation `WsSessionRegistry`、`WsHandler`、`WsAuthHandshakeInterceptor` | 单用户连接上限 8、单连接消息频率 5 条/秒，鉴权复核周期可配（`ws.*`） |
| M20 | 已修复 | frontend `ws.ts`、`Detail.tsx`、`Square.tsx` | 收到 1008/4xxx 关闭码先刷新 token 再重连；刷新失败停止自动重连；清理未用 prop 与临时 token |

### 9.3 L 级（工程质量、文档与可维护性）

| 编号 | 状态 | 对应模块 | 修复要点 |
| --- | --- | --- | --- |
| L-文档死链 | 已修复 | docs、`peergrab-backend/README.md` | 恢复架构设计文档（`docs/architecture/架构设计与技术选型.md`）并新增配置参考；后端 README 链接改为 `../docs/architecture/...`，不再指向已删除路径 |
| L-注释与代码不一致 | 已修复 | `JdbcErrandRepository`、`GetErrandDetailUseCase`、`ErrandRepository.findConfirmTimeout` | 重写与实现不符的注释；Bloom 校准语义、CAS SQL 条件、宽限期参数命名按实际行为描述 |
| L-死代码/未用端口 | 已缓解并标注边界 | domain/application | 删除 CLOSED、CANCEL_AFTER_GRAB、DELIVERY_LATE、CANDIDATE/EXPIRED、NOT_GRABBABLE、`cancelAfterGrabBizNo`；边界：`GrabSlotPort.remainingSlot` 与 `ErrandRepository.findAutoSettleDue` 保留给集成测试和游标兜底 |
| L-规模化隐患 | 已修复（含边界） | `JdbcErrandQueryAdapter`、`CreditRankingRebuildJob`、docker 索引迁移、worker `DataRetentionJob` | 随机抽样改为 min/max 随机起点范围扫描；runner 任务列表改 `EXISTS` 并新增 `idx_runner_errand`；校区列表缓存 5 分钟；新增 5 个清理/查询索引并定期清理历史表。边界：对账 SQL 的相关子查询未改写，仍按校园量级设计，未做十万级压测 |
| L-IT 门禁 | 已修复 | `MiddlewareAvailable`、各 IT 类的 `@EnabledIfSystemProperty` | `-Dpeergrab.it=true` 时中间件不可用直接失败；本地无中间件时 IT 类不执行，不再静默"全绿" |
| L-可观测性 | 已缓解并标注边界 | `docker-compose.observability.yaml`、`docker/observability/` | 新增 Prometheus/Grafana overlay 与 11 条告警规则；边界：无 tracing、无日志聚合、无 Alertmanager，单实例本地 TSDB 保留 7 天 |

### 9.4 第二轮全项目自查追加修复（2026-10）

在 9.1–9.3 合入后，又对整个项目做了一轮"重新审计 + 运行时冒烟 + 旧库升级路径"验证，追加修复以下问题：

| 问题 | 状态 | 对应模块 | 说明 |
| --- | --- | --- | --- |
| 登录限速表无界增长 | 已修复 | `LoginAttemptGuard` | 每 60 秒清理"窗口已过且未锁定"的 key；补 `trackedKeys()` 测试验证 |
| 重发/outbox 任务预算耗尽时空占领取租约 | 已修复 | `LocalMessageRetryJob`、`FundEventOutboxJob`、`LocalMessageRepository`、`JdbcFundEventOutboxRepository` | 一次 claim 100 条但单轮预算只够发少量时，未处理消息会被 lease 120 秒；现在预算结束/异常退出时逐条释放未处理租约，并补单测 |
| 校区列表空结果不缓存 | 已修复 | `CreditRankingRebuildJob` | 空列表也会每分钟重复查库；改为按时间缓存 |
| 批量自动结算缺少真实数据库验证 | 已补测试 | `AutoSettleBatchIT` | 验证批量过账与单笔结算账务一致、重复调用 skipped 不重复入账、L1/L2/L3 对账全绿 |
| 旧库升级路径未验证 | 已验收 | `init.sql`/`migrate-*.sql` | 用改动前 `init.sql` 建库 → 执行两个迁移脚本 → 全量 289 项测试通过，校验 `transfer_count`、9001 账户、5 个新索引 |
| 运行时启动未验证 | 已验收 | bootstrap/worker jar | 隔离 MySQL/Redis 上 API 与 Worker 均可启动且 readiness=UP；MQ 关闭时仍可启动 |
| 保留期 SQL 未验证 | 已验收 | `DataRetentionJob` | 在真实 MySQL 上插入新旧数据执行 6 条 DELETE SQL，旧数据被删、新数据保留 |

**复核**：`mvn -Dpeergrab.it=true test`（289 项、0 失败/0 跳过）覆盖以上改动；运行时冒烟与旧库升级验证脚本未入库，命令见本轮交付说明。

**合计**：H 7 项、M 20 项、L 6 类，共 33 条记录；其中 28 条"已修复"、5 条"已缓解并标注边界"。复核入口：`git diff`、`mvn -Dpeergrab.it=true test`、[单机部署说明](../../peergrab-backend/docker/ECS_DEPLOY.md)与[可观测性 README](../../peergrab-backend/docker/observability/README.md)。
