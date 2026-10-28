# 每单账户模型本机 IT 观测（2026-10-22）

> 口径：本机隔离 MySQL 8／Redis 7（Docker，独立库与实例），Maven `mvn -B -ntp -Dpeergrab.it=true clean test`（Windows Maven 访问本机 Docker 端口）。
> 这不是 ECS 容量、稳态吞吐或全站 SLA；只能与同为“每单账户模型 + 同负载”的结果比较。

## 1. 变更内容

- 旧模型：所有任务共享 `ESCROW(-1)` / `COMMISSION(-2)` 两行系统账户。
- 新模型：每个任务在发布事务内创建 `owner_id=errandId` 的独立 `ESCROW` / `COMMISSION` 账户；结算、退款、仲裁、批量结算只读写本任务账户。
- `init.sql` 不再预置 `-1/-2`；旧库使用 `docker/migrate-wallet-per-errand.sql` 迁移。

## 2. 测试结果

| 项 | 结果 | 说明 |
| --- | --- | --- |
| 后端全量测试 | **315 项、0 失败、0 错误、0 跳过；81 项 IT 真实执行**（当次快照；当前口径 318 项/80 IT，以根 README 与最新 surefire 汇总为准） | 隔离 MySQL/Redis；`summarize_tests.py --strict --require-it` |
| 新增每单账户 IT | 3 项通过 | 发布开户、结算/退款只动本任务账户、缺账户 fail-closed |
| 钱包并发/幂等 IT | `PublishEscrowIT`、`AutoSettleBatchIT`、`SettlementIT`、`SettleConcurrencyIT`、`SettlementAccountAutoProvisionIT`、`WalletLedgerConcurrencyIT` 等通过 | 含 200/500 并发资金一致性与三层对账 |
| S4-a 本机 IT | 500 个不同任务、64 工作线程、单轮，约 **118–119 TPS**（两轮观测） | 来自 `bench_run` 的 `IT/S4` 记录；S4-b 同单并发仍恰好一次成功 |
| 迁移演练 | 旧共享户 fixture 2 个 HELD 单、3000 分：首次迁移成功，第二次 no-op；L1 借贷差 0、系统户 L2 快照差 0 | `migrate-wallet-per-errand.sql`；不是自动化 IT |

## 3. 边界

- 本机 Docker MySQL/Redis 与 ECS 的 CPU、网络、并发模型不同，**不得与 ECS 报告混算**。
- S4-a 是单笔 `settle` 路径，不是 `settleAutoBatch`；批量结算新模型尚未独立压测。
- 118–119 TPS 是本机短批次观测，不是长期容量；剩余竞争点是同一跑腿/发单人 USER 钱包与批量页内共享账户。
- 迁移演练未纳入 Maven IT；执行前仍需在恢复副本上按 `ECS_DEPLOY.md` 演练并核对 `verify_fund.sql`。
