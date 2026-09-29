"""Safety gates for the independent automatic-settlement runner; no load is sent."""
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import preflight
import run_auto_settle_probe as probe


class AutoSettleRunnerSafetyTest(unittest.TestCase):
    def setUp(self):
        values = {
            "PEERGRAB_BENCH_PROJECT": "peergrab-bench-test",
            "COMPOSE_PROJECT_NAME": "peergrab-bench-test",
            "PEERGRAB_BENCH_DISPOSABLE": "YES",
            "PEERGRAB_BENCH_BASE_URL": "http://127.0.0.1:38080",
            "PEERGRAB_TEST_DB_HOST": "127.0.0.1",
            "PEERGRAB_TEST_DB_PORT": "33307",
            "PEERGRAB_TEST_DB_PASSWORD": "test-password",
        }
        env = patch.dict(os.environ, values)
        env.start()
        self.addCleanup(env.stop)

    def test_production_project_refused_before_runner_creation(self):
        with patch.dict(os.environ, {"PEERGRAB_BENCH_PROJECT": "peergrab-prod",
                                       "COMPOSE_PROJECT_NAME": "peergrab-prod"}), \
             patch.object(probe, "command") as command:
            with self.assertRaisesRegex(preflight.Refused, "Invalid benchmark project"):
                probe.run("combined", 1, 30, 30)
            command.assert_not_called()

    def test_running_production_refused_before_runner_creation(self):
        with patch.object(probe.preflight, "check", return_value={}), \
             patch.object(probe.preflight, "require_maintenance_window",
                          side_effect=preflight.Refused("Production containers are running")), \
             patch.object(probe, "command") as command:
            with self.assertRaisesRegex(preflight.Refused, "Production containers are running"):
                probe.run("combined", 1, 30, 30)
            command.assert_not_called()

    def test_unapproved_maintenance_refused_before_runner_creation(self):
        with patch.dict(os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": ""}), \
             patch.object(probe.preflight, "check", return_value={}), \
             patch.object(probe.preflight, "docker") as docker, \
             patch.object(probe, "command") as command:
            with self.assertRaisesRegex(preflight.Refused, "MAINTENANCE_APPROVED"):
                probe.run("scan", 1, 30, 30)
            docker.assert_not_called()
            command.assert_not_called()

    def test_missing_worker_scan_interval_refused_before_runner_creation(self):
        worker = {"Config": {"Env": ["PEERGRAB_MQ_ENABLED=false"]}}
        with patch.object(probe.preflight, "check", return_value={}), \
             patch.object(probe.preflight, "require_maintenance_window"), \
             patch.object(probe, "service_container", return_value=worker), \
             patch.object(probe, "command") as command:
            with self.assertRaisesRegex(preflight.Refused, "scan interval"):
                probe.run("scan", 1, 30, 30)
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
