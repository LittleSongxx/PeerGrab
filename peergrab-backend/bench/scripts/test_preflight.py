"""No-Docker tests for the benchmark safety boundary."""
import io
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import preflight


PROJECT = "peergrab-bench-test"
URL = "http://127.0.0.1:38080"


def container(service, port=None, volume=None):
    labels = {
        preflight.PROJECT_LABEL: PROJECT,
        preflight.SERVICE_LABEL: service,
        preflight.BENCH_LABEL: "true",
        "com.docker.compose.project.config_files": str(preflight.COMPOSE_FILES[2]),
    }
    env = ["SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/peer_grab?x=y",
           "SPRING_DATA_REDIS_HOST=redis", "PEERGRAB_MQ_ENDPOINTS=rmqbroker:8081",
           "PEERGRAB_MQ_ENABLED=true"]
    return {
        "Id": service, "Config": {"Labels": labels, "Env": env},
        "State": {"Running": True},
        "NetworkSettings": {"Ports": port or {}, "Networks": {"default": {"NetworkID": "network-id"}}},
        "Mounts": ([{"Destination": volume, "Type": "volume", "Name": PROJECT + "_peergrab-" +
                    {"mysql": "mysql-data", "redis": "redis-data", "rmqbroker": "rmq-broker-store"}[service]}]
                   if volume else []),
    }


class Health(io.BytesIO):
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.containers = {
            "mysql": container("mysql", {"3306/tcp": [{"HostIp": "127.0.0.1", "HostPort": "33307"}]}, "/var/lib/mysql"),
            "redis": container("redis", {"6379/tcp": [{"HostIp": "127.0.0.1", "HostPort": "36380"}]}, "/data"),
            "rmqbroker": container("rmqbroker", {"8081/tcp": [{"HostIp": "127.0.0.1", "HostPort": "38081"}]}, "/home/rocketmq/store"),
            "rmqnamesrv": container("rmqnamesrv", {"9876/tcp": None}),
            "app": container("app", {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "38080"}]}),
            "worker": container("worker"),
        }
        one_shot = container("mq-init")
        one_shot["State"]["Running"] = False
        one_shot["NetworkSettings"]["Networks"] = {}
        self.containers["mq-init"] = one_shot
        env = {"PEERGRAB_BENCH_DISPOSABLE": "YES", "PEERGRAB_BENCH_PROJECT": PROJECT,
               "COMPOSE_PROJECT_NAME": PROJECT, "PEERGRAB_TEST_MQ_PORT": "38081"}
        self.env_patch = patch.dict(os.environ, env)
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)

    def fake_docker(self, *args):
        if args[:2] == ("ps", "-aq"):
            return "\n".join(self.containers)
        if args[:1] == ("exec",):
            return PROJECT
        raise AssertionError(args)

    def fake_inspect(self, kind, names):
        if kind == "container":
            return [self.containers[n] for n in names]
        if kind == "network":
            return [{"Name": PROJECT + "_default", "Labels": {preflight.PROJECT_LABEL: PROJECT}}]
        if kind == "volume":
            return [{"Labels": {preflight.PROJECT_LABEL: PROJECT, preflight.BENCH_LABEL: "true"}}]
        raise AssertionError(kind)

    def check(self, url=URL, mq_mode=None, scan_mode=None, confirm_seconds=None):
        with patch.object(preflight, "docker", self.fake_docker), \
             patch.object(preflight, "inspect", self.fake_inspect), \
             patch.object(preflight, "urlopen", return_value=Health(
                 b'{"code":"OK","data":{"status":"UP"}}')):
            return preflight.check(PROJECT, url, "127.0.0.1", "33307", mq_mode, scan_mode,
                                   confirm_seconds)

    def test_verified_stack_passes(self):
        result = self.check()
        self.assertEqual(result["project"], PROJECT)
        self.assertIsNone(result["worker_scan_interval_ms"])
        self.assertEqual(result["worker_scan_interval_source"], "not_exposed_by_container_env")

    def test_explicit_worker_scan_interval_reported(self):
        self.containers["worker"]["Config"]["Env"].append("PEERGRAB_TIMEOUT_SCAN_INTERVAL_MS=2500")
        result = self.check()
        self.assertEqual(result["worker_scan_interval_ms"], 2500)
        self.assertEqual(result["worker_scan_interval_source"], "worker_container_env")

    def test_wrong_api_port_refused(self):
        with self.assertRaisesRegex(preflight.Refused, "API URL"):
            self.check("http://127.0.0.1:25173")

    def test_shared_volume_refused(self):
        self.containers["mysql"]["Mounts"][0]["Name"] = "peergrab-prod_peergrab-mysql-data"
        with self.assertRaisesRegex(preflight.Refused, "Cross-project volume"):
            self.check()

    def test_public_domain_refused(self):
        with self.assertRaisesRegex(preflight.Refused, "host-local"):
            self.check("https://www.peergrab.cn")

    def test_mq_mode_mismatch_refused(self):
        with self.assertRaisesRegex(preflight.Refused, "MQ mode"):
            self.check(mq_mode="false")

    def test_timeout_scan_mode_mismatch_refused(self):
        self.containers["worker"]["Config"]["Env"].append("PEERGRAB_TIMEOUT_SCAN_ENABLED=true")
        with self.assertRaisesRegex(preflight.Refused, "timeout scan mode"):
            self.check(scan_mode="false")

    def test_confirmation_timeout_mismatch_refused(self):
        self.containers["worker"]["Config"]["Env"].append("PEERGRAB_TIMEOUT_CONFIRM_SECONDS=300")
        with self.assertRaisesRegex(preflight.Refused, "confirmation timeout"):
            self.check(confirm_seconds=5)
        self.check(confirm_seconds=300)


if __name__ == "__main__":
    unittest.main()
