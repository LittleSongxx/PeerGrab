"""Offline safety and accounting checks for the external S2 load generator."""

import argparse
import asyncio
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_remote_s2 as remote


IDENTITY = {"project": "peergrab-bench-test", "api": "http://127.0.0.1:38080",
            "mysql_container_id": "a" * 64,
            "services": sorted(remote.REQUIRED_SERVICES),
            "volumes": ["peergrab-bench-test_" + name for name in ("mysql", "redis", "rocketmq")]}


class NoNetworkTest(unittest.TestCase):
    def test_direct_mode_requires_exact_https_prefix_and_explicit_flag(self):
        with tempfile.TemporaryDirectory() as temporary:
            key = Path(temporary) / "key"
            key.write_text("fake")
            args = argparse.Namespace(project="peergrab-bench-test",
                                      remote_env=".env.bench.test",
                                      remote_backend="/data/peergrab-bench-test/peergrab-backend",
                                      ssh_host="root@127.0.0.1", key=key,
                                      warmup=1, sample=1, rates=(1,),
                                      direct=False, direct_base_url=None)
            args.ssh_host = None
            with self.assertRaisesRegex(remote.Refused, "ssh-host"):
                remote.validate_config(args)
            args.ssh_host = "-oProxyCommand=bad"
            with self.assertRaisesRegex(remote.Refused, "ssh-host"):
                remote.validate_config(args)
            args.ssh_host = "root@127.0.0.1"
            remote.validate_config(args)
            url = "https://www.peergrab.cn/__bench_" + "a" * 24 + "/"
            args.direct_base_url = url
            with self.assertRaisesRegex(remote.Refused, "together"):
                remote.validate_config(args)
            args.direct = True
            remote.validate_config(args)
            for invalid in ("http://" + url[8:], url + "api/errands",
                            url + "?x=1", url.replace("www.peergrab.cn", "peergrab.cn"),
                            url.replace("www.peergrab.cn", "www.peergrab.cn.evil.test"),
                            url.replace("www.peergrab.cn", "www.peergrab.cn:443"),
                            "https://www.peergrab.cn/__bench_short/"):
                args.direct_base_url = invalid
                with self.assertRaises(remote.Refused):
                    remote.validate_config(args)

    def test_direct_route_must_match_remote_benchmark_seed(self):
        seed = 930000000001
        payload = {"code": "OK", "data": [{"id": seed + i} for i in range(20)]}
        remote.verify_direct_list(payload, {seed + i for i in range(20)})
        remote.verify_direct_list({"code": "OK", "data": [{"id": str(seed + i)}
                                                          for i in range(20)]},
                                  {seed + i for i in range(20)})
        with self.assertRaisesRegex(remote.Refused, "isolated S2"):
            remote.verify_direct_list(payload, {seed + i for i in range(19)})
        with self.assertRaisesRegex(remote.Refused, "isolated S2"):
            remote.verify_direct_list({"code": "OK", "data": [{"id": i} for i in range(20)]},
                                      set(range(20)))

    def test_rejects_non_benchmark_project_and_remote_path_before_ssh(self):
        with tempfile.TemporaryDirectory() as temporary:
            key = Path(temporary) / "key"
            key.write_text("fake")
            args = argparse.Namespace(project="peergrab-local", remote_env=".env.bench.test",
                                      remote_backend="/data/peergrab-backend",
                                      ssh_host="root@127.0.0.1", key=key,
                                      warmup=1, sample=1, rates=(1,))
            with self.assertRaisesRegex(remote.Refused, "Project"):
                remote.validate_config(args)
            args.project = "peergrab-bench-test"
            args.remote_backend = "/data/../peergrab-backend"
            with self.assertRaisesRegex(remote.Refused, "Remote backend"):
                remote.validate_config(args)

    def test_remote_preflight_binds_tunnel_port_to_verified_identity(self):
        args = argparse.Namespace(project="peergrab-bench-test",
                                  remote_backend="/data/peergrab-bench-test/peergrab-backend",
                                  remote_env=".env.bench.test")
        with patch.object(remote, "ssh_read", return_value=json.dumps(IDENTITY)):
            result = remote.remote_preflight(args)
        self.assertEqual(result["api_port"], 38080)
        changed = {**IDENTITY, "project": "peergrab-local"}
        with patch.object(remote, "ssh_read", return_value=json.dumps(changed)):
            with self.assertRaisesRegex(remote.Refused, "invalid benchmark identity"):
                remote.remote_preflight(args)

    def test_password_is_never_in_preflight_command_and_seed_requires_count(self):
        args = argparse.Namespace(project="peergrab-bench-test",
                                  remote_backend="/data/peergrab-bench-test/peergrab-backend",
                                  remote_env=".env.bench.test")
        self.assertNotIn("printf '%s'", remote.remote_script(args))
        with patch.object(remote, "ssh_read", return_value="9999"):
            with self.assertRaisesRegex(remote.Refused, "10,000"):
                remote.verify_seed(args, IDENTITY)
        with patch.object(remote, "ssh_read", return_value="10000") as read:
            self.assertEqual(remote.verify_seed(args, IDENTITY), 10000)
            self.assertIn("PUBLISHED", read.call_args.args[1])

    def test_dry_run_never_starts_tunnel_login_or_load(self):
        with tempfile.TemporaryDirectory() as temporary:
            key = Path(temporary) / "key"
            key.write_text("fake")
            argv = ["--project", "peergrab-bench-test", "--remote-backend",
                    "/data/peergrab-bench-test/peergrab-backend", "--remote-env",
                    ".env.bench.test", "--ssh-host", "root@127.0.0.1",
                    "--key", str(key), "--dry-run"]
            with patch.object(remote, "remote_preflight", return_value={**IDENTITY, "api_port": 38080}), \
                    patch.object(remote, "verify_seed", return_value=10000), \
                    patch.object(remote, "start_tunnel") as tunnel, \
                    patch.object(remote, "demo_password") as password, \
                    patch.object(remote, "run") as run:
                self.assertEqual(remote.main(argv), 0)
                tunnel.assert_not_called()
                password.assert_not_called()
                run.assert_not_called()

    def test_tunnel_uses_verified_remote_loopback_port_only(self):
        class Process:
            def poll(self):
                return None

        class Connection:
            def __init__(self, host, port, timeout):
                self.host, self.port = host, port

            def request(self, method, path):
                self.method, self.path = method, path

            def getresponse(self):
                return argparse.Namespace(status=200, read=lambda _: b'{"code":"OK","data":{"status":"UP"}}')

            def close(self):
                pass

        args = argparse.Namespace(key=remote.KEY, ssh_host="root@127.0.0.1")
        with patch.object(remote, "local_port", return_value=39123), \
                patch.object(remote.subprocess, "Popen", return_value=Process()) as popen, \
                patch.object(remote.http.client, "HTTPConnection", Connection):
            _, local = remote.start_tunnel(args, 38080)
        self.assertEqual(local, 39123)
        command = popen.call_args.args[0]
        self.assertIn("127.0.0.1:39123:127.0.0.1:38080", command)
        self.assertNotIn("peergrab.cn", " ".join(command))

    def test_client_rejection_or_failed_attempt_stops_next_stage(self):
        self.assertTrue(remote.degraded({"offered": 5, "ok": 4, "schedulerMissed": 1}))
        self.assertFalse(remote.degraded({"offered": 2500, "sent": 2498, "ok": 2498,
                                          "schedulerMissed": 2}))
        self.assertTrue(remote.degraded({"offered": 2500, "sent": 2480, "ok": 2480,
                                         "schedulerMissed": 20}))
        self.assertTrue(remote.degraded({"offered": 5, "ok": 4, "http5xx": 1}))
        self.assertTrue(remote.degraded({"offered": 100, "ok": 100,
                                         "completedWithinWindow": 90}))
        self.assertFalse(remote.degraded({"offered": 5, "ok": 5}))


class FakeTunnel:
    def poll(self):
        return None


class FakeResponse:
    def __init__(self, status=200, code="OK"):
        self.status = status
        self.raw = ("{\"code\":\"" + code + "\"}").encode()
        self.content_length = len(self.raw)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return False

    async def read(self):
        return self.raw


class FakeSession:
    def __init__(self, status=200):
        self.status = status
        self.paths = []

    def get(self, url, **kwargs):
        self.paths.append(url)
        return FakeResponse(self.status)


class PhaseTest(unittest.IsolatedAsyncioTestCase):
    async def test_direct_mode_does_not_require_tunnel(self):
        session = FakeSession()
        result = await remote.run_phase(session, "https://www.peergrab.cn/__bench_" + "a" * 24,
                                        "secret", 5, 2, 10, None, [])
        self.assertEqual(result["offered"], 10)
        self.assertGreater(result.get("sent", 0), 0)
        self.assertEqual(result.get("ok", 0), result["sent"])
        self.assertEqual(result.get("networkErrors", 0), 0)
        self.assertTrue(all(url.startswith("https://www.peergrab.cn/__bench_")
                            for url in session.paths))

    async def test_fixed_arrival_counts_and_per_second_cpu_without_network(self):
        session = FakeSession()
        result = await remote.run_phase(session, "http://127.0.0.1:12345", "secret",
                                        2, 1, 2, FakeTunnel(), [])
        self.assertEqual(result["offered"], 2)
        self.assertEqual(result.get("sent", 0) + result.get("schedulerMissed", 0)
                         + result.get("capacityRejected", 0), 2)
        self.assertEqual(result.get("ok", 0), result.get("sent", 0))
        self.assertEqual(result["perSecond"][0]["offered"], 2)
        self.assertLessEqual(result["okWithinWindow"], result.get("sent", 0))
        self.assertEqual(result["okPerSecInWindow"], result["okWithinWindow"])
        self.assertIn("generatorCpuPercentOneCore", result["perSecond"][0])
        self.assertTrue(all(url.endswith(remote.LIST_PATH) for url in session.paths))

    async def test_http_5xx_is_separate_from_business_and_network_errors(self):
        result = await remote.run_phase(FakeSession(status=503), "http://127.0.0.1:12345",
                                        "secret", 1, 1, 1, FakeTunnel(), [], 5000)
        self.assertEqual(result["http5xx"], 1)
        self.assertEqual(result.get("businessErrors", 0), 0)
        self.assertEqual(result.get("networkErrors", 0), 0)
        self.assertIsNone(result["p99Ms"])
        self.assertIsNotNone(result["allAttemptP99Ms"])
        self.assertEqual(result["latencySamplesAllAttempts"], 1)
        self.assertEqual(result["timeoutMillis"], 5000)


if __name__ == "__main__":
    unittest.main()
