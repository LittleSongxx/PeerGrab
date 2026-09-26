"""No-network checks for the temporary isolated full-path frontend guard."""

import argparse
import copy
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import start_s2_fullpath_frontend as frontend


ENV = (b"COMPOSE_PROJECT_NAME=peergrab-bench-test\n"
       b"PEERGRAB_API_PORT=38080\nPEERGRAB_MYSQL_PORT=33307\n"
       b"PEERGRAB_RMQ_PROXY_PORT=38081\nPEERGRAB_MYSQL_PASSWORD=secret\n"
       b"PEERGRAB_BENCH_HIKARI_POOL_SIZE=20\n"
       b"PEERGRAB_BENCH_TOMCAT_THREADS_MAX=200\n")
OVERLAY = b"# isolated max stack\nservices:\n  app:\n    image: peergrab-prod-app:latest\n  worker:\n    image: peergrab-prod-worker:latest\n"
PROJECT = "peergrab-bench-test"
STACK = {"project": PROJECT, "api": "http://127.0.0.1:38080", "mysql_container_id": "mysql-id",
         "volumes": [PROJECT + "_peergrab-mysql-data", PROJECT + "_peergrab-redis-data",
                     PROJECT + "_peergrab-rmq-broker-store"]}
CONTAINERS = {name: {"Id": name + "-id"} for name in ("app", "mysql", "worker")}


def models():
    old_front = {"profiles": ["web"], "ports": [],
                 "labels": {frontend.preflight.BENCH_LABEL: "true"},
                 "depends_on": {"app": {"condition": "service_healthy"}},
                 "build": {"context": "/tmp/frontend"}}
    before = {"name": PROJECT, "services": {"app": {"image": "app"},
             "mysql": {"image": "mysql"}, "worker": {"image": "worker"},
             "frontend": old_front}, "volumes": {"peergrab-mysql-data": {"name": "bench"}}}
    after = copy.deepcopy(before)
    after["services"]["frontend"].update({
        "image": frontend.FRONTEND_IMAGE,
        "ports": [{"host_ip": "127.0.0.1", "published": "38082", "target": 80,
                   "protocol": "tcp"}], "restart": "no"})
    del after["services"]["frontend"]["build"]
    return before, after


class FullPathFrontendTest(unittest.TestCase):
    def test_overlay_amendment_is_narrow_and_rejects_existing_frontend(self):
        amended = frontend.amended_overlay(OVERLAY)
        self.assertEqual(amended.count(b"  frontend:"), 1)
        self.assertIn(b'127.0.0.1:38082:80', amended)
        self.assertIn(b"build: !reset null", amended)
        with self.assertRaisesRegex(frontend.Refused, "no frontend override"):
            frontend.amended_overlay(amended)
        with self.assertRaisesRegex(frontend.Refused, "only services"):
            frontend.amended_overlay(OVERLAY + b"volumes:\n  unsafe: {}\n")

    def test_resolved_model_requires_loopback_image_and_unchanged_backend(self):
        before, after = models()
        frontend.validate_config(before, after, PROJECT)
        invalid = copy.deepcopy(after)
        invalid["services"]["frontend"]["ports"][0]["host_ip"] = "0.0.0.0"
        with self.assertRaisesRegex(frontend.Refused, "127.0.0.1:38082"):
            frontend.validate_config(before, invalid, PROJECT)
        invalid = copy.deepcopy(after)
        invalid["services"]["frontend"]["build"] = {"context": "/tmp/frontend"}
        with self.assertRaisesRegex(frontend.Refused, "without build"):
            frontend.validate_config(before, invalid, PROJECT)
        invalid = copy.deepcopy(after)
        invalid["services"]["app"]["image"] = "other-app"
        with self.assertRaisesRegex(frontend.Refused, "Non-frontend"):
            frontend.validate_config(before, invalid, PROJECT)

    def test_maintenance_requires_marker_and_stopped_prod(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(frontend.Refused, "Maintenance approval"):
                frontend.production_stopped()
        with patch.dict(os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": "YES"}), \
                patch.object(frontend.preflight, "docker", return_value="prod-id"):
            with self.assertRaisesRegex(frontend.Refused, "Production containers"):
                frontend.production_stopped()

    def test_failed_start_restores_overlay_and_removes_frontend(self):
        with tempfile.TemporaryDirectory() as temporary:
            docker_dir = Path(temporary)
            env_path = docker_dir / ".env.bench.test"
            env_path.write_bytes(ENV)
            env_path.chmod(0o600)
            overlay_path = docker_dir / frontend.OVERLAY_NAME
            overlay_path.write_bytes(OVERLAY)
            before, after = models()
            files = tuple(docker_dir / name for name in
                          ("docker-compose.yaml", "docker-compose.full.yaml",
                           "docker-compose.bench.yaml", frontend.OVERLAY_NAME))
            args = argparse.Namespace(env_file=env_path, compose_overlay=overlay_path,
                                      dry_run=False, stop=False)
            def docker(*command):
                if command[:2] == ("ps", "-q"):
                    return ""
                if command[:2] == ("image", "inspect"):
                    return "sha256:" + "a" * 64
                raise AssertionError(command)
            with patch.dict(os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": "YES"}), \
                    patch.object(frontend, "DOCKER_DIR", docker_dir), \
                    patch.object(frontend.ablation, "DOCKER_DIR", docker_dir), \
                    patch.object(frontend.ablation, "RUNS_ROOT", docker_dir / "runs"), \
                    patch.object(frontend.preflight, "docker", side_effect=docker), \
                    patch.object(frontend.preflight, "check", return_value=STACK), \
                    patch.object(frontend.ablation, "project_containers", return_value=CONTAINERS), \
                    patch.object(frontend.ablation, "compose_files_for_project", return_value=files), \
                    patch.object(frontend, "available_port"), \
                    patch.object(frontend, "resolved_config", side_effect=[before, after, after]), \
                    patch.object(frontend, "run_command", side_effect=frontend.Refused("synthetic up failure")), \
                    patch.object(frontend, "remove_new_frontend") as remove:
                with self.assertRaisesRegex(frontend.Refused, "synthetic up failure"):
                    frontend.start(args)
            self.assertEqual(overlay_path.read_bytes(), OVERLAY)
            self.assertFalse(overlay_path.with_name(overlay_path.name + frontend.BACKUP_SUFFIX).exists())
            remove.assert_called_once_with(PROJECT)

    def test_stop_removes_only_verified_frontend_and_restores_exact_backup(self):
        with tempfile.TemporaryDirectory() as temporary:
            docker_dir = Path(temporary)
            env_path = docker_dir / ".env.bench.test"
            env_path.write_bytes(ENV)
            env_path.chmod(0o600)
            overlay_path = docker_dir / frontend.OVERLAY_NAME
            overlay_path.write_bytes(frontend.amended_overlay(OVERLAY))
            backup = overlay_path.with_name(overlay_path.name + frontend.BACKUP_SUFFIX)
            backup.write_bytes(OVERLAY)
            backup.chmod(0o600)
            files = tuple(docker_dir / name for name in
                          ("docker-compose.yaml", "docker-compose.full.yaml",
                           "docker-compose.bench.yaml", frontend.OVERLAY_NAME))
            app = {**CONTAINERS["app"], "NetworkSettings": {
                "Networks": {"bench": {"NetworkID": "bench-network"}}}}
            expected_image = "sha256:" + "a" * 64
            running = {**CONTAINERS, "app": app, "frontend": {
                "Id": "frontend-id", "Image": expected_image,
                "State": {"Running": True}, "Mounts": [],
                "Config": {"Labels": {
                    frontend.preflight.PROJECT_LABEL: PROJECT,
                    frontend.preflight.BENCH_LABEL: "true",
                    frontend.preflight.SERVICE_LABEL: "frontend"}},
                "NetworkSettings": {
                    "Ports": {"80/tcp": [{"HostIp": "127.0.0.1", "HostPort": "38082"}]},
                    "Networks": {"bench": {"NetworkID": "bench-network"}}}}}
            remaining = {**CONTAINERS, "app": app}
            args = argparse.Namespace(env_file=env_path, compose_overlay=overlay_path,
                                      dry_run=False, stop=True)
            calls = []
            def docker(*command):
                calls.append(command)
                if command[:2] == ("ps", "-q"):
                    return ""
                if command[:2] == ("image", "inspect"):
                    return expected_image
                if command[:2] == ("rm", "-f"):
                    self.assertEqual(command[2], "frontend-id")
                    return "frontend-id"
                raise AssertionError(command)
            with patch.dict(os.environ, {"PEERGRAB_MAINTENANCE_APPROVED": "YES"}), \
                    patch.object(frontend, "DOCKER_DIR", docker_dir), \
                    patch.object(frontend.ablation, "DOCKER_DIR", docker_dir), \
                    patch.object(frontend.ablation, "RUNS_ROOT", docker_dir / "runs"), \
                    patch.object(frontend.preflight, "docker", side_effect=docker), \
                    patch.object(frontend.preflight, "check", return_value=STACK), \
                    patch.object(frontend.ablation, "project_containers",
                                 side_effect=[running, remaining]), \
                    patch.object(frontend.ablation, "compose_files_for_project",
                                 return_value=files):
                frontend.start(args)
            self.assertEqual(overlay_path.read_bytes(), OVERLAY)
            self.assertFalse(backup.exists())
            self.assertIn(("rm", "-f", "frontend-id"), calls)

    def test_stop_refuses_changed_overlay_before_removing_container(self):
        with tempfile.TemporaryDirectory() as temporary:
            docker_dir = Path(temporary)
            overlay_path = docker_dir / frontend.OVERLAY_NAME
            overlay_path.write_bytes(frontend.amended_overlay(OVERLAY) + b"# changed\n")
            backup = overlay_path.with_name(overlay_path.name + frontend.BACKUP_SUFFIX)
            backup.write_bytes(OVERLAY)
            backup.chmod(0o600)
            with patch.object(frontend, "DOCKER_DIR", docker_dir):
                with self.assertRaisesRegex(frontend.Refused, "changed since"):
                    frontend.original_backup(overlay_path)
            self.assertTrue(backup.exists())

    def test_stopped_frontend_can_be_verified_for_cleanup(self):
        app = {"NetworkSettings": {"Networks": {"bench": {"NetworkID": "bench-network"}}}}
        stopped = {"Id": "frontend-id", "Image": "sha256:" + "a" * 64,
                   "State": {"Running": False}, "Mounts": [],
                   "Config": {"Labels": {
                       frontend.preflight.PROJECT_LABEL: PROJECT,
                       frontend.preflight.BENCH_LABEL: "true",
                       frontend.preflight.SERVICE_LABEL: "frontend"}},
                   "HostConfig": {"NetworkMode": PROJECT + "_default",
                                  "PortBindings": {"80/tcp": [{"HostIp": "127.0.0.1",
                                                               "HostPort": "38082"}]}}}
        containers = {"app": app, "frontend": stopped}
        self.assertIs(frontend.verified_frontend(containers, PROJECT, stopped["Image"],
                                                  allow_stopped=True), stopped)
        stopped["HostConfig"]["PortBindings"]["80/tcp"][0]["HostIp"] = "0.0.0.0"
        with self.assertRaisesRegex(frontend.Refused, "expected loopback"):
            frontend.verified_frontend(containers, PROJECT, stopped["Image"],
                                       allow_stopped=True)


if __name__ == "__main__":
    unittest.main()
