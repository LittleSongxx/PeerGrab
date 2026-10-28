# PeerGrab 配置参考

本表覆盖两处 `application.yaml`：`peergrab-bootstrap`（API 进程）与 `peergrab-worker`，以及代码内 `@Value`/`@ConditionalOnProperty` 的默认值。键名与默认值均从仓库实际读取；未列出的前缀不属于 `peergrab.*`，见文末"非 peergrab 前缀"。

## 单一事实来源

- `peergrab-bootstrap/src/main/resources/application.yaml`（API 进程）与 `peergrab-worker/src/main/resources/application.yaml`（worker 进程）是两个独立事实源。**同名共享键必须取值一致**；只改一处会让手动/自动结算、发送端/消费端出现口径漂移。
- **共享键**：跨进程有语义的键（`settle.*`、`mq.*`、`timeout.confirm-seconds`、`timeout.max-transfer-rounds`、`credit.*`、`cache.shards`、`cache.bloom-enabled` 等）新增或改值时**必须两处同步**。两处 yaml 都刻意保留了对侧专用键（API 保留 `settle.scan-*`、`retention.*`，worker 保留 `auth.*`、`grab.*`），这是“对称保留”，不代表该进程读取它们。
- **门禁**：共享键一致性由 `peergrab-bootstrap/src/test/java/com/peergrab/config/SharedConfigConsistencyTest.java` 守。它解析两份 `application.yaml`，对**交集键**做值相等断言（`PROCESS_SPECIFIC_KEYS` 是白名单，只放真实的进程差异：`worker-id`、`application.name`、`server.port`、`ws.enabled`、readiness 组成、连接池大小、连接串），并断言 `MUST_SHARE_KEYS` 中的键在两侧都存在。新增“允许不同”的键必须显式加白并写明原因，不能靠改测试悄悄放过。注意：`credit.min-score`/`credit.max-ongoing` 目前不在 `MUST_SHARE_KEYS` 里（worker yaml 未显式列出），属门禁尚未覆盖的隐式共享键。
- **判定标准**：同一键在两处取值不同、且不属于“进程专属键”，即视为缺陷，而不是“以某一侧为准”。

## 约定

- **默认值**：代码内建默认值；yaml 显式设置时以 yaml 为准（表中与 yaml 一致的直接写值，环境变量占位符除外）。
- **生效进程**：
  - `API`：键由 `peergrab-presentation` 读取，只有 API 进程装配。
  - `worker`：键由 `peergrab-worker` 读取，只有 worker 进程装配。
  - `两者`：键由 `peergrab-application`/`peergrab-infrastructure` 读取，两进程都会装配；业务触发可能只在一侧（例如 `cache.check-sample-size` 由 worker 的校验任务调用）。
- **yaml 对称保留**：API 的 yaml 也保留 `credit.calibration.*`、`settle.scan-*`、`cache.bloom-refresh-interval-ms` 等 worker 专用键，但 API 进程不读取；改行为只对 worker 生效。
- **环境变量**按 Spring 宽松绑定映射，例如 `PEERGRAB_AUTH_MODE` 对应 `peergrab.auth.mode`。
- **不在 Spring 配置里**的运行时项：容器时区、Redis `allkeys-lru`、compose 端口与卷，见[单机部署说明](../../peergrab-backend/docker/ECS_DEPLOY.md)。

## 共享键矩阵

### 必须一致（同一键、同一值）

| 键组 | 键 | 为什么必须一致 | 两处应相同的值 |
| --- | --- | --- | --- |
| 结算口径 | `peergrab.settle.commission-rate`、`peergrab.settle.auto-settle-seconds` | 手动结算与自动结算同一分账口径；自动结算扫描与 deadline 修复按同一期限回填 | `0.05`、`86400` |
| 消息 Topic | `peergrab.mq.topic.confirm-timeout`、`.auto-settle`、`.cache-evict`、`.fund-event` | 发送端与消费端必须同源，否则“发到 A、订阅 B”，主通道失效只剩扫描 | `errand-confirm-timeout`、`errand-auto-settle`、`errand-cache-evict`、`errand-fund-event-v2` |
| 消息开关与地址 | `peergrab.mq.enabled`、`peergrab.mq.endpoints` | API 发、worker 收；一端关闭即退化为纯扫描 | `true`、`127.0.0.1:8081` |
| 超时规则 | `peergrab.timeout.confirm-seconds`、`peergrab.timeout.max-transfer-rounds` | API 写 `confirm_deadline_at`、worker 判到期，必须同一期限；递补上限不同会让“第 N 轮”语义分裂 | `300`、`5` |
| 资格规则 | `peergrab.credit.min-score`、`peergrab.credit.max-ongoing` | 抢单与递补都按同一门槛复核；worker yaml 未显式列出，依赖代码默认值，属**隐式共享键** | `40`、`5` |
| 信用校准 | `peergrab.credit.calibration-cron`、`.calibration-batch-size` | 只在 worker 生效，但 API yaml 也保留；仍按“一致”管理，避免误读为两侧都跑 | `0 0 3 * * ?`、`1000` |
| 缓存结构 | `peergrab.cache.shards`、`peergrab.cache.bloom-enabled`、`peergrab.cache.bloom-refresh-interval-ms` | 缓存键结构与 Bloom 就绪语义必须两进程一致，否则一侧写、另一侧读不到或误判 | `1`、`true`、`1800000` |

### 允许不同（进程专属）

| 键 | API | worker | 原因 |
| --- | --- | --- | --- |
| `peergrab.worker-id` | `1` | `2` | 雪花节点号必须不同，否则可能产生重复 ID |
| `peergrab.ws.enabled` | `true` | `false` | 只有 API 持有 WebSocket；worker 关闭后装配 `RedisFanoutRealtimeNotifier` |
| `peergrab.scheduler.*-pool-size` | 未配置 | `fast=3`、`maintenance=3`、`mq-send=2` | 只有 worker 有 Spring 调度池 |
| `peergrab.timeout.scan-*`、`settle.scan-*`、`retention.*`、`message.*`、`fund-outbox.*`、`deadline-repair.*` | 部分保留但不读取 | 读取 | worker 专属后台任务 |
| `peergrab.auth.*`（除 `arbitrator-id`、`jwt-secret`、`session-ttl-minutes`、`refresh-ttl-minutes`、`resolve-cache-ms`） | 读取 | 不读 | 登录、demo 口令、登录限速只在 API |
| `peergrab.grab.limit.*`、`peergrab.web.cors-origins` | 读取 | 不读 | 抢单入口与 CORS 只在 API |
| `server.port` | `8080` | `8082` | 两进程同机部署，端口必须分开 |
| `spring.datasource.hikari.maximum-pool-size` | `20` | `10` | 在线请求与后台批量的并发模型不同 |
| readiness 组成 | `db,redis,snowflakeLease` | 追加 `workerScheduler` | worker 的就绪还取决于调度线程池是否可用 |

## 身份与鉴权（auth.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.auth.mode` | `session`（未配置按 session） | 鉴权实现：`session`=Redis 会话（本机演示），`jwt`=MySQL 真值 JWT（生产 compose 显式设为 jwt） | 两者 |
| `peergrab.auth.allow-header-identity` | `false` | 是否信任 `X-User-Id` 请求头身份；生产必须 false | API |
| `peergrab.auth.jwt-secret` | 空 | JWT 签名密钥；jwt 模式要求至少 32 字节 | 两者 |
| `peergrab.auth.session-ttl-minutes` | `120` | access token / 会话有效期 | 两者 |
| `peergrab.auth.refresh-ttl-minutes` | `10080` | refresh token 有效期（7 天） | 两者 |
| `peergrab.auth.resolve-cache-ms` | `1000` | 每请求会话校验的本地正结果缓存；0 关闭；跨实例登出的最坏延迟 | 两者 |
| `peergrab.auth.arbitrator-id` | `9001` | 仲裁员身份 ID；该身份被禁止发布与抢单 | 两者 |
| `peergrab.auth.login.enabled` | `true` | 登录失败限速开关 | API |
| `peergrab.auth.login.max-attempts` | `5` | 窗口内最大失败次数 | API |
| `peergrab.auth.login.window-seconds` | `60` | 失败计数窗口（秒） | API |
| `peergrab.auth.login.lock-seconds` | `300` | 超限后的锁定时间（秒） | API |
| `peergrab.auth.demo.enabled` | `false` | 演示账号开关；生产必须关闭 | API |
| `peergrab.auth.demo.password-1001` | 空 | 演示口令（发单人 1001） | API |
| `peergrab.auth.demo.password-2001` | 空 | 演示口令（跑腿者 2001） | API |
| `peergrab.auth.demo.password-2002` | 空 | 演示口令（跑腿者 2002） | API |
| `peergrab.auth.demo.password-9001` | 空 | 演示口令（仲裁员 9001，仅演示） | API |
| `peergrab.auth.cleanup-interval-ms` | `3600000` | 过期会话清理周期 | worker |
| `peergrab.auth.cleanup-initial-delay-ms` | `60000` | 清理任务首次延迟 | worker |
| `peergrab.auth.cleanup-batch-size` | `500` | 单批删除行数 | worker |
| `peergrab.auth.cleanup-max-batches` | `20` | 单轮最多批次 | worker |

## 实时连接（ws.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.ws.enabled` | API `true`、worker `false` | WebSocket 装配开关；worker 关闭时装配 `RedisFanoutRealtimeNotifier` | 两者 |
| `peergrab.ws.auth-recheck-ms` | `60000` | 长连接鉴权复核周期 | API |
| `peergrab.ws.max-sessions-per-user` | `8` | 单用户连接上限（多端与重连余量） | API |
| `peergrab.ws.max-messages-per-second` | `5` | 单连接消息频率上限 | API |

## 调度线程池（scheduler.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.scheduler.fast-pool-size` | `3` | 超时与自动结算扫描线程池 | worker |
| `peergrab.scheduler.maintenance-pool-size` | `3` | 对账、信用与缓存投影重建、保留清理 | worker |
| `peergrab.scheduler.mq-send-pool-size` | `2` | MQ 同步发送独立线程池，Broker 故障不占扫描线程 | worker |

## 消息（mq.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.mq.enabled` | `true` | MQ 总开关；false 时装配 Noop 适配器并退化为纯扫描 | 两者 |
| `peergrab.mq.endpoints` | `127.0.0.1:8081` | RocketMQ 5.x gRPC proxy 地址 | 两者 |
| `peergrab.mq.topic.confirm-timeout` | `errand-confirm-timeout` | 确认超时延迟消息 Topic（发送与消费同源） | 两者 |
| `peergrab.mq.topic.auto-settle` | `errand-auto-settle` | 自动结算延迟消息 Topic | 两者 |
| `peergrab.mq.topic.cache-evict` | `errand-cache-evict` | 缓存失效与延迟双删 Topic | 两者 |
| `peergrab.mq.topic.fund-event` | `errand-fund-event-v2` | 资金事件 outbox 发送 Topic | 两者 |
| `peergrab.mq.group.confirm-timeout` | `peergrab-timeout-consumer` | 确认超时消费组 | worker |
| `peergrab.mq.group.auto-settle` | `peergrab-autosettle-consumer` | 自动结算消费组 | worker |
| `peergrab.mq.group.cache-evict` | `peergrab-cache-evict-consumer` | 缓存失效消费组 | worker |
| `peergrab.mq.group.fund-event` | `peergrab-fund-event-consumer` | 资金事件消费组（原 push 组已退役） | worker |
| `peergrab.mq.consumer-reconnect-ms` | `10000` | 消费端重连间隔 | worker |
| `peergrab.mq.failure-cooldown-ms` | `5000` | 发送失败后 `available()` 的冷却窗口 | 两者 |

## 补偿任务预算（message.* / fund-outbox.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.message.retry-interval-ms` | `10000` | 本地消息重发周期 | worker |
| `peergrab.message.retry-max-run-ms` | `10000` | 单轮重发时间预算 | worker |
| `peergrab.fund-outbox.retry-interval-ms` | `2000` | 资金事件 outbox 投递周期 | worker |
| `peergrab.fund-outbox.max-run-ms` | `10000` | 单轮投递时间预算 | worker |

## 超时与递补（timeout.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.timeout.confirm-seconds` | `300` | 抢中后确认期限 | 两者 |
| `peergrab.timeout.max-transfer-rounds` | `5` | 连续递补上限（`errand.transfer_count`），达到后回退重开 | 两者 |
| `peergrab.timeout.dispatch-sender-threads` | `4` | 延迟消息后台发送线程数 | 两者 |
| `peergrab.timeout.dispatch-queue-capacity` | `2048` | 后台发送队列容量 | 两者 |
| `peergrab.timeout.scan-interval-ms` | `5000` | worker 超时扫描周期 | worker |
| `peergrab.timeout.scan-grace-seconds` | `2` | 扫描宽限期，让 MQ 主通道先处理 | worker |
| `peergrab.timeout.scan-max-batches` | `5` | 单轮最多页数 | worker |
| `peergrab.timeout.scan-max-run-ms` | `10000` | 单轮扫描时间预算 | worker |
| `peergrab.timeout.scan.enabled` | `true` | 扫描兜底开关 | worker |

## 结算（settle.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.settle.commission-rate` | `0.05` | 平台佣金比例 | 两者 |
| `peergrab.settle.auto-settle-seconds` | `86400` | 送达后自动结算期限 | 两者 |
| `peergrab.settle.scan-interval-ms` | `5000` | 自动结算扫描周期 | worker |
| `peergrab.settle.scan-batch-size` | `100` | 单页结算上限；越大公共账户锁持有越久 | worker |
| `peergrab.settle.scan-max-batches` | `5` | 单轮最多页数 | worker |
| `peergrab.settle.scan-max-run-ms` | `30000` | 单轮时间预算 | worker |

## 抢单与信用（grab.* / credit.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.grab.limit.type` | `sentinel` | 限流实现选择（`sentinel` 或 `local`） | 两者 |
| `peergrab.grab.limit.enabled` | `true` | 抢单 L1 热点限流开关 | 两者 |
| `peergrab.grab.limit.per-second` | `500` | 单实例单任务每秒放行数 | 两者 |
| `peergrab.grab.limit.resource` | `peer_grab_grab` | Sentinel 资源名 | 两者 |
| `peergrab.credit.min-score` | `40` | 抢单最低信用分 | 两者 |
| `peergrab.credit.max-ongoing` | `5` | 单人在途任务上限 | 两者 |
| `peergrab.credit.calibration-cron` | `0 0 3 * * ?` | 信用校准 cron（按容器 TZ 解释） | worker |
| `peergrab.credit.calibration-batch-size` | `1000` | 单批用户数 | worker |
| `peergrab.credit.ranking-rebuild-interval-ms` | `60000` | 信用榜单重建周期 | worker |

## 抢单请求幂等（grab-request.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.grab-request.max-in-flight` | `10` | 单实例同时执行的抢单请求上限，满载 503 | API |
| `peergrab.grab-request.lease-seconds` | `30` | 请求执行租约，过期后由恢复扫描接管 | API |
| `peergrab.grab-request.recovery-enabled` | `true` | 是否启用请求恢复扫描 | worker |
| `peergrab.grab-request.recovery-interval-ms` | `10000` | 恢复扫描周期 | worker |
| `peergrab.grab-request.recovery-batch` | `200` | 单轮扫描上限 | worker |

## 候选队列（candidate.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.candidate.ttl-seconds` | `86400` | Redis 候选队列 TTL | 两者 |
| `peergrab.candidate.lease-seconds` | `30` | 候选领取租约 | 两者 |

## 缓存（cache.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.cache.enabled` | `true` | 详情缓存总开关；false 时装配 Noop 缓存 | 两者 |
| `peergrab.cache.shards` | `1` | 缓存分片数（当前单 Redis 实例） | 两者 |
| `peergrab.cache.ttl-seconds` | `600` | 逻辑过期与物理 TTL | 两者 |
| `peergrab.cache.jitter-seconds` | `120` | TTL 随机抖动幅度 | 两者 |
| `peergrab.cache.empty-ttl-seconds` | `60` | 空值缓存 TTL | 两者 |
| `peergrab.cache.double-delete-enabled` | `true` | 延迟双删开关 | 两者 |
| `peergrab.cache.double-delete-ms` | `500` | 双删间隔 | 两者 |
| `peergrab.cache.double-delete-sender-threads` | `2` | 双删发送线程数 | 两者 |
| `peergrab.cache.double-delete-queue-capacity` | `2048` | 双删发送队列容量 | 两者 |
| `peergrab.cache.bloom-enabled` | `true` | Bloom 判否短路开关（未就绪 fail-open） | 两者 |
| `peergrab.cache.bloom-audit-denominator` | `100` | 判否抽样审计分母（1/100 仍回源核实） | 两者 |
| `peergrab.cache.bloom-rebuild-interval-ms` | `300000` | 未就绪时的重建周期 | worker |
| `peergrab.cache.bloom-refresh-interval-ms` | `1800000` | 就绪后全量校准周期（30 分钟） | worker |
| `peergrab.cache.redisson-pool-size` | `16` | Redisson 连接池大小 | 两者 |
| `peergrab.cache.local-fallback-ttl-millis` | `1000` | Redis 故障时本地降级缓存 TTL | 两者 |
| `peergrab.cache.local-fallback-max-entries` | `10000` | 本地降级缓存条目上限 | 两者 |
| `peergrab.cache.check-interval-ms` | `300000` | 缓存一致性抽样校验周期 | worker |
| `peergrab.cache.check-sample-size` | `100` | 单次抽样条数（含负值缓存核对） | 两者 |

## 数据保留（retention.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.deadline-repair.initial-delay-ms` | `30000` | 修复任务首次执行延迟 | worker |
| `peergrab.deadline-repair.interval-ms` | `300000` | NULL 截止时间数据修复周期 | worker |
| `peergrab.retention.enabled` | `true` | 保留期清理开关 | worker |
| `peergrab.retention.cron` | `0 30 4 * * ?` | 清理 cron（按容器 TZ 解释） | worker |
| `peergrab.retention.batch-size` | `500` | 单批删除行数 | worker |
| `peergrab.retention.max-batches` | `20` | 单表单轮最多批次 | worker |
| `peergrab.retention.local-message-days` | `7` | 已发送本地消息保留天数 | worker |
| `peergrab.retention.fund-outbox-days` | `30` | 已发送资金事件保留天数 | worker |
| `peergrab.retention.publish-request-days` | `30` | 发布幂等登记保留天数（不得短于客户端离线重试窗口） | worker |
| `peergrab.retention.notification-days` | `180` | 已读通知保留天数 | worker |
| `peergrab.retention.sync-diff-days` | `90` | 缓存一致性差异保留天数 | worker |

## 进程、租约与对账（worker-id / worker-lease.* / recon.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.worker-id` | API `1`、worker `2` | 雪花节点号，两进程必须不同 | 两者 |
| `peergrab.worker-lease.seconds` | `30` | 发号节点租约时长 | 两者 |
| `peergrab.worker-lease.renew-interval-ms` | `5000` | 续租周期 | 两者 |
| `peergrab.worker-lease.close-on-loss` | `true` | 确认租约丢失时是否关闭进程（DB 瞬时失败不会触发） | 两者 |
| `peergrab.recon.cron` | `0 0 2 * * ?` | 资金对账 cron | worker |

## Web（web.*）

| 键 | 默认值 | 作用 | 生效进程 |
| --- | --- | --- | --- |
| `peergrab.web.cors-origins` | `http://localhost:5173,http://127.0.0.1:5173` | CORS 白名单 | API |

## 非 peergrab 前缀（同一 yaml 内的进程级配置）

| 键 | 默认值 | 作用 |
| --- | --- | --- |
| `server.port` | API `8080`、worker `8082` | HTTP 与 management 同端口 |
| `spring.datasource.hikari.maximum-pool-size` | API `20`、worker `10` | MySQL 连接池 |
| `spring.data.redis.*` | `127.0.0.1:6380`、超时 `500ms` | Redis 连接与读超时 |
| `management.endpoints.web.exposure.include` | `health,prometheus` | 暴露健康与指标端点 |
| `logging.level.com.peergrab` | `INFO` | 业务日志级别 |

> 本表键数：`peergrab.*` 共 104 个。口径：98 个由 `@Value` 读取 + 6 个只由 `@ConditionalOnProperty` 判断的开关/选择型键（`auth.mode`、`cache.enabled`、`mq.enabled`、`ws.enabled`、`timeout.scan.enabled`、`grab.limit.type`）。交叉核对：两处 yaml 的 `peergrab.*` 叶子键共 84 个（其中 4 个属于上述开关），代码内有默认值但 yaml 未出现的 `@Value` 键 18 个，yaml 未出现的开关 2 个。原文记 103 个，差在漏计 `peergrab.deadline-repair.initial-delay-ms`（worker，默认 `30000`），本次已补入“数据保留”表。如与代码不一致，以 `application.yaml` 与 `@Value` 为准。
