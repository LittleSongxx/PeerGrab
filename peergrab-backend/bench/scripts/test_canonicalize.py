"""Offline checks for the canonical multi-round benchmark summarizer."""

from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import canonicalize


def write_json(directory: Path, name: str, payload: dict) -> Path:
    path = directory / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def jmeter_summary() -> dict:
    return {
        "project": "peergrab-bench-test",
        "generator": "Apache JMeter 5.6.3 on target ECS",
        "transport": "ECS local Nginx TLS",
        "seedCount": 10000,
        "warmupSeconds": 20,
        "sampleSeconds": 180,
        "status": "COMPLETE",
        "rounds": [
            {"rateTargetRps": 500, "round": 1, "sample": {
                "samplesStarted": 90000, "successfulSamples": 90000,
                "failedSamples": 0, "nonHttpErrors": 0, "httpNon200": 0,
                "http200BusinessFailures": 0, "allP50Ms": 5,
                "allP95Ms": 46, "allP99Ms": 241, "maxElapsedMs": 638,
                "actualStartedRps": 500.0, "scheduledWindowSeconds": 180}},
            {"rateTargetRps": 500, "round": 2, "sample": {
                "samplesStarted": 90000, "successfulSamples": 90000,
                "failedSamples": 0, "nonHttpErrors": 0, "httpNon200": 0,
                "http200BusinessFailures": 0, "allP50Ms": 4,
                "allP95Ms": 7, "allP99Ms": 13, "maxElapsedMs": 123,
                "actualStartedRps": 500.0, "scheduledWindowSeconds": 180}},
            {"rateTargetRps": 500, "round": 3, "sample": {
                "samplesStarted": 90000, "successfulSamples": 90000,
                "failedSamples": 0, "nonHttpErrors": 0, "httpNon200": 0,
                "http200BusinessFailures": 0, "allP50Ms": 4,
                "allP95Ms": 13, "allP99Ms": 22, "maxElapsedMs": 173,
                "actualStartedRps": 500.0, "scheduledWindowSeconds": 180}},
        ],
    }


def vegeta_stage(rate: int, sample: dict) -> dict:
    return {
        "offeredRps": rate,
        "warmup": {"offered": rate * 20},
        "sample": sample,
    }


def vegeta_payload(stages: list[dict]) -> dict:
    return {
        "project": "peergrab-bench-8cpu",
        "transport": "verified direct HTTPS HTTP/1.1",
        "generator": "Vegeta",
        "status": "DEGRADED",
        "stages": stages,
    }


def vegeta_sample(rate: int, *, http_other: int = 0, transport: int = 0,
                  p99: float = 50.0, actual: float | None = None) -> dict:
    return {
        "offered": rate * 60,
        "resultRecords": rate * 60,
        "completed": rate * 60,
        "http2xx": rate * 60 - http_other - transport,
        "httpOther": http_other,
        "transportErrors": transport,
        "actualIssueRate": actual if actual is not None else float(rate),
        "successfulThroughput": float(rate) - http_other / 60,
        "p50Ms": 37.0, "p95Ms": 45.0, "p99Ms": p99,
    }


def knee_manifest() -> dict:
    return {
        "project": "peergrab-bench-knee",
        "api": "http://127.0.0.1:38080",
        "rates": [50, 100],
        "warmupSeconds": 30,
        "sampleSeconds": 60,
        "status": "COMPLETE",
        "stages": [
            {"runId": "r1", "offeredRps": 50, "sampleSeconds": 60,
             "sample": {"offered": 3000, "ok": 3000, "errors": 0,
                        "localRejected": 0, "schedulerMissed": 0,
                        "capacityRejected": 0, "serverRejected": 0,
                        "completedRps": 50.0, "p50Ms": 3.2, "p95Ms": 5.5,
                        "p99Ms": 7.5, "maxMs": 21.8}},
            {"runId": "r2", "offeredRps": 100, "sampleSeconds": 60,
             "sample": {"offered": 6000, "ok": 5990, "errors": 10,
                        "localRejected": 0, "schedulerMissed": 0,
                        "capacityRejected": 0, "serverRejected": 0,
                        "completedRps": 99.8, "p50Ms": 3.1, "p95Ms": 5.6,
                        "p99Ms": 7.7, "maxMs": 14.7}},
        ],
    }


class CanonicalizeTest(unittest.TestCase):
    def test_jmeter_three_rounds_reports_median_and_cold_round_spread(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_json(root, "s2-evidence/summary.json", jmeter_summary())
            resources = root / "s2-evidence" / "resources.jsonl"
            resources.write_text("\n".join(json.dumps({"kind": "sample", "host": {
                "cpuPercent": cpu, "ioWaitPercent": 0.1,
                "memAvailableBytes": 1024}}) for cpu in (20.0, 30.0, 40.0)),
                encoding="utf-8")
            canonical, warnings = canonicalize.collect([root / "s2-evidence"])
        groups = canonical["groups"]
        self.assertEqual(1, len(groups))
        group = groups[0]
        self.assertEqual("S2", group["scenario"])
        self.assertEqual("jmeter", group["tool"])
        self.assertEqual(3, len(group["rounds"]))
        self.assertEqual(22, group["p99Ms"]["median"])
        self.assertEqual(13, group["p99Ms"]["min"])
        self.assertEqual(241, group["p99Ms"]["max"])
        self.assertEqual(270000, group["requests"])
        self.assertEqual(270000, group["success"])
        self.assertIn("P99 spread", " ".join(group["warnings"]))
        self.assertIn("JMeter client latency only", " ".join(group["warnings"]))
        self.assertEqual(30, group["hostCpuPercentMedian"]["median"])
        self.assertTrue(any("P99 spread" in warning for warning in warnings))

    def test_single_round_and_errors_are_not_zero_error_capacity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = vegeta_sample(600, http_other=1, transport=0)
            # The generator over-recorded a sample; success exceeds the
            # documented denominator and the tool must say so instead of
            # silently printing a rate above 100%.
            sample["offered"] = 35998
            sample["resultRecords"] = 35998
            write_json(root, "s2/vegeta.json", vegeta_payload([
                vegeta_stage(600, sample)]))
            canonical, _ = canonicalize.collect([root / "s2"])
        group = canonical["groups"][0]
        self.assertEqual(1, len(group["rounds"]))
        self.assertEqual(1, group["httpErrors"])
        self.assertIn("1 round(s) < 3", " ".join(group["warnings"]))
        self.assertIn("errors present", " ".join(group["warnings"]))
        self.assertIn("success count exceeds request denominator",
                      " ".join(group["warnings"]))

    def test_vegeta_stage_uses_actual_issue_rate_and_derives_window(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_json(root, "s2/vegeta.json", vegeta_payload([
                vegeta_stage(1200, vegeta_sample(1200, http_other=3,
                                                 actual=1199.5, p99=321.2))]))
            canonical, _ = canonicalize.collect([root / "s2"])
        group = canonical["groups"][0]
        self.assertAlmostEqual(1199.5, group["rounds"][0]["actual_rps"], places=3)
        self.assertIsNotNone(group["rounds"][0]["window_seconds"])
        self.assertEqual(321.2, group["p99Ms"]["median"])

    def test_target_deviation_over_five_percent_is_flagged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = vegeta_sample(1500, actual=1400.0)
            write_json(root, "s2/vegeta.json", vegeta_payload([
                vegeta_stage(1500, sample)]))
            canonical, _ = canonicalize.collect([root / "s2"])
        group = canonical["groups"][0]
        self.assertIn("vs target 1500.000", " ".join(group["warnings"]))
        self.assertLessEqual(len(group["rounds"]), 1)

    def test_knee_manifest_reads_stage_resources_and_manifest_rounds(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "knee-run"
            write_json(root, "manifest.json", knee_manifest())
            for rate in (50, 100):
                resources = root / f"stage-{rate}rps" / "resources.jsonl"
                resources.parent.mkdir(parents=True, exist_ok=True)
                lines = [
                    json.dumps({"kind": "sample", "host": {
                        "cpuPercent": 20.0 + rate / 100,
                        "ioWaitPercent": 0.1,
                        "memAvailableBytes": 1024}}),
                    json.dumps({"kind": "sample", "host": {
                        "cpuPercent": 30.0 + rate / 100,
                        "ioWaitPercent": 0.2,
                        "memAvailableBytes": 2048},
                        "dockerStats": [{"Name": "peergrab-bench-knee-app-1",
                                         "CPUPerc": "6.47%"}],
                        "mysql": {"Threads_running": 2}}),
                ]
                resources.write_text("\n".join(lines) + "\n", encoding="utf-8")
            canonical, _ = canonicalize.collect([root])
        groups = canonical["groups"]
        self.assertEqual(2, len(groups))
        rates = sorted(group["rate"] for group in groups)
        self.assertEqual([50, 100], rates)
        by_rate = {group["rate"]: group for group in groups}
        self.assertAlmostEqual(25.5, by_rate[50]["rounds"][0]["resources"][
            "hostCpuPercentMedian"], places=3)
        self.assertEqual(2, by_rate[100]["rounds"][0]["resources"][
            "mysqlThreadsRunningMax"])
        self.assertIn("1 round(s) < 3", " ".join(by_rate[50]["warnings"]))

    def test_directory_with_manifest_does_not_double_count_stage_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_json(root, "manifest.json", knee_manifest())
            for rate in (50, 100):
                write_json(root, f"stage-{rate}rps/result.json",
                           knee_manifest()["stages"][rate // 50 - 1])
            canonical, _ = canonicalize.collect([root])
        self.assertEqual(2, len(canonical["groups"]))
        self.assertEqual(2, sum(len(group["rounds"])
                                for group in canonical["groups"]))

    def test_unrecognized_and_missing_inputs_are_warnings_not_crashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_json(root, "notes-summary.json", {"hello": "world"})
            canonical, warnings = canonicalize.collect(
                [root, root / "missing.json"])
        self.assertEqual([], canonical["groups"])
        self.assertTrue(any("does not exist" in warning for warning in warnings))
        self.assertTrue(any("not a recognized run summary" in warning
                            for warning in warnings))

    def test_markdown_uses_integer_denominators_and_transport(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_json(root, "s2/summary.json", jmeter_summary())
            canonical, _ = canonicalize.collect([root / "s2"])
        markdown = canonicalize._markdown(
            canonical["groups"], canonical["warnings"], [])
        self.assertIn("270,000", markdown)
        self.assertNotIn("270,000.0", markdown)
        self.assertIn("ECS local Nginx TLS", markdown)

    def test_json_output_is_machine_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_json(root, "s2/summary.json", jmeter_summary())
            output = root / "canonical.json"
            code = canonicalize.main(["--input", str(root / "s2"),
                                      "--format", "json", "--out", str(output)])
            self.assertEqual(0, code)
            payload = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(canonicalize.SCHEMA_VERSION, payload["schemaVersion"])
        self.assertEqual(3, payload["groups"][0]["rounds"][0]["window_seconds"] // 60)


if __name__ == "__main__":
    unittest.main()
