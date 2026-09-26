"""Refusal tests for the one-shot MQ runner's host-side safety boundary."""
import os
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import preflight
import run_mq_probe


PROJECT = "peergrab-bench-test"
NETWORK_ID = "bench-network-id"


def service(network_name="peergrab-bench-test_default", scan="false"):
    return {
        "Config": {"Env": [f"PEERGRAB_TIMEOUT_SCAN_ENABLED={scan}"]},
        "NetworkSettings": {"Networks": {network_name: {"NetworkID": NETWORK_ID}}},
    }


class MqRunnerSafetyTest(unittest.TestCase):
    def setUp(self):
        values = {
            "PEERGRAB_BENCH_PROJECT": PROJECT,
            "PEERGRAB_BENCH_BASE_URL": "http://127.0.0.1:38080",
            "PEERGRAB_TEST_DB_HOST": "127.0.0.1",
            "PEERGRAB_TEST_DB_PORT": "33307",
        }
        env = patch.dict(os.environ, values)
        env.start()
        self.addCleanup(env.stop)
        self.verified = {"worker_scan_interval_ms": 5000}

    def test_production_project_rejected_before_container_creation(self):
        with patch.dict(os.environ, {"PEERGRAB_BENCH_PROJECT": "peergrab-prod",
                                       "COMPOSE_PROJECT_NAME": "peergrab-prod",
                                       "PEERGRAB_BENCH_DISPOSABLE": "YES"}), \
             patch.object(run_mq_probe, "command") as command:
            with self.assertRaisesRegex(preflight.Refused, "Invalid benchmark project"):
                run_mq_probe.run("delay", SimpleNamespace(delay_seconds=2))
            command.assert_not_called()

    def test_scan_enabled_mq_round_rejected_before_container_creation(self):
        containers = {"app": service(), "worker": service(scan="true"), "rmqbroker": service()}
        with patch.object(run_mq_probe.preflight, "check", return_value=self.verified), \
             patch.object(run_mq_probe, "service_container", side_effect=lambda _, name: containers[name]), \
             patch.object(run_mq_probe, "command") as command:
            with self.assertRaisesRegex(preflight.Refused, "timeout scan disabled"):
                run_mq_probe.run("s5", SimpleNamespace(count=1, lead_seconds=20,
                                                        confirm_seconds=5, timeout_seconds=30))
            command.assert_not_called()

    def test_broker_on_different_network_rejected_before_container_creation(self):
        containers = {"app": service(), "worker": service(),
                      "rmqbroker": service(network_name="wrong-network")}
        with patch.object(run_mq_probe.preflight, "check", return_value=self.verified), \
             patch.object(run_mq_probe, "service_container", side_effect=lambda _, name: containers[name]), \
             patch.object(run_mq_probe, "command") as command:
            with self.assertRaisesRegex(preflight.Refused, "shared benchmark app/broker network"):
                run_mq_probe.run("delay", SimpleNamespace(delay_seconds=2))
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
