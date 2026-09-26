"""No-network tests for the maintenance-only S2 variant switch."""

import argparse
from contextlib import ExitStack
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_s2_ablations as ablation
import set_s2_near_knee_variant as switch


ENV = (b"COMPOSE_PROJECT_NAME=peergrab-bench-test\n"
       b"PEERGRAB_API_PORT=38080\nPEERGRAB_MYSQL_PORT=33307\n"
       b"PEERGRAB_RMQ_PROXY_PORT=38081\nPEERGRAB_MYSQL_PASSWORD=secret\n"
       b"PEERGRAB_BENCH_HIKARI_POOL_SIZE=20\n"
       b"PEERGRAB_BENCH_TOMCAT_THREADS_MAX=200\n")
STACK = {"project": "peergrab-bench-test", "api": "http://127.0.0.1:38080",
         "mysql_container_id": "mysql-id", "volumes": ["peergrab-bench-test_mysql-data"]}
CONTAINERS = {"mysql": {"Id": "mysql-id"}, "app": {"Id": "app-id"},
              "worker": {"Id": "worker-id"}}


class SwitchTest(unittest.TestCase):
    def fixture(self, root, prior=ENV):
        docker = Path(root) / "docker"
        docker.mkdir()
        env = docker / ".env.bench.test"
        env.write_bytes(prior)
        env.chmod(0o600)
        overlay = docker / "docker-compose.bench.max.yaml"
        overlay.write_text("services: {}\n")
        args = argparse.Namespace(env_file=env, compose_overlay=overlay,
                                  variant="hikari8", dry_run=False)
        return docker, env, overlay, args

    def mocks(self, stack, docker, overlay):
        stack.enter_context(patch.object(ablation, "DOCKER_DIR", docker))
        stack.enter_context(patch.object(switch, "require_maintenance"))
        stack.enter_context(patch.object(ablation.first_round, "checked_stack", return_value=STACK))
        stack.enter_context(patch.object(ablation.first_round, "require_s2_seed", return_value=10000))
        stack.enter_context(patch.object(ablation.first_round, "check_memory", return_value=8_000_000_000))
        stack.enter_context(patch.object(ablation.first_round, "public_health",
                                   side_effect=AssertionError("public health must not be used")))
        stack.enter_context(patch.object(ablation, "project_containers", return_value=CONTAINERS))
        stack.enter_context(patch.object(ablation, "compose_files_for_project",
                                   return_value=(*ablation.preflight.COMPOSE_FILES, overlay)))
        stack.enter_context(patch.object(ablation, "app_limits", return_value={
            "NanoCpus": 0, "Memory": 0, "CpuQuota": 0, "CpuPeriod": 0}))
        stack.enter_context(patch.object(ablation, "verify_app", return_value="app-id"))
        return stack.enter_context(patch.object(ablation, "recreate_app"))

    def test_requires_approved_maintenance_and_stopped_production(self):
        with patch.dict(switch.os.environ, {}, clear=True):
            with self.assertRaisesRegex(ablation.Stopped, "maintenance opt-in"):
                switch.require_maintenance()
        with patch.dict(switch.os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": "YES"}), \
                patch.object(ablation.preflight, "docker", return_value="running-id"):
            with self.assertRaisesRegex(ablation.Stopped, "production containers"):
                switch.require_maintenance()
        with patch.dict(switch.os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": "YES"}), \
                patch.object(ablation.preflight, "docker", return_value=""):
            switch.require_maintenance()

    def test_switches_only_app_and_keeps_env_other_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            docker, env, overlay, args = self.fixture(root)
            with ExitStack() as stack:
                recreate = self.mocks(stack, docker, overlay)
                report = switch.change(args)
            self.assertTrue(report["changed"])
            self.assertEqual(report["fromVariant"], "baseline")
            self.assertEqual(report["toVariant"], "hikari8")
            self.assertEqual(env.read_bytes(), ablation.setting_bytes(ENV, 8, 200))
            recreate.assert_called_once()
            self.assertEqual(recreate.call_args.args[2:4], (8, 200))
            self.assertEqual(recreate.call_args.args[-1][-1], overlay)

    def test_recreate_failure_restores_original_env_and_app(self):
        with tempfile.TemporaryDirectory() as root:
            docker, env, overlay, args = self.fixture(root)
            with ExitStack() as stack:
                recreate = self.mocks(stack, docker, overlay)
                recreate.side_effect = [RuntimeError("synthetic startup failure"), None]
                with self.assertRaisesRegex(ablation.Stopped, "original app restored"):
                    switch.change(args)
            self.assertEqual(env.read_bytes(), ENV)
            self.assertEqual([call.args[2:4] for call in recreate.call_args_list],
                             [(8, 200), (20, 200)])

    def test_baseline_noop_and_unsupported_initial_pair(self):
        with tempfile.TemporaryDirectory() as root:
            docker, env, overlay, args = self.fixture(root)
            args.variant = "baseline"
            with ExitStack() as stack:
                recreate = self.mocks(stack, docker, overlay)
                report = switch.change(args)
            self.assertFalse(report["changed"])
            recreate.assert_not_called()
            env.write_bytes(ablation.setting_bytes(ENV, 12, 200))
            with ExitStack() as stack:
                self.mocks(stack, docker, overlay)
                with self.assertRaisesRegex(ablation.Stopped, "not a supported variant"):
                    switch.change(args)


if __name__ == "__main__":
    unittest.main()
