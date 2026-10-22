# PeerGrab 压测方法论

> 本文回答“怎样让压测数字可复现、可辩护”，不替代[压测运行手册](../../peergrab-backend/bench/README.md)的建栈/执行步骤，也不替代同批的 [design-benchmark.md](../architecture/design-benchmark.md)（实验设计）。所有数字都来自仓库内报告，本文不新造结果；引用时保留原来的环境、工具、路径与窗口。
>
> 现行 HTTP 发压工具是 **Apache JMeter 5.6.3**；S5 的 Worker 到期事件仍由专用 Java 探针测量；S6 的故障控制与单笔业务验证由 Python 脚本执行。仓库里只登记已落地的 JMeter、Java 客户端与 Python 探针；未落地的工具不写入本文。

## 1. 三条纪律

压测报告的可信度，首先取决于“哪些变量被控制住了”。PeerGrab 的资产围绕三条纪律组织：

### 1.1 隔离：只压可销毁的独立栈

- 只允许全新 `peergrab-bench-*` Compose 项目和独立数据卷；`preflight.py` 核对项目标签、容器/卷身份、回环端口、数据库 `bench_guard` 与 API 健康。
- 正式 ECS 压测必须停掉 `peergrab-prod`，或使用同规格独立目标机；不得对演示库或生产数据发压。
- 每个场景、每个缓存/Worker 模式对照都用**新项目和卷**，不能只换 `run_id`；`run_id` 用于追溯，不提供隔离。
- 同一时刻只运行一个隔离项目；`cleanup.sh` 按项目身份销毁，不按轮次删业务行。

**为什么**：缓存、名额、Bloom、候选队列、MQ 位点都可能跨轮残留；只换 `run_id` 会让下一轮读到上一轮的热度或积压，数字无法复现。

### 1.2 预热：把 JIT、连接池、缓存冷启动排除出正式窗口

- 现行 JMeter S2 每档先预热 20 秒，再进入 60/180 秒正式采样；预热初秒可能低于目标速率，单独披露，不混入正式窗口。
- S3 的“冷态”只指新栈首次读取；同一项目连续三批共享 JVM、连接和缓存热度，**不是三份独立冷启动样本**。报告把首批与后续批次分开列。
- S4 的发布、抢单、确认、取件、送达准备不计入结算批次；结算 TPS 只取数据库持久成功的任务数。
- S1 先跑 30 个不计时任务预热发布/抢单路径，再开始正式尖峰。

**为什么**：JVM 解释执行、类加载、Hikari 懒初始化、Redis 连接建立、缓存冷 miss 都会把前几十秒拉慢；把冷态当稳态，会得到不可复现的低值或高值。

### 1.3 只取稳态：短批次、尖峰与稳态是三种东西

- 只有“目标持续、实际发出接近目标、错误可分类、窗口足够长”的区间，才可称为稳态观察。
- S1 是**一次性尖峰**：2,000 个线程用 `Synchronizing Timer` 释放，但请求实际开始横跨 2.228 秒；`2,000 ÷ 8.869` 不是 TPS。
- S3 是**固定请求数的封闭批次**：5,000 次读的完成速率不是开放到达率下的 QPS。
- S4 是**短批次结算**：200 单 / 32 线程约 5.76 秒，34.72 TPS 只代表这个共享钱包热点批次。
- S5 是**同刻到期**：1,000 条任务在同一截止时刻到期，测的是排空延迟，不是 HTTP 吞吐。

**为什么**：短批次的完成速率会随批次长度、预热程度、并发形状剧烈变化；把它写成“稳定 QPS/TPS”，等于把实验条件抹掉。

### 1.4 为什么同机基线不可外推

同机压测不是“无效”，而是“只能回答同机问题”。PeerGrab 的报告明确记录了这些限制：

- 2026-09-26 的 4 vCPU 基线是在**生产服务同时运行**、压测容器带 CPU/内存配额（app 0.75 CPU、MySQL 0.5 CPU、Worker 0.25 CPU 等）的条件下取得的；它回答“这台机器在当时的配额下能跑多少”，不是“应用本身的上限”。
- 同机发压端会与目标争 CPU、内存、磁盘和网络；客户端 `schedulerMissed`、`capacityRejected` 可能先于服务端饱和出现。
- SSH 转发本身消耗 ECS CPU：4 vCPU 轮次中 650 RPS 时 `sshd` 约 0.8 核，所以极限表改用直连 HTTPS；SSH 隧道轮与直连轮不能混算容量。
- 回环 API 不含 Nginx/TLS；公网 HTTPS 轮含两层 Nginx 与网络往返。两类 P99 不能直接比较。
- 4 vCPU 与 8 vCPU、50 Mbps 与 100 Mbps 是不同机器/带宽；报告只对“请求、路径、持续时间、客户端模型一致”的档位做对照，且明确“单次采样不是扩容倍率”。

**结论**：同机数字可以作为回归基线、瓶颈定位和“这台机器上的保守档位”，但不能外推为公网容量、生产 SLA 或跨机器扩容倍率。

## 2. 场景矩阵 S1–S6

| 场景 | 回答什么问题 | 现行资产 | 指标与分母 | 已知失败轮 / 边界 |
| --- | --- | --- | --- | --- |
| S1 | N 人抢 1 个名额是否零超卖；独立任务固定到达率下能否持续完成有效抢单 | [s1.jmx](../../peergrab-backend/bench/jmeter/business/s1.jmx)、[s1_distinct.jmx](../../peergrab-backend/bench/jmeter/business/s1_distinct.jmx)、[run.py](../../peergrab-backend/bench/jmeter/business/run.py)；受保护的 [run_s1_distinct_fixed.py](../../peergrab-backend/bench/scripts/run_s1_distinct_fixed.py) | 成功数、`slot_taken`、`GRABBED` 行数、拒绝分类；独立任务另看 offered/sent/completed、窗口内完成率、P99、`durableGrabTps` | 2,000 线程请求开始横跨 2.228 秒，P99 8.735 秒；固定到达 50 RPS × 15 秒只有 749/750 在窗口内完成；都不能叫稳态 TPS |
| S2 | 广场游标首屏在固定到达率下的延迟/错误与拐点 | [cursor-first-pooled.jmx](../../peergrab-backend/bench/jmeter/s2/cursor-first-pooled.jmx)、[run_s2_jmeter.py](../../peergrab-backend/bench/jmeter/s2/run_s2_jmeter.py)、[run_s2_jmeter_ecs.py](../../peergrab-backend/bench/jmeter/s2/run_s2_jmeter_ecs.py)、[jtl_summary.py](../../peergrab-backend/bench/jmeter/s2/jtl_summary.py) | 目标 RPS、实际开始数、HTTP 200、非 HTTP 错误、P50/P95/P99、每秒开始数；分母是实际启动的请求 | 600 长档第二轮 1 次连接超时，第三轮未跑；500 三轮零错误但 P99 升至 255 ms；CTT 不是严格开放模型 |
| S3 | 详情缓存开关/冷热/热 Key/读写混合的收益与代价 | [s3_read.jmx](../../peergrab-backend/bench/jmeter/business/s3_read.jmx)、[s3_mixed.jmx](../../peergrab-backend/bench/jmeter/business/s3_mixed.jmx)、[seed_bench.py](../../peergrab-backend/bench/scripts/seed_bench.py)、[prewarm_cache.py](../../peergrab-backend/bench/scripts/prewarm_cache.py) | 冷/热 P99、详情回源 `dbLoads`、命中、MySQL `Com_select` 增量、混合场景发布/读成功数 | 首次混合 JMX 只发出 500 发布 + 450 读，判无效；冷态 P99 可能高于无缓存；无多分片对照 |
| S4 | 并发结算的持久化吞吐与资金正确性 | [s4.jmx](../../peergrab-backend/bench/jmeter/business/s4.jmx)、[run.py](../../peergrab-backend/bench/jmeter/business/run.py)、[verify_fund.sql](../../peergrab-backend/bench/scripts/verify_fund.sql) | **数据库持久化结算数 / 批次耗时**，不是 HTTP 成功计数；另看 P99、行锁等待、L1/L2/L3、重复结算 | 200 单 / 32 线程 34.72 TPS；共享钱包热点；短批次不是长期容量；同单 8 次尝试只有 1 次成功是预期 |
| S5 | 确认超时/自动结算的到期事件，MQ 主通道与扫描兜底各多快 | [S5TimelineProbe.java](../../peergrab-backend/peergrab-bench/src/main/java/com/peergrab/bench/S5TimelineProbe.java)、[run_mq_probe.py](../../peergrab-backend/bench/scripts/run_mq_probe.py)、[summarize_s5.py](../../peergrab-backend/bench/scripts/summarize_s5.py)、[S5_TIMELINE.md](../../peergrab-backend/bench/S5_TIMELINE.md) | 预定到期时刻 → `errand_status_log.created_at` 的 P50/P95/P99；未处理/重复/提前/错误状态；消费者结束快照 | 1,000 单单轮；时间戳是事务内生成，不是提交/可见时刻；MQ 轮与扫描轮是机制对比，不能算单变量加速 |
| S6 | Redis/Broker 单节点暂停时，读、抢单、结算与恢复是否仍正确 | [detail-read.jmx](../../peergrab-backend/bench/jmeter/s6/detail-read.jmx)、[run_s6_fault_smoke.py](../../peergrab-backend/bench/scripts/run_s6_fault_smoke.py)、[run_s6_grab_broker_smoke.py](../../peergrab-backend/bench/scripts/run_s6_grab_broker_smoke.py)、[run_s6_broker_settlement_load.py](../../peergrab-backend/bench/scripts/run_s6_broker_settlement_load.py) | 故障窗口、详情 P95、单笔业务结果、数据库真值、outbox PENDING 峰值/排空、通知端到端 | 默认配置 Broker 暂停时客户端 8 秒超时但数据库已提交；Redis 暂停 P95 约 2 秒；首个 Redis 轮被看门狗判无效；不是主从切换/HA |

### 2.1 S1：抢单

S1 有两个子场景，必须分开：

- **同单尖峰**：`s1.jmx` 为每个线程准备不同跑腿 JWT 和 requestId，`Synchronizing Timer` 释放后抢同一个名额；数据库验收要求 `slot_taken=grabbed_rows=1`、超卖 0。2,000 线程档的结果见 [JMeter 报告](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-jmeter-20260928.md)。
- **独立任务固定到达率**：`s1_distinct.jmx` 或 `run_s1_distinct_fixed.py` 先为每个任务分配独立跑腿和 requestId，再按固定速率各发一次；指标是 `offered/sent/completed`、窗口内完成率、P99 和数据库逐单 `LOCKED`/`GRABBED`。`run_s1_distinct_fixed.py` 使用 Python `urllib`，每次独立连接，不能与 JMeter 复用连接的 P99 直接比较。

S1 的关键不是“QPS 多高”，而是“在给定并发/到达率下，名额与记录是否始终自洽”。业务拒绝 `SLOT_FULL`/`GRAB_CONFLICT` 是预期结果，不计系统错误，但它们的等待时间仍计入客户感知延迟。

### 2.2 S2：广场列表

S2 的现行口径是**真实广场游标首屏**：`GET /api/errands?campusId=1&cursor=&size=20`，10,000 条同校区 `PUBLISHED` 任务，JMeter 128 个可复用连接用户 + Constant Throughput Timer。

- 异机轮经公网 HTTPS → 宿主 Nginx → 隔离前端 Nginx → API；本机轮走 ECS 回环或本机 TLS，**不含公网往返**。
- 每轮先 20 秒预热，再 60/180 秒采样；结果同时记录目标、实际开始、每秒开始数、HTTP/非 HTTP 错误、P50/P95/P99、发压端 CPU/RSS/FD 与 ECS 资源。
- CTT 只能“尝试”目标 RPS；线程被慢请求占满时实际发出率会下降，不能叫严格开放模型。

现行结果：500 目标 RPS × 180 秒 × 3 轮，实际开始 **269,608** 次、全部 HTTP 200，最差轮 P99 **255 ms**；600 目标 RPS 长档第二轮出现 **1 次连接超时**，按门禁停止第三轮。历史 4 vCPU 的 700/750 QPS 与完整公网 450/500 QPS 只作为**同接口、不同机器/路径**的对照，不能拼成一条容量曲线。

### 2.3 S3：缓存

S3 必须把三种状态分开：

- **无缓存**：每次详情都回源 MySQL；5,000 次封闭批次。
- **缓存开启冷态**：新栈首次读取，包含 Redis 读、重建锁、MySQL 回源、序列化、回填。
- **缓存开启热态**：同一项目后续批次，JVM、连接、缓存都已热。

现行 JMeter 轮的 `Com_select` 增量从 **30,460** 降到 **15,526（约 -49.0%）**，但这是 MySQL 实例级计数，包含鉴权和后台查询；详情回源从 5,000 降到 100 再到 0，才是缓存路径的直接证据。冷态 P99 **246 → 479 ms**，说明“缓存开启”不保证首批更快；90% 热 Key 5,000 次命中、P99 105 ms，只证明这个热 Key 场景，不证明多分片扩展。

读写混合的正式轮是 **500 发布 + 4,500 读**（每发布一次读同一 ID 九次），5,000/5,000 成功，P99 1,193 ms，抽样差异 0。P99 同时包含发布与读，不能当纯读 P99；抽样零差异也不代表所有并发时刻强一致。

### 2.4 S4：结算

S4 的分子必须是**数据库中最终 `SETTLED` 的独立任务数**，不是客户端收到的成功响应数：响应超时但数据库已提交时，客户端计数器会少记。

现行 JMeter 轮：200 个不同任务 / 32 线程，**34.72 持久化 TPS**，P50/P95/P99 = 604/983/1,081 ms；201 个任务持久结算、201 个托管释放；同任务 8 次并发尝试只有 1 次成功、7 次业务拒绝。窗口内 `Innodb_row_lock_waits` 增 205、`Innodb_row_lock_time` 增 68,442 ms，包含准备、不同任务结算和重复尝试，不能除以 200 当单请求锁等待。

历史原工具两轮 39.52/36.09 TPS、分散钱包 33.27 TPS、共享钱包 32.04 TPS 只用于同工具、同批次对照。以上均为**旧共享系统户模型**的历史口径；2026-10 已改为每单托管户/佣金户，旧串行点不再代表当前模型。34.72 TPS 不是长期稳定容量。

### 2.5 S5：到期事件

S5 没有 HTTP 替代物。`S5TimelineProbe` 在隔离栈造 `LOCKED` 任务，设定统一未来到期时刻，读取 `errand_status_log.created_at`，计算“预定到期 → 状态日志写入”的 P50/P95/P99、最大值、提前处理数、重复事件数、卡单数。

- MQ 轮关闭扫描，只由定时消息推进；扫描轮关闭 MQ，只由 DB 扫描推进；两轮用不同全新栈。
- 1,000 单结果：MQ P50/P95/P99 = **5.914/9.241/9.512 s**，最大 24.802 s；扫描 **13.610/24.628/25.100 s**，最大 25.207 s；两轮均 1,000/1,000 完成，未处理/重复/提前/错误状态均为 0。
- 时间戳在事务内生成，不是提交时刻；`pending` 是业务待处理量，不是 RocketMQ 堆积；结束时的消费者快照 `Diff=0/Inflight=0` 不代表运行中峰值。

### 2.6 S6：故障

S6 是**单节点暂停/恢复冒烟**，不是容量或 HA 测试：

- Redis 轮：`docker pause` 一个已核验完整 ID 的 Redis 容器；JMeter 在暂停前/中/后发详情读；Python 并行做一笔真实抢单，最后用 SQL 核对 `LOCKED`、`slot_taken=1`、1 条 `GRABBED`、托管 `HELD`、两条托管流水。
- Broker 轮：暂停 Broker，发起一笔真实结算；核对客户端响应、数据库是否已提交、`fund_event_outbox` 是否 `PENDING`、恢复后是否 `SENT`、通知是否齐备。
- 同镜像原工具轮：Redis 暂停期间 250 次详情读全部成功，P95 **2,013.163 ms**；JMeter 复测轮为暂停前/中/后 50/180/50 次，P95 **137/2,109/105 ms**。默认配置 Broker 暂停时客户端 **8 秒超时**，应用侧稍后记录 HTTP 200、耗时 **8.204 s**，数据库已 `SETTLED`。隔离对照仅关闭延迟双删后，同一单笔结算约 **94.856 ms** 返回；该设置未部署生产。

S6 的通过条件是“业务与资金正确 + 恢复后收敛”，不是“故障期间吞吐不降”。客户端超时但数据库已提交，必须单列为“超时但已提交”，不能算失败业务重做。

## 3. 指标口径

### 3.1 目标 / 实际 / 窗口内完成

| 口径 | 含义 | 常见误用 |
| --- | --- | --- |
| 目标 RPS / offered | 计划到达率或计划请求数 | 当成实际发出 |
| 实际开始 / sent | 发压器真正启动的请求数 | 用目标数做分母 |
| 窗口内完成 / `completedWithinWindowRps` | 在采样窗口内完成的请求速率 | 与最终完成数混算 |
| 最终完成 | 包括窗口结束后才返回的请求 | 当成窗口内完成率 |

S2 的 500 RPS 三轮实际开始率是 500.01/498.63/499.18，而不是精确 500；600 长档第二轮实际开始 108,141、HTTP 200 108,140、1 次连接超时。报告必须同时给目标、实际、完成与错误，不能只写目标。

### 3.2 分位数

- **P50/P95/P99 是“对应工具、对应分母”的分位数**：JMeter 是 HTTP 采样耗时；Vegeta 是客户端全请求耗时；S5 是状态日志时间差；S6 是客户端请求耗时。
- 分位数应基于**全部尝试**（含失败）或明确说明“仅成功响应”；不能把传输超时从 P99 中隐去。JMeter 的 P99 不包含定时器等待与同步起跑前的排队。
- 单轮 P99 不足以判断稳定；至少看多轮、每秒曲线、错误数与资源窗口是否完整。600 长档第一轮资源只覆盖 75 个采样点，就不能把该轮 CPU 中位当完整时段值。

### 3.3 错误分母

| 类别 | 例子 | 计数方式 |
| --- | --- | --- |
| 传输错误 | 连接超时、Socket 超时、无 HTTP 响应 | 单独列，不能算 HTTP 5xx |
| HTTP 错误 | 非 2xx | 单独列 |
| 业务错误 | `code != OK`、断言失败 | 单独列 |
| 预期业务拒绝 | `SLOT_FULL`、`GRAB_CONFLICT`、同单重复结算拒绝 | 单独列，不算系统错误 |
| 发压端问题 | `schedulerMissed`、`capacityRejected`、本地漏发 | 单独列，不算服务端失败 |

“全部成功”必须说明分母：是全部计划、全部实际发出，还是全部收到响应的请求。

### 3.4 短批次 TPS 与稳态 QPS 不可混算

- S1 的 `2,000 ÷ 8.869` 是尖峰完成速率参考，不是 TPS；请求开始本身横跨 2.228 秒。
- S3 的 5,000 次批次完成率不是开放到达率 QPS；批次内并发固定、请求数固定。
- S4 的 34.72 TPS 是 200 单短批次；同工具历史 32.04/33.27/36.09/39.52 也只能与同批次对照。
- S5 的“到期后每秒完成数”是事件排空速率，不是 HTTP QPS。

### 3.5 Little's Law 与配比

Little's Law：`并发请求数 N ≈ 吞吐 X × 平均延迟 R`。用途是反推线程/连接池，而不是拿 P99 代入：

- 目标 500 RPS、平均延迟 20 ms → 平均在途约 10；若用 P99 255 ms 代入，会得到 127.5，严重高估。
- 连接池/线程池应大于“平均在途 + 峰值余量”，不是越大越好；HikariCP 官方经验值是 `CPU 核数 × 2 + 有效磁盘数`，但 PeerGrab 的实测显示 20→8、Tomcat 200→64 在 80 RPS 首屏查询中没有可重复收益，**必须按实测曲线复核**。
- `REQUIRES_NEW` 会额外占一个连接：`TransactionPitfallIT` 的注释给出经验式 `池大小 >= (1 + 嵌套层数) × 预期并发`；池=2、并发=4 时可稳定压出连接耗尽。
- Worker 分池：fast 3、maintenance 3、mqSend 2；MQ 同步发送与扫描分池，避免 Broker 故障拖垮兜底。

### 3.6 到达模型

| 模型 | PeerGrab 资产 | 能回答 | 不能回答 |
| --- | --- | --- | --- |
| 固定线程/封闭循环 | JMeter 固定用户池、S3/S4 固定线程批次、`SpikeLoadClient` | 给定并发下的完成速率/延迟 | 固定到达率下的排队与拐点 |
| 固定到达率/开放 | Vegeta、`OpenLoopLoadClient`、`MixedWorkloadClient`、`run_s1_distinct_fixed.py` | 目标速率下的漏发、排队、错误与拐点 | 与复用连接工具的 P99 直接比较 |
| 同步起跑 | JMeter `Synchronizing Timer`、`CountDownLatch` | 瞬时竞争的正确性 | “同一纳秒到达”；线程释放≠请求抵达 |
| 固定请求数批次 | S3 5,000 次、S4 200 单 | 批次完成时间与正确性 | 长期稳态 QPS/TPS |

JMeter 的 Constant Throughput Timer 是“尝试目标速率”，不是严格开放模型；S2 必须同时报告实际开始率。固定到达率客户端也要区分 `offered/sent/completed`，`schedulerMissed` 与 `capacityRejected` 不是服务端错误。

### 3.7 多轮统计与 canonical 汇总

单轮只能说明“这一次”，不能说明“稳定”。同一档位至少 **3 轮**，并在报告里同时给：

- 轮次数 `n`、中位数、min-max；只有一轮必须显式标“单轮，不作稳定结论”。
- 每轮实发/完成 RPS、成功数、HTTP/传输/业务/本地拒绝分类，以及 P50/P95/P99（有则 P999）。
- 预热窗口与正式窗口分开；首轮冷启动（JIT、连接池、缓存）单独解释，不能只取最好一轮。
- 客户端与服务端延迟分开：JMeter 客户端 P99 不能替代服务端 `$request_time`；客户端先饱和时不能判服务端容量。
- 宿主 CPU/iowait/内存、Hikari pending、InnoDB 行锁、慢 SQL 等资源水位；没有资源采样就不能下“CPU/IO 瓶颈”结论。
- 同一档位不混用工具；不同工具的结果只能并列，不能合并或计算“提升”。

本仓库提供两个只读工具（单元测试已纳入 `benchmark-assets` CI）：

```bash
python3 peergrab-backend/bench/scripts/canonicalize.py \
  --input peergrab-backend/bench/runs/<run-dir> --format markdown --details
python3 peergrab-backend/bench/scripts/lint_metrics.py \
  peergrab-backend/bench/reports/peergrab-current/*.md
```

`canonicalize.py` 统一解析 JMeter S2 summary、Vegeta stage summary、S2 knee
manifest/result，输出 n/中位数/min-max、错误分类、资源水位和警告（单轮、P99
轮间离散、目标/实发偏差、缺资源、只有客户端延迟、success>requests 等）。
`lint_metrics.py` 拦截“极限压测/全站容量/SLA/百万级/裸 QPS/缓存提升 X%”等表述，
caveat 行、代码块和多引用示例会跳过。

## 4. 拐点判定与容量结论

### 4.1 判据

出现以下任一情况，且**可重复**，才可判“接近/越过拐点”：

1. 目标上升但实际完成不再上升（先排除发压端漏发/拒载）。
2. 多轮同档 P99 持续上升，或 P99 随负载超线性增长。
3. 传输/HTTP/业务错误开始出现并在重复轮复现。
4. 宿主 CPU 接近饱和、iowait/带宽/连接池成为可见瓶颈，且客户端不是瓶颈。

单轮、单档、单次超时都不足以定拐点；失败轮与有效轮必须分开，失败轮不能拿来算“最大容量”。

### 4.2 已观测到的“档位”而不是“容量”

- 4 vCPU、生产并行、带配额的 Vegeta 固定到达率轮：700 offered QPS × 60 秒两轮 42,000/42,000 HTTP 200；720 QPS 一轮 43,192 成功 + 8 无响应、重跑全成功；750 QPS 44,980 成功 + 20 无响应、P99 1,991.2 ms；800 QPS 短档未通过。报告把实用上限记为约 700 QPS、拐点约 700–750 QPS。
- 同轮完整公网路径（Vegeta）：450 QPS × 180 秒 81,000/81,000 零错误，P99 169.4 ms；500 QPS × 180 秒 89,994/90,000、6 次无响应；525 QPS P99 331.1 ms；550 QPS P99 949.4 ms。报告结论是“450 QPS 是经 3 分钟复核的保守持续档位”，不是容量承诺。
- 8 vCPU、100 Mbps 的 Vegeta 固定到达率轮：650 QPS × 180 秒 117,000/117,000 零错误，是本轮已验证的最高 180 秒零错误档；700/800 QPS 同长档各 2 次无响应；1,500 QPS 档只发出 88,689/90,000，欠发 1,311，不能算“1,500 QPS 零错误容量”。
- JMeter 当前镜像：500 目标 RPS × 180 秒 × 3 轮 269,608 次零错误，但 P99 从 112 升到 245/255 ms；600 长档第二轮 1 次连接超时。可写的结论是“这三个 180 秒窗口在约 499–500 次/秒实发下零请求错误，观察到尾延迟抬升”，不是“600 RPS 稳定”。

### 4.3 容量结论的写法

容量结论至少包含五要素：

1. **环境**：CPU/内存/带宽、单机还是多机、生产是否同机、容器配额。
2. **工具与路径**：JMeter/Vegeta/Java/Python、版本、是否公网 HTTPS、是否含 Nginx/TLS。
3. **数据与窗口**：数据规模、游标/热 Key/钱包分布、预热与采样时长。
4. **分母**：目标/实际/完成、错误分类、P50/P95/P99 的定义。
5. **边界**：单接口、短批次、单节点、无 HA、非 SLA。

推荐句式：

> 在 8 vCPU / 100 Mbps 单 ECS、生产停站的隔离栈上，JMeter 5.6.3 经公网 HTTPS 请求广场游标首屏（10,000 条任务，`size=20`），500 目标 RPS × 180 秒 × 3 轮实际开始 269,608 次、0 请求错误；最差轮 P99 255 ms。该结果只覆盖单一首屏、固定身份和这三个窗口，不代表全站容量或长期 SLA。

不推荐句式：

> “支持 500 QPS”“600 RPS 三轮零错误”“结算 35 TPS”“到期处理 10 秒”。

## 5. 报告模板

每份报告至少包含以下小节；可直接复用 [TEMPLATE.md](../../peergrab-backend/bench/reports/TEMPLATE.md)，但不要省略“失败轮”和“边界声明”。

### 5.1 环境与版本

- Git SHA、镜像 digest（API/Worker/前端/中间件）、JMX/脚本 SHA-256。
- ECS 规格、CPU/内存/带宽、容器配额、Docker/Compose 版本。
- 发压机位置、是否同机、公网/回环/SSH 隧道、是否含 Nginx/TLS。
- Compose 项目、卷、`preflight.py` 结果、生产是否停站。

### 5.2 工具与计划

- JMeter 5.6.3 或具体 Java/Python 入口；计划文件、参数、线程/到达率、`maxInFlight`、超时。
- 是否使用 CTT/Synchronizing Timer；为什么它不是严格开放模型。
- 原始 JTL/JSONL/日志的私有路径与哈希（不提交敏感内容）。

### 5.3 数据规模与分布

- 任务数、状态分布、校区、游标页/热 Key、钱包分布（共享/分散）、用户池。
- 造数脚本与种子；是否空表起跑；是否新卷。
- 结果集大小、索引、缓存冷/热状态、MQ Topic/消费组。

### 5.4 采样窗口

- 预热、采样、冷却时长；UTC 起止；每秒开始数是否平稳。
- 资源采样覆盖了多少个点；缺失窗口不能当中位数结论。

### 5.5 目标 / 实际 / 完成率

- 每档：目标 RPS、实际开始、窗口内完成、最终完成、完成率。
- 拒绝分类、`schedulerMissed`、`capacityRejected`、本地漏发。

### 5.6 延迟与错误

- P50/P95/P99/Max，注明分母（全部尝试/成功响应）。
- 传输/HTTP/业务错误分开；预期业务拒绝单列。
- 失败轮单独列表，保留原因与是否重跑；不能只留成功轮。

### 5.7 资源水位

- 客户端 CPU/RSS/FD；ECS 宿主 CPU/iowait/网络；容器 CPU/内存。
- MySQL 活跃连接、行锁等待、`Com_select`；Redis OPS/命中/内存；MQ 消费进度/积压。
- 明确“发压端是否先成为瓶颈”。

### 5.8 SQL 校验

- 抢单：名额、`GRABBED` 行数、同用户重复、Redis/DB 一致性。
- 资金：L1 借贷平衡、L2 快照、L3 托管闭环、结算幂等、金额守恒。
- 到期：未处理/重复/提前/错误状态；outbox PENDING/SENT；通知条数。
- 保存 SQL 输出与行数/哈希，不只写“校验通过”。

### 5.9 边界声明

- 单接口/单机/单节点/短批次/无 HA/非 SLA。
- 哪些数字不能互相比较（跨工具、跨路径、跨机器、跨窗口）。
- 哪些失败轮被排除，为什么。

## 6. 简历/评审口径

每个数字必须能回答五个问题：**什么环境、什么工具、什么路径、什么窗口、错误分母是什么**。失败轮与有效轮分开写。

### 6.1 可以这样写

- “8 vCPU / 100 Mbps 单 ECS，JMeter 5.6.3 经公网 HTTPS 请求广场游标首屏，500 目标 RPS × 180 秒 × 3 轮实际开始 269,608 次、0 请求错误，最差轮 P99 255 ms。”
- “同镜像 JMeter S4，200 个不同任务 / 32 线程短批次，34.72 持久化 TPS，P99 1,081 ms；共享钱包热点，非长期稳态。”
- “1,000 单同刻到期，MQ 路径 P99 9.512 秒、扫描兜底 P99 25.100 秒，均 1,000/1,000 排空，重复/提前/错误状态为 0。”
- “2,000 线程同单抢 1 名额，恰好 1 人成功、0 超卖；P99 8.735 秒，请求开始横跨 2.228 秒。”

### 6.2 不要这样写

- “极限压测 / 全站容量 / SLA”：当前是单机隔离栈的有限窗口，不是生产容量或长期 SLA；这类词没有可核对的边界。
- “支持 500 QPS / 600 QPS 零错误”：500 是目标与实际约 499–500，600 长档有 1 次连接超时且没有第三轮。
- “结算 35 TPS”：这是 200 单短批次、共享钱包热点，不是稳定容量。
- “2,000 并发 TPS 225”：`2,000 ÷ 8.869` 不是 TPS。
- “缓存提升 49% 吞吐”：49.0% 是 `Com_select` 降幅，不是吞吐；冷态 P99 反而更高。
- “S5 到期 P99 10 秒”：MQ 与扫描是两种机制，且时间戳不是提交时刻。
- “JMeter 比 Vegeta 快/慢多少”：工具、连接模型、路径与窗口不同，不能算跨工具增益。

### 6.3 失败轮怎么写

失败轮不是“删掉”，而是“保留并解释”：

> 600 目标 RPS 长档第二轮出现 1 次 `ConnectTimeoutException`（连接耗时约 5,004 ms），按门禁停止第三轮；因此不能写“600 RPS 三轮零错误”。500 RPS 三轮零错误，但实发略低于目标且 P99 上升，仍不构成长期容量结论。

## 7. 证据索引

- 当前镜像 JMeter：[report-ecs-jmeter-20260928.md](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-jmeter-20260928.md)
- 同镜像原工具六场景：[report-ecs-spectrum-20260928.md](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-spectrum-20260928.md)
- 4 vCPU 维护窗口：[report-ecs-max-20260926.md](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-max-20260926.md)
- 8 vCPU 复测：[report-ecs-8cpu-20260927.md](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-8cpu-20260927.md)
- 上线后复测：[report-ecs-release-20260927.md](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-release-20260927.md)
- 优化与补测：[performance-optimization-analysis-20260929.md](../../peergrab-backend/bench/reports/peergrab-current/performance-optimization-analysis-20260929.md)、[report-ecs-remediation-20260929.md](../../peergrab-backend/bench/reports/peergrab-current/report-ecs-remediation-20260929.md)
- 按版本索引：[peergrab-current/README.md](../../peergrab-backend/bench/reports/peergrab-current/README.md)
- 资产与保留规则：[ASSET_CATALOG.md](../../peergrab-backend/bench/ASSET_CATALOG.md)、[private-evidence-index.json](../../peergrab-backend/bench/reports/private-evidence-index.json)
- 运行手册：[bench/README.md](../../peergrab-backend/bench/README.md)；JMeter 入口：[jmeter/README.md](../../peergrab-backend/bench/jmeter/README.md)
- 历史工具复现：[LEGACY_RUNBOOK.md](../../peergrab-backend/bench/LEGACY_RUNBOOK.md)

私有 JTL、JWT CSV、生产备份不在 Git 中；公开报告只写脱敏汇总与哈希。引用证据时优先链接报告，不链接私有目录中的具体文件。

## 8. 执行前检查清单

- [ ] 正式报告同一档位至少 3 轮；`canonicalize.py` 已运行并贴出 n/中位数/min-max 与警告。
- [ ] 报告已跑 `lint_metrics.py`，没有“极限/全站容量/SLA/裸 QPS/缓存提升 X%”等无口径表述。
- [ ] 新 `peergrab-bench-*` 项目、新卷、独立端口；生产已停或使用独立目标机。
- [ ] `preflight.py` 通过；数据库 `bench_guard`、容器/卷标签、回环端口均匹配。
- [ ] 业务表与 `bench_run` 为空（S2/S3/S5/S6 的入口有明确要求）。
- [ ] 造数达到报告口径（S2 至少 10,000 条；混合负载至少 2,500 条）。
- [ ] 预热时长、采样时长、超时阈值、线程/到达率、`maxInFlight` 已记录。
- [ ] JMX/脚本 SHA-256、镜像 digest、Git SHA 已记录。
- [ ] 资源采样与 JTL/JSONL 输出目录已创建，权限 0700/0600。
- [ ] SQL 校验脚本准备好；失败轮也要保存证据。
- [ ] 清理脚本只销毁本轮项目；不删生产备份。
- [ ] 报告里单轮结果显式标“单轮”；首轮冷启动、客户端先饱和、缺资源采样时不得写成稳定容量。

## 9. 待补测的 canonical 矩阵（本地已准备，执行仍需要隔离栈）

| 补测 | 目标配置 | 轮次/窗口 | 验收 | 当前状态 |
| --- | --- | --- | --- | --- |
| S2 容量曲线 | 8 vCPU/100 Mbps、独立发压机、650/900/1200/1350 offered | 3 轮×180 s，预热 30 s | 最高“全部轮次零请求错误且 P99<500 ms”的档位；每档给 n/中位/min-max/P999/CPU | 只有单轮阶梯 |
| S4 结算 canonical | 8 vCPU、32 线程、200 个独立任务、共享钱包 | 3 轮，每轮同配置 | 中位 TPS + P99 + InnoDB row lock waits/order；轮间偏差 ≤10% 才可引用 | 15–57 TPS 多口径并存 |
| S3 缓存高负载 | 300–500 RPS 详情读，开/关缓存 | 3 轮×300 s | 命中率、回源 QPS、Com_select、P99/P999；冷/热分开 | 只有 5,000 次固定批次 |
| S6 故障长时 | Redis 暂停 60 s@100 RPS；Broker 暂停 120 s + 持续结算 | 各 3 轮 | 错误率、P95/P99、outbox 积压/排空时间 | 单轮、低负载 |
| 30–60 min 长稳 | 450–500 RPS 混合（5–10% 写） | 1 轮≥30 min | P99 漂移、GC、内存、连接池、资金差 0 | 只有 100 RPS×30 min |

当前 `run_s2_knee.py` 的 `--rates` 白名单是 50/100/200/400/800，且一次只跑一轮；650/900/1200/1350 的公网容量曲线用 `run_remote_s2_vegeta.py`（支持 1–5000 的递增档位），同一配置执行三次（每次独立 run 目录），再用 `canonicalize.py` 按档位合并出 n/中位/min-max。
