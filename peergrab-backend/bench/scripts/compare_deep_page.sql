-- Read-only to application tables: all generated rows live in session-scoped TEMPORARY tables.
-- Run with: mysql -u ... -p peer_grab < bench/scripts/compare_deep_page.sql
-- Compare actual time/rows from EXPLAIN ANALYZE, not just the optimizer's estimated cost.

CREATE TEMPORARY TABLE perf_digits (n INT NOT NULL PRIMARY KEY);
INSERT INTO perf_digits (n) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
-- MySQL cannot reopen one TEMPORARY table five times in a self-join.
CREATE TEMPORARY TABLE perf_digits_b AS SELECT n FROM perf_digits;
CREATE TEMPORARY TABLE perf_digits_c AS SELECT n FROM perf_digits;
CREATE TEMPORARY TABLE perf_digits_d AS SELECT n FROM perf_digits;
CREATE TEMPORARY TABLE perf_digits_e AS SELECT n FROM perf_digits;

CREATE TEMPORARY TABLE perf_errand LIKE errand;
INSERT INTO perf_errand
    (id, campus_id, publisher_id, type, title, reward_amount, slot_total,
     slot_taken, status, round, version, created_at)
SELECT 1000000 + seq.n, 1, 1001 + MOD(seq.n, 10), 'DELIVERY',
       CONCAT('bench-', seq.n), 100, 1, 0, 'PUBLISHED', 0, 0,
       TIMESTAMP('2026-01-01 00:00:00') + INTERVAL seq.n SECOND
  FROM (
    SELECT a.n + 10*b.n + 100*c.n + 1000*d.n + 10000*e.n AS n
      FROM perf_digits a CROSS JOIN perf_digits_b b CROSS JOIN perf_digits_c c
      CROSS JOIN perf_digits_d d CROSS JOIN perf_digits_e e
  ) seq;

CREATE TEMPORARY TABLE perf_grab_record LIKE grab_record;
INSERT INTO perf_grab_record
    (id, campus_id, errand_id, runner_id, seq, round, result, created_at)
SELECT 2000000 + id, 1, id, 3001, 1, 0, 'GRABBED', created_at
  FROM perf_errand WHERE MOD(id, 10) = 0;
INSERT INTO perf_grab_record
    (id, campus_id, errand_id, runner_id, seq, round, result, created_at)
SELECT 3000000 + id, 1, id, 3001, 1, 1, 'GRABBED', created_at
  FROM perf_errand WHERE MOD(id, 10) = 0;

SELECT 'marketplace OFFSET 90000' AS scenario;
EXPLAIN ANALYZE SELECT * FROM perf_errand
 WHERE campus_id = 1 AND status = 'PUBLISHED'
 ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 90000;

SELECT created_at, id INTO @cursor_created_at, @cursor_id
  FROM perf_errand WHERE campus_id = 1 AND status = 'PUBLISHED'
 ORDER BY created_at DESC, id DESC LIMIT 1 OFFSET 89999;
SELECT 'marketplace cursor after row 90000' AS scenario;
EXPLAIN ANALYZE SELECT * FROM perf_errand
 WHERE campus_id = 1 AND status = 'PUBLISHED'
   AND (created_at < @cursor_created_at OR (created_at = @cursor_created_at AND id < @cursor_id))
 ORDER BY created_at DESC, id DESC LIMIT 20;

SELECT 'runner history OFFSET 8000, existing index' AS scenario;
EXPLAIN ANALYZE SELECT e.* FROM perf_errand e
 JOIN (SELECT DISTINCT errand_id FROM perf_grab_record WHERE runner_id = 3001) g
   ON g.errand_id = e.id
 ORDER BY e.created_at DESC LIMIT 20 OFFSET 8000;

ALTER TABLE perf_grab_record ADD INDEX idx_runner_errand (runner_id, errand_id);
SELECT 'runner history OFFSET 8000, covering membership index' AS scenario;
EXPLAIN ANALYZE SELECT e.* FROM perf_errand e
 JOIN (SELECT DISTINCT errand_id FROM perf_grab_record WHERE runner_id = 3001) g
   ON g.errand_id = e.id
 ORDER BY e.created_at DESC LIMIT 20 OFFSET 8000;

ALTER TABLE perf_grab_record ALTER INDEX idx_runner_errand INVISIBLE;
SELECT 'runner history OFFSET 8000, old index repeat' AS scenario;
EXPLAIN ANALYZE SELECT e.* FROM perf_errand e
 JOIN (SELECT DISTINCT errand_id FROM perf_grab_record WHERE runner_id = 3001) g
   ON g.errand_id = e.id
 ORDER BY e.created_at DESC LIMIT 20 OFFSET 8000;

-- A separate table preserves the old wallet index for an A/B/A query-plan check.
CREATE TEMPORARY TABLE perf_wallet_ledger (
    id BIGINT NOT NULL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount BIGINT NOT NULL,
    ref_type VARCHAR(24) NOT NULL,
    ref_id BIGINT NOT NULL,
    biz_no VARCHAR(64) NOT NULL,
    KEY idx_account_time (account_id, created_at)
) ENGINE=InnoDB;
INSERT INTO perf_wallet_ledger
    (id, account_id, user_id, created_at, direction, amount, ref_type, ref_id, biz_no)
SELECT id, 1, publisher_id, created_at, 'CREDIT', 100, 'SETTLE', id,
       CONCAT('bench:', id)
  FROM perf_errand;

SELECT 'wallet user ledger first page, old indexes' AS scenario;
EXPLAIN ANALYZE SELECT created_at, direction, amount, ref_type, ref_id, biz_no
  FROM perf_wallet_ledger WHERE user_id = 1001
 ORDER BY created_at DESC, id DESC LIMIT 20;

SELECT 'wallet user ledger OFFSET 8000, old indexes' AS scenario;
EXPLAIN ANALYZE SELECT created_at, direction, amount, ref_type, ref_id, biz_no
  FROM perf_wallet_ledger WHERE user_id = 1001
 ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 8000;

ALTER TABLE perf_wallet_ledger ADD INDEX idx_ledger_user_time (user_id, created_at, id);
SELECT 'wallet user ledger first page, user-time index' AS scenario;
EXPLAIN ANALYZE SELECT created_at, direction, amount, ref_type, ref_id, biz_no
  FROM perf_wallet_ledger WHERE user_id = 1001
 ORDER BY created_at DESC, id DESC LIMIT 20;

SELECT 'wallet user ledger OFFSET 8000, user-time index' AS scenario;
EXPLAIN ANALYZE SELECT created_at, direction, amount, ref_type, ref_id, biz_no
  FROM perf_wallet_ledger WHERE user_id = 1001
 ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 8000;

SELECT created_at, id INTO @wallet_cursor_created_at, @wallet_cursor_id
  FROM perf_wallet_ledger WHERE user_id = 1001
 ORDER BY created_at DESC, id DESC LIMIT 1 OFFSET 7999;
SELECT 'wallet user ledger cursor after row 8000, user-time index' AS scenario;
EXPLAIN ANALYZE SELECT created_at, direction, amount, ref_type, ref_id, biz_no
  FROM perf_wallet_ledger WHERE user_id = 1001
   AND (created_at < @wallet_cursor_created_at
        OR (created_at = @wallet_cursor_created_at AND id < @wallet_cursor_id))
 ORDER BY created_at DESC, id DESC LIMIT 20;

ALTER TABLE perf_wallet_ledger ALTER INDEX idx_ledger_user_time INVISIBLE;
SELECT 'wallet user ledger OFFSET 8000, old indexes repeat' AS scenario;
EXPLAIN ANALYZE SELECT created_at, direction, amount, ref_type, ref_id, biz_no
  FROM perf_wallet_ledger WHERE user_id = 1001
 ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 8000;
