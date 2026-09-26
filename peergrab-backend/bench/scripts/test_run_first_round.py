"""No-network checks for the guarded first-round driver."""

import argparse
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_first_round as driver


STACK = {"project": "peergrab-bench-test", "api": "http://127.0.0.1:38080",
         "mysql_container_id": "mysql-id"}


class FirstRoundTest(unittest.TestCase):
    def test_refused_stack_prevents_even_public_health_request(self):
        args = argparse.Namespace(public_health_url=driver.DEFAULT_PUBLIC_HEALTH, dry_run=True,
                                  rates=driver.DEFAULT_RATES)
        with patch.object(driver, "checked_stack", side_effect=driver.Stopped("bad stack")), \
                patch.object(driver, "public_health") as public:
            with self.assertRaisesRegex(driver.Stopped, "bad stack"):
                driver.run(args)
            public.assert_not_called()

    def test_dry_run_checks_gates_without_starting_load(self):
        args = argparse.Namespace(public_health_url=driver.DEFAULT_PUBLIC_HEALTH, dry_run=True,
                                  rates=driver.DEFAULT_RATES)
        with patch.object(driver, "checked_stack", return_value=STACK), \
                patch.object(driver, "require_s2_seed", return_value=10000), \
                patch.object(driver, "public_health", return_value=0.1), \
                patch.object(driver, "check_memory", return_value=8 * 1024 ** 3), \
                patch.object(driver, "run_stage") as stage:
            driver.run(args)
            stage.assert_not_called()

    def test_failed_bench_status_stops_before_next_rate_and_persists(self):
        args = argparse.Namespace(public_health_url=driver.DEFAULT_PUBLIC_HEALTH, dry_run=False,
                                  rates=driver.DEFAULT_RATES)
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(driver, "RUNS_ROOT", Path(temporary)), \
                patch.object(driver, "checked_stack", return_value=STACK), \
                patch.object(driver, "require_s2_seed", return_value=10000), \
                patch.object(driver, "public_health", return_value=0.1), \
                patch.object(driver, "check_memory", return_value=8 * 1024 ** 3), \
                patch.object(driver, "run_stage", return_value={
                    "runId": "20260926-123456", "status": "FAIL", "summary": {"offeredRps": 5}
                }) as stage:
            with self.assertRaisesRegex(driver.Stopped, "status is FAIL"):
                driver.run(args)
            stage.assert_called_once()
            output = next(Path(temporary).glob("first-round-*/manifest.json"))
            manifest = json.loads(output.read_text())
            self.assertEqual(manifest["status"], "STOPPED")
            self.assertEqual(manifest["stages"][0]["runId"], "20260926-123456")
            self.assertEqual(manifest["stages"][0]["status"], "FAIL")

    def test_resource_stop_gates(self):
        samples = [
            {"kind": "metadata"},
            {"kind": "sample", "host": {"cpuPercent": 86, "ioWaitPercent": 2,
                                         "memAvailableBytes": 8 * 1024 ** 3}},
            {"kind": "sample", "host": {"cpuPercent": 89, "ioWaitPercent": 2,
                                         "memAvailableBytes": 8 * 1024 ** 3}},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "resources.jsonl"
            path.write_text("\n".join(json.dumps(item) for item in samples) + "\n")
            with self.assertRaisesRegex(driver.Stopped, "CPU exceeded 85%"):
                driver.check_resource_samples(path, {"seen": 0, "high_cpu": 0})
            samples[-1]["host"]["cpuPercent"] = 10
            samples[-1]["host"]["ioWaitPercent"] = 11
            path.write_text("\n".join(json.dumps(item) for item in samples) + "\n")
            with self.assertRaisesRegex(driver.Stopped, "iowait exceeded 10%"):
                driver.check_resource_samples(path, {"seen": 0, "high_cpu": 0})

    def test_new_result_accepts_running_row_for_abort_export(self):
        run_id = "20260926-123456"
        with patch.object(driver, "mysql", side_effect=[run_id, "RUNNING\tNULL"]):
            result = driver.new_result("mysql-id", set(), 5, 32)
        self.assertEqual(result, {"runId": run_id, "status": "RUNNING", "summary": None})

    def test_pass_summary_uses_requested_in_flight_limit(self):
        run_id = "20260926-123456"
        summary = {"offeredRps": 160, "warmupSeconds": 10, "sampleSeconds": 30,
                   "maxInFlightLimit": 64, "timeoutMillis": 5000,
                   "offered": 4800, "ok": 4800, "localRejected": 0}
        with patch.object(driver, "mysql", side_effect=[run_id,
                "PASS\t" + json.dumps(summary)]):
            result = driver.new_result("mysql-id", set(), 160, 64)
        self.assertEqual(result["status"], "PASS")
        with patch.object(driver, "mysql", side_effect=[run_id,
                "PASS\t" + json.dumps(summary)]):
            with self.assertRaisesRegex(driver.Stopped, "summary differs"):
                driver.new_result("mysql-id", set(), 160, 32)

    def test_guard_abort_updates_only_exact_verified_running_row(self):
        reason = "Host CPU exceeded 85%; quote=' and 中文"
        with patch.object(driver, "checked_stack", return_value=STACK), \
                patch.object(driver, "mysql", return_value="1") as mysql:
            driver.fail_interrupted_run(STACK["project"], STACK["api"],
                                        STACK["mysql_container_id"], "20260926-170537", reason)
        mysql.assert_called_once()
        self.assertEqual(mysql.call_args.args[0], STACK["mysql_container_id"])
        sql = mysql.call_args.args[1]
        self.assertIn("WHERE run_id='20260926-170537' AND kind='BENCH'", sql)
        self.assertIn("AND scenario='S2-OPEN' AND status='RUNNING'", sql)
        self.assertIn("finished_at=NOW(3)", sql)
        self.assertIn(reason.encode("utf-8").hex(), sql)
        self.assertNotIn(reason, sql)

    def test_abort_finalization_refuses_changed_stack_or_invalid_run_id(self):
        changed = {**STACK, "mysql_container_id": "replacement"}
        with patch.object(driver, "checked_stack", return_value=changed), \
                patch.object(driver, "mysql") as mysql:
            with self.assertRaisesRegex(driver.Stopped, "identity changed"):
                driver.fail_interrupted_run(STACK["project"], STACK["api"],
                                            STACK["mysql_container_id"], "20260926-170537", "abort")
            mysql.assert_not_called()
        with patch.object(driver, "checked_stack") as checked, \
                patch.object(driver, "mysql") as mysql:
            with self.assertRaisesRegex(driver.Stopped, "invalid benchmark run"):
                driver.fail_interrupted_run(STACK["project"], STACK["api"],
                                            STACK["mysql_container_id"], "x'; DROP TABLE bench_run;", "abort")
            checked.assert_not_called()
            mysql.assert_not_called()

    def test_abort_finalization_requires_one_affected_row(self):
        with patch.object(driver, "checked_stack", return_value=STACK), \
                patch.object(driver, "mysql", return_value="0"):
            with self.assertRaisesRegex(driver.Stopped, "not a single RUNNING"):
                driver.fail_interrupted_run(STACK["project"], STACK["api"],
                                            STACK["mysql_container_id"], "20260926-170537", "abort")

    def test_resource_guard_abort_closes_running_row_and_saves_reason(self):
        class FakeProcess:
            pid = 12345
            returncode = None

            def poll(self):
                return None

        commands = []

        def fake_popen(argv, **_):
            commands.append(argv)
            if "collect_metrics.py" in " ".join(argv):
                output = Path(argv[argv.index("--output") + 1])
                output.write_text('{"kind":"metadata"}\n')
            return FakeProcess()

        run_id = "20260926-170537"
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(driver.subprocess, "Popen", side_effect=fake_popen), \
                patch.object(driver, "run_ids", return_value=set()), \
                patch.object(driver, "check_memory", return_value=8 * 1024 ** 3), \
                patch.object(driver, "check_resource_samples", side_effect=[
                    None, driver.Stopped("Host CPU exceeded 85% for two consecutive metrics samples")]), \
                patch.object(driver, "terminate_group"), \
                patch.object(driver, "new_result", side_effect=[
                    {"runId": run_id, "status": "RUNNING", "summary": None},
                    {"runId": run_id, "status": "FAIL", "summary": {"abortReason": "CPU"}}
                ]), \
                patch.object(driver, "fail_interrupted_run") as finalize:
            result = driver.run_stage(STACK["project"], STACK["api"], Path(temporary),
                                      320, STACK["mysql_container_id"], 64)
            finalize.assert_called_once_with(STACK["project"], STACK["api"],
                                             STACK["mysql_container_id"], run_id,
                                             "Host CPU exceeded 85% for two consecutive metrics samples")
            self.assertEqual(result["status"], "FAIL")
            self.assertIn("Host CPU", result["abortReason"])
            self.assertIn("-Dexec.args=http://127.0.0.1:38080 320 10 30 64 5000", commands[1])
            saved = json.loads((Path(temporary) / "stage-320rps" / "result.json").read_text())
            self.assertEqual(saved, result)

    def test_seed_guard_requires_official_s2_fixture(self):
        with patch.object(driver, "mysql", return_value="9999"):
            with self.assertRaisesRegex(driver.Stopped, "10,000"):
                driver.require_s2_seed("mysql-id")

    def test_health_url_only_allows_public_https_health(self):
        for url in ("http://www.peergrab.cn/api/health", "https://localhost/api/health",
                    "https://www.peergrab.cn/other", "https://www.peergrab.cn/api/health?x=1"):
            with self.subTest(url=url), self.assertRaises(driver.Stopped):
                driver.validate_health_url(url)
        self.assertEqual(driver.validate_health_url(driver.DEFAULT_PUBLIC_HEALTH),
                         driver.DEFAULT_PUBLIC_HEALTH)

    def test_optional_rates_accept_only_short_increasing_allowlist(self):
        self.assertEqual(driver.parse_rates("40"), (40,))
        self.assertEqual(driver.parse_rates("40,80,160"), (40, 80, 160))
        self.assertEqual(driver.parse_rates("320"), (320,))
        for value in ("640", "5,10,20,40", "20,10", "40,40", "1,40", "40,abc", ""):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                driver.parse_rates(value)

    def test_manifest_uses_requested_rate(self):
        args = argparse.Namespace(public_health_url=driver.DEFAULT_PUBLIC_HEALTH, dry_run=False,
                                  rates=(40,), max_in_flight=64)
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(driver, "RUNS_ROOT", Path(temporary)), \
                patch.object(driver, "checked_stack", return_value=STACK), \
                patch.object(driver, "require_s2_seed", return_value=10000), \
                patch.object(driver, "public_health", return_value=0.1), \
                patch.object(driver, "check_memory", return_value=8 * 1024 ** 3), \
                patch.object(driver, "run_stage", return_value={
                    "runId": "20260926-123456", "status": "PASS", "summary": {"offeredRps": 40}
                }) as stage:
            driver.run(args)
            stage.assert_called_once_with(STACK["project"], STACK["api"],
                                          stage.call_args.args[2], 40, STACK["mysql_container_id"], 64)
            output = next(Path(temporary).glob("first-round-*/manifest.json"))
            manifest = json.loads(output.read_text())
            self.assertEqual(manifest["rates"], [40])
            self.assertEqual(manifest["maxInFlight"], 64)
            self.assertEqual(manifest["stages"][0]["offeredRps"], 40)

    def test_in_flight_rejects_outside_allowlist_before_public_probe(self):
        args = argparse.Namespace(public_health_url=driver.DEFAULT_PUBLIC_HEALTH, dry_run=True,
                                  rates=(40,), max_in_flight=256)
        with patch.object(driver, "checked_stack", return_value=STACK), \
                patch.object(driver, "public_health") as public:
            with self.assertRaisesRegex(driver.Stopped, "max-in-flight"):
                driver.run(args)
            public.assert_not_called()


if __name__ == "__main__":
    unittest.main()
