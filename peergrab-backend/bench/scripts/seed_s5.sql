-- Historical S5 fixture entry, retired on 2026-09-26.
-- The old SQL inserted already-expired LOCKED tasks. It could measure only
-- fallback draining, not natural due-time latency or RocketMQ scheduling.
-- Its original SQL remains in Git history for interpreting dated reports.
-- Use S5TimelineProbe on a fresh disposable stack; see bench/S5_TIMELINE.md.
SELECT * FROM __peergrab_legacy_s5_seed_disabled_20260926__;
