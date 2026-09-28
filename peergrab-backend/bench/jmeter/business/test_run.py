"""Privacy and result-gate tests for the JMeter business runner."""
import csv
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import run


class BusinessRunnerTest(unittest.TestCase):
    def test_cache_counters_accept_json_decimal_strings_only(self):
        self.assertEqual(run.json_count("4", "requests"), 4)
        self.assertEqual(run.json_count(0, "dbLoads"), 0)
        for invalid in (None, -1, True, "4.0", "-1", "1e3", "secret"):
            with self.assertRaises(RuntimeError):
                run.json_count(invalid, "requests")

    def test_jtl_accepts_only_bounded_business_outcomes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.jtl"
            fields = ["timeStamp", "elapsed", "label", "responseCode", "success", "peergrabOutcome"]
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields)
                writer.writeheader()
                writer.writerow(dict(timeStamp="1790000000000", elapsed="12", label="S1 grab",
                                     responseCode="200", success="true", peergrabOutcome="GRABBED"))
            parsed = run.parse_jtl(Path(directory))
            self.assertEqual(parsed[0]["responseMessage"], "GRABBED")
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields + ["URL"])
                writer.writeheader()
                writer.writerow(dict(timeStamp="1790000000000", elapsed="12", label="S1 grab",
                                     responseCode="200", success="true", peergrabOutcome="GRABBED",
                                     URL="http://example.invalid/secret"))
            with self.assertRaisesRegex(RuntimeError, "unsafe columns"):
                run.parse_jtl(Path(directory))

    def test_postcheck_never_follows_redirect_with_bearer(self):
        received = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                received.append((self.path, self.headers.get("Authorization")))
                self.send_response(302)
                self.send_header("Location", "/leak")
                self.end_headers()

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertRaisesRegex(RuntimeError, "redirect"):
                run.api(f"http://127.0.0.1:{server.server_address[1]}", "/start", "private-token")
            self.assertEqual(received, [("/start", "Bearer private-token")])
        finally:
            server.shutdown()
            server.server_close()

    def test_jmeter_process_does_not_inherit_benchmark_secrets(self):
        with patch.dict(os.environ, {"PEERGRAB_AUTH_JWT_SECRET": "private",
                                     "PEERGRAB_BENCH_TOKEN": "private",
                                     "HTTP_PROXY": "http://proxy.invalid:8080"}):
            env = run.clean_jmeter_env()
        self.assertNotIn("PEERGRAB_AUTH_JWT_SECRET", env)
        self.assertNotIn("PEERGRAB_BENCH_TOKEN", env)
        self.assertNotIn("HTTP_PROXY", env)


if __name__ == "__main__":
    unittest.main()
