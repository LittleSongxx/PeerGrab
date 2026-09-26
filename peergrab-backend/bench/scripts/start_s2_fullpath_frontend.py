#!/usr/bin/env python3
"""Start a loopback-only frontend for the isolated ECS S2 full-path benchmark.

Run after the app-only ablations. This changes only the existing fourth benchmark
Compose overlay and creates its frontend service; it never builds or pulls images.
The original overlay is kept beside it for explicit post-test restoration.
"""

import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import re
import socket
import stat
import subprocess
import sys
import tempfile

import preflight
import run_s2_ablations as ablation


DOCKER_DIR = preflight.ROOT / "docker"
OVERLAY_NAME = "docker-compose.bench.max.yaml"
FRONTEND_IMAGE = "peergrab-prod-frontend:latest"
FRONTEND_PORT = 38082
BACKUP_SUFFIX = ".pre-fullpath-frontend"
ADDITION = b'''\n  # Temporary isolated full-path S2 frontend; remove after the benchmark.
  frontend:
    image: peergrab-prod-frontend:latest
    build: !reset null
    ports: !override
      - "127.0.0.1:38082:80"
    restart: "no"
'''


class Refused(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Refused(message)


def run_command(command, *, env=None, timeout=30):
    result = subprocess.run(command, cwd=DOCKER_DIR, env=env, capture_output=True,
                            text=True, timeout=timeout)
    if result.returncode:
        # Compose diagnostics can contain expanded environment values.
        raise Refused(f"Command failed: {' '.join(command[:3])}")
    return result.stdout.strip()


def production_stopped():
    require(os.getenv("PEERGRAB_MAINTENANCE_APPROVED") == "YES",
            "Maintenance approval marker is required")
    running = preflight.docker("ps", "-q", "--filter",
                               "label=com.docker.compose.project=peergrab-prod")
    require(not running, "Production containers are running")


@contextmanager
def ablations_idle(project):
    """Do not alter the Compose model while the app-only ablation owns its lock."""
    ablation.RUNS_ROOT.mkdir(parents=True, exist_ok=True)
    lock = ablation.RUNS_ROOT / f".{project}.s2-ablations.lock"
    descriptor = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise Refused("App ablation is still running") from exc
        yield
    finally:
        os.close(descriptor)


def available_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", FRONTEND_PORT))
        except OSError as exc:
            raise Refused("Frontend loopback port is occupied") from exc


def overlay_path(path):
    path = Path(path)
    require(path.name == OVERLAY_NAME and not path.is_symlink()
            and path.parent.resolve() == DOCKER_DIR.resolve(),
            "Overlay must be the existing benchmark max file in docker/")
    metadata = path.stat()
    require(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == os.getuid(),
            "Overlay must be an owned regular file")
    return path.resolve()


def amended_overlay(original):
    source = original.decode("utf-8")
    headings = re.findall(r"(?m)^([A-Za-z][A-Za-z0-9_-]*):\s*$", source)
    require(headings == ["services"] and not re.search(r"(?m)^  frontend:\s*$", source),
            "Overlay must have only services and no frontend override")
    require(re.search(r"(?m)^  app:\s*$", source) and
            re.search(r"(?m)^  worker:\s*$", source),
            "Existing max overlay must define app and worker")
    return original.rstrip(b"\n") + b"\n" + ADDITION


def compose_command(env_path, project, files):
    command = ["docker", "compose", "--env-file", str(env_path), "-p", project,
               "--profile", "web"]
    for file in files:
        command += ["-f", str(file)]
    return command


def resolved_config(command, env):
    try:
        return json.loads(run_command(command + ["config", "--format", "json"], env=env))
    except (ValueError, TypeError) as exc:
        raise Refused("Compose did not return valid JSON") from exc


def preview_config(env_path, project, files, amended, env):
    descriptor, temporary = tempfile.mkstemp(prefix=".frontend-preview-", suffix=".yaml",
                                             dir=DOCKER_DIR)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(amended)
        return resolved_config(compose_command(env_path, project,
                                               (*files[:-1], Path(temporary))), env)
    finally:
        Path(temporary).unlink(missing_ok=True)


def validate_config(before, after, project):
    require(before.get("name") == after.get("name") == project,
            "Compose project changed")
    old_services, services = before["services"], after["services"]
    require(set(old_services) == set(services) and "frontend" in services,
            "Compose services changed")
    require({key: value for key, value in old_services.items() if key != "frontend"}
            == {key: value for key, value in services.items() if key != "frontend"},
            "Non-frontend Compose service changed")
    require(before.get("volumes") == after.get("volumes"), "Compose volumes changed")
    old_front, front = old_services["frontend"], services["frontend"]
    require(not old_front.get("ports"), "Frontend already has a published port")
    ignored = {"image", "build", "ports", "restart"}
    require({key: value for key, value in old_front.items() if key not in ignored}
            == {key: value for key, value in front.items() if key not in ignored},
            "Frontend gained an unexpected Compose setting")
    require(front.get("image") == FRONTEND_IMAGE and not front.get("build"),
            "Frontend must use the existing local image without build")
    ports = front.get("ports") or []
    require(len(ports) == 1 and isinstance(ports[0], dict)
            and ports[0].get("host_ip") == "127.0.0.1"
            and str(ports[0].get("published")) == str(FRONTEND_PORT)
            and ports[0].get("target") == 80
            and ports[0].get("protocol", "tcp") == "tcp",
            "Frontend must publish only 127.0.0.1:38082:80")
    require("web" in front.get("profiles", []), "Frontend must retain the web profile")
    require(front.get("labels", {}).get(preflight.BENCH_LABEL) == "true",
            "Frontend lost its disposable benchmark label")


def atomic_write(path, data, mode):
    descriptor, temporary = tempfile.mkstemp(prefix=".frontend-overlay-", dir=path.parent)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def backup_overlay(path, original):
    backup = path.with_name(path.name + BACKUP_SUFFIX)
    descriptor = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(original)
        stream.flush()
        os.fsync(stream.fileno())
    return backup


def original_backup(path):
    backup = path.with_name(path.name + BACKUP_SUFFIX)
    require(not backup.is_symlink(), "Overlay backup must not be a symlink")
    metadata = backup.stat()
    require(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == os.getuid()
            and stat.S_IMODE(metadata.st_mode) == 0o600,
            "Overlay backup must be an owned private file")
    original = backup.read_bytes()
    require(path.read_bytes() == amended_overlay(original),
            "Overlay changed since frontend startup")
    return backup, original


def verified_frontend(containers, project, image_id, *, allow_stopped=False):
    frontend = containers.get("frontend")
    require(frontend and (frontend["State"]["Running"] or allow_stopped),
            "Benchmark frontend is missing or stopped")
    labels = frontend["Config"].get("Labels") or {}
    require(labels.get(preflight.PROJECT_LABEL) == project
            and labels.get(preflight.BENCH_LABEL) == "true"
            and labels.get(preflight.SERVICE_LABEL) == "frontend",
            "Frontend lacks benchmark Compose identity")
    require(frontend["Image"] == image_id and not frontend["Mounts"],
            "Benchmark frontend image or mounts differ")
    if frontend["State"]["Running"]:
        require(preflight.mapped_port(frontend, "80/tcp") == FRONTEND_PORT
                and not any(bindings for port, bindings in
                            frontend["NetworkSettings"]["Ports"].items() if port != "80/tcp"),
                "Benchmark frontend has an unexpected published port")
        networks = {item["NetworkID"] for item in frontend["NetworkSettings"]["Networks"].values()}
        app_networks = {item["NetworkID"] for item in containers["app"]["NetworkSettings"]["Networks"].values()}
        require(bool(networks & app_networks), "Frontend is not on the benchmark app network")
    else:
        binding = frontend["HostConfig"]["PortBindings"]
        require(set(binding) == {"80/tcp"} and len(binding["80/tcp"]) == 1
                and binding["80/tcp"][0].get("HostIp") == "127.0.0.1"
                and str(binding["80/tcp"][0].get("HostPort")) == str(FRONTEND_PORT)
                and frontend["HostConfig"].get("NetworkMode") == project + "_default",
                "Stopped frontend lacks the expected loopback port or bench network")
    return frontend


def live_frontend(project, image_id, original_ids, volumes):
    production_stopped()
    containers = ablation.project_containers(project)
    require(all(containers[name]["Id"] == identity for name, identity in original_ids.items()),
            "A pre-existing benchmark container changed")
    frontend = verified_frontend(containers, project, image_id)
    require(frontend["State"].get("Health", {}).get("Status") == "healthy",
            "Benchmark frontend is not healthy")
    checked = preflight.check(project, f"http://127.0.0.1:{os.environ['PEERGRAB_API_PORT']}",
                              "127.0.0.1", os.environ["PEERGRAB_MYSQL_PORT"])
    require(checked["mysql_container_id"] == original_ids["mysql"]
            and checked["volumes"] == volumes, "Benchmark database or volumes changed")
    response = run_command(["curl", "-fsS", "--max-time", "5",
                            "--noproxy", "*",
                            f"http://127.0.0.1:{FRONTEND_PORT}/api/health"])
    payload = json.loads(response)
    require(payload.get("code") == "OK" and payload.get("data", {}).get("status") == "UP",
            "Frontend API proxy did not reach the benchmark app")


def remove_new_frontend(project):
    containers = ablation.project_containers(project)
    frontend = containers.get("frontend")
    if frontend:
        labels = frontend["Config"].get("Labels") or {}
        require(labels.get(preflight.PROJECT_LABEL) == project
                and labels.get(preflight.BENCH_LABEL) == "true"
                and labels.get(preflight.SERVICE_LABEL) == "frontend",
                "Refusing to remove an unverified frontend")
        preflight.docker("rm", "-f", frontend["Id"])


def start_locked(args):
    production_stopped()
    env_path = Path(args.env_file).resolve()
    _, values = ablation.private_env(env_path)
    project = values["COMPOSE_PROJECT_NAME"]
    ablation.apply_environment(values)
    overlay = overlay_path(args.compose_overlay)
    original = overlay.read_bytes()
    amended = amended_overlay(original)
    available_port()
    baseline = preflight.check(project, os.environ["PEERGRAB_BENCH_BASE_URL"],
                               "127.0.0.1", values["PEERGRAB_MYSQL_PORT"])
    containers = ablation.project_containers(project)
    require("frontend" not in containers, "Benchmark frontend already exists")
    files = ablation.compose_files_for_project(containers, overlay)
    original_ids = {name: item["Id"] for name, item in containers.items()}
    image_id = preflight.docker("image", "inspect", FRONTEND_IMAGE,
                                "--format", "{{.Id}}")
    require(image_id.startswith("sha256:"), "Local frontend image is missing")
    env = {**os.environ, **values}
    command = compose_command(env_path, project, files)
    before = resolved_config(command, env)
    require(not before["services"]["frontend"].get("ports"),
            "Frontend already publishes a host port")
    validate_config(before, preview_config(env_path, project, files, amended, env), project)
    if args.dry_run:
        print(json.dumps({"dryRun": True, "project": project, "overlay": str(overlay),
                          "frontendImageId": image_id, "frontendPort": FRONTEND_PORT,
                          "appId": original_ids["app"], "mysqlId": original_ids["mysql"],
                          "workerId": original_ids["worker"],
                          "volumes": baseline["volumes"]}, sort_keys=True))
        return
    backup = backup_overlay(overlay, original)
    mode = stat.S_IMODE(overlay.stat().st_mode)
    installed = False
    try:
        atomic_write(overlay, amended, mode)
        installed = True
        after = resolved_config(command, env)
        validate_config(before, after, project)
        production_stopped()
        run_command(command + ["up", "-d", "--no-deps", "--no-build", "--pull",
                               "never", "--wait", "frontend"], env=env, timeout=180)
        live_frontend(project, image_id, original_ids, baseline["volumes"])
    except BaseException:
        removed = False
        try:
            remove_new_frontend(project)
            removed = True
        finally:
            if installed and overlay.read_bytes() == amended:
                atomic_write(overlay, original, mode)
            if removed and overlay.read_bytes() == original:
                backup.unlink(missing_ok=True)
        raise
    print(json.dumps({"status": "READY", "project": project,
                      "frontend": f"http://127.0.0.1:{FRONTEND_PORT}",
                      "frontendImageId": image_id, "overlayBackup": str(backup),
                      "appId": original_ids["app"], "mysqlId": original_ids["mysql"],
                      "workerId": original_ids["worker"],
                      "volumes": baseline["volumes"]}, sort_keys=True))


def stop_locked(args):
    production_stopped()
    env_path = Path(args.env_file).resolve()
    _, values = ablation.private_env(env_path)
    project = values["COMPOSE_PROJECT_NAME"]
    ablation.apply_environment(values)
    overlay = overlay_path(args.compose_overlay)
    backup, original = original_backup(overlay)
    baseline = preflight.check(project, os.environ["PEERGRAB_BENCH_BASE_URL"],
                               "127.0.0.1", values["PEERGRAB_MYSQL_PORT"])
    containers = ablation.project_containers(project)
    ablation.compose_files_for_project(containers, overlay)
    image_id = preflight.docker("image", "inspect", FRONTEND_IMAGE,
                                "--format", "{{.Id}}")
    require(image_id.startswith("sha256:"), "Local frontend image is missing")
    target = verified_frontend(containers, project, image_id, allow_stopped=True)
    original_ids = {name: item["Id"] for name, item in containers.items() if name != "frontend"}
    if args.dry_run:
        print(json.dumps({"dryRun": True, "stop": True, "project": project,
                          "frontendId": target["Id"], "overlayBackup": str(backup),
                          "appId": original_ids["app"], "mysqlId": original_ids["mysql"],
                          "workerId": original_ids["worker"],
                          "volumes": baseline["volumes"]}, sort_keys=True))
        return
    production_stopped()
    preflight.docker("rm", "-f", target["Id"])
    mode = stat.S_IMODE(overlay.stat().st_mode)
    atomic_write(overlay, original, mode)
    remaining = ablation.project_containers(project)
    require("frontend" not in remaining and
            all(remaining[name]["Id"] == identity for name, identity in original_ids.items()),
            "A pre-existing benchmark container changed during frontend removal")
    checked = preflight.check(project, os.environ["PEERGRAB_BENCH_BASE_URL"],
                              "127.0.0.1", values["PEERGRAB_MYSQL_PORT"])
    require(checked["mysql_container_id"] == original_ids["mysql"]
            and checked["volumes"] == baseline["volumes"],
            "Benchmark database or volumes changed during frontend removal")
    backup.unlink()
    print(json.dumps({"status": "STOPPED", "project": project,
                      "restoredOverlay": str(overlay), "appId": original_ids["app"],
                      "mysqlId": original_ids["mysql"], "workerId": original_ids["worker"],
                      "volumes": baseline["volumes"]}, sort_keys=True))


def start(args):
    """Hold the same nonblocking lock as the app-only ablation throughout changes."""
    _, values = ablation.private_env(Path(args.env_file).resolve())
    with ablations_idle(values["COMPOSE_PROJECT_NAME"]):
        if args.stop:
            return stop_locked(args)
        return start_locked(args)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--compose-overlay", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--stop", action="store_true",
                        help="remove the verified benchmark frontend and restore its overlay backup")
    args = parser.parse_args()
    try:
        start(args)
    except BaseException as exc:
        print(f"Full-path frontend refused: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
