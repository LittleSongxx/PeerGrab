# 详情缓存预热

详情读的冷 miss 会额外执行 Redis 查询、重建权竞争、MySQL 回源、JSON 编解码和缓存回填。维护窗口或新版本发布前，可以把即将访问的任务 ID 一次性预热到 Redis，避免第一批用户请求承担这组初始化开销。

## 安全边界

- 预热接口是 `POST /api/internal/cache-prewarm`，只接受已认证的仲裁员身份。
- application 层限制单次最多 1000 个正数 ID，去重后按 250 个一批从 MySQL 读取；不存在的 ID 只计入 `missing`，不会写空值。
- 单 Redis 实例使用 pipeline 批量 `SETEX`，每个任务仍保留独立逻辑过期和物理 TTL；多分片继续沿用原子代发布路径。
- 预热只加速详情展示，资金、抢单和名额裁决不读取缓存。业务写事务原有的 afterCommit 失效、延迟双删和一致性校验不变。
- Redis 故障或批量写失败会返回 `cacheHealthy=false`，调用方应把本轮标为失败；正常详情请求仍回到原有 Cache Aside 路径。

## 基准测试

标准 S3 对照仍然保留真正冷态。需要验证预热效果时，在同一隔离栈运行：

```bash
python3 bench/jmeter/business/run.py s3-read \
  --base-url "$PEERGRAB_BENCH_BASE_URL" \
  --output "bench/runs/s3-prewarm-$(date -u +%Y%m%dT%H%M%SZ)" \
  --threads 50 --iterations 100 --distribution uniform \
  --cache-enabled true --prewarm
```

结果目录中的 `prewarm.json` 只保存请求数、命中数、写入数、缺失数和耗时，不保存 JWT 或详情正文。该轮应与 `cache-enabled=true` 的冷态和热态分开命名，不能把预热后的 P99 当成冷启动 P99。
