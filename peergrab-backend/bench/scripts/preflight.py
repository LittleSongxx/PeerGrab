#!/usr/bin/env python3
"""Fail-closed, host-local identity check before any benchmark load or stack deletion.

The Docker daemon and Docker CLI are trusted; environment strings alone are not.
Run on the Docker host. This intentionally does not support the public domain.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit
from urllib.request import urlopen

BENCH_LABEL = "org.peergrab.bench.disposable"
PROJECT_LABEL = "com.docker.compose.project"
SERVICE_LABEL = "com.docker.compose.service"
PROJECT_RE = re.compile(r"peergrab-bench-[a-z0-9][a-z0-9_-]*\Z")
ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILES = tuple(ROOT / "docker" / name for name in (
    "docker-compose.yaml", "docker-compose.full.yaml", "docker-compose.bench.yaml"))
REQUIRED_RUNNING = {"mysql", "redis", "rmqnamesrv", "rmqbroker", "app", "worker"}
REQUIRED_VOLUMES = {
    "mysql": "/var/lib/mysql", "redis": "/data", "rmqbroker": "/home/rocketmq/store"}


class Refused(RuntimeError):
    pass


def docker(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True)
    if result.returncode:
        raise Refused(f"docker {' '.join(args[:2])} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def inspect(kind, names):
    if not names:
        raise Refused(f"No {kind} to inspect")
    return json.loads(docker(kind, "inspect", *names))


def require(ok, reason):
    if not ok:
        raise Refused(reason)


def mapped_port(container, internal):
    bindings = container["NetworkSettings"]["Ports"].get(internal)
    require(bindings is not None and len(bindings) == 1, f"Expected one published {internal} port")
    binding = bindings[0]
    require(binding["HostIp"] == "127.0.0.1", f"{internal} must bind only 127.0.0.1")
    return int(binding["HostPort"])


def check(project, base_url, db_host, db_port, required_mq_mode=None,
          required_timeout_scan_mode=None, required_confirm_seconds=None):
    require(os.getenv("PEERGRAB_BENCH_DISPOSABLE") == "YES", "Missing disposable opt-in")
    require(PROJECT_RE.fullmatch(project) is not None, "Invalid benchmark project name")
    require(os.getenv("PEERGRAB_BENCH_PROJECT") == project, "Project env mismatch")
    require(os.getenv("COMPOSE_PROJECT_NAME") == project, "Compose project env mismatch")
    require(db_host in {"127.0.0.1", "localhost"}, "DB host must be local Docker binding")
    require(db_port.isdecimal(), "Invalid DB port")
    parsed = urlsplit(base_url)
    require(parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost"}
            and parsed.port is not None and not parsed.path.rstrip("/") and not parsed.query
            and not parsed.fragment and not parsed.username and not parsed.password,
            "API base URL must be host-local http://127.0.0.1:<port>")
    api_port = parsed.port

    ids = docker("ps", "-aq", "--filter", f"label={PROJECT_LABEL}={project}").splitlines()
    require(ids, "No containers for benchmark Compose project")
    containers = inspect("container", ids)
    by_service = {}
    for c in containers:
        labels = c["Config"].get("Labels") or {}
        service = labels.get(SERVICE_LABEL)
        require(labels.get(PROJECT_LABEL) == project and labels.get(BENCH_LABEL) == "true",
                f"Container {c['Id'][:12]} lacks benchmark identity")
        require(str(COMPOSE_FILES[2]) in labels.get("com.docker.compose.project.config_files", ""),
                f"Container {c['Id'][:12]} was not created with benchmark overlay")
        require(service and service not in by_service, f"Duplicate/unknown service {service}")
        by_service[service] = c
    require(REQUIRED_RUNNING <= by_service.keys(), "Incomplete benchmark stack")
    for service in REQUIRED_RUNNING:
        require(by_service[service]["State"]["Running"], f"{service} is not running")
    app = by_service["app"]
    mysql = by_service["mysql"]
    require(mapped_port(app, "8080/tcp") == api_port, "API URL does not map to benchmark app")
    require(mapped_port(mysql, "3306/tcp") == int(db_port), "DB port does not map to benchmark MySQL")
    require(mapped_port(by_service["redis"], "6379/tcp") > 0, "Redis is not loopback bound")
    require(not any(by_service["rmqnamesrv"]["NetworkSettings"]["Ports"].values()),
            "rmqnamesrv must not publish host ports")
    require(mapped_port(by_service["rmqbroker"], "8081/tcp") ==
            int(os.getenv("PEERGRAB_TEST_MQ_PORT", "0")),
            "MQ proxy port does not map to benchmark broker")
    require(not any(bindings for port, bindings in by_service["rmqbroker"]["NetworkSettings"]["Ports"].items()
                    if port != "8081/tcp"), "Broker must only publish its proxy")

    network_ids = set()
    for service, c in by_service.items():
        if not c["State"]["Running"]:
            continue  # one-shot mq/init containers release their network attachment
        networks = c["NetworkSettings"]["Networks"]
        require(networks, f"{service} has no network")
        network_ids.update(n["NetworkID"] for n in networks.values())
    for n in inspect("network", list(network_ids)):
        require(n["Labels"].get(PROJECT_LABEL) == project and n["Name"].startswith(project + "_"),
                f"Cross-project network: {n['Name']}")
    app_networks = {n["NetworkID"] for n in app["NetworkSettings"]["Networks"].values()}
    for service in ("mysql", "redis", "rmqbroker"):
        service_networks = {n["NetworkID"] for n in by_service[service]["NetworkSettings"]["Networks"].values()}
        require(app_networks & service_networks, f"app and {service} have no shared bench network")

    for service, destination in REQUIRED_VOLUMES.items():
        mounts = [m for m in by_service[service]["Mounts"] if m["Destination"] == destination]
        require(len(mounts) == 1 and mounts[0]["Type"] == "volume",
                f"{service} data mount is not a named volume")
        name = mounts[0]["Name"]
        require(name.startswith(project + "_"), f"Cross-project volume {name}")
        volume = inspect("volume", [name])[0]
        labels = volume.get("Labels") or {}
        require(labels.get(PROJECT_LABEL) == project and labels.get(BENCH_LABEL) == "true",
                f"Volume {name} lacks benchmark identity")

    for service in ("app", "worker"):
        env = dict(value.split("=", 1) for value in by_service[service]["Config"]["Env"] if "=" in value)
        require(env.get("SPRING_DATASOURCE_URL", "").startswith("jdbc:mysql://mysql:3306/peer_grab?"),
                f"{service} is not using benchmark MySQL service")
        require(env.get("SPRING_DATA_REDIS_HOST") == "redis"
                and env.get("PEERGRAB_MQ_ENDPOINTS") == "rmqbroker:8081",
                f"{service} has cross-stack Redis/MQ target")
        if required_mq_mode is not None:
            require(env.get("PEERGRAB_MQ_ENABLED", "").lower() == required_mq_mode,
                    f"{service} MQ mode differs from requested benchmark mode")

    worker_env = dict(value.split("=", 1) for value in by_service["worker"]["Config"]["Env"]
                      if "=" in value)
    if required_confirm_seconds is not None:
        require(worker_env.get("PEERGRAB_TIMEOUT_CONFIRM_SECONDS") == str(required_confirm_seconds),
                "Worker confirmation timeout differs from S5 probe parameter")
    if required_timeout_scan_mode is not None:
        require(worker_env.get("PEERGRAB_TIMEOUT_SCAN_ENABLED", "").lower()
                == required_timeout_scan_mode,
                "Worker timeout scan mode differs from requested benchmark mode")
    scan_interval = worker_env.get("PEERGRAB_TIMEOUT_SCAN_INTERVAL_MS")
    if scan_interval is not None:
        require(scan_interval.isdecimal() and int(scan_interval) > 0,
                "Worker scan interval override is not a positive millisecond value")

    guard = docker("exec", mysql["Id"], "sh", "-ec",
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B peer_grab '
                   '-e "SELECT project_name FROM bench_guard WHERE guard_key=\'project\'"')
    require(guard == project, "Database bench_guard does not match Compose project")
    with urlopen(base_url.rstrip("/") + "/api/health", timeout=5) as response:
        health = json.load(response)
        require(response.status == 200 and health.get("code") == "OK"
                and health.get("data", {}).get("status") == "UP",
                "Benchmark API health did not return UP")
    return {"project": project, "api": base_url, "mysql_port": int(db_port),
            "mysql_container_id": mysql["Id"],
            "worker_scan_interval_ms": int(scan_interval) if scan_interval is not None else None,
            "worker_scan_interval_source": ("worker_container_env" if scan_interval is not None
                                            else "not_exposed_by_container_env"),
            "services": sorted(by_service), "volumes": sorted(
                m["Name"] for service, dest in REQUIRED_VOLUMES.items()
                for m in by_service[service]["Mounts"] if m["Destination"] == dest)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=os.getenv("PEERGRAB_BENCH_PROJECT"))
    parser.add_argument("--base-url", default=os.getenv("PEERGRAB_BENCH_BASE_URL"))
    parser.add_argument("--db-host", default=os.getenv("PEERGRAB_TEST_DB_HOST"))
    parser.add_argument("--db-port", default=os.getenv("PEERGRAB_TEST_DB_PORT"))
    parser.add_argument("--require-mq-enabled", choices=("true", "false"))
    parser.add_argument("--require-timeout-scan-enabled", choices=("true", "false"))
    parser.add_argument("--require-confirm-seconds", type=int)
    args = parser.parse_args()
    try:
        require(all((args.project, args.base_url, args.db_host, args.db_port)),
                "Set project, API URL, DB host and DB port explicitly")
        print(json.dumps(check(args.project, args.base_url, args.db_host, args.db_port,
                               args.require_mq_enabled, args.require_timeout_scan_enabled,
                               args.require_confirm_seconds), sort_keys=True))
    except Exception as exc:
        print(f"Benchmark preflight refused: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
