"""Offline safety checks for the optional Vegeta cross-check."""

import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_remote_s2_vegeta as vegeta


ARGS = ["--project", "peergrab-bench-test", "--remote-backend",
        "/data/peergrab-bench-test/peergrab-backend", "--remote-env", ".env.bench.test",
        "--direct-base-url", "https://www.peergrab.cn/__bench_" + "a" * 24 + "/"]
IDENTITY = {"project": "peergrab-bench-test", "api": "http://127.0.0.1:38080",
            "mysql_container_id": "a" * 64, "volumes": ["peergrab-bench-test_" + x
                                                   for x in ("mysql", "redis", "rocketmq")],
            "services": ["mysql", "redis", "rmqnamesrv", "rmqbroker", "app", "worker"],
            "api_port": 38080}


class VegetaSafetyTest(unittest.TestCase):
    def test_default_does_only_identity_and_single_route_probe(self):
        with patch.object(vegeta.guard, "validate_config"), \
                patch.object(vegeta.guard, "remote_preflight", return_value=IDENTITY), \
                patch.object(vegeta.guard, "verify_seed", return_value=10000), \
                patch.object(vegeta, "check_identity") as probe, \
                patch.object(vegeta.guard, "demo_password") as password, \
                patch.object(vegeta, "run_phase") as attack:
            self.assertEqual(vegeta.main(ARGS), 0)
            probe.assert_called_once()
            password.assert_not_called()
            attack.assert_not_called()

    def test_execute_requires_confirmation_before_network(self):
        with patch.object(vegeta.guard, "validate_config"), \
                patch.object(vegeta.guard, "remote_preflight") as remote:
            self.assertEqual(vegeta.main(ARGS + ["--execute"]), 1)
            remote.assert_not_called()

    def test_summary_excludes_error_strings_and_private_target(self):
        report = {"requests": 100, "status_codes": {"200": 99, "500": 1},
                  "latencies": {"50th": 1_000_000, "95th": 2_000_000,
                                "99th": 5_000_000},
                  "rate": 100, "throughput": 99, "duration": 1_000_000_000,
                  "wait": 1_000_000, "errors": ["Bearer NEVER_WRITE_ME"]}
        summary = vegeta.inspect_report(report, 100, 1)
        self.assertNotIn("NEVER_WRITE_ME", json.dumps(summary))
        self.assertTrue(vegeta.degraded(summary))
        self.assertEqual(summary["http2xxQps"], 99)
        self.assertEqual(summary["transportErrorKinds"], ["other"])

    def test_zero_status_is_a_transport_failure(self):
        report = {"requests": 100, "status_codes": {"200": 99, "0": 1},
                  "latencies": {}, "rate": 100, "throughput": 99,
                  "duration": 1_000_000_000, "wait": 0}
        summary = vegeta.inspect_report(report, 100, 1)
        self.assertEqual(summary["transportErrors"], 1)
        self.assertEqual(summary["httpOther"], 0)
        self.assertEqual(summary["resultRecords"], 100)
        self.assertEqual(summary["completed"], 99)
        self.assertTrue(vegeta.degraded(summary))


if __name__ == "__main__":
    unittest.main()
