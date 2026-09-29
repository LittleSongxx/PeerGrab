# S3 缓存冷启动与 Redis 降级分析（代码修复记录）

## 现象与证据

当前 ECS 隔离栈的 S3 详情读对照均为 5,000/5,000 HTTP 200：

| 配置 | P50/P95/P99 | 回源与命中 |
| --- | --- | --- |
| 缓存关闭 | 95/187/227 ms | MySQL 5,000 次，命中 0 |
| 缓存开启（v2，优化前） | 87/178/425 ms | MySQL 100 次，命中 4,900 |
| 缓存开启（v4，优化后） | 89/169/359 ms | MySQL 110 次，命中 4,890 |
| 缓存批量预热（v5） | 88/174/226 ms | 预热 100 个任务耗时 234ms；MySQL 0 次，命中 5,000 |

缓存开启后数据库回源量下降约 98%；v4 将冷态 P99 从 425ms 降到 359ms，下降约 15.5%，但仍高于无缓存组的 227ms。该结果是固定 50 并发、100 个任务、5,000 次封闭批次，不能解释为稳态容量。v2 汇总 SHA-256 为 `66c6c795663095ef75c54794b18a0c2eec8ccff4fd9d245579ea6f6bb98c615c`；v4 汇总保存在 ECS 私有维护目录，仍需多轮重复验证。

v5 预热轮不是冷启动基准：批量读取 100 个详情并用 Redis pipeline 写入耗时 234ms，随后 5000 次读取全部命中，P99 降至 226ms。它证明预热可以把冷填充成本移出用户请求，但预热本身需要由发布后任务、热门任务或维护窗口触发，不能对全量任务无界预热。

## 代码调用链与根因判断

缓存开启的冷 miss 读取链路为：

1. `StringRedisTemplate` 读取详情 key；
2. 可选 Bloom 判定（判否仍需 MySQL 核实）；
3. 获取重建锁；
4. `JdbcErrandRepository.findById` 回源；
5. JSON 序列化并同步回填 Redis；
6. 释放重建锁。

因此，冷态第一次读取比关闭缓存多出 Redis 读、锁协议、序列化和缓存写。多分片配置还会将一次回填放大为每片 SET 加活动指针切换。缓存指标能确认回源和 Redis 操作耗时，但不能把单轮 P99 的全部差异归因到某一阶段；JIT、Tomcat/Hikari 排队、发压器首批并发和 Redis 连接池竞争仍需同窗时序才能区分。长时混合轮的应用指标曾观测到详情缓存 lookup 最大约 3.5 ms、重建锁最大约 2.2 ms、DB load 最大约 0.6 ms、fill 最大约 0.6 ms；这些热态指标不能反推 S3 冷态 P99。

Redis 暂停时，当前适配器在第一次异常后进入约 2 秒本地冷却；预热过且本地副本未过 1 秒 TTL 的详情可直接降级返回，其他请求回源 MySQL。尚未发现 Redis 故障时反复执行 Bloom 或重建锁的路径。故障请求中首批已经进入 Lettuce 的调用仍需等待 `spring.data.redis.timeout`（当前候选配置 500 ms），所以没有本地副本时，100 RPS 单轮的 P95 仍可能接近该上限；后续请求才会快速降级。v4 同样的 100 RPS Redis 暂停轮为 1800/1800 成功、P95 442ms，原 500ms 轮为 452ms，改善有限但故障正确性保持；该结果仍是单轮，不能写成降级 SLA。

## 已实施的代码优化

### 1. shards=1 冷 miss 的重建锁

`RedisErrandCacheAdapter` 将 Redisson `RLock.tryLock(0, 10s)` 替换为 Redis 原生 `SET key token NX PX 10000`，释放时执行比较 token 后 `DEL` 的 Lua 脚本：

- 获取与释放协议各一个 Redis 命令，减少 Redisson 锁客户端的协议和调度开销；
- UUID token 防止过期旧持有者删除新持有者的锁；
- 10 秒租约仍限制进程异常退出留下的锁；
- 调用方仍然不等待，热点逻辑过期读保持返回旧值，冷 miss 仍由单个持有者回源。

### 2. 多分片回填

分片数大于 1 时使用一个原子 Lua EVAL 同时写入所有新代 key 和 active 指针，替代逐片 SET 再切换指针：

- 回填从 `2 × shards + 1` 个网络往返降为一次；
- active 指针在脚本末尾切换，且整个脚本原子执行，不暴露半写代；
- 单分片路径继续使用直接 key，不增加指针读取。

### 3. Redis 故障时的短时本地降级

适配器现在保留一个有上限、默认 1 秒 TTL 的进程内详情副本。Redis 读异常或处于本地冷却期时，详情用例只在副本仍未逻辑过期的情况下返回它；写路径的 `evict` 先删除本地副本，再向 Redis 发送失效，避免已提交状态继续被本进程复用。副本只用于故障降级，不能替代 Redis 或 MySQL；跨实例更新最多存在配置 TTL 内的展示陈旧窗口，名额裁决和资金状态不读取该副本。新增 `degradedLocalHits` 观测计数；该计数不并入 Redis `cacheHits`，用于把故障期本地命中与 MySQL 回源分开。

## 自动化验证

已通过：

```text
mvn -q -pl peergrab-infrastructure -am -DskipTests compile          PASS
mvn -q -pl peergrab-infrastructure -am \
  -Dtest=RedisErrandCacheAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test                    PASS (7 tests)
mvn -q -pl peergrab-application -am \
  -Dtest=GetErrandDetailUseCaseTest \
  -Dsurefire.failIfNoSpecifiedTests=false test                    PASS (5 tests)
```

测试覆盖部分分片失败不切活动指针、单分片直接 key、JSON 信封解析、原子分片脚本的 key 集合、token 锁的 `SET NX PX` 与比较删除、Redis 故障时短时本地副本和失效清理，以及应用层冷 miss 单飞和降级本地命中。以上是本地编译与单测结果；v4 ECS 已完成一轮 S3 和 Redis 故障复测，仍需多轮重复验证。

## 下一轮服务器复测口径

在全新隔离栈、相同 100 任务和 50 并发条件下各跑至少三轮：缓存关闭、缓存开启冷态、缓存开启热态；记录 P50/P95/P99、首批 10 秒分位数、Redis lookup/lock/DB/fill Timer、Hikari pending、JIT/CPU、回源次数和实际完成率。Redis 故障轮至少记录 10/100 RPS 的传输错误、P95/P99、首批异常请求数、进入冷却后的回源延迟和数据库连接等待，并把 500 ms 超时配置与新配置分开归档。只有同镜像、同链路、同数据和多轮结果才能写优化前后差异。
