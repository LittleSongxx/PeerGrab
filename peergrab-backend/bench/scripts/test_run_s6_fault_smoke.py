import unittest
from unittest.mock import patch
from types import SimpleNamespace
import time
import tempfile
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
from urllib.request import ProxyHandler

import run_s6_fault_smoke as s6


PROJECT = "peergrab-bench-s6test"
CONTAINER_ID = "a" * 64


def target(paused=False, running=True):
    return {"Id": CONTAINER_ID, "State": {"Paused": paused, "Running": running}}


class S6SafetyTest(unittest.TestCase):
    def test_jwt_requests_ignore_proxy_env_and_never_follow_redirect(self):
        target_seen, diverted_seen = [], []

        class Diverted(BaseHTTPRequestHandler):
            def do_GET(self):
                diverted_seen.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"code":"OK","data":{"diverted":true}}')

            def log_message(self, *_args):
                pass

        diverted = ThreadingHTTPServer(("127.0.0.1", 0), Diverted)
        diverted_url = f"http://127.0.0.1:{diverted.server_port}"

        class Target(BaseHTTPRequestHandler):
            def do_GET(self):
                target_seen.append(self.headers.get("Authorization"))
                if self.path == "/api/redirect":
                    self.send_response(302)
                    self.send_header("Location", diverted_url + "/stolen")
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"code": "OK", "data": {"direct": True}}).encode())

            def log_message(self, *_args):
                pass

        target_server = ThreadingHTTPServer(("127.0.0.1", 0), Target)
        for server in (diverted, target_server):
            threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            base_url = f"http://127.0.0.1:{target_server.server_port}"
            with patch.dict(s6.os.environ, {"HTTP_PROXY": diverted_url,
                                            "HTTPS_PROXY": diverted_url,
                                            "ALL_PROXY": diverted_url,
                                            "NO_PROXY": ""}, clear=False):
                self.assertEqual({"direct": True}, s6.api(base_url, "GET", "/api/direct", "jwt-test"))
                with self.assertRaises(s6.Refused):
                    s6.api(base_url, "GET", "/api/redirect", "jwt-test")
            self.assertEqual(["Bearer jwt-test", "Bearer jwt-test"], target_seen)
            self.assertEqual([], diverted_seen)
            handlers = [h for h in s6.DIRECT_HTTP.handlers if isinstance(h, ProxyHandler)]
            # An explicit empty ProxyHandler suppresses the environment-derived
            # default; urllib omits the empty handler from the final handler list.
            self.assertEqual([], handlers)
            with self.assertRaises(s6.Refused):
                s6.api("https://example.com", "GET", "/api/direct", "jwt-test")
        finally:
            target_server.shutdown()
            diverted.shutdown()
            target_server.server_close()
            diverted.server_close()

    def test_fixed_rate_detail_phase_reports_attempt_denominators_and_p95(self):
        sample = {"outcome": "ok", "state": "PUBLISHED", "latencyMs": 2.5}
        with patch.object(s6, "DETAIL_RATE", 4), \
                patch.object(s6, "detail_probe", return_value=sample):
            result = s6.detail_phase("http://127.0.0.1:1", "private", 1, 1, "before")
        self.assertEqual(4, result["offered"])
        self.assertEqual(4, result["sent"])
        self.assertEqual(4, result["completed"])
        self.assertEqual(4, result["ok"])
        self.assertEqual(0, result["transportErrors"])
        self.assertEqual(2.5, result["p95Ms"])

    def test_jmeter_detail_phase_keeps_token_out_of_command_and_jtl(self):
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory)
            executable = private / "jmeter"
            executable.write_text("#!/bin/sh\nexit 0\n")
            executable.chmod(0o700)

            def fake_popen(command, **kwargs):
                self.assertNotIn("private-token", " ".join(command))
                self.assertEqual("private-token", kwargs["env"]["PEERGRAB_BENCH_TOKEN"])
                self.assertTrue(all(not name.lower().endswith("_proxy")
                                    for name in kwargs["env"]))
                self.assertTrue(kwargs["start_new_session"])
                jtl = Path(command[command.index("-l") + 1])
                jtl.write_text("timeStamp,elapsed,label,responseCode,success,bytes,sentBytes,"
                               "grpThreads,allThreads,Latency,IdleTime,Connect\n"
                               "1790000000000,17,S6-detail,200,true,100,100,1,1,15,0,5\n")
                return SimpleNamespace(wait=lambda timeout: 0)

            with patch.object(s6, "RUNS", private), \
                    patch.object(s6.subprocess, "Popen", side_effect=fake_popen), \
                    patch.dict(s6.os.environ, {"HTTP_PROXY": "http://example.invalid"}, clear=False):
                result = s6.jmeter_detail_phase("http://127.0.0.1:28080", "private-token",
                                                7, 1, "before", executable)
            self.assertEqual(1, result["sent"])
            self.assertEqual(1, result["ok"])
            self.assertIsNone(result["schedulerMissed"])
            self.assertEqual(17, result["p95Ms"])

    def test_jmeter_timeout_terminates_shell_and_java_process_group(self):
        class Stuck:
            pid = 1234
            waits = 0

            def wait(self, timeout):
                self.waits += 1
                if self.waits == 1:
                    raise s6.subprocess.TimeoutExpired("jmeter", timeout)
                return -15

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "jmeter"
            binary.write_text("#!/bin/sh\nexit 0\n")
            binary.chmod(0o700)
            process = Stuck()
            with patch.object(s6, "RUNS", root), \
                    patch.object(s6.subprocess, "Popen", return_value=process), \
                    patch.object(s6.os, "killpg") as killpg:
                with self.assertRaisesRegex(s6.Refused, "bounded time limit"):
                    s6.jmeter_detail_phase("http://127.0.0.1:28080", "private-token",
                                           7, 1, "paused", binary)
            killpg.assert_called_once_with(1234, s6.signal.SIGTERM)
            self.assertEqual(2, process.waits)

    def test_publish_id_accepts_json_decimal_string_without_precision_loss(self):
        snowflake = "1902319203123456789"
        self.assertEqual(int(snowflake), s6.parse_errand_id(snowflake))
        self.assertEqual(7, s6.parse_errand_id(7))
        for bad in (True, 0, "0", "7.0", " 7", "9223372036854775808", None):
            with self.subTest(bad=bad), self.assertRaises(s6.Refused):
                s6.parse_errand_id(bad)

    def test_fault_window_unpauses_exact_target_after_workload_failure(self):
        state = {"paused": False}
        calls = []

        def inspect(_project, _service):
            return target(paused=state["paused"])

        def docker(*args, **_kwargs):
            calls.append(args)
            if args[:2] == ("docker", "pause"):
                state["paused"] = True
            elif args[:2] == ("docker", "unpause"):
                state["paused"] = False
            return ""

        with patch.object(s6, "production_stopped"), \
                patch.object(s6, "inspected_service", side_effect=inspect), \
                patch.object(s6, "command", side_effect=docker):
            with self.assertRaisesRegex(RuntimeError, "workload failed"):
                with s6.FaultWindow(PROJECT, "redis", CONTAINER_ID, lambda *_a, **_k: None):
                    raise RuntimeError("workload failed")

        self.assertFalse(state["paused"])
        self.assertEqual([("docker", "pause", CONTAINER_ID),
                          ("docker", "unpause", CONTAINER_ID)], calls)

    def test_first_unpause_failure_retries_before_watchdog_is_cancelled(self):
        state = {"paused": False}
        unpause_attempts = []

        def inspect(_project, _service):
            return target(paused=state["paused"])

        def docker(*args, **_kwargs):
            if args[:2] == ("docker", "pause"):
                state["paused"] = True
            elif args[:2] == ("docker", "unpause"):
                unpause_attempts.append(args[2])
                if len(unpause_attempts) == 1:
                    raise s6.Refused("temporary Docker unpause error")
                state["paused"] = False
            return ""

        with patch.object(s6, "production_stopped"), \
                patch.object(s6, "inspected_service", side_effect=inspect), \
                patch.object(s6, "command", side_effect=docker):
            with s6.FaultWindow(PROJECT, "rmqbroker", CONTAINER_ID,
                                lambda *_a, **_k: None, max_seconds=2) as window:
                self.assertTrue(state["paused"])
            self.assertEqual([CONTAINER_ID, CONTAINER_ID], unpause_attempts)
            self.assertFalse(state["paused"])
            self.assertFalse(window.watchdog_fired)

    def test_prepaused_or_replaced_target_cannot_be_injected(self):
        with patch.object(s6, "production_stopped"), \
                patch.object(s6, "inspected_service", return_value=target(paused=True)), \
                patch.object(s6, "command") as docker:
            with self.assertRaises(s6.Refused):
                with s6.FaultWindow(PROJECT, "rmqbroker", CONTAINER_ID, lambda *_a, **_k: None):
                    pass
            docker.assert_not_called()

    def test_prepaused_dependency_refuses_even_if_fault_target_is_healthy(self):
        with patch.object(s6, "inspected_service", side_effect=lambda _project, service:
                target(paused=(service == "worker"))):
            with self.assertRaisesRegex(s6.Refused, "worker"):
                s6.require_unpaused_services(PROJECT)

    def test_hard_deadline_restores_even_when_workload_does_not_exit(self):
        state = {"paused": False}

        def inspect(_project, _service):
            return target(paused=state["paused"])

        def docker(*args, **_kwargs):
            if args[:2] == ("docker", "pause"):
                state["paused"] = True
            elif args[:2] == ("docker", "unpause"):
                state["paused"] = False
            return ""

        with patch.object(s6, "production_stopped"), \
                patch.object(s6, "inspected_service", side_effect=inspect), \
                patch.object(s6, "command", side_effect=docker):
            with s6.FaultWindow(PROJECT, "redis", CONTAINER_ID,
                                lambda *_a, **_k: None, max_seconds=2) as window:
                time.sleep(1.2)
            self.assertTrue(window.watchdog_fired)
            self.assertFalse(state["paused"])

    def test_restoration_refuses_replacement_container(self):
        window = s6.FaultWindow(PROJECT, "redis", CONTAINER_ID, lambda *_a, **_k: None)
        window.attempted = True
        with patch.object(s6, "inspected_service", return_value={
                "Id": "b" * 64, "State": {"Paused": True, "Running": True}}), \
                patch.object(s6, "command") as docker:
            with self.assertRaisesRegex(s6.Refused, "identity changed"):
                window.restore()
            docker.assert_not_called()

    def test_invariants_reject_money_or_slot_drift(self):
        baseline = {"walletTotal": 110000, "debitMinusCredit": 0,
                    "systemSnapshotDiffs": 0, "escrowClosureDiffs": 0, "badSlotRows": 0}
        s6.assert_invariants(baseline, dict(baseline))
        for change in ({"walletTotal": 110001}, {"debitMinusCredit": 1},
                       {"systemSnapshotDiffs": 1}, {"escrowClosureDiffs": 1},
                       {"badSlotRows": 1}):
            with self.subTest(change=change), self.assertRaises(s6.Refused):
                s6.assert_invariants(baseline, {**baseline, **change})

    def test_fresh_stack_gate_rejects_existing_business_rows(self):
        with patch.object(s6, "one_row", return_value=["1", "0", "0", "0", "0", "0", "0", "0", "0"]), \
                patch.object(s6, "scalar") as scalar:
            with self.assertRaisesRegex(s6.Refused, "fresh disposable stack"):
                s6.fresh_stack(CONTAINER_ID)
            scalar.assert_not_called()

    def test_dry_run_does_not_create_running_manifest_or_inject(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(s6, "RUNS", Path(directory)), \
                patch.object(s6, "production_stopped"), \
                patch.object(s6.preflight, "check", return_value={"mysql_container_id": CONTAINER_ID}), \
                patch.object(s6, "inspected_service", return_value=target()), \
                patch.object(s6, "require_unpaused_services"), \
                patch.object(s6, "fresh_stack"), \
                patch.object(s6, "execute") as execute, \
                patch.object(s6.signal, "signal"), \
                patch.dict(s6.os.environ, {"PEERGRAB_BENCH_PROJECT": PROJECT,
                                           "COMPOSE_PROJECT_NAME": PROJECT}, clear=False), \
                patch.object(s6.sys, "argv", ["run_s6_fault_smoke.py", "--project", PROJECT,
                                              "--fault", "redis", "--detail-generator", "python"]):
            self.assertEqual(0, s6.main())
            self.assertEqual([], list(Path(directory).iterdir()))
            execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
