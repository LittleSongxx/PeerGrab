"""Offline safety checks for the optional Vegeta cross-check."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

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
    def test_default_does_only_identity_probes(self):
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

    def test_workloads_are_exact_and_cursor_is_default(self):
        args = vegeta.parse_args(ARGS)
        self.assertEqual(args.workload, "cursor-first")
        self.assertEqual(vegeta.WORKLOADS[args.workload],
                         ("square-cursor-first-v1", "/api/errands?campusId=1&cursor=&size=20"))
        legacy = vegeta.parse_args(ARGS + ["--workload", "legacy-first"])
        self.assertEqual(vegeta.WORKLOADS[legacy.workload][1], vegeta.guard.LIST_PATH)
        with self.assertRaises(SystemExit):
            vegeta.parse_args(ARGS + ["--workload", "../../other"])

    def test_selected_identity_probes_the_cursor_as_well_as_legacy(self):
        args = vegeta.parse_args(ARGS)
        with patch.object(vegeta, "check_stack_identity", return_value=IDENTITY), \
                patch.object(vegeta.guard, "verify_direct_target", new_callable=AsyncMock) as legacy, \
                patch.object(vegeta, "verify_cursor_target", new_callable=AsyncMock) as cursor:
            vegeta.check_identity(args, IDENTITY)
            legacy.assert_awaited_once()
            cursor.assert_awaited_once()
            args.workload = "legacy-first"
            vegeta.check_identity(args, IDENTITY)
            self.assertEqual(legacy.await_count, 2)
            cursor.assert_awaited_once()

    def test_cursor_probe_requires_isolated_ids_and_next_cursor(self):
        seed = 930_000_000_001
        expected = {seed + i for i in range(20)}
        payload = {"code": "OK", "data": {
            "items": [{"id": str(seed + i)} for i in range(20)],
            "nextCursor": "opaque-cursor"}}
        vegeta.verify_cursor_payload(payload, expected)
        payload["data"]["nextCursor"] = ""
        with self.assertRaises(vegeta.guard.Refused):
            vegeta.verify_cursor_payload(payload, expected)
        payload["data"]["nextCursor"] = "opaque-cursor"
        payload["data"]["items"][0]["id"] = "123"
        with self.assertRaises(vegeta.guard.Refused):
            vegeta.verify_cursor_payload(payload, expected)

    def test_saved_evidence_records_workload_version_without_bearer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "vegeta"
            binary.write_bytes(b"offline-test-executable")
            phase = {"offered": 1, "completed": 1, "httpOther": 0,
                     "transportErrors": 0, "http2xx": 1, "http2xxQps": 1,
                     "p99Ms": 1.0}
            argv = ARGS + ["--vegeta", str(binary), "--rates", "1",
                           "--warmup", "1", "--sample", "1", "--execute",
                           "--confirm-project", "peergrab-bench-test"]
            with patch.object(vegeta.guard, "RUNS", root), \
                    patch.object(vegeta.guard, "validate_config"), \
                    patch.object(vegeta.guard, "remote_preflight", return_value=IDENTITY), \
                    patch.object(vegeta.guard, "verify_seed", return_value=10000), \
                    patch.object(vegeta, "check_identity"), \
                    patch.object(vegeta.guard, "demo_password", return_value="private-password"), \
                    patch.object(vegeta.guard, "login", new_callable=AsyncMock,
                                 return_value="PRIVATE_BEARER_TOKEN"), \
                    patch.object(vegeta, "run_phase", return_value=phase):
                self.assertEqual(vegeta.main(argv), 0)
            files = list(root.glob("external-s2-vegeta-*.json"))
            self.assertEqual(len(files), 1)
            raw = files[0].read_text()
            saved = json.loads(raw)
            self.assertEqual(saved["workload"], "/api/errands?campusId=1&cursor=&size=20")
            self.assertEqual(saved["workloadVersion"], "square-cursor-first-v1")
            self.assertNotIn("PRIVATE_BEARER_TOKEN", raw)

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

    def test_periodic_reports_store_only_sanitized_cumulative_deltas(self):
        first = {"requests": 2, "status_codes": {"200": 2},
                 "latencies": {"total": 4_000_000, "50th": 2_000_000,
                               "95th": 3_000_000, "99th": 3_000_000},
                 "rate": 2, "throughput": 2, "duration": 1_000_000_000,
                 "wait": 1_000_000, "latest": "2026-09-28T12:00:01Z",
                 "errors": ["Bearer NEVER_WRITE_ME"]}
        second = {"requests": 4, "status_codes": {"200": 3, "0": 1},
                  "latencies": {"total": 10_000_000, "50th": 2_000_000,
                                "95th": 5_000_000, "99th": 5_000_000},
                  "rate": 2, "throughput": 1.5, "duration": 2_000_000_000,
                  "wait": 5_000_000, "latest": "2026-09-28T12:00:02Z",
                  "errors": ["Bearer NEVER_WRITE_ME"]}
        raw = (vegeta.ANSI_CLEAR + json.dumps(first).encode() + b"\n"
               + vegeta.ANSI_CLEAR + json.dumps(second).encode() + b"\n")
        summary, snapshots = vegeta.inspect_periodic_reports(raw, 2, 2)
        self.assertEqual(summary["offered"], 4)
        self.assertEqual(summary["transportErrors"], 1)
        self.assertEqual(snapshots[-1]["resultRecordsSincePrevious"], 2)
        self.assertEqual(snapshots[-1]["statusCodesSincePrevious"], {"200": 1, "0": 1})
        self.assertEqual(snapshots[-1]["meanLatencyMsSincePrevious"], 3.0)
        self.assertNotIn("NEVER_WRITE_ME", json.dumps(snapshots))

    def test_periodic_counters_must_be_monotonic(self):
        first = {"requests": 2, "status_codes": {"200": 2},
                 "latencies": {"total": 4_000_000}, "latest": "2026-09-28T12:00:01Z"}
        second = {"requests": 2, "status_codes": {"200": 1, "0": 1},
                  "latencies": {"total": 5_000_000}, "latest": "2026-09-28T12:00:02Z"}
        raw = (json.dumps(first) + "\n" + json.dumps(second)).encode()
        with self.assertRaises(vegeta.guard.Refused):
            vegeta.inspect_periodic_reports(raw, 2, 2)

    def test_long_phase_requires_periodic_coverage(self):
        single = {"requests": 20, "status_codes": {"200": 20},
                  "latencies": {"total": 20_000_000},
                  "latest": "2026-09-28T12:00:09Z"}
        with self.assertRaisesRegex(vegeta.guard.Refused, "did not cover"):
            vegeta.inspect_periodic_reports(json.dumps(single).encode(), 2, 10)

    def test_long_phase_requires_requests_across_the_phase(self):
        repeated = {"requests": 2, "status_codes": {"200": 2},
                    "latencies": {"total": 2_000_000},
                    "duration": 1_000_000_000,
                    "latest": "2026-09-28T12:00:01Z"}
        raw = (json.dumps(repeated) + "\n") * 10
        with self.assertRaisesRegex(vegeta.guard.Refused, "did not span"):
            vegeta.inspect_periodic_reports(raw.encode(), 2, 10)


if __name__ == "__main__":
    unittest.main()
