# PeerGrab 弱指标优化分析与复测

## 已定位的问题

### 自动结算长尾

原扫描器每次最多取 200 条，调度间隔为 5 秒。历史 1000 单曲线出现约 200、400、600、800、1000 条的阶梯，页间有明显空档，因此原 scan-only P99 为 45.737 秒。每条结算还会独立开启事务，并重复锁共享 ESCROW/COMMISSION 钱包。

### 结算并发

共享钱包组 32.04 TPS、分散钱包组 33.27 TPS，仅提升约 3.8%，说明 runner 钱包不是主要瓶颈。所有任务仍共同争用系统 ESCROW 和 COMMISSION 行，Hikari 扩容无法突破这两个数据库行锁串行点。

### 缓存冷启动

冷 miss 需要 Redis 读取、重建锁、MySQL 回源、JSON 序列化、Redis 回填和解锁。原实现使用 Redisson 锁，分片回填还需要多次 Redis 往返。缓存开启虽然把回源从 5000 次降到 100 次，但一次冷态批次 P99 为 425ms。

## 已实施的优化

- 自动结算单次调度连续处理最多 5 页，并以 30 秒预算限制占用 Worker 调度线程；保留游标、失败重试和幂等跳过。
- 结算 owner 查询合并为单次批量 SQL；钱包账户锁合并为按主键排序的单条 `IN (...) ORDER BY id FOR UPDATE`，保持锁顺序和账务语义不变。
- Redis 详情重建锁改为 `SET NX PX token` 与 Lua compare-delete，减少 Redisson 协议往返。
- 多分片详情回填改为单次原子 Lua EVAL，统一写入分片和 active 指针。
- Redis 故障时增加有界 1 秒进程内详情副本；仅用于详情展示降级，失效时先清本地副本，资金和抢单裁决不读取它；`degradedLocalHits` 单独计数，不混入 Redis 命中率。
- 自动结算页内改为单事务批量过账：一次锁定公共和用户账户，内存聚合余额/版本，再批量更新账户和流水；整批冲突时回滚并逐条幂等 fallback。
- 增加有界详情批量预热接口，MySQL 批量读取后使用 Redis pipeline 写入，避免首批用户请求承担冷填充成本。

## ECS 复测结果

| 场景 | 优化前 | 优化后 | 结论 |
|---|---:|---:|---|
| 自动结算 scan-only P99 | 45.737s | 25.144s | 下降约 45%，主要来自消除页间 5 秒空档 |
| S4 共享钱包 TPS | 32.04 | 32.86 | 提升约 2.6% |
| S4 共享钱包 P99 | 1955ms | 1548ms | 下降约 21%，但系统钱包行锁仍是瓶颈 |
| S3 缓存开启冷态 P99 | 425ms | 359ms | 下降约 15.5%，仍高于无缓存 227ms |
| Redis 暂停 100 RPS P95 | 452ms | 442ms | 小幅下降，1800/1800 成功 |
| 自动结算 MQ+scan P99 | 20.646s | 19.474s | 小幅下降，不能归因纯 MQ |
| 自动结算批量事务 scan-only P99 | 25.144s | 17.308s | 相比单条结算再降约 31%，1000/1000 资金和幂等校验通过 |
| 缓存预热后详情 P99 | 冷态 359ms | 预热后 226ms | 100 个任务预热耗时 234ms，5000 次请求 DB 回源为 0 |

所有优化后场景均通过资金守恒、状态幂等和无重复流水校验。优化后结果仍是单机 ECS 隔离栈单轮或短批次，不能写成生产容量 SLA。

## 尚未贸然实施的结构性改造

彻底消除 S4 串行点需要把单一 ESCROW/COMMISSION 系统钱包改为分片系统钱包或每任务托管账户，并同步改造 `escrow_order` 映射、发布、结算、退款、仲裁、迁移、对账和资金校验。这会改变资金账务拓扑，不能只改锁查询后直接上线。当前版本已先完成批量事务和低风险 SQL/锁路径优化，保留该结构性改造作为下一阶段专项。

## 证据

优化后私有 ECS 证据位于 `maintenance/evidence-optimization/`，文件哈希如下：

```text
v3-autoscan.log                  87f5bc7012b3ac6da2227e6cd2d4f79c2490b721e1086f45d0d8ff96588d1507
v3-autoscan-worker.prom          4dde79235b456d174ad26ff10b618d0072aefb1fb59199dca96b04e4c6c960d3
v3-s4shared-summary.json         ec63ccb8b40d575d7c08be551cb290f560216910dec84db49a3216778d3ab35d
v4-autocombined.log              69a5185c7226e1fb031ac0714562647ff9f1aece07b7feb44885b67cde917b63
v4-redis-fault.log               bb9bbd9e015c76f417cd1dd01fda7939f7d4cc15c4945448454b6ae69b6928d0
v4-s3on-summary.json             a44a80bfaabd1db2cdfd654b5a41b9809d1d2824748ea1a66d8fdf2a6f5f153e
v5-autoscan.log                 7a6f83a618a353a0701deb8c166717476208745dc594db96f4bce0d17fca42d3
v5-s3-prewarm.log               bffc949c87adc7a9469f238622f484f0129fb6917b3ddaecbc793a3859037a51
```
