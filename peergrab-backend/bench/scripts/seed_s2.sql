-- Deterministic S2 list dataset. Run only through seed_bench.py after preflight.
-- Input session variable @bench_seed_count: 1..100000, default 10000.
-- Reserved IDs: [930000000001, 930000100000]; one-shot on a fresh bench stack.
SET @bench_seed_count = COALESCE(@bench_seed_count, 10000);
SET @bench_seed_base = 930000000000;

INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title,
                    reward_amount, slot_total, slot_taken, status, round, version,
                    created_at, updated_at)
WITH digits AS (
  SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
), seq AS (
  SELECT 1 + a.d + 10*b.d + 100*c.d + 1000*d.d + 10000*e.d AS n
  FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e
)
SELECT @bench_seed_base + n, 1, 1001, NULL, 'DELIVERY', CONCAT('bench_s2_', LPAD(n, 6, '0')),
       1000, 1, 0, 'PUBLISHED', 0, 1,
       TIMESTAMP('2026-09-01 00:00:00') + INTERVAL n SECOND,
       TIMESTAMP('2026-09-01 00:00:00') + INTERVAL n SECOND
FROM seq
JOIN bench_guard AS bg ON bg.guard_key = 'project'
WHERE n <= @bench_seed_count
  AND bg.project_name REGEXP '^peergrab-bench-[a-z0-9][a-z0-9_-]*$';
