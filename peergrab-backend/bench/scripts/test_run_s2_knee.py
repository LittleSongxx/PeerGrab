"""Offline checks for the guarded S2 capacity-knee runner."""

import argparse
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_s2_knee as knee


STACK = {"project": "peergrab-bench-test", "api": "http://127.0.0.1:38080",
         "mysql_container_id": "mysql-id", "volumes": ["peergrab-bench-test_mysql_data"]}
SERVICES = {"app": "app-id", "mysql": "mysql-id", "worker": "worker-id"}


def args(**changes):
    values = {"rates": (50, 100), "warmup_seconds": 30, "sample_seconds": 60,
              "max_in_flight": 128, "timeout_ms": 5000, "public_health_url":
              knee.first.DEFAULT_PUBLIC_HEALTH, "dry_run": True, "maintenance": False}
    values.update(changes)
    return argparse.Namespace(**values)


class KneeTest(unittest.TestCase):
    def test_full_docker_ids_are_required_for_identity_pin(self):
        full = "a" * 64
        with patch.object(knee.collect_metrics, "command", return_value=full) as command:
            self.assertEqual(knee.full_container_ids(STACK["project"]), [full])
            self.assertIn("--no-trunc", command.call_args.args)
        with patch.object(knee.collect_metrics, "command", return_value=full[:12]):
            with self.assertRaisesRegex(knee.Stopped, "full container IDs"):
                knee.full_container_ids(STACK["project"])

    def test_stack_identity_compares_full_ids_not_docker_ps_abbreviations(self):
        full_ids = ["a" * 64, "b" * 64, "c" * 64]
        with patch.object(knee, "full_container_ids", return_value=full_ids), \
                patch.object(knee.first, "checked_stack", return_value=STACK):
            knee.assert_same_stack(STACK["project"], STACK["api"], STACK, full_ids)
        with patch.object(knee, "full_container_ids", return_value=["a" * 12, *full_ids[1:]]), \
                patch.object(knee.first, "checked_stack", return_value=STACK):
            with self.assertRaisesRegex(knee.Stopped, "container set changed"):
                knee.assert_same_stack(STACK["project"], STACK["api"], STACK, full_ids)

    def test_rates_and_minimum_duration_are_bounded(self):
        self.assertEqual(knee.parse_rates("50,100,200,400,800"), knee.RATES)
        for invalid in ("", "10", "50,50", "400,100", "50,100,1000", "50,nope"):
            with self.subTest(invalid=invalid), self.assertRaises(argparse.ArgumentTypeError):
                knee.parse_rates(invalid)
        with self.assertRaisesRegex(knee.Stopped, "warmup"):
            knee.verify_configuration(args(warmup_seconds=29))
        with self.assertRaisesRegex(knee.Stopped, "warmup"):
            knee.verify_configuration(args(sample_seconds=59))

    def test_failed_stack_blocks_login_public_health_and_load(self):
        with patch.object(knee.first, "checked_stack", side_effect=knee.Stopped("bad stack")), \
                patch.object(knee.first, "public_health") as public, \
                patch.object(knee, "token_for_bench") as login, \
                patch.object(knee, "run_stage") as stage:
            with self.assertRaisesRegex(knee.Stopped, "bad stack"):
                knee.run(args(dry_run=False))
            public.assert_not_called()
            login.assert_not_called()
            stage.assert_not_called()

    def test_dry_run_checks_identity_without_logging_in_or_offering(self):
        with patch.object(knee.first, "checked_stack", return_value=STACK), \
                patch.object(knee.first, "require_s2_seed", return_value=10000), \
                patch.object(knee.first, "validate_health_url"), \
                patch.object(knee.first, "public_health", return_value=.1), \
                patch.object(knee.first, "check_memory", return_value=8 * 1024 ** 3), \
                patch.object(knee, "stack_identity", return_value=SERVICES), \
                patch.object(knee.collect_metrics, "container_metadata", return_value=[]), \
                patch.object(knee.collect_metrics, "command", return_value="sha"), \
                patch.object(knee, "token_for_bench") as login, \
                patch.object(knee, "run_stage") as stage:
            knee.run(args())
            login.assert_not_called()
            stage.assert_not_called()

    def test_cpu_gate_needs_two_consecutive_samples(self):
        base = {"memAvailableBytes": 8 * 1024 ** 3,
                "dockerDataFreeBytes": 20 * 1024 ** 3, "ioWaitPercent": 2,
                "cpuPercent": 90}
        self.assertEqual(knee.gate_resource(base, 0), 1)
        self.assertEqual(knee.gate_resource({**base, "cpuPercent": 20}, 1), 0)
        with self.assertRaisesRegex(knee.Stopped, "two consecutive"):
            knee.gate_resource(base, 1)
        with self.assertRaisesRegex(knee.Stopped, "iowait"):
            knee.gate_resource({**base, "cpuPercent": 20, "ioWaitPercent": 11}, 0)
        with self.assertRaisesRegex(knee.Stopped, "3 GiB"):
            knee.gate_resource({**base, "memAvailableBytes": 2 * 1024 ** 3}, 0)
        with self.assertRaisesRegex(knee.Stopped, "5 GiB"):
            knee.gate_resource({**base, "dockerDataFreeBytes": 4 * 1024 ** 3}, 0)
        self.assertEqual(knee.gate_resource(base, 1, maintenance=True), 2)

    def test_maintenance_requires_explicit_opt_in_and_stopped_production(self):
        with patch.dict(knee.os.environ, {}, clear=True), patch.object(knee.preflight, "docker") as docker:
            with self.assertRaisesRegex(knee.Stopped, "MAINTENANCE_APPROVED"):
                knee.verify_maintenance()
            docker.assert_not_called()
        with patch.dict(knee.os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": "YES"}), \
                patch.object(knee.preflight, "docker", return_value="prod-container-id"):
            with self.assertRaisesRegex(knee.Stopped, "still running"):
                knee.verify_maintenance()
        with patch.dict(knee.os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": "YES"}), \
                patch.object(knee.preflight, "docker", return_value="") as docker:
            knee.verify_maintenance()
            self.assertIn("peergrab-prod", docker.call_args.args[-1])

    def test_maintenance_dry_run_skips_public_health_only_after_stopped_prod_check(self):
        with patch.object(knee.first, "checked_stack", return_value=STACK), \
                patch.object(knee.first, "require_s2_seed", return_value=10000), \
                patch.object(knee.first, "public_health") as public, \
                patch.object(knee.first, "check_memory", return_value=8 * 1024 ** 3), \
                patch.object(knee, "verify_maintenance") as maintenance, \
                patch.object(knee, "stack_identity", return_value=SERVICES), \
                patch.object(knee.collect_metrics, "container_metadata", return_value=[]), \
                patch.object(knee.collect_metrics, "command", return_value="sha"):
            knee.run(args(maintenance=True))
            maintenance.assert_called_once()
            public.assert_not_called()

    def test_per_second_timeline_distinguishes_generator_and_server_failures(self):
        timeline = knee.Timeline(2)
        timeline.offer(0, sent=True)
        timeline.offer(0, rejected="scheduler")
        timeline.offer(1, sent=True)
        timeline.offer(1, rejected="capacity")
        with patch.object(knee.time, "monotonic", return_value=timeline.started + .5):
            timeline.complete(True, 12.0)
        with patch.object(knee.time, "monotonic", return_value=timeline.started + 1.5):
            timeline.complete(False, 17.0, "HTTP_503")
        summary = timeline.summary(2)
        self.assertEqual(summary["offered"], 4)
        self.assertEqual(summary["completedWithinWindow"], 2)
        self.assertEqual(summary["ok"], 1)
        self.assertEqual(summary["errors"], 1)
        self.assertEqual(summary["schedulerMissed"], 1)
        self.assertEqual(summary["capacityRejected"], 1)
        self.assertEqual(summary["serverRejected"], 1)
        self.assertEqual(summary["perSecond"][1]["completed"], 1)

    def test_finalize_requires_same_verified_stack_and_one_running_row(self):
        record = {"offeredRps": 50, "status": "FAIL", "abortReason": "CPU 90%"}
        container = {"Id": "mysql-id", "State": {"Running": True},
                     "Config": {"Labels": {knee.preflight.PROJECT_LABEL: STACK["project"],
                                           knee.preflight.SERVICE_LABEL: "mysql",
                                           knee.preflight.BENCH_LABEL: "true"}},
                     "Mounts": [{"Destination": "/var/lib/mysql",
                                 "Name": "peergrab-bench-test_mysql_data"}]}
        with patch.object(knee.preflight, "inspect", return_value=[container]), \
                patch.object(knee, "mysql", side_effect=[STACK["project"], "1"]) as query:
            knee.finish_run(STACK, "20260926-200000-123456", "FAIL", record)
            sql = query.call_args_list[-1].args[1]
            self.assertIn("status='FAIL'", sql)
            self.assertIn("AND status='RUNNING'", sql)
            self.assertNotIn("CPU 90%", sql)
            self.assertIn(json.dumps(record, ensure_ascii=False, separators=(",", ":")).encode().hex(), sql)
        changed = {**container, "Id": "other"}
        with patch.object(knee.preflight, "inspect", return_value=[changed]), \
                patch.object(knee, "mysql") as query:
            with self.assertRaisesRegex(knee.Stopped, "identity changed"):
                knee.finish_run(STACK, "20260926-200000-123456", "FAIL", record)
            query.assert_not_called()

    def test_aborted_stage_is_kept_in_manifest(self):
        result = {"runId": "20260926-200000-123456", "offeredRps": 50,
                  "status": "FAIL", "abortReason": "CPU above 85%"}

        def fail_stage(_stack, _services, output, rate, *_):
            stage = output / f"stage-{rate}rps"
            stage.mkdir()
            knee.write_json(stage / "result.json", result)
            raise knee.Stopped("CPU above 85%")

        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(knee, "RUNS_ROOT", Path(temporary)), \
                patch.object(knee.first, "checked_stack", return_value=STACK), \
                patch.object(knee.first, "require_s2_seed", return_value=10000), \
                patch.object(knee.first, "validate_health_url"), \
                patch.object(knee.first, "public_health", return_value=.1), \
                patch.object(knee.first, "check_memory", return_value=8 * 1024 ** 3), \
                patch.object(knee, "stack_identity", return_value=SERVICES), \
                patch.object(knee.collect_metrics, "container_metadata", return_value=[]), \
                patch.object(knee.collect_metrics, "command", return_value="sha"), \
                patch.object(knee, "token_for_bench", return_value="fake"), \
                patch.object(knee, "assert_same_stack"), \
                patch.object(knee, "run_stage", side_effect=fail_stage) as stage:
            with self.assertRaisesRegex(knee.Stopped, "CPU above 85%"):
                knee.run(args(dry_run=False))
            stage.assert_called_once()
            manifest = json.loads(next(Path(temporary).glob("s2-knee-*/manifest.json")).read_text())
            self.assertEqual(manifest["status"], "STOPPED")
            self.assertEqual(manifest["stages"], [result])

    def test_monitor_abort_marks_its_run_fail_without_sending_requests(self):
        monitor = MagicMock()
        monitor.wait_ready.side_effect = knee.Stopped("iowait above 10%")
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(knee, "register_run", return_value="20260926-200000-123456"), \
                patch.object(knee, "Monitor", return_value=monitor), \
                patch.object(knee, "assert_same_stack"), \
                patch.object(knee.first, "public_health", return_value=.1), \
                patch.object(knee, "finish_run") as finish, \
                patch.object(knee, "run_phase") as phase:
            with self.assertRaisesRegex(knee.Stopped, "iowait"):
                knee.run_stage(STACK, SERVICES, Path(temporary), 50, args(dry_run=False),
                               "fake-token", .1)
            phase.assert_not_called()
            self.assertEqual(finish.call_args.args[2], "FAIL")
            record = json.loads((Path(temporary) / "stage-50rps" / "result.json").read_text())
            self.assertEqual(record["status"], "FAIL")
            self.assertIn("iowait", record["abortReason"])


if __name__ == "__main__":
    unittest.main()
