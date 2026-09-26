-- Historical wallet fixture entry, retired on 2026-09-26.
-- The old script reset shared escrow, commission, publisher, and runner wallet
-- balances. Its original SQL remains in Git history for interpreting dated
-- benchmark reports.
-- Use seed_bench.py for S2/S3. S1/S4/S5 clients prepare their own fixtures on
-- a fresh disposable stack after the identity preflight in bench/README.md.
SELECT * FROM __peergrab_legacy_wallet_seed_disabled_20260926__;
