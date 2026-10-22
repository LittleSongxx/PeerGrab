# 钱包账户拓扑：每单托管户与佣金户

> 状态：已实施（2026-10）。本文记录“为什么从全局共享系统户改为每单独立账户”，以及迁移、锁序和代价。

## 1. 背景

旧模型只有两个全局系统账户：

| 账户 | owner_id | 用途 |
| --- | ---: | --- |
| `ESCROW` | `-1` | 所有 HELD 托管单的资金汇总 |
| `COMMISSION` | `-2` | 所有已结算佣金的汇总 |

发布、结算、退款、仲裁和批量自动结算都会更新这两行。资金正确性没有问题，但所有结算在数据库层串行争用同一对行锁；S4 短批次实测只有约 32–57 TPS，批量页越大，单事务持有这两行锁的时间越长。

## 2. 决定

每个任务在发布事务内创建一对独立账户：

| 账户 | `owner_id` | `owner_type` | 初始余额 |
| --- | --- | --- | ---: |
| 任务托管户 | `errand_id` | `ESCROW` | 0（发布过账后等于悬赏额） |
| 任务佣金户 | `errand_id` | `COMMISSION` | 0（结算/仲裁时入账） |

- 账户仍存于 `wallet_account`，由 `uk_owner(owner_id, owner_type)` 唯一约束；不新增表/列。
- 开户通过 `WalletRepository.ensureAccount(ownerId, type)` 幂等完成，插入使用 `ON DUPLICATE KEY UPDATE`。
- 发布事务内：创建两个账户 → 发单人扣款 → 任务托管户入账 → 写 `escrow_order` → 任务 DRAFT→PUBLISHED。
- 结算/退款/仲裁/批量结算都通过 `(errand_id, ESCROW/COMMISSION)` 查本任务账户，不再有全局系统户查找，也不做“缺账户回退共享户”。
- 旧 `-1/-2` 行只作为历史流水归属保留；新代码不再读写。

## 3. 不变量

1. `ESCROW` 账户的 `available` 等于该任务 `HELD` 托管单金额；结算/退款后归零。
2. `COMMISSION` 账户余额等于该任务已结算/仲裁产生的佣金；退款任务保持 0。
3. 所有账户 `available >= 0`（`ck_balance`），金额以整数分存储。
4. 每笔资金动作的 `wallet_ledger` 借贷平衡；`uk_biz_direction` 保证同一业务同一账户同一方向只记一次。
5. 对账 L2 按账户逐个核对“快照 == 流水净额”；L3 继续按托管单断闭环。

## 4. 锁序与并发

- 任务状态 CAS：`DELIVERED → SETTLED`；
- 托管单状态 CAS：`HELD → RELEASED/REFUNDED`；
- 多账户过账按账户 ID 升序 `SELECT ... FOR UPDATE`；
- 批量结算在一个事务里锁本页各任务的账户，不再锁全局共享户；在线结算与批量结算仍使用各自路径。

## 5. 收益与代价

**收益**

- 消除全局系统户行锁热点；不同任务的结算可以并行。
- 每个任务的托管/佣金余额可以直接按账户核对，审计更直观。
- 批量结算锁的是本页任务账户，不再阻塞其他任务的结算。

**代价**

- `wallet_account` 行数随任务数增长（每任务 2 行）；需要监控表体积和索引。
- 对账 L2 从“两个系统户”变为“每个任务两个账户”，SQL 聚合仍需按账户进行。
- 旧库必须执行 `migrate-wallet-per-errand.sql`；新代码不做共享户 fallback。

## 6. 迁移与回滚

- 新库：`init.sql` 不再预置 `-1/-2`，由发布路径创建每单账户。
- 旧库：停 app/worker、备份、执行 `migrate-wallet-per-errand.sql`。脚本只处理 `status='HELD'` 的托管单，把共享 `ESCROW(-1)` 余额转入每单托管户，补每单 `COMMISSION` 账户，并写 `wallet-migrate:{errandId}` 借贷流水；脚本幂等。
- 迁移后运行 `verify_fund.sql` 的 L1/L2/L3 和一次发布/结算 smoke。
- 回滚：恢复迁移前数据库备份 + 回滚应用代码；迁移脚本不删除旧账户/旧流水。

## 7. 备选方案

**系统账户分片**：保留共享语义，把 `ESCROW/COMMISSION` 各拆 N 个分片账户，按 `errand_id` 哈希路由。改动较小，但仍是“分片内共享”；批量结算若锁住全部分片，仍可能和在线结算争用。为了彻底消除共享系统户热点，选择每单账户。

## 8. 相关代码位置

- `peergrab-backend/peergrab-domain/src/main/java/com/peergrab/domain/wallet/ports/WalletRepository.java`
- `peergrab-backend/peergrab-infrastructure/src/main/java/com/peergrab/infrastructure/persistence/JdbcWalletRepository.java`
- `peergrab-backend/peergrab-application/src/main/java/com/peergrab/application/usecase/PublishErrandUseCase.java`
- `peergrab-backend/peergrab-application/src/main/java/com/peergrab/application/usecase/SettleErrandUseCase.java`
- `peergrab-backend/peergrab-application/src/main/java/com/peergrab/application/usecase/RefundErrandUseCase.java`
- `peergrab-backend/peergrab-application/src/main/java/com/peergrab/application/usecase/ArbitrateSettleStep.java`
- `peergrab-backend/docker/init.sql`
- `peergrab-backend/docker/migrate-wallet-per-errand.sql`
