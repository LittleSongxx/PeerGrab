# PeerGrab 不变式与对照实验

> 本文定义“系统必须始终成立什么”，并给出断言点、代码落点、验证测试与对照实验。它不重复[架构设计](架构设计与技术选型.md)的链路讲解，也不重复[压测方法论](../benchmark/methodology.md)的运行步骤。
>
> 配套阅读：[压测方法论](../benchmark/methodology.md)、[压测设计](design-benchmark.md)、[设计演进记录](../engineering/design-evolution.md)、[全面深度分析报告](../analysis/PeerGrab-全面深度分析报告.md)。
>
> 口径：只写当前代码可核对的事实；实测数字未回填的地方保留占位符，不编造。

## 1. 名词与口径

### 1.1 抢单“四层防护”（只叫四层）

PeerGrab 的抢单同步链路是**四层**，不是五层：

| 层 | 作用 | 代码落点 | 失败时的行为 |
| --- | --- | --- | --- |
| L1 限流 | 按 `errandId` 挡掉过热流量 | `GrabRateLimiterPort.tryPass`；`SentinelGrabRateLimiterAdapter` / `LocalGrabRateLimiterAdapter`；调用点在 `GrabErrandUseCase.grab` | 返回 `GRAB_RATE_LIMITED`，不进入后续判定 |
| L2 资格 | 信用分门槛 + 在途任务上限 + 角色/自抢校验 | `CreditRepository.scoreOf`、`ErrandQueryPort.countOngoingByRunner`、`ActorRolePolicy`；`GrabErrandUseCase.grab` | 返回 `CREDIT_TOO_LOW`/`TOO_MANY_ONGOING`/`ROLE_NOT_ALLOWED`/`SELF_GRAB_FORBIDDEN` |
| L3 Redis Lua | 原子完成名额判断、扣减、已抢集合、幂等键、预占位 | `GrabSlotPort.tryAcquire`；`RedisGrabSlotAdapter`；[`grab.lua`](../../peergrab-backend/peergrab-infrastructure/src/main/resources/lua/grab.lua) | 返回 `SLOT_FULL`/`ALREADY_GRABBED`/`DUPLICATE_REQUEST`/`REQUEST_ID_CONFLICT`/`SLOT_MISSING`；`SLOT_MISSING` 交给 L4 |
| L4 MySQL CAS | 最终裁决：状态 + 版本 + 名额条件更新，抢单记录唯一索引兜底 | `GrabTransactionalStep.lockAndRecord`；`RunnerQuotaPort.lockAndHasCapacity`；`ErrandRepository.casLockForRunner`；`GrabRecordRepository.insert` | CAS 0 行或唯一索引冲突则事务回滚，返回冲突/名额满 |

**候选队列、失败回补、DB 扫描兜底不是第五层防护**：它们分别负责“失败后的机会”“Redis 与 DB 不一致的补偿”“MQ 不可用时的兜底”，都不参与“这一次谁抢中”的同步裁决。把四层说成五层，会模糊“正确性由哪里保证”。

### 1.2 “双唯一索引”

`grab_record` 上真正承担“双保险”的是两个唯一索引：

- `uk_errand_round_seq(errand_id, round, seq)`：同一任务同一轮、同一个名额序号只能有一条记录。
- `uk_errand_round_user(errand_id, round, runner_id)`：同一任务同一轮、同一用户只能占一个名额。

另有 `uk_errand_request(errand_id, request_id)` 专管请求幂等，**不计入“双唯一索引”**，但它是 INV-3 的最终落点。三个索引都带 `errand_id`，且 `round` 进入前两个索引，原因是任务回退/递补后 round 自增，索引语义必须是“同一轮内唯一”，而不是“整个任务生命周期唯一”。

### 1.3 INV-1..4

| 编号 | 不变式 | 精确表述 | 备注 |
| --- | --- | --- | --- |
| INV-1 | 不超卖 | 对任意任务，`slot_taken <= slot_total`；单名额任务在任一时刻 `COUNT(grab_record WHERE result='GRABBED' AND round=当前round) <= 1` | 最终以 DB 为准；Redis 只是提示 |
| INV-2 | 同一用户不重复占名额 | 同一 `(errand_id, round, runner_id)` 至多一条成功记录 | 由 `uk_errand_round_user` 与 Lua `SISMEMBER` 双重约束 |
| INV-3 | 重复请求只生效一次 | 同一 `(errand_id, round, request_id)` 至多一条成功记录；重放只能返回同一 runner 的成功结果 | 跨轮不重放；跨用户不返回他人结果 |
| INV-4 | 失败者有候选机会 | 任务 `LOCKED`/名额满时，失败请求进入候选队列并获得排队结果；候选被领取时有租约，不能被两个 worker 同时消费 | 这是活性/公平性约束，不是资金正确性约束 |

### 1.4 资金“三道幂等闸门”

结算、退款、仲裁结算都必须穿过三道独立闸门：

| 闸门 | 作用 | 代码落点 | 数据库约束 |
| --- | --- | --- | --- |
| 1. 任务状态 CAS | `DELIVERED → SETTLED` 只有一个事务能成功 | `ErrandRepository.casSettle` / `casAutoSettle`；`SettleErrandUseCase.doSettle` | `status='DELIVERED' AND version=?` |
| 2. 托管单状态 CAS | `HELD → RELEASED` 只有一个事务能成功 | `WalletRepository.casEscrowStatus`；`SettleErrandUseCase.doSettle` / `settleAutoBatch` | `escrow_order.status='HELD'` |
| 3. 账本业务号唯一 | 同一业务号在同一账户同一方向只能记一次 | `WalletPosting.post` / `postBatch`；`LedgerEntry.settleBizNo` | `uk_biz_direction(biz_no, account_id, direction)`；结算 `biz_no=settle:{errandId}` |

三道闸门是“并联”的：即使前一道被绕过（理论上不应发生），后一道仍会拦住重复入账。金额守恒（`跑腿所得 + 佣金 == 托管金额`）是结果断言，不是闸门。

### 1.5 异步“幂等三件套”

确认超时/递补链路面对的是 at-least-once 消息，必须同时具备：

| 件 | 作用 | 代码落点 | 约束 |
| --- | --- | --- | --- |
| 1. version CAS | 任务被确认、改派或其它操作改过，旧消息的 CAS 必然失败 | `ErrandRepository.casTransferToNext` / `casRevertToPublished` | `version=?` |
| 2. round | 区分消息属于第几轮，防 A→B→A 轮回时旧消息误判 | `TimeoutTransferUseCase.handleTimeout(errandId, expectedRound)` | `round=?` |
| 3. `msg_key` 唯一 | 同一轮的超时消息只登记一次 | `DelayTaskPolicy.timeoutKey`；`LocalMessageRepository.enqueue`；`local_message.uk_msg_key` | `timeout:{errandId}:{round}` |

自动结算消息的 key 是 `autosettle:{errandId}`，不带 round（一个任务只送达一次）；它与超时消息共用 `local_message`，所以前缀必须区分，否则唯一索引会让两类消息互相顶掉。

### 1.6 对账“L1/L2/L3”

| 层 | 校验 | 代码落点 | 差异落点 |
| --- | --- | --- | --- |
| L1 | 全局借贷平衡：`SUM(DEBIT) == SUM(CREDIT)` | `ReconRepository.debitMinusCredit()`；`ReconciliationJob.run` | `recon_diff(DEBIT_CREDIT)` |
| L2 | 每单系统账户（ESCROW/COMMISSION，owner_id=errandId）快照 = 该账户流水净额；用户户“最新流水 vs 快照 + 版本” | `findSnapshotDiffs()`、`findUserBalanceDiffs()`；`ReconciliationJob.run` | `recon_diff(SNAPSHOT/USER_SNAPSHOT)` |
| L3 | 托管闭环：`RELEASED/REFUNDED` 托管单有对应结算/退款流水；任务已结算/退款但托管仍 `HELD` 也是差异 | `findEscrowClosureDiffs()`；`ReconciliationJob.run` | `recon_diff(ESCROW_CLOSURE)` |

`recon_diff` 有记录就说明代码有 bug，不是“允许的小误差”；同一天重复跑由 `uk_date_type_subject(check_date, check_type, subject)` 去重。`recon_diff` 是审计证据，**不参与保留期自动清理**。

## 2. 断言点、代码落点与验证测试

### 2.1 抢单链路

| 不变式 | 断言点 | 代码落点 | 验证测试 |
| --- | --- | --- | --- |
| INV-1 | 应用层成功数 = 1；DB `GRABBED` 行数 = 1；`slot_taken=1`；Redis 剩余 = `slot_total - slot_taken` | `GrabTransactionalStep.lockAndRecord`；`JdbcErrandRepository.casLockForRunner`（`status='PUBLISHED' AND version=? AND slot_taken<slot_total`）；`uk_errand_round_seq` | `GrabConcurrencyIT.spike_2000_concurrent_only_one_wins`；`OversellControlExperimentIT` 实验 A/B |
| INV-2 | 同一 runner 的 `GRABBED` 记录数 ≤ 1；第二次抢返回 `ALREADY_GRABBED` | `grab.lua` 的 `SISMEMBER`；`uk_errand_round_user`；`GrabErrandUseCase` 的 `target.grabberId()==runnerId` 快速分支 | `GrabConcurrencyIT.same_user_cannot_grab_twice` |
| INV-3 | 同一 requestId 两次调用只产生 1 条 `GRABBED`；换用户重放返回 `DUPLICATE_REQUEST`；跨轮不重放 | `GrabRecordRepository.findGrabbedByRequestId(..., round)`；`JdbcGrabRecordRepository`；`uk_errand_request` | `GrabConcurrencyIT.duplicate_request_is_idempotent`、`request_id_cannot_be_replayed_by_another_runner`；`GrabErrandUseCaseTest.stale_request_id_from_previous_round_is_not_reported_as_success` |
| INV-4 | 失败者 `candidateRank != null`；候选队列 size 增加；租约到期可恢复；旧 worker 不能 ack 新租约 | `CandidateQueuePort.offer/pollBest/acknowledge/release`；`RedisCandidateQueueAdapter`；`candidate_*.lua` | `GrabConcurrencyIT.losers_enter_candidate_queue`；`CandidateLeaseIT` |

补充断言：

- Redis 名额键丢失时，开放任务仍能由 DB CAS 抢中：`GrabConcurrencyIT.missing_redis_slot_falls_back_to_database_cas`。
- 回退后 Redis 名额键重建：`TimeoutTransferIT.timeout_reverts_when_no_candidate` 断言 `remainingSlot=1` 且新跑腿能抢到。
- 抢单事务内 CAS 成功后 `ensureUserAccount`，避免“无账户身份”在结算时中毒；账户开通在抢单事务内的锁序是 errand→wallet。

### 2.2 资金链路

| 闸门/约束 | 断言点 | 代码落点 | 验证测试 |
| --- | --- | --- | --- |
| 任务 CAS | 200 并发同一任务只有 1 次 `SETTLED` | `SettleErrandUseCase.doSettle`；`ErrandRepository.casSettle` | `SettleConcurrencyIT.concurrent_settle_same_errand` |
| 托管 CAS | 托管单只从 `HELD` 转 `RELEASED` 一次；结算后 escrow 余额 0 | `WalletRepository.casEscrowStatus`；`SettleErrandUseCase` | `SettleConcurrencyIT`、`AutoSettleBatchIT` |
| 账本唯一 | `settle:{id}` 恰好 3 条流水（托管借 + 跑腿贷 + 佣金贷，佣金为 0 时 2 条） | `WalletPosting.post`；`uk_biz_direction` | `SettleConcurrencyIT`、`AutoSettleBatchIT` |
| 金额守恒 | 跑腿所得 + 佣金 = 托管金额；全局钱包总额不变 | `SettleErrandUseCase.commissionCents`（BigDecimal DOWN）；`WalletPosting` | `SettleConcurrencyIT`、`AutoSettleBatchIT` |
| 三层对账 | L1/L2/L3 全绿；`recon_diff` 为空 | `ReconRepository`；`ReconciliationJob` | `SettleConcurrencyIT`、`AutoSettleBatchIT`；`UserBalanceReconIT` 验证用户户漂移可被发现 |

### 2.3 异步链路

| 约束 | 断言点 | 代码落点 | 验证测试 |
| --- | --- | --- | --- |
| 旧消息不流转 | 已确认/已流转任务的超时消息返回 `SKIPPED` | `TimeoutTransferUseCase.handleTimeout`；`casTransferToNext` 的 `version/round/confirm_deadline_at` 条件 | `TimeoutTransferIT.confirmed_errand_is_not_transferred`、`stale_round_message_is_discarded` |
| 重复消息只生效一次 | 同一消息投 5 次只 `TRANSFERRED` 1 次 | `casTransferToNext`；`msg_key` 唯一 | `TimeoutTransferIT.duplicate_timeout_message_transfers_once` |
| 早到消息不提前领取候选 | 未到截止时间返回 `SKIPPED`，候选队列不变 | `ErrandRepository.confirmTimeoutDue`；`TimeoutTransferUseCase` | `TimeoutTransferIT.early_timeout_message_cannot_transfer` |
| 轮次上限回退 | 达到 `max-transfer-rounds` 后回退 `PUBLISHED`，不再逐个试候选 | `TimeoutTransferUseCase.handleTimeout`；`errand.transfer_count`；`casRevertToPublished` | `TimeoutTransferIT.max_rounds_reached_triggers_revert` |
| 截止时间完整 | `LOCKED`/`DELIVERED` 任务没有 NULL 截止时间 | `ErrandDeadlineRepairJob.repair` | `ErrandDeadlineRepairJobTest` |

### 2.4 对账层

| 层 | 断言点 | 代码落点 | 验证测试 |
| --- | --- | --- | --- |
| L1 | `debitMinusCredit()==0` | `JdbcReconRepository` | `SettleConcurrencyIT`、`AutoSettleBatchIT` |
| L2 | `findSnapshotDiffs()` 为空；`findUserBalanceDiffs()` 为空 | `JdbcReconRepository` | `AutoSettleBatchIT`；`UserBalanceReconIT` |
| L3 | `findEscrowClosureDiffs()` 为空 | `JdbcReconRepository` | `SettleConcurrencyIT`、`AutoSettleBatchIT` |

## 3. 对照实验清单

对照实验的目的不是“再跑一遍正常路径”，而是**故意用错误实现证明测试能失败**。只会通过的测试没有价值。

### 3.1 抢单超卖：`OversellControlExperimentIT`

| 实验 | 错误实现 | 预期异常现象 | 断言 | 教训 |
| --- | --- | --- | --- | --- |
| A | 把 `grab.lua` 拆成 `GET → 判断 → DECR` 三次 Redis 调用 | 多个线程读到同一个 `slot=1` 并都通过 Redis 判定；名额被扣成负数 | `passedRedis > 1`；`remaining < 0`；本实验不写 DB，因此**不能**用它声称“DB 只让 1 人落库”，DB 兜底由实验 B 的反证与 `GrabConcurrencyIT` 证明 | Redis 只是提示；没有 DB 终裁，Redis 判错就可能超卖 |
| B | 在 A 的基础上把 DB 改成无条件 `UPDATE errand SET status='LOCKED', grabber_id=?...` | 多人同时“抢中”同一个名额；`slot_taken` 与成功人数不一致 | `passed > 1`；打印 `slot_taken` 作为反例 | 两层同时拆掉才会真正超卖；这反证了“Lua + DB CAS”两层都不能省 |

### 3.2 事务失效：`TransactionPitfallIT`

| 场景 | 错误实现 | 预期异常现象 | 断言 | 教训 |
| --- | --- | --- | --- | --- |
| 1 同类自调用 | `this.txInsertThenFail()` | 事务注解不生效，数据残留 | 错误写法 `count=1`；走代理 `count=0` | 同类自调用绕过 AOP；事务方法必须单独成 Bean 或从代理调用 |
| 2 非 public | `protected` 方法加 `@Transactional` | 注解静默失效，数据残留 | `count=1` | 代理只拦截 public；不要用“注解在”判断“事务在” |
| 3 异常被吞 | `catch` 后不抛、不标记回滚 | 代理感知不到异常，事务提交 | 错误写法 `count=1`；`setRollbackOnly` 后 `count=0` | 吞异常等于告诉框架“一切正常” |
| 4 checked 异常 | `@Transactional` 不写 `rollbackFor` | 默认只对运行时异常回滚，数据残留 | 错误写法 `count=1`；声明 `rollbackFor=Exception.class` 后 `count=0` | 受检异常必须显式声明 |
| 5 手动 new | `new ManualNewService(jdbc)` | 对象不被代理，注解无效 | `count=1` | 事务对象必须从容器拿 |
| 6 MyISAM | 表引擎是 MyISAM | 引擎不支持事务，注解无能为力 | `count=1`；`information_schema` 确认引擎为 MyISAM | 先确认引擎，再谈事务 |
| 连接池死锁 | 池=2、并发=4，主事务内嵌 `REQUIRES_NEW` | 内层借不到第二个连接，连接耗尽 | `exhausted > 0`；15 秒内收敛 | `池大小 >= (1 + 嵌套层数) × 预期并发`；审计 `REQUIRES_NEW` 要单独算连接预算 |

### 3.3 缓存窗口：`CacheDoubleDeleteControlIT` 与 `CacheStaleReaderWindowIT`

| 实验 | 错误/受控实现 | 预期异常现象 | 断言 | 教训 |
| --- | --- | --- | --- | --- |
| 双删接线 | 第二次删除不执行（测试用 Noop 投递） | 慢读回填的旧值残留到 TTL | 发布登记 1 次双删；抢单再登记 1 次；延迟 400–700 ms | 双删是“缩小窗口”，不是强一致；接线必须有断言，不能只看代码 |
| 慢读窗口 | 读在提交前开始，提交后才回填；第二次删除未执行 | 缓存仍是 `PUBLISHED`，DB 已是 `LOCKED` | 缓存值含 `PUBLISHED` 且不含 `LOCKED`；DB 查询为 `LOCKED` | 固定延迟的第二次删除不能证明严格一致；必须承认窗口并靠 TTL/校验兜底 |

### 3.4 结算并发：`SettleConcurrencyIT`

| 实验 | 受控实现 | 预期现象 | 断言 | 教训 |
| --- | --- | --- | --- | --- |
| 不同任务并发 | 500 个任务、64 工作线程持续结算 | 每个任务恰好结算一次；允许少量资源型异常 | DB 实际结算数 = 500；冲突 0；L1/L2/L3 全绿；总额守恒 | 客户端计数会因响应超时少记，必须以 DB 流水为准 |
| 同一任务并发 | 200 线程同时结算同一任务 | 恰好 1 次成功，其余幂等/冲突拒绝 | `settled=1`；跑腿只加 1900 分；`settle:{id}` 恰好 3 条流水 | 同单幂等靠任务 CAS + 托管 CAS + 账本唯一三道闸门，不靠客户端去重 |

## 4. 实测记录（2026-10-19，隔离 MySQL/Redis）

> 以下 4 个 IT 需要**隔离 MySQL/Redis** 才能运行；测试会删除流水、托管单并重置账户，**只能连接可丢弃的测试库**。
> 实测命令（`peergrab-backend` 目录）：`mvn -B -ntp -Dpeergrab.it=true -Dtest=OversellControlExperimentIT,TransactionPitfallIT,CacheDoubleDeleteControlIT,CacheStaleReaderWindowIT -Dsurefire.failIfNoSpecifiedTests=false test`；
> 环境：独立 compose MySQL 8.0（3307）/ Redis 7.4（6380），`peergrab.it=true`，共 12 个用例全部通过（0 failure / 0 error / 0 skipped）。

| IT | 实验/断言 | 关键指标 | 实测 |
| --- | --- | --- | --- |
| `OversellControlExperimentIT` | 实验 A：Redis 非原子判定；实验 B：再拆掉 DB CAS 条件 | 实验 A 的 Redis 通过人数、名额余额；实验 B 的无条件 UPDATE 成功人数、`slot_taken` | **A：Redis 通过 14 人、名额余 -13**（Redis 层已超卖，DB CAS + 唯一索引兜底）；**B：无条件 UPDATE 成功 22 人（同一名额）**，`slot_taken=0`。2 个用例通过 |
| `TransactionPitfallIT` | 6 个 `@Transactional` 失效场景 + 池=2/并发=4 的 `REQUIRES_NEW` 连接耗尽 | 每个场景错误写法/正确写法的回滚结果、连接耗尽计数 | **7 个用例通过**：陷阱 1–6（自调用/非 public/吞异常/checked 异常/手动 new/MyISAM）全部按预期区分回滚与不回滚；连接池并发实测 **池=2、并发=4、成功=1、连接耗尽=3** |
| `CacheDoubleDeleteControlIT` | 双删接线 + 慢读窗口 | 第二次删除是否登记、慢读旧值是否残留 | **2 个用例通过**：提交后登记约 500ms 的第二次删除；移除第二次删除时旧值残留到 TTL |
| `CacheStaleReaderWindowIT` | 慢读在提交/失效后回填 | 回填后缓存状态、DB 状态、是否读到旧值 | **1 个用例通过**：慢读在提交+失效完成后回填，详情会暂时陈旧，验证缓存不参与正确性裁决 |

### 4.1 复跑命令

在 `peergrab-backend` 目录执行，先导出隔离 MySQL/Redis 连接：

```bash
export PEERGRAB_TEST_DB_HOST=127.0.0.1
export PEERGRAB_TEST_DB_PORT='<独立测试 MySQL 端口>'
export PEERGRAB_TEST_DB_PASSWORD='<独立测试 MySQL 密码>'
export PEERGRAB_TEST_REDIS_HOST=127.0.0.1
export PEERGRAB_TEST_REDIS_PORT='<独立测试 Redis 端口>'

mvn -B -ntp -Dpeergrab.it=true -Dtest=OversellControlExperimentIT test
mvn -B -ntp -Dpeergrab.it=true -Dtest=TransactionPitfallIT test
mvn -B -ntp -Dpeergrab.it=true -Dtest=CacheDoubleDeleteControlIT test
mvn -B -ntp -Dpeergrab.it=true -Dtest=CacheStaleReaderWindowIT test
```

说明：

- 如果从仓库根或聚合模块运行，`-Dtest` 可能让没有匹配测试的模块报“No tests were executed”；可在 `peergrab-backend` 根目录执行，或加 `-Dsurefire.failIfNoSpecifiedTests=false`（只用于本地复跑，不改变 CI 门禁）。
- 这些 IT 都带 `@EnabledIfSystemProperty(named="peergrab.it", matches="true")`；不设置 `-Dpeergrab.it=true` 时不会执行。设置后中间件不可用会由 `MiddlewareAvailable.require()` 直接失败，不再静默跳过。
- `OversellControlExperimentIT` 的实验 B 会留下错误实现产生的数据，测试注释说不删并登记到 `bench_run_item`；实际清理由销毁整个隔离项目完成（`cleanup.sh` 只接受 `--destroy`/`--confirm`，没有 `--keep-last` 参数）。只在一次性测试库运行。

### 4.2 回填时记录什么

- 代码基线（`git rev-parse --short HEAD`）、镜像/中间件版本。
- 并发数、迭代次数、是否虚拟线程、连接池大小。
- 每个断言的实测值（不是“通过/失败”四个字），以及失败时的日志片段。
- 测试耗时与是否出现资源型异常；资源型异常不能算业务失败，但必须披露。

## 5. 与其它文档的边界

- **本文**：不变式、断言点、代码落点、对照实验。
- [压测方法论](../benchmark/methodology.md)：环境隔离、预热、指标口径、拐点判定、报告模板。
- [压测设计](design-benchmark.md)：场景与实验设计、变量控制、样本量。
- [设计演进记录](../engineering/design-evolution.md)：这些不变式是为什么变成现在这样的，以及踩过哪些坑。
- [全面深度分析报告](../analysis/PeerGrab-全面深度分析报告.md)：独立审计发现与修复记录。

如果代码修改触及以下任一位置，应先更新本文的断言表，再改测试：

- `grab.lua` 返回码或 `SlotOutcome`；
- `casLockForRunner` / `casTransferToNext` / `casSettle` 的 WHERE 条件；
- `grab_record` / `wallet_ledger` / `local_message` 的唯一索引；
- `SettleErrandUseCase.doSettle` 的闸门顺序；
- `ReconciliationJob` 的 L1/L2/L3 查询。

## 6. 一句话总结

PeerGrab 的正确性不是靠某一个“银弹”保证的，而是靠**分层断言**：Redis 挡流量、DB CAS 定胜负、唯一索引兜底、状态机管生命周期、三道闸门防重复入账、三层对账发现漏网之鱼。对照实验的作用，是证明每一层在缺失时真的会出错——否则“测试通过”只是测试太弱。
