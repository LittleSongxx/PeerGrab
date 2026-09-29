import argparse
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import run_s1_distinct_fixed as fixed


def config(rate=50, seconds=15, max_in_flight=64, timeout_ms=8000):
    return argparse.Namespace(rate=rate, seconds=seconds,
                              max_in_flight=max_in_flight, timeout_ms=timeout_ms)


class S1DistinctFixedTest(unittest.TestCase):
    def test_capacity_limits_bound_funded_fixtures_and_resources(self):
        self.assertEqual(750, fixed.validate_config(config()))
        for args in (config(rate=100, seconds=10), config(max_in_flight=129),
                     config(timeout_ms=10001)):
            with self.subTest(args=args), self.assertRaises(fixed.s6.Refused):
                fixed.validate_config(args)

    def test_output_is_new_immediate_private_runs_child(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory) / "runs"
            runs.mkdir()
            with patch.object(fixed, "RUNS", runs):
                self.assertEqual(runs / "new-run", fixed.output_path(runs / "new-run"))
                for bad in (Path(directory) / "outside", runs / "bad name"):
                    with self.subTest(path=bad), self.assertRaises(fixed.s6.Refused):
                        fixed.output_path(bad)
                (runs / "existing").mkdir()
                with self.assertRaises(fixed.s6.Refused):
                    fixed.output_path(runs / "existing")

    def test_fixture_requires_unique_task_and_request_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            file = output / "s1_distinct.csv"
            file.write_text("errand_id,token,request_id\n"
                            "1," + "a" * 30 + ",00000000-0000-0000-0000-000000000001\n"
                            "2," + "b" * 30 + ",00000000-0000-0000-0000-000000000002\n")
            self.assertEqual(2, len(fixed.fixture_rows(output, 2)))
            file.write_text("errand_id,token,request_id\n"
                            "1," + "a" * 30 + ",00000000-0000-0000-0000-000000000001\n"
                            "1," + "b" * 30 + ",00000000-0000-0000-0000-000000000002\n")
            with self.assertRaises(fixed.s6.Refused):
                fixed.fixture_rows(output, 2)

    def test_fixed_arrival_counts_every_offer_and_does_not_expose_tokens(self):
        fixtures = [{"errand_id": str(i + 1), "token": "private-token",
                     "request_id": "00000000-0000-0000-0000-000000000000"}
                    for i in range(20)]

        def successful(_base, fixture, _timeout, scheduled_ns):
            started = time.monotonic_ns()
            return {"errandId": int(fixture["errand_id"]), "outcome": "ok", "httpStatus": 200,
                    "latencyMs": 1.0, "startLagMs": max(0, started - scheduled_ns) / 1_000_000,
                    "startedNs": started, "finishedNs": started + 1_000_000}

        with patch.object(fixed, "grab_request", side_effect=successful):
            result = fixed.fixed_arrival_phase("http://127.0.0.1:28080", fixtures, 20, 1, 4, 1000)
        self.assertEqual((20, 20, 20), (result["offered"], result["sent"], result["completed"]))
        self.assertEqual(0, result["schedulerMissed"])
        self.assertEqual(0, result["localRejected"])
        self.assertEqual({"ok": 20}, result["outcomes"])
        self.assertNotIn("private-token", json.dumps(result))

    def test_pass_gate_rejects_local_miss_and_non_unique_durable_grabs(self):
        phase = {"offered": 20, "sent": 20, "completed": 20, "schedulerMissed": 0,
                 "localRejected": 0, "workerStartLate": 0, "outcomes": {"ok": 20}}
        truth = {"tracked": 20, "locked": 20, "grabbedRows": 20,
                 "distinctGrabbedTasks": 20, "runnerMismatches": 0}
        self.assertTrue(fixed.clean_result(phase, truth, 20))
        self.assertFalse(fixed.clean_result({**phase, "schedulerMissed": 1}, truth, 20))
        self.assertFalse(fixed.clean_result({**phase, "workerStartLate": 1}, truth, 20))
        self.assertFalse(fixed.clean_result(phase, {**truth, "distinctGrabbedTasks": 19}, 20))

    def test_durable_truth_rejects_untrusted_run_id_before_sql(self):
        with patch.object(fixed.s6, "mysql") as mysql:
            with self.assertRaises(fixed.s6.Refused):
                fixed.durable_truth("mysql-id", "x'; DROP TABLE errand; --")
            mysql.assert_not_called()

    def test_execute_requires_exact_project_before_fixture_writes(self):
        project = "peergrab-bench-fixedtest"
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(fixed, "RUNS", Path(directory)), \
                patch.dict(fixed.os.environ,
                           {"PEERGRAB_BENCH_PROJECT": project, "COMPOSE_PROJECT_NAME": project},
                           clear=False), \
                patch.object(fixed.signal, "signal"), \
                patch.object(fixed.s6, "production_stopped"), \
                patch.object(fixed.preflight, "check", return_value={"mysql_container_id": "mysql"}), \
                patch.object(fixed.s6, "require_unpaused_services"), \
                patch.object(fixed, "runtime_config", return_value="app"), \
                patch.object(fixed.s6, "fresh_stack"), \
                patch.object(fixed.subprocess, "run") as process:
            result = fixed.main(["--project", project,
                                 "--output", str(Path(directory) / "fresh"),
                                 "--rate", "1", "--seconds", "5", "--execute",
                                 "--confirm-project", "peergrab-bench-wrong"])
        self.assertEqual(1, result)
        process.assert_not_called()


if __name__ == "__main__":
    unittest.main()
