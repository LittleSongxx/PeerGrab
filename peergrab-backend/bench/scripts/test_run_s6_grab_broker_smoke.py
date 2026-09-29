import io
import json
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import URLError

import run_s6_grab_broker_smoke as grab_smoke


PROJECT = "peergrab-bench-grabtest"
CONTAINER_ID = "a" * 64


class GrabBrokerSmokeSafetyTest(unittest.TestCase):
    def test_grab_probe_refuses_nonlocal_origin_before_sending_token(self):
        with patch.object(grab_smoke.s6.DIRECT_HTTP, "open") as opened:
            with self.assertRaises(grab_smoke.s6.Refused):
                grab_smoke.grab_probe("https://example.com", "private-jwt", 7,
                                       "00000000-0000-0000-0000-000000000000")
            opened.assert_not_called()

    def test_grab_probe_records_timeout_without_token_or_response_body(self):
        with patch.object(grab_smoke.s6.DIRECT_HTTP, "open",
                          side_effect=URLError(socket.timeout("timed out"))):
            result = grab_smoke.grab_probe("http://127.0.0.1:28080", "private-jwt", 7,
                                             "00000000-0000-0000-0000-000000000000")
        self.assertEqual("transport_timeout", result["outcome"])
        self.assertNotIn("private-jwt", json.dumps(result))
        self.assertEqual(8, result["clientTimeoutSeconds"])

    def test_grab_probe_accepts_only_confirmed_grab_success(self):
        class Response(io.BytesIO):
            status = 200

        def returned(payload):
            return Response(json.dumps(payload).encode())

        request_id = "00000000-0000-0000-0000-000000000000"
        with patch.object(grab_smoke.s6.DIRECT_HTTP, "open",
                          return_value=returned({"code": "OK", "data": {"grabbed": False}})):
            rejected = grab_smoke.grab_probe("http://127.0.0.1:28080", "jwt", 7, request_id)
        with patch.object(grab_smoke.s6.DIRECT_HTTP, "open",
                          return_value=returned({"code": "OK", "data": {"grabbed": True}})):
            accepted = grab_smoke.grab_probe("http://127.0.0.1:28080", "jwt", 7, request_id)
        self.assertEqual("application_error", rejected["outcome"])
        self.assertEqual("ok", accepted["outcome"])

    def test_runtime_guard_rejects_header_identity_and_short_deadline(self):
        def service(name, env):
            return {"Id": name, "Config": {"Env": env}}

        app = service("app", ["PEERGRAB_AUTH_MODE=jwt",
                              "PEERGRAB_AUTH_ALLOW_HEADER_IDENTITY=true",
                              "PEERGRAB_TIMEOUT_CONFIRM_SECONDS=300"])
        worker = service("worker", ["PEERGRAB_TIMEOUT_CONFIRM_SECONDS=300"])
        with patch.object(grab_smoke.s6, "inspected_service",
                          side_effect=lambda _project, name: {"app": app, "worker": worker}[name]):
            with self.assertRaisesRegex(grab_smoke.s6.Refused, "header identity"):
                grab_smoke.runtime_config(PROJECT, 30, 180)
            app["Config"]["Env"][1] = "PEERGRAB_AUTH_ALLOW_HEADER_IDENTITY=false"
            app["Config"]["Env"][2] = "PEERGRAB_TIMEOUT_CONFIRM_SECONDS=100"
            with self.assertRaisesRegex(grab_smoke.s6.Refused, "deadline"):
                grab_smoke.runtime_config(PROJECT, 30, 180)

    def test_durable_state_requires_single_winner_and_held_escrow(self):
        correct = {"status": "LOCKED", "slotTotal": 1, "slotTaken": 1,
                   "grabberId": 2001, "round": 0, "grabbedRecords": 1,
                   "escrowStatus": "HELD", "escrowLedgerLegs": 2}
        grab_smoke.assert_durable_grab(correct)
        for changed in ({"slotTaken": 2}, {"grabbedRecords": 2},
                        {"escrowStatus": "RELEASED"}, {"escrowLedgerLegs": 1},
                        {"grabberId": 2002}):
            with self.subTest(changed=changed), self.assertRaises(grab_smoke.s6.Refused):
                grab_smoke.assert_durable_grab({**correct, **changed})

    def test_timeout_outbox_requires_one_message_on_expected_topic(self):
        valid = "PENDING\terrand-confirm-timeout\t0"
        with patch.object(grab_smoke.s6, "mysql", return_value=valid):
            self.assertEqual("PENDING", grab_smoke.timeout_message("mysql-id", 7)["status"])
        for bad in (valid + "\n" + valid, "PENDING\twrong-topic\t0", ""):
            with self.subTest(bad=bad), patch.object(grab_smoke.s6, "mysql", return_value=bad):
                with self.assertRaises(grab_smoke.s6.Refused):
                    grab_smoke.timeout_message("mysql-id", 7)

    def test_execute_gate_prevents_workload_without_ecs_opt_in(self):
        broker = {"Id": CONTAINER_ID, "State": {"Running": True, "Paused": False}}
        stack = {"mysql_container_id": "mysql-id"}
        environment = {"PEERGRAB_BENCH_PROJECT": PROJECT, "COMPOSE_PROJECT_NAME": PROJECT,
                       "PEERGRAB_BENCH_ECS_MAINTENANCE": "NO"}
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(grab_smoke.os.environ, environment, clear=False), \
                patch.object(grab_smoke, "RUNS", Path(directory)), \
                patch.object(grab_smoke.signal, "signal"), \
                patch.object(grab_smoke.s6, "production_stopped"), \
                patch.object(grab_smoke.preflight, "check", return_value=stack), \
                patch.object(grab_smoke.s6, "require_unpaused_services"), \
                patch.object(grab_smoke, "runtime_config"), \
                patch.object(grab_smoke.s6, "fresh_stack"), \
                patch.object(grab_smoke.s6, "inspected_service", return_value=broker), \
                patch.object(grab_smoke, "execute") as execute:
            code = grab_smoke.main(["--project", PROJECT, "--execute",
                                    "--confirm-project", PROJECT])
        self.assertEqual(1, code)
        execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
