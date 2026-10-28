# 持久幂等抢单请求（grab-request v2）

> 状态：已实施（P12）。本文说明为什么在既有“四层防护 + DB CAS”之外再引入一层**数据库请求事实**，
> 以及它如何与 Redis 预占、数据库终裁、Worker 恢复协同工作。
> 对应代码：`peergrab-application/.../GrabRequestUseCase.java`、
> `peergrab-infrastructure/.../JdbcGrabRequestRepository.java`、
> `peergrab-worker/.../GrabRequestRecoveryJob.java`。

## 1. 问题：同步抢单的幂等只覆盖“成功后的重放”

v1 的幂等依赖 `grab_record.uk_errand_request(errand_id, request_id)`：**只有抢中并落库之后**，
重复请求才能靠 `findGrabbedByRequestId` 拿到同一结果。但下面三类窗口没有持久事实可查：

1. **请求已到服务端、结果尚未落库**：客户端超时重试，第二次请求可能重复消耗 Redis 预占、
   再次打 DB CAS（虽然不会超卖，但会产生无意义的冲突与额外的补偿风险）；
2. **Lua 预占成功、DB 落库前进程崩溃**：Redis 名额被占着，但没有任何持久记录说明“谁在抢、抢到哪一步”，
   只能等残留状态自然过期或靠巡检清理；
3. **DB 已提交、响应在网络上丢失**：客户端无法区分“成功但响应丢了”和“失败”，
   重试依赖 `grab_record` 才能收敛。

v2 的目标：**requestId 本身成为持久事实**。请求先登记，执行后终结；任何时刻崩溃，
恢复线程都能从数据库判断“业务到底提交没有”，客户端用同一 requestId 查询也不会得到第二个结果。

## 2. 模型：grab_request 表与状态机

```text
PROCESSING ──(抢中/确定失败)──> SUCCEEDED / REJECTED
     │
     └──(租约过期，业务未提交)──> EXPIRED
```

| 字段 | 作用 |
|---|---|
| `campus_id, errand_id, runner_id, request_id` | 唯一作用域：`uk_grab_request_scope(campus_id, runner_id, operation, request_id)` |
| `payload_hash` | 同一 requestId 只允许对应同一 `(errandId, round)`；内容不一致返回 `REQUEST_CONFLICT` |
| `request_round, base_errand_version` | 请求发起时看到的轮次/版本，用于拒绝过期请求与恢复判定 |
| `reservation_token` | 执行线程的租约令牌；只有持有令牌的线程能写终态，防止过期线程“复活” |
| `lease_until` | 数据库时钟签发，**不随重试续期**；默认 30s |
| `status, result_code, http_status, result_json` | 持久结果，重放时原样返回，不再执行业务 |
| `completed_at` | 终态时间，便于审计与对账 |

状态迁移只允许 `PROCESSING → 终态`，终态不可逆；所有终态写入都是条件 UPDATE（带 token 与状态）。

## 3. 接口契约

| 接口 | 说明 |
|---|---|
| `POST /api/errands/{id}/grab-v2`（Header `X-Request-Id` 必填 UUID，Body `{"expectedRound": N}`） | 登记并执行；HTTP 200=已确定结果，202=处理中，409=确定失败/冲突，429=限流，503=依赖暂不可用 |
| `GET /api/errands/{id}/grab-requests/{requestId}` | 用同一 requestId 查询持久结果；202/网络失败后的唯一恢复入口 |

响应体仍是统一 `Result<T>`，`data` 包含 `requestId, status, code, grabbed, appliedRound, replayed, retryAfterMillis, httpStatus`。
**同一 requestId 的重放一定返回同一结果**（`replayed=true`），不会二次占名额。

与 v1 的关系：
- v1 `POST /grab` 保留，供灰度与回滚；v2 使用独立路径，不改变旧契约；
- v2 内部复用 `GrabErrandUseCase`，四层防护、DB CAS、候选队列、超时消息等语义完全一致；
- 禁止 v1/v2 写进程长期混跑：切换完成后应下线 v1 端点或加配置开关。

## 4. 执行流程

```text
1. 校验 requestId 为 UUID、expectedRound >= 0
2. 读任务（campusId/round/version/status）
3. 查已有请求：
   - 终态 → 直接重放（replayed=true）
   - PROCESSING 且租约有效 → 202 + retryAfter
   - PROCESSING 且租约过期 → 进入恢复裁决（见 §5）
   - payloadHash 不一致 → 409 REQUEST_CONFLICT
4. 获取进程内在途容量（Semaphore，默认 10）——满载 503，避免打满连接池
5. 登记 grab_request(PROCESSING, token, lease)
   - 唯一键冲突 → 说明并发同 ID，重读后重放
6. expectedRound / 任务状态前置校验，不满足则终结为 REJECTED
7. 调 GrabErrandUseCase.grab（Redis 预占 + DB CAS + 候选队列 + 超时消息）
8. 把结果写回请求行（条件 UPDATE token+PROCESSING+租约）
   - 成功 → SUCCEEDED / 200
   - 业务失败 → REJECTED / 对应 HTTP 状态码
   - 未知异常 → 保持 PROCESSING，返回 202；由恢复线程收敛
```

**关键取舍：Redis 仍然是“提示与削峰”，不是事实来源。** v2 没有替换 Lua 预占，
而是在外面加了一层可查询的持久请求；因此 Redis 丢失/错判仍由数据库 CAS 与唯一索引兜底。

## 5. 恢复与崩溃矩阵

`GrabRequestRecoveryJob` 每 10s 扫描 `status='PROCESSING' AND lease_until <= now` 的请求，逐条调用 `GrabRequestStep.reconcile`：

| 崩溃点 | DB 业务事实 | 恢复动作 | 客户端最终看到 |
|---|---|---|---|
| 登记后、Redis 预占前 | 无 grab_record | 请求置 EXPIRED；任务仍 PUBLISHED 时丢弃陈旧 Redis 快照 | 409 REQUEST_EXPIRED（可用新 ID 重试） |
| Redis 预占后、DB 提交前 | 无 grab_record | 同上 | 409 REQUEST_EXPIRED |
| DB 提交后、请求行终结前 | grab_record 存在且 runner 匹配 | 请求置 SUCCEEDED，并补跑超时消息/缓存失效/实时通知 | 200 成功 |
| 请求行终结后、响应丢失 | 终态已持久 | 重放 | 原结果 |

> `reconcile` 只认 `grab_record + requestId + round` 这一条证据：查不到就按 EXPIRED 收敛，
> 绝不因为“没查到记录”猜测成功；查到但 runner 不匹配也不会把成功结果回放给别人。

## 6. 配置

| 键 | 默认值 | 生效进程 | 说明 |
|---|---|---|---|
| `peergrab.grab-request.max-in-flight` | 10 | API | 单实例同时执行的抢单请求数上限，满载 503 |
| `peergrab.grab-request.lease-seconds` | 30 | API | 请求执行租约；恢复线程在到期后接管 |
| `peergrab.grab-request.recovery-enabled` | true | Worker | 是否启用恢复扫描 |
| `peergrab.grab-request.recovery-interval-ms` | 10000 | Worker | 恢复扫描间隔 |
| `peergrab.grab-request.recovery-batch` | 200 | Worker | 单轮扫描上限 |

## 7. 迁移与回滚

- 新库：`docker/init.sql` 已包含 `grab_request`；
- 旧库：停写 → 备份 → 执行 `docker/migrate-grab-request-v2.sql`（幂等建表）→ 启动新版本；
- 灰度：前端可先切 v2，v1 端点保留；观察 `grab_request` 中 PROCESSING 残留与 202 比例；
- 回滚：前端切回 v1 + 停用 v2 端点；`grab_request` 表保留（不影响旧链路），不做反向迁移；
- **禁止 v1/v2 写进程混跑**：两套协议对“同一 requestId”的持久化位置不同，混跑会导致重放口径不一致。

## 8. 已知边界

1. **v2 与 v1 并存**：当前 v2 是增量端点，旧端点仍可写；正式切换后应删除 v1 或加开关，避免长期双路径。
2. **429/503 的语义变化**：本分支把确定失败（含限流）持久化，客户端应换新 requestId 重试；
   若需要“原 ID 重试”，需要额外的可重开状态，当前不做。
3. **Redis 补偿采用“丢弃陈旧快照”**：恢复时不盲目 INCR 名额（避免把已提交名额加回去），
   只清“数据库明确可抢且无人占用”的旧预占；代价是丢失一次 Redis 削峰机会，由 DB CAS 兜底。
4. **进程内 Semaphore**：只约束单实例并发，不是跨实例限流；跨实例仍依赖网关/跑腿维度限流（后续 P2）。
5. **无 repair-task 表**：当前恢复只有“请求收敛 + 丢弃陈旧快照”两个动作，足以保证正确性；
   若后续引入更复杂的跨存储补偿，再评估持久任务表。

## 9. 验证

- 单元测试：`GrabRequestUseCaseTest`（登记/重放/冲突/过期轮次/202 查询）、
  `GrabRequestStepTest`（提交恢复/未提交过期/幂等）；
- 集成测试：`GrabRequestV2IT`（同 ID 单事实、换轮次冲突、失败重放、恢复收敛）；
- 压测：沿用 S1/S2/S4 计划，v2 主要新增“同 requestId 重放零额外业务写入”的断言；
- 故障注入：在 Redis 预占后/DB 提交后强杀 API，观察恢复扫描把请求收敛到 SUCCEEDED 或 EXPIRED。
