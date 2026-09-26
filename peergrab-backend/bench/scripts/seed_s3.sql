-- S3 缓存压测造数：100 个 PUBLISHED 任务，固定 id 段 [920000000001, 920000000100]
-- 只能通过 seed_bench.py 运行：入口先验证独立栈身份和空任务表。
-- 一次性插入，遇到 ID 冲突直接失败，不删除已有业务数据。
--
-- S3 是读场景压测，只需要 errand 表数据（详情接口不读资金表）。
-- 注意：SQL 造数绕过了发布用例，布隆过滤器里没有这些 id，
-- 压测前必须调用 POST /api/internal/bloom-rebuild 补登记，
-- 否则详情请求会被防穿透逻辑拦成 404（P5 实测确认的运维约束）。

SET @base = 920000000000;

INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title,
                    reward_amount, slot_total, slot_taken, status, round, version)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100
)
SELECT @base + n, 1, 1001, NULL, 'DELIVERY', CONCAT('bench_s3_', n),
       1000, 1, 0, 'PUBLISHED', 0, 1
FROM seq
JOIN bench_guard AS bg ON bg.guard_key = 'project'
WHERE bg.project_name REGEXP '^peergrab-bench-[a-z0-9][a-z0-9_-]*$';

SELECT CONCAT('已造 100 个 PUBLISHED 任务，id 段 [', @base + 1, ', ', @base + 100, ']') AS seeded;
