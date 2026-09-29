"""Offline safety checks for same-host S2; no ECS or load access."""

import os
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_s2_jmeter_ecs as bench


PROJECT = "peergrab-bench-ecs-test"
IDS = set(range(930000000001, 930000000021))


class EcsS2Test(unittest.TestCase):
    def test_execute_confirmation_precedes_stack_access(self):
        with patch.object(bench, "checked_stack") as stack:
            self.assertEqual(1, bench.main(["--project", PROJECT, "--execute"]))
            stack.assert_not_called()

    def test_dry_run_never_starts_jmeter(self):
        identity = {"mysql_container_id": "a" * 64, "api": "http://127.0.0.1:28080"}
        with patch.object(bench, "checked_stack", return_value=identity), \
                patch.object(bench, "seed_ids", return_value=(10000, IDS)), \
                patch.object(bench.s6, "login", return_value="private-token"), \
                patch.object(bench, "route_probe") as route, \
                patch.object(bench, "run_round") as run:
            self.assertEqual(0, bench.main(["--project", PROJECT]))
            route.assert_called_once()
            run.assert_not_called()

    def test_https_mode_requires_exact_route_and_disallows_ambient_proxy(self):
        args = bench.parse_args(["--project", PROJECT, "--transport", "local-https"])
        with self.assertRaisesRegex(bench.s6.Refused, "exact"):
            bench.validate(args)
        args.https_route = "/__bench_" + "a" * 32 + "/"
        bench.validate(args)
        with patch.dict(os.environ, {"HTTP_PROXY": "http://wrong.invalid",
                                     "JAVA_TOOL_OPTIONS": "-Dhttps.proxyHost=wrong.invalid",
                                     "PEERGRAB_AUTH_DEMO_PASSWORD_1001": "private-password"}):
            environment = bench.clean_env("private-token", args.https_route.rstrip("/"),
                                          1024, Path("/data/bench/jdk-hosts"))
        self.assertNotIn("HTTP_PROXY", environment)
        self.assertNotIn("JAVA_TOOL_OPTIONS", environment)
        self.assertNotIn("PEERGRAB_AUTH_DEMO_PASSWORD_1001", environment)
        self.assertEqual("-Djdk.net.hosts.file=/data/bench/jdk-hosts",
                         environment["JVM_ARGS"])
        self.assertEqual("private-token", environment["PEERGRAB_BENCH_TOKEN"])

    def test_page_must_be_from_verified_seed_ids(self):
        rows = [{"id": str(value)} for value in sorted(IDS)]
        good = {"code": "OK", "data": {"items": rows, "nextCursor": "opaque"}}
        bench.verify_page(good, IDS)
        with self.assertRaises(bench.s6.Refused):
            bench.verify_page(None, IDS)
        with self.assertRaises(bench.s6.Refused):
            bench.verify_page({"code": "OK", "data": {"items": rows[:-1],
                                                        "nextCursor": "opaque"}}, IDS)
        with self.assertRaises(bench.s6.Refused):
            bench.verify_page(good, {1, 2, 3})

    def test_https_probe_pins_tcp_to_loopback_and_keeps_domain_sni(self):
        raw = MagicMock()
        raw.__enter__.return_value = raw
        secure = MagicMock()
        secure.__enter__.return_value = secure
        context = MagicMock()
        context.wrap_socket.return_value = secure
        response = MagicMock()
        response.status = 200
        response.read.return_value = b'{"code":"OK"}'
        with patch.object(bench.socket, "create_connection", return_value=raw) as connect, \
                patch.object(bench.ssl, "create_default_context", return_value=context), \
                patch.object(bench.http.client, "HTTPResponse", return_value=response):
            self.assertEqual({"code": "OK"},
                             bench.local_https_json("/__bench_safe/api/errands", "private-token"))
        connect.assert_called_once_with(("127.0.0.1", 443), timeout=8)
        context.wrap_socket.assert_called_once_with(raw, server_hostname="www.peergrab.cn")
        payload = secure.sendall.call_args.args[0]
        self.assertIn(b"Host: www.peergrab.cn\r\n", payload)
        self.assertIn(b"GET /__bench_safe/api/errands HTTP/1.1", payload)

    def test_jmx_is_read_only_and_uses_environment_token(self):
        root = ET.parse(bench.PLAN).getroot()
        samplers = root.findall(".//HTTPSamplerProxy")
        self.assertEqual(1, len(samplers))
        values = {item.attrib.get("name"): item.text for item in samplers[0].iter("stringProp")}
        self.assertEqual("GET", values["HTTPSampler.method"])
        self.assertIn("PEERGRAB_BENCH_PREFIX", values["HTTPSampler.path"])
        self.assertIn("invalid.invalid", values["HTTPSampler.domain"])


if __name__ == "__main__":
    unittest.main()
