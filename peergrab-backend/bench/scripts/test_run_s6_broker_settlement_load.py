"""Offline checks for the maintenance-only Broker settlement load entry."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

import run_s6_broker_settlement_load as probe


PROJECT = "peergrab-bench-s6broker-test"
CONTAINER_ID = "a" * 64


class BrokerSettlementLoadTest(unittest.TestCase):
    def test_request_distinguishes_success_and_business_failure(self):
        seen = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                seen.append((self.path, self.headers.get("Authorization")))
                body = ({"code": "OK", "data": {"result": "SETTLED"}}
                        if self.path.endswith("/1/settle") else
                        {"code": "CONFLICT", "data": None})
                encoded = json.dumps(body).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def log_message(self, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            with patch.dict(os.environ, {"HTTP_PROXY": "http://example.invalid",
                                         "ALL_PROXY": "http://example.invalid"}, clear=False):
                ok = probe.settlement_request(base, "secret", 1, 2)
                failed = probe.settlement_request(base, "secret", 2, 2)
            self.assertEqual("ok", ok["outcome"])
            self.assertEqual("business_error", failed["outcome"])
            self.assertEqual("CONFLICT", failed["businessCode"])
            self.assertEqual([("/api/errands/1/settle", "Bearer secret"),
                              ("/api/errands/2/settle", "Bearer secret")], seen)
            self.assertNotIn("secret", json.dumps((ok, failed)))
            with self.assertRaises(probe.s6.Refused):
                probe.settlement_request("https://example.com", "secret", 1, 2)
        finally:
            server.shutdown()
            server.server_close()

    def test_rate_phase_has_explicit_denominators(self):
        def response(_base, _token, task_id, _timeout):
            return {"errandId": task_id, "outcome": "ok", "httpStatus": 200,
                    "businessCode": "OK", "latencyMs": 3.0}

        with patch.object(probe, "settlement_request", side_effect=response):
            result = probe.fixed_arrival_phase("http://127.0.0.1:1", "secret", [1, 2], 2, 1, 1)
        self.assertEqual(2, result["offered"])
        self.assertEqual(2, result["sent"])
        self.assertEqual(2, result["completed"])
        self.assertEqual(0, result["schedulerMissed"])
        self.assertEqual(0, result["localRejected"])
        self.assertEqual(2, result["outcomes"]["ok"])
        self.assertEqual(3.0, result["p99Ms"])

    def test_recovery_requires_every_task_and_two_notifications(self):
        good = {1: {"outboxStatus": "SENT", "notificationCount": 2},
                2: {"outboxStatus": "SENT", "notificationCount": 2}}
        self.assertTrue(probe.check_recovery(good, [1, 2]))
        self.assertFalse(probe.check_recovery({1: good[1]}, [1, 2]))
        self.assertFalse(probe.check_recovery({1: good[1], 2: {"outboxStatus": "PENDING",
                                                             "notificationCount": 2}}, [1, 2]))
        self.assertFalse(probe.check_recovery({1: good[1], 2: {"outboxStatus": "SENT",
                                                             "notificationCount": 1}}, [1, 2]))

    def test_dry_run_is_read_only_and_does_not_create_manifest(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(probe, "RUNS", Path(directory)), \
                patch.object(probe.s6, "production_stopped"), \
                patch.object(probe.preflight, "check",
                             return_value={"mysql_container_id": CONTAINER_ID}), \
                patch.object(probe.s6, "require_unpaused_services"), \
                patch.object(probe.s6, "fresh_stack"), \
                patch.object(probe, "execute") as execute, \
                patch.object(probe.signal, "signal"), \
                patch.dict(probe.os.environ, {"PEERGRAB_BENCH_PROJECT": PROJECT,
                                             "COMPOSE_PROJECT_NAME": PROJECT}, clear=False), \
                patch.object(sys, "argv", ["probe", "--project", PROJECT]):
            self.assertEqual(0, probe.main())
            self.assertEqual([], list(Path(directory).iterdir()))
            execute.assert_not_called()

    def test_execute_confirmation_precedes_business_writes(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(probe, "RUNS", Path(directory)), \
                patch.object(probe.s6, "production_stopped"), \
                patch.object(probe.preflight, "check",
                             return_value={"mysql_container_id": CONTAINER_ID}), \
                patch.object(probe.s6, "require_unpaused_services"), \
                patch.object(probe.s6, "fresh_stack"), \
                patch.object(probe, "execute") as execute, \
                patch.object(probe.signal, "signal"), \
                patch.dict(probe.os.environ, {"PEERGRAB_BENCH_PROJECT": PROJECT,
                                             "COMPOSE_PROJECT_NAME": PROJECT}, clear=False), \
                patch.object(sys, "argv", ["probe", "--project", PROJECT, "--execute"]):
            self.assertEqual(1, probe.main())
            execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
