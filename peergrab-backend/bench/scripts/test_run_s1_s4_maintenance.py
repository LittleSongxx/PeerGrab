"""Offline gates for the maintenance-only S1/S4 runner."""

from contextlib import redirect_stdout
from io import StringIO
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_s1_s4_maintenance as runner


BASE = {"s2Rows": 10000, "s2Published": 10000, "s2Sha256": "a" * 64,
        "walletTotal": 110000, "ledgerDifference": 0, "publisherAvailable": 100000,
        "s2GrabRows": 0, "s2EscrowRows": 0, "errandRows": 10000,
        "grabRows": 0, "escrowRows": 0, "walletAccountRows": 5,
        "ledgerRows": 0, "benchRunRows": 3}


class MaintenanceRunnerTest(unittest.TestCase):
    def test_project_defaults_to_legacy_stack_and_accepts_new_disposable_stack(self):
        with patch.dict(runner.os.environ, {}, clear=True):
            self.assertEqual(runner.selected_project(), "peergrab-bench-max0926")
        with patch.dict(runner.os.environ,
                        {"PEERGRAB_BENCH_PROJECT": "peergrab-bench-8cpu0927"}, clear=True):
            self.assertEqual(runner.selected_project(), "peergrab-bench-8cpu0927")

    def test_project_rejects_production_and_unsafe_names(self):
        for project in ("", "peergrab-prod", "peergrab-bench-", "PeerGrab-bench-test",
                        "peergrab-bench-test;rm", "peergrab-bench-../other",
                        "peergrab-bench-test\nother"):
            with self.subTest(project=project), patch.dict(
                    runner.os.environ, {"PEERGRAB_BENCH_PROJECT": project}, clear=True):
                with self.assertRaisesRegex(runner.Stopped, "peergrab-bench-\\*"):
                    runner.selected_project()

    def test_checked_stack_uses_only_matching_disposable_project(self):
        project = "peergrab-bench-8cpu0927"
        variables = {"PEERGRAB_BENCH_PROJECT": project,
                     "COMPOSE_PROJECT_NAME": project,
                     "PEERGRAB_TEST_DB_HOST": "127.0.0.1",
                     "PEERGRAB_TEST_DB_PORT": "33307",
                     "PEERGRAB_TEST_DB_PASSWORD": "private",
                     "PEERGRAB_AUTH_JWT_SECRET": "private"}
        with patch.dict(runner.os.environ, variables, clear=True), \
             patch.object(runner, "production_stopped"), \
             patch.object(runner, "resource_gate"), \
             patch.object(runner.preflight, "check", return_value={"mysql_container_id": "id"}) as check, \
             patch.object(runner, "command", return_value="a" * 64) as command:
            _, ids = runner.checked_stack("http://127.0.0.1:38080")
            self.assertEqual(ids, ["a" * 64])
            check.assert_called_once_with(project, "http://127.0.0.1:38080",
                                          "127.0.0.1", "33307")
            self.assertEqual(command.call_args.args[-1],
                             "label=com.docker.compose.project=" + project)
        with patch.dict(runner.os.environ,
                        {**variables, "COMPOSE_PROJECT_NAME": "peergrab-bench-other"},
                        clear=True), \
             patch.object(runner, "production_stopped"), \
             patch.object(runner, "resource_gate"), \
             patch.object(runner.preflight, "check") as check:
            with self.assertRaisesRegex(runner.Stopped, "must match"):
                runner.checked_stack("http://127.0.0.1:38080")
            check.assert_not_called()

    def test_workloads_put_funds_before_spike(self):
        self.assertEqual([stage[0] for stage in runner.STAGES],
                         ["s4-20-4", "s4-50-8", "s4-100-16",
                          "s1-16", "s1-64", "s1-128"])

    def test_optional_s4_high_stage_is_only_selected_for_s4_or_all(self):
        for only, include_high, expected in (
                ("all", False, ["s4-20-4", "s4-50-8", "s4-100-16",
                                "s1-16", "s1-64", "s1-128"]),
                ("all", True, ["s4-20-4", "s4-50-8", "s4-100-16", "s4-200-32",
                               "s1-16", "s1-64", "s1-128"]),
                ("s4", True, ["s4-20-4", "s4-50-8", "s4-100-16", "s4-200-32"]),
                ("s1", True, ["s1-16", "s1-64", "s1-128"]),
        ):
            with self.subTest(only=only, include_high=include_high):
                self.assertEqual([stage[0] for stage in
                                  runner.selected_stages(only, include_high)], expected)
        self.assertEqual(runner.S4_HIGH_STAGE[2], (200, 32, 8, 30000))

    def test_production_must_be_stopped_with_explicit_maintenance_guard(self):
        with patch.dict(runner.os.environ, {}, clear=True), \
             patch.object(runner, "command") as command:
            with self.assertRaisesRegex(runner.Stopped, "MAINTENANCE_APPROVED"):
                runner.production_stopped()
            command.assert_not_called()
        with patch.dict(runner.os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": "YES"}), \
             patch.object(runner, "command", return_value="container-id"):
            with self.assertRaisesRegex(runner.Stopped, "Production containers"):
                runner.production_stopped()

    def test_s2_rows_and_wallet_total_are_immutable(self):
        runner.fixture_gate(BASE, BASE.copy())
        for change in ({"s2Sha256": "b" * 64}, {"s2Published": 9999},
                       {"s2GrabRows": 1}, {"walletTotal": 109999},
                       {"ledgerDifference": 1}):
            with self.subTest(change=change), self.assertRaises(runner.Stopped):
                runner.fixture_gate(BASE, {**BASE, **change})
        with self.assertRaisesRegex(runner.Stopped, "not exactly"):
            runner.fixture_gate({**BASE, "s2Rows": 9999}, BASE)

    def test_stage_counts_follow_published_lifecycle(self):
        s4 = {**BASE, "errandRows": BASE["errandRows"] + 23,
              "escrowRows": BASE["escrowRows"] + 23,
              "grabRows": BASE["grabRows"] + 22,
              "benchRunRows": BASE["benchRunRows"] + 1}
        runner.stage_counts_gate(BASE, s4, "s4-20-4")
        with self.assertRaisesRegex(runner.Stopped, "escrow rows"):
            runner.stage_counts_gate(BASE, {**s4, "escrowRows": 22}, "s4-20-4")
        s1 = {**BASE, "errandRows": BASE["errandRows"] + 31,
              "escrowRows": BASE["escrowRows"] + 31,
              "grabRows": BASE["grabRows"] + 31,
              "benchRunRows": BASE["benchRunRows"] + 1}
        runner.stage_counts_gate(BASE, s1, "s1-64")

    def test_run_id_requires_exactly_one_unique_safe_value(self):
        self.assertEqual(runner.run_id_from_log("runId=20260926-123456\nrunId=20260926-123456"),
                         "20260926-123456")
        self.assertEqual(runner.run_id_from_log("runId=20260926-123456-2"),
                         "20260926-123456-2")
        for bad in ("", "runId=secret", "runId=20260926-123456 runId=20260926-123457"):
            with self.subTest(log=bad), self.assertRaises(runner.Stopped):
                runner.run_id_from_log(bad)

    def test_per_run_sql_invariants_catch_nonempty_duplicate_set(self):
        with patch.object(runner, "mysql", return_value="0\t0\t0\t0\t31\t31"):
            self.assertEqual(runner.run_invariants("mysql", "20260926-123456", "s1-64")
                             ["trackedErrands"], 31)
        with patch.object(runner, "mysql", return_value="0\t1\t0\t0\t31\t31"):
            with self.assertRaisesRegex(runner.Stopped, "durable"):
                runner.run_invariants("mysql", "20260926-123456", "s1-64")

    def test_dry_run_never_starts_maven_load(self):
        stack = {"mysql_container_id": "bench-mysql"}
        with patch.dict(runner.os.environ, {"PEERGRAB_BENCH_BASE_URL": "http://127.0.0.1:38080",
                                           "PEERGRAB_BENCH_PROJECT": "peergrab-bench-8cpu0927"},
                        clear=True), \
             patch.object(runner, "checked_stack", return_value=(stack, ["a" * 64])), \
             patch.object(runner, "snapshot", return_value=BASE), \
             patch.object(runner, "run_stage") as stage, \
             patch.object(sys, "argv", ["run_s1_s4_maintenance.py", "--dry-run"]):
            output = StringIO()
            with redirect_stdout(output):
                self.assertEqual(runner.main(), 0)
            self.assertEqual(json.loads(output.getvalue())["project"],
                             "peergrab-bench-8cpu0927")
            stage.assert_not_called()

    def test_dry_run_lists_optional_high_stage_only_with_s4(self):
        stack = {"mysql_container_id": "bench-mysql"}
        variables = {"PEERGRAB_BENCH_BASE_URL": "http://127.0.0.1:38080",
                     "PEERGRAB_BENCH_PROJECT": "peergrab-bench-8cpu0927"}
        with patch.dict(runner.os.environ, variables, clear=True), \
             patch.object(runner, "checked_stack", return_value=(stack, ["a" * 64])), \
             patch.object(runner, "snapshot", return_value=BASE), \
             patch.object(runner, "run_stage") as stage:
            for only, includes_high in (("s4", True), ("all", True), ("s1", False)):
                with self.subTest(only=only), \
                     patch.object(sys, "argv", ["run_s1_s4_maintenance.py", "--dry-run",
                                                "--only", only, "--include-s4-high"]):
                    output = StringIO()
                    with redirect_stdout(output):
                        self.assertEqual(runner.main(), 0)
                    self.assertEqual("s4-200-32" in json.loads(output.getvalue())["stages"],
                                     includes_high)
            stage.assert_not_called()


if __name__ == "__main__":
    unittest.main()
