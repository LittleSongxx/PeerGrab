"""Offline safety checks; they never open the ECS benchmark route."""

import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_s2_jmeter as bench


ARGS = ["--project", "peergrab-bench-test", "--remote-backend",
        "/data/peergrab-bench-test/peergrab-backend", "--remote-env", ".env.bench.test",
        "--direct-base-url", "https://www.peergrab.cn/__bench_" + "a" * 24 + "/"]


class JMeterS2SafetyTest(unittest.TestCase):
    def test_default_checks_route_but_never_loads(self):
        with patch.object(bench.guard, "validate_config"), \
                patch.object(bench.guard, "remote_preflight", return_value={"project": "peergrab-bench-test"}), \
                patch.object(bench.guard, "verify_seed", return_value=10000), \
                patch.object(bench, "check_route") as route, \
                patch.object(bench, "run_jmeter") as load:
            self.assertEqual(bench.main(ARGS), 0)
            route.assert_called_once()
            load.assert_not_called()

    def test_execute_requires_confirmation_before_remote_access(self):
        with patch.object(bench.guard, "validate_config"), \
                patch.object(bench.guard, "remote_preflight") as remote:
            self.assertEqual(bench.main(ARGS + ["--execute"]), 1)
            remote.assert_not_called()

    def test_private_token_only_in_child_environment(self):
        with patch.dict(os.environ, {"HTTP_PROXY": "http://wrong.proxy",
                                     "JAVA_TOOL_OPTIONS": "-Dhttps.proxyHost=wrong"}):
            environment = bench.clean_env("secret-token", "/__bench_" + "a" * 24, 1024)
        self.assertEqual(environment["PEERGRAB_BENCH_TOKEN"], "secret-token")
        self.assertNotIn("HTTP_PROXY", environment)
        self.assertNotIn("JAVA_TOOL_OPTIONS", environment)
        self.assertEqual(environment["HEAP"], "-Xms512m -Xmx1024m")

    def test_stock_plan_is_read_only_and_does_not_embed_credentials(self):
        root = ET.parse(bench.PLAN).getroot()
        groups = root.findall(".//OpenModelThreadGroup")
        self.assertEqual(len(groups), 1)
        samplers = root.findall(".//HTTPSamplerProxy")
        self.assertEqual(len(samplers), 1)
        props = {node.attrib["name"]: node.text for node in samplers[0].iter("stringProp")}
        self.assertEqual(props["HTTPSampler.method"], "GET")
        self.assertIn("/api/errands?campusId=1&cursor=&size=20",
                      props["HTTPSampler.path"])
        self.assertNotIn("secret-token", bench.PLAN.read_text())
        self.assertNotIn("response_message=true", bench.PROPERTIES.read_text())

    def test_degraded_includes_assertion_and_generator_pacing(self):
        clean = {"failedSamples": 0, "outsideScheduledWindowBeyond100Ms": 0,
                 "arrivalCountCheck": {"grossUnderproduction": False},
                 "arrivalPacing": {"badSeconds": []}}
        self.assertFalse(bench.degraded(clean))
        clean["failedSamples"] = 1
        self.assertTrue(bench.degraded(clean))
        clean["failedSamples"] = 0
        clean["arrivalPacing"]["badSeconds"] = [3]
        self.assertTrue(bench.degraded(clean))


if __name__ == "__main__":
    unittest.main()
