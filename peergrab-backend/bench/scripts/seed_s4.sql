-- Historical S4 fixture entry, retired on 2026-09-26.
-- The old SQL deleted ledger/escrow rows in a fixed ID range and reset shared
-- wallet balances. Its original SQL remains in Git history for interpreting
-- dated benchmark reports.
-- Use FundsHttpLoadClient on a fresh disposable stack after the identity
-- preflight in bench/README.md. This file deliberately contains no writes.
SELECT * FROM __peergrab_legacy_s4_seed_disabled_20260926__;
