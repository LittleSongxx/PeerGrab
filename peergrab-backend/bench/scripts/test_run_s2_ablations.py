"""No-network checks of S2 A/B/A guards and restoration."""

import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_s2_ablations as ablation


STACK = {"project": "peergrab-bench-test", "api": "http://127.0.0.1:38080",
         "mysql_container_id": "mysql-id", "volumes": ["peergrab-bench-test_mysql-data"]}
ENV = (b"COMPOSE_PROJECT_NAME=peergrab-bench-test\n"
       b"PEERGRAB_API_PORT=38080\nPEERGRAB_MYSQL_PORT=33307\n"
       b"PEERGRAB_RMQ_PROXY_PORT=38081\nPEERGRAB_MYSQL_PASSWORD=secret\n"
       b"PEERGRAB_BENCH_HIKARI_POOL_SIZE=20\n"
       b"PEERGRAB_BENCH_TOMCAT_THREADS_MAX=200\n")


class S2AblationTest(unittest.TestCase):
    def setup_env(self, directory):
        docker_dir = Path(directory)
        path = docker_dir / ".env.bench.test"
        path.write_bytes(ENV)
        path.chmod(0o600)
        return docker_dir, path

    def test_env_rejects_symlink_permissions_and_nonbench_project(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(ablation, "DOCKER_DIR", Path(temporary)):
            _, path = self.setup_env(temporary)
            self.assertEqual(ablation.private_env(path)[0], ENV)
            path.chmod(0o644)
            with self.assertRaisesRegex(ablation.Stopped, "0600"):
                ablation.private_env(path)
            path.chmod(0o600)
            alias = Path(temporary) / ".env.bench.alias"
            alias.symlink_to(path)
            with self.assertRaisesRegex(ablation.Stopped, "regular file"):
                ablation.private_env(alias)
            path.write_bytes(ENV.replace(b"peergrab-bench-test", b"peergrab-local"))
            with self.assertRaisesRegex(ablation.Stopped, "disposable benchmark"):
                ablation.private_env(path)

    def test_replacement_preserves_other_values_and_refuses_external_change(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(ablation, "DOCKER_DIR", Path(temporary)):
            _, path = self.setup_env(temporary)
            variant = ablation.setting_bytes(ENV, 8, 200)
            self.assertIn(b"PEERGRAB_BENCH_HIKARI_POOL_SIZE=8\n", variant)
            self.assertEqual(variant.replace(b"SIZE=8", b"SIZE=20"), ENV)
            ablation.restore_or_replace(path, ENV, variant)
            self.assertEqual(path.read_bytes(), variant)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaisesRegex(ablation.Stopped, "changed externally"):
                ablation.restore_or_replace(path, ENV, b"other")
            ablation.restore_or_replace(path, variant, ENV)
            self.assertEqual(path.read_bytes(), ENV)

    def test_compose_recreates_only_app_and_overrides_stale_shell_knobs(self):
        with patch.object(ablation.subprocess, "run") as run, \
                patch.dict(ablation.os.environ, {"PEERGRAB_BENCH_HIKARI_POOL_SIZE": "20"}):
            ablation.recreate_app(Path("/tmp/.env.bench.test"), "peergrab-bench-test", 8, 200,
                                  ablation.preflight.COMPOSE_FILES)
        command = run.call_args.args[0]
        self.assertEqual(command[-9:], ["up", "-d", "--no-deps", "--force-recreate", "--wait",
                                        "--no-build", "--pull", "never", "app"])
        self.assertEqual(run.call_args.kwargs["env"]["PEERGRAB_BENCH_HIKARI_POOL_SIZE"], "8")
        self.assertEqual(run.call_args.kwargs["env"]["PEERGRAB_BENCH_TOMCAT_THREADS_MAX"], "200")
        self.assertEqual(command[command.index("-p") + 1], "peergrab-bench-test")

    def test_relative_env_path_is_absolute_for_compose_and_restored_on_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            docker_dir = Path(temporary) / "docker"
            docker_dir.mkdir()
            _, path = self.setup_env(docker_dir)
            runs = Path(temporary) / "runs"
            relative_path = Path(os.path.relpath(path, Path.cwd()))
            self.assertFalse(relative_path.is_absolute())
            args = argparse.Namespace(env_file=relative_path, compose_overlay=None,
                                      dry_run=False, trials=2, rate=80,
                                      warm_round=True,
                                      public_health_url=ablation.first_round.DEFAULT_PUBLIC_HEALTH)
            containers = {"mysql": {"Id": "mysql-id"}, "app": {"Id": "app-id"},
                          "worker": {"Id": "worker-id"}}
            with patch.object(ablation, "DOCKER_DIR", docker_dir), \
                    patch.object(ablation, "RUNS_ROOT", runs), \
                    patch.object(ablation.first_round, "checked_stack", return_value=STACK), \
                    patch.object(ablation.first_round, "require_s2_seed", return_value=10000), \
                    patch.object(ablation.first_round, "public_health", return_value=0.1), \
                    patch.object(ablation.first_round, "check_memory", return_value=8_000_000_000), \
                    patch.object(ablation, "project_containers", return_value=containers), \
                    patch.object(ablation, "compose_files_for_project",
                                 return_value=ablation.preflight.COMPOSE_FILES), \
                    patch.object(ablation, "app_limits", return_value={"NanoCpus": 750_000_000,
                                                                     "Memory": 1_610_612_736}), \
                    patch.object(ablation, "verify_app", return_value="app-id"), \
                    patch.object(ablation, "recreate_app") as recreate, \
                    patch.object(ablation, "warm_ramp", return_value={"status": "PASS"}), \
                    patch.object(ablation, "one_measurement", return_value={
                        "status": "STOPPED", "abortReason": "synthetic resource abort"
                    }) as measure:
                with self.assertRaisesRegex(ablation.Stopped, "synthetic resource abort"):
                    ablation.run(args)
            self.assertEqual(path.read_bytes(), ENV)
            self.assertEqual(recreate.call_count, 2)
            self.assertEqual(recreate.call_args_list[0].args[0], path.resolve())
            self.assertEqual(recreate.call_args_list[-1].args[0], path.resolve())
            self.assertEqual(recreate.call_args_list[0].args[-3:-1], (20, 200))
            self.assertEqual(recreate.call_args_list[-1].args[-3:-1], (20, 200))
            measure.assert_called_once()
            manifest = json.loads(next(runs.glob("s2-ablations-*/manifest.json")).read_text())
            self.assertEqual(manifest["status"], "STOPPED")
            self.assertTrue(manifest["restoredOriginalEnvAndApp"])
            self.assertEqual(manifest["measurements"][0]["warmRound"], True)
            self.assertEqual(manifest["warmRamps"][0]["status"], "PASS")

    def test_cpu_delta_preserves_throttling_semantics(self):
        before = {"available": True, "usage_usec": 100, "nr_throttled": 3,
                  "throttled_usec": 40}
        after = {"available": True, "usage_usec": 350, "nr_throttled": 8,
                 "throttled_usec": 150}
        self.assertEqual(ablation.cpu_stat_delta(before, after), {
            "usage_usec": 250, "nr_throttled": 5, "throttled_usec": 110})
        self.assertIsNone(ablation.cpu_stat_delta({"available": False}, after))

    def test_live_extra_overlay_must_be_explicit_and_match_identity(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(ablation, "DOCKER_DIR", Path(temporary)), \
                patch.object(ablation.preflight, "COMPOSE_FILES", tuple(
                    Path(temporary) / name for name in ("base.yaml", "full.yaml", "bench.yaml"))):
            overlay = Path(temporary) / "docker-compose.bench.ecs.yaml"
            overlay.write_text("services: {}\n")
            files = (*ablation.preflight.COMPOSE_FILES, overlay)
            labels = {"com.docker.compose.project.config_files": ",".join(map(str, files))}
            containers = {name: {"Config": {"Labels": labels}} for name in ("app", "mysql")}
            with self.assertRaisesRegex(ablation.Stopped, "pass --compose-overlay"):
                ablation.compose_files_for_project(containers, None)
            self.assertEqual(ablation.compose_files_for_project(containers, overlay), files)
            wrong = Path(temporary) / "wrong.yaml"
            wrong.write_text("services: {}\n")
            with self.assertRaisesRegex(ablation.Stopped, "does not match"):
                ablation.compose_files_for_project(containers, wrong)

    def test_recreated_app_refuses_lost_cpu_or_memory_cap(self):
        app = {"Id": "new-app", "State": {"Running": True,
                                            "Health": {"Status": "healthy"}},
               "HostConfig": {"NanoCpus": 0, "Memory": 0,
                              "CpuQuota": 0, "CpuPeriod": 0},
               "Config": {"Env": ["SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=8",
                                  "SERVER_TOMCAT_THREADS_MAX=200"]}}
        with patch.object(ablation, "project_containers", return_value={"app": app, "mysql": {
                "Id": "mysql-id"}}):
            with self.assertRaisesRegex(ablation.Stopped, "limits changed"):
                ablation.verify_app("peergrab-bench-test", 8, 200, {"mysql": "mysql-id"},
                                    {"NanoCpus": 750_000_000, "Memory": 1_610_612_736,
                                     "CpuQuota": 0, "CpuPeriod": 0})

    def test_warm_ramp_uses_guarded_20_then_80_and_records_manifest(self):
        args = argparse.Namespace(public_health_url=ablation.first_round.DEFAULT_PUBLIC_HEALTH)
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(ablation, "RUNS_ROOT", Path(temporary)), \
                patch.object(ablation.first_round, "checked_stack", return_value=STACK), \
                patch.object(ablation, "verify_app", return_value="app-id"), \
                patch.object(ablation.first_round, "public_health", return_value=0.1), \
                patch.object(ablation.first_round, "check_memory", return_value=8_000_000_000):
            def guarded_round(first_round_args):
                self.assertEqual(first_round_args.rates, (20, 80))
                self.assertEqual(first_round_args.max_in_flight, 64)
                directory = Path(temporary) / "first-round-warm"
                directory.mkdir()
                (directory / "manifest.json").write_text(json.dumps({
                    "status": "PASS", "stages": [
                        {"offeredRps": 20, "runId": "20260926-120000", "status": "PASS"},
                        {"offeredRps": 80, "runId": "20260926-120001", "status": "PASS"},
                    ]}))

            with patch.object(ablation.first_round, "run", side_effect=guarded_round):
                result = ablation.warm_ramp(args, STACK["project"], 1, STACK,
                                           {"mysql": "mysql-id"}, 20, 200, 0.1,
                                           {"NanoCpus": 750_000_000})
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["maxInFlight"], 64)
            self.assertEqual([stage["offeredRps"] for stage in result["stages"]], [20, 80])
            self.assertEqual(Path(result["firstRoundManifest"]).name, "manifest.json")

    def test_selected_rate_uses_64_inflight_and_records_matching_resource_path(self):
        status = {name: 1 for name in ablation.STATUS_NAMES}
        containers = {"app": {"HostConfig": {"NanoCpus": 750_000_000,
                                               "CpuQuota": 0, "CpuPeriod": 0}}}
        for rate in (80, 160):
            with self.subTest(rate=rate):
                args = argparse.Namespace(rate=rate,
                                          public_health_url=ablation.first_round.DEFAULT_PUBLIC_HEALTH)
                with tempfile.TemporaryDirectory() as temporary, \
                        patch.object(ablation, "RUNS_ROOT", Path(temporary)), \
                        patch.object(ablation.first_round, "checked_stack", return_value=STACK), \
                        patch.object(ablation, "verify_app", return_value="app-id"), \
                        patch.object(ablation, "project_containers", return_value=containers), \
                        patch.object(ablation.first_round, "public_health", return_value=0.1), \
                        patch.object(ablation.first_round, "check_memory", return_value=8_000_000_000), \
                        patch.object(ablation, "database_status", return_value=status), \
                        patch.object(ablation, "app_cpu_stat", return_value={"available": False}):
                    def guarded_round(first_round_args):
                        self.assertEqual(first_round_args.rates, (rate,))
                        self.assertEqual(first_round_args.max_in_flight, 64)
                        directory = Path(temporary) / f"first-round-{rate}"
                        resources = directory / f"stage-{rate:02d}rps" / "resources.jsonl"
                        resources.parent.mkdir(parents=True)
                        resources.write_text("{}\n")
                        (directory / "manifest.json").write_text(json.dumps({
                            "status": "PASS", "stages": [{"offeredRps": rate,
                                                           "runId": "20260926-120002",
                                                           "status": "PASS"}]}))

                    with patch.object(ablation.first_round, "run", side_effect=guarded_round):
                        result = ablation.one_measurement(args, Path(temporary), STACK["project"],
                                                          1, 1, STACK, {}, 20, 200, 0.1,
                                                          {"NanoCpus": 750_000_000})
                self.assertEqual(result["status"], "PASS")
                self.assertEqual(result["offeredRps"], rate)
                self.assertEqual(result["maxInFlight"], 64)
                self.assertTrue(result["resourcesJsonl"].endswith(
                    f"stage-{rate:02d}rps/resources.jsonl"))

    def test_failed_warm_ramp_stops_before_measured_rate_and_restores_app(self):
        with tempfile.TemporaryDirectory() as temporary:
            docker_dir = Path(temporary) / "docker"
            docker_dir.mkdir()
            _, path = self.setup_env(docker_dir)
            runs = Path(temporary) / "runs"
            args = argparse.Namespace(env_file=path, compose_overlay=None, dry_run=False,
                                      trials=2, warm_round=True, rate=80,
                                      public_health_url=ablation.first_round.DEFAULT_PUBLIC_HEALTH)
            containers = {"mysql": {"Id": "mysql-id"}, "app": {"Id": "app-id"},
                          "worker": {"Id": "worker-id"}}
            with patch.object(ablation, "DOCKER_DIR", docker_dir), \
                    patch.object(ablation, "RUNS_ROOT", runs), \
                    patch.object(ablation.first_round, "checked_stack", return_value=STACK), \
                    patch.object(ablation.first_round, "require_s2_seed", return_value=10000), \
                    patch.object(ablation.first_round, "public_health", return_value=0.1), \
                    patch.object(ablation.first_round, "check_memory", return_value=8_000_000_000), \
                    patch.object(ablation, "project_containers", return_value=containers), \
                    patch.object(ablation, "compose_files_for_project",
                                 return_value=ablation.preflight.COMPOSE_FILES), \
                    patch.object(ablation, "app_limits", return_value={"NanoCpus": 750_000_000}), \
                    patch.object(ablation, "verify_app", return_value="app-id"), \
                    patch.object(ablation, "recreate_app") as recreate, \
                    patch.object(ablation, "warm_ramp", return_value={
                        "status": "STOPPED", "abortReason": "80 RPS failed"
                    }), \
                    patch.object(ablation, "one_measurement") as measure:
                with self.assertRaisesRegex(ablation.Stopped, "80 RPS failed"):
                    ablation.run(args)
            measure.assert_not_called()
            self.assertEqual(recreate.call_count, 2)
            self.assertEqual(path.read_bytes(), ENV)
            manifest = json.loads(next(runs.glob("s2-ablations-*/manifest.json")).read_text())
            self.assertEqual(manifest["status"], "STOPPED")
            self.assertEqual(manifest["warmRamps"][0]["abortReason"], "80 RPS failed")
            self.assertTrue(manifest["restoredOriginalEnvAndApp"])


if __name__ == "__main__":
    unittest.main()
