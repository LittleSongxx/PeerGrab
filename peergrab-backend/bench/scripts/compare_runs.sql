-- Compare distinct PASS runs only when their workloads and recorded parameters match.
-- Replace __RUN_A__ and __RUN_B__ with run IDs from the disposable bench database.
-- S1's old "throughput" is a burst duration quotient, not steady QPS; omit it here.
-- This SQL cannot prove that images, seed snapshots or hardware match; verify those
-- from each run's resource capture and report before interpreting any delta.
SET @a = '__RUN_A__';
SET @b = '__RUN_B__';

SELECT run_id, scenario, status, concurrency, started_at, finished_at, env_note, summary
  FROM bench_run WHERE run_id IN (@a, @b) ORDER BY started_at;

WITH normalized AS (
  SELECT run_id, scenario, status, concurrency, summary,
         CASE
           WHEN scenario = 'S2' THEN 'completed QPS'
           WHEN scenario = 'S2-OPEN' THEN 'completed in-window RPS'
           WHEN scenario IN ('S3-a', 'S3-b', 'S3-c', 'S3-d') THEN 'completed RPS'
           WHEN scenario = 'S4-HTTP' THEN 'distinct durable settlements/s'
           ELSE NULL
         END AS rate_unit,
         CASE
           WHEN scenario = 'S2' THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(summary, '$.completedQps')) AS DECIMAL(14,3))
           WHEN scenario = 'S2-OPEN' THEN
             CAST(JSON_UNQUOTE(JSON_EXTRACT(summary, '$.completedWithinWindow')) AS DECIMAL(14,3)) /
             NULLIF(CAST(JSON_UNQUOTE(JSON_EXTRACT(summary, '$.sampleSeconds')) AS DECIMAL(14,3)), 0)
           WHEN scenario IN ('S3-a', 'S3-b', 'S3-c', 'S3-d') THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(summary, '$.rps')) AS DECIMAL(14,3))
           WHEN scenario = 'S4-HTTP' THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(summary, '$.distinct.durableSettledTps')) AS DECIMAL(14,3))
           ELSE NULL
         END AS rate_value,
         CASE
           WHEN scenario = 'S4-HTTP' THEN
             CAST(JSON_UNQUOTE(JSON_EXTRACT(summary, '$.distinct.allAttemptP99Us')) AS DECIMAL(14,3)) / 1000
           ELSE CAST(JSON_UNQUOTE(JSON_EXTRACT(summary, '$.p99Ms')) AS DECIMAL(14,3))
         END AS p99_ms
    FROM bench_run WHERE run_id IN (@a, @b)
), pair AS (
  SELECT a.run_id AS run_a, b.run_id AS run_b,
         a.scenario AS scenario_a, b.scenario AS scenario_b,
         a.rate_unit AS rate_unit_a, b.rate_unit AS rate_unit_b,
         a.rate_value AS rate_a, b.rate_value AS rate_b,
         a.p99_ms AS p99_a, b.p99_ms AS p99_b,
         (a.run_id <> b.run_id AND a.status = 'PASS' AND b.status = 'PASS'
          AND a.concurrency <=> b.concurrency
          AND CASE
            WHEN a.scenario = 'S1' AND b.scenario = 'S1' THEN
              JSON_CONTAINS_PATH(a.summary, 'all', '$.slotTotal', '$.p99Ms') = 1
              AND JSON_CONTAINS_PATH(b.summary, 'all', '$.slotTotal', '$.p99Ms') = 1
              AND JSON_EXTRACT(a.summary, '$.slotTotal') <=> JSON_EXTRACT(b.summary, '$.slotTotal')
            WHEN a.scenario = 'S2' AND b.scenario = 'S2' THEN
              JSON_CONTAINS_PATH(a.summary, 'all', '$.warmupSeconds', '$.sampleSeconds',
                                 '$.completedQps', '$.p99Ms') = 1
              AND JSON_CONTAINS_PATH(b.summary, 'all', '$.warmupSeconds', '$.sampleSeconds',
                                     '$.completedQps', '$.p99Ms') = 1
              AND
              JSON_EXTRACT(a.summary, '$.warmupSeconds') <=> JSON_EXTRACT(b.summary, '$.warmupSeconds')
              AND JSON_EXTRACT(a.summary, '$.sampleSeconds') <=> JSON_EXTRACT(b.summary, '$.sampleSeconds')
            WHEN a.scenario = 'S2-OPEN' AND b.scenario = 'S2-OPEN' THEN
              JSON_CONTAINS_PATH(a.summary, 'all', '$.path', '$.offeredRps', '$.warmupSeconds',
                                 '$.sampleSeconds', '$.maxInFlightLimit', '$.timeoutMillis',
                                 '$.completedWithinWindow', '$.p99Ms') = 1
              AND JSON_CONTAINS_PATH(b.summary, 'all', '$.path', '$.offeredRps', '$.warmupSeconds',
                                     '$.sampleSeconds', '$.maxInFlightLimit', '$.timeoutMillis',
                                     '$.completedWithinWindow', '$.p99Ms') = 1
              AND JSON_EXTRACT(a.summary, '$.path') <=> JSON_EXTRACT(b.summary, '$.path')
              AND JSON_EXTRACT(a.summary, '$.warmupSeconds') <=> JSON_EXTRACT(b.summary, '$.warmupSeconds')
              AND JSON_EXTRACT(a.summary, '$.offeredRps') <=> JSON_EXTRACT(b.summary, '$.offeredRps')
              AND JSON_EXTRACT(a.summary, '$.sampleSeconds') <=> JSON_EXTRACT(b.summary, '$.sampleSeconds')
              AND JSON_EXTRACT(a.summary, '$.maxInFlightLimit') <=> JSON_EXTRACT(b.summary, '$.maxInFlightLimit')
              AND JSON_EXTRACT(a.summary, '$.timeoutMillis') <=> JSON_EXTRACT(b.summary, '$.timeoutMillis')
            WHEN a.scenario IN ('S3-a', 'S3-b', 'S3-c', 'S3-d')
                 AND b.scenario IN ('S3-a', 'S3-b', 'S3-c', 'S3-d')
                 AND (a.scenario = b.scenario
                      OR (a.scenario IN ('S3-a','S3-b') AND b.scenario IN ('S3-a','S3-b'))) THEN
              JSON_CONTAINS_PATH(a.summary, 'all', '$.requests', '$.rps', '$.p99Ms') = 1
              AND JSON_CONTAINS_PATH(b.summary, 'all', '$.requests', '$.rps', '$.p99Ms') = 1
              AND
              JSON_EXTRACT(a.summary, '$.requests') <=> JSON_EXTRACT(b.summary, '$.requests')
            WHEN a.scenario = 'S4-HTTP' AND b.scenario = 'S4-HTTP' THEN
              JSON_CONTAINS_PATH(a.summary, 'all', '$.count', '$.sameTaskAttempts',
                                 '$.timeoutMillis', '$.rewardCents', '$.distinct.durableSettledTps',
                                 '$.distinct.allAttemptP99Us') = 1
              AND JSON_CONTAINS_PATH(b.summary, 'all', '$.count', '$.sameTaskAttempts',
                                     '$.timeoutMillis', '$.rewardCents', '$.distinct.durableSettledTps',
                                     '$.distinct.allAttemptP99Us') = 1
              AND
              JSON_EXTRACT(a.summary, '$.count') <=> JSON_EXTRACT(b.summary, '$.count')
              AND JSON_EXTRACT(a.summary, '$.sameTaskAttempts') <=> JSON_EXTRACT(b.summary, '$.sameTaskAttempts')
              AND JSON_EXTRACT(a.summary, '$.timeoutMillis') <=> JSON_EXTRACT(b.summary, '$.timeoutMillis')
              AND JSON_EXTRACT(a.summary, '$.rewardCents') <=> JSON_EXTRACT(b.summary, '$.rewardCents')
            WHEN a.scenario = b.scenario AND a.scenario IN ('S5-mq', 'S5-fallback') THEN
              JSON_CONTAINS_PATH(a.summary, 'all', '$.expected', '$.leadSeconds',
                                 '$.confirmSeconds', '$.timeoutAfterDueSeconds',
                                 '$.workerScanIntervalMs', '$.p99Ms') = 1
              AND JSON_CONTAINS_PATH(b.summary, 'all', '$.expected', '$.leadSeconds',
                                     '$.confirmSeconds', '$.timeoutAfterDueSeconds',
                                     '$.workerScanIntervalMs', '$.p99Ms') = 1
              AND JSON_TYPE(JSON_EXTRACT(a.summary, '$.workerScanIntervalMs')) <> 'NULL'
              AND JSON_TYPE(JSON_EXTRACT(b.summary, '$.workerScanIntervalMs')) <> 'NULL'
              AND JSON_EXTRACT(a.summary, '$.expected') <=> JSON_EXTRACT(b.summary, '$.expected')
              AND
              JSON_EXTRACT(a.summary, '$.leadSeconds') <=> JSON_EXTRACT(b.summary, '$.leadSeconds')
              AND JSON_EXTRACT(a.summary, '$.confirmSeconds') <=> JSON_EXTRACT(b.summary, '$.confirmSeconds')
              AND JSON_EXTRACT(a.summary, '$.timeoutAfterDueSeconds') <=> JSON_EXTRACT(b.summary, '$.timeoutAfterDueSeconds')
              AND JSON_EXTRACT(a.summary, '$.workerScanIntervalMs') <=> JSON_EXTRACT(b.summary, '$.workerScanIntervalMs')
            ELSE FALSE
          END) AS comparable
    FROM normalized a JOIN normalized b ON a.run_id = @a AND b.run_id = @b
)
SELECT run_a, run_b, scenario_a, scenario_b,
       IF(comparable, 'COMPARABLE', 'NOT_COMPARABLE: check load, status, configuration and data snapshot') AS verdict,
       IF(comparable, rate_unit_a, NULL) AS rate_unit,
       IF(comparable, rate_a, NULL) AS rate_a,
       IF(comparable, rate_b, NULL) AS rate_b,
       IF(comparable AND rate_a IS NOT NULL AND rate_b IS NOT NULL, rate_b - rate_a, NULL) AS rate_delta,
       IF(comparable, p99_a, NULL) AS p99_a_ms,
       IF(comparable, p99_b, NULL) AS p99_b_ms,
       IF(comparable AND p99_a IS NOT NULL AND p99_b IS NOT NULL, p99_b - p99_a, NULL) AS p99_delta_ms
  FROM pair;

-- A/B attribution still requires the same seed snapshot, warmup, image and resource
-- limits outside bench_run. S5 MQ-vs-fallback is intentionally side-by-side only.
SELECT run_id, COUNT(*) AS tracked_errands
  FROM bench_run_item WHERE run_id IN (@a, @b) AND entity_type = 'ERRAND'
 GROUP BY run_id;
