#!/usr/bin/env python3
"""Run tiny or full S5 MQ probes inside a host-verified disposable Compose network.

The RocketMQ 5 client follows broker-advertised private proxy addresses after its
first request. A host-local published port is therefore insufficient, especially
when a VPN captures Docker subnets. This wrapper never changes host routes.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

import preflight

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "maven:3.9-eclipse-temurin-21"
RUNNER_PROJECT_LABEL = "org.peergrab.bench.runner_project"


def command(*args, capture=True, env=None):
    result = subprocess.run(args, text=True, capture_output=capture, env=env)
    if result.returncode:
        detail = (result.stderr or result.stdout or "").strip() if capture else ""
        raise preflight.Refused(f"{args[0]} {args[1]} failed: {detail}")
    return result.stdout.strip() if capture else ""


def service_container(project, service):
    ids = preflight.docker("ps", "-q", "--filter", f"label=com.docker.compose.project={project}",
                           "--filter", f"label=com.docker.compose.service={service}").splitlines()
    preflight.require(len(ids) == 1, f"Expected exactly one running {service} container")
    return preflight.inspect("container", ids)[0]


def run(kind, values):
    project = os.getenv("PEERGRAB_BENCH_PROJECT")
    base_url = os.getenv("PEERGRAB_BENCH_BASE_URL")
    db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
    db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
    preflight.require(all((project, base_url, db_host, db_port)),
                      "Export the benchmark environment shown in bench/README.md")
    verified = preflight.check(project, base_url, db_host, db_port, "true",
                               "false" if kind == "s5" else None)
    app = service_container(project, "app")
    worker = service_container(project, "worker")
    broker = service_container(project, "rmqbroker")
    if kind == "s5":
        worker_env = dict(item.split("=", 1) for item in worker["Config"]["Env"] if "=" in item)
        preflight.require(worker_env.get("PEERGRAB_TIMEOUT_SCAN_ENABLED", "true").lower() == "false",
                          "S5 MQ round requires Worker timeout scan disabled for attribution")
    shared = set(app["NetworkSettings"]["Networks"]) & set(broker["NetworkSettings"]["Networks"])
    preflight.require(len(shared) == 1, "Expected one shared benchmark app/broker network")
    network_name = shared.pop()
    network_id = app["NetworkSettings"]["Networks"][network_name]["NetworkID"]
    preflight.require(worker["NetworkSettings"]["Networks"].get(network_name, {}).get("NetworkID")
                      == network_id, "Worker is not on the benchmark network")
    network = preflight.inspect("network", [network_id])[0]
    preflight.require(network["Labels"].get(preflight.PROJECT_LABEL) == project,
                      "Runner network lacks benchmark project label")

    repo_cache = Path.home() / ".m2"
    preflight.require(repo_cache.is_dir(), "Build peergrab-bench on the host before running the MQ probe")
    runner_name = f"{project}-mq-probe-{uuid.uuid4().hex[:12]}"
    env = os.environ.copy()
    env.update({
        "PEERGRAB_BENCH_RUNNER_CONTEXT": "container",
        "PEERGRAB_BENCH_BASE_URL": "http://app:8080",
        "PEERGRAB_TEST_DB_HOST": "mysql",
        "PEERGRAB_TEST_DB_PORT": "3306",
        "PEERGRAB_TEST_MQ_PORT": "8081",
        "PEERGRAB_BENCH_VERIFIED_MQ_MODE": "true",
        "PEERGRAB_BENCH_VERIFIED_SCAN_MODE": "false" if kind == "s5" else "",
    })
    if verified["worker_scan_interval_ms"] is not None:
        env["PEERGRAB_BENCH_WORKER_SCAN_INTERVAL_MS"] = str(verified["worker_scan_interval_ms"])
    passed_env = (
        "PEERGRAB_BENCH_RUNNER_CONTEXT", "PEERGRAB_BENCH_BASE_URL", "PEERGRAB_TEST_DB_HOST",
        "PEERGRAB_TEST_DB_PORT", "PEERGRAB_TEST_MQ_PORT", "PEERGRAB_BENCH_VERIFIED_MQ_MODE",
        "PEERGRAB_BENCH_VERIFIED_SCAN_MODE",
        "PEERGRAB_TEST_DB_PASSWORD", "PEERGRAB_BENCH_PROJECT", "COMPOSE_PROJECT_NAME",
        "PEERGRAB_BENCH_DISPOSABLE",
    )
    if "PEERGRAB_BENCH_WORKER_SCAN_INTERVAL_MS" in env:
        passed_env += ("PEERGRAB_BENCH_WORKER_SCAN_INTERVAL_MS",)
    preflight.require(bool(env.get("PEERGRAB_TEST_DB_PASSWORD")), "Missing benchmark DB password")
    runner_id = None
    try:
        runner_id = command("docker", "run", "-d", "--read-only", "--tmpfs", "/tmp:rw,exec,mode=1777",
                            "--network", network_name, "--name", runner_name,
                            "--label", f"{RUNNER_PROJECT_LABEL}={project}",
                            "--label", f"{preflight.BENCH_LABEL}=true",
                            "-v", f"{ROOT}:/workspace:ro", "-v", f"{repo_cache}:/m2:ro",
                            "-w", "/workspace", "-u", f"{os.getuid()}:{os.getgid()}",
                            *[flag for key in passed_env for flag in ("-e", key)],
                            "-e", "HOME=/tmp",
                            "-e", "MAVEN_OPTS=-Dmaven.repo.local=/m2/repository -Drocketmq.log.root=/tmp/rocketmq",
                            IMAGE, "sleep", "600", env=env)
        preflight.require(re.fullmatch(r"[a-f0-9]{64}", runner_id) is not None,
                          "Docker returned an invalid runner ID")
        runner = preflight.inspect("container", [runner_id])[0]
        labels = runner["Config"].get("Labels") or {}
        preflight.require(labels.get(RUNNER_PROJECT_LABEL) == project
                          and labels.get(preflight.BENCH_LABEL) == "true",
                          "Runner labels differ from benchmark project")
        networks = runner["NetworkSettings"]["Networks"]
        preflight.require(set(networks) == {network_name}
                          and networks[network_name]["NetworkID"] == network_id,
                          "Runner is not exclusively attached to benchmark network")
        ip = networks[network_name]["IPAddress"]
        preflight.require(bool(ip), "Runner has no benchmark network address")
        if kind == "delay":
            main_class = "com.peergrab.bench.DelayMessageProbe"
            args = f"rmqbroker:8081 {values.delay_seconds}"
        else:
            main_class = "com.peergrab.bench.S5TimelineProbe"
            args = (f"mq {values.count} {values.lead_seconds} {values.confirm_seconds} "
                    f"{values.timeout_seconds} rmqbroker:8081")
        print(f"Verified {project} runner {runner_id[:12]} on {network_name}; running {kind} probe", flush=True)
        command("docker", "exec", "-e", f"PEERGRAB_BENCH_RUNNER_ID={runner_id}",
                "-e", f"PEERGRAB_BENCH_RUNNER_IP={ip}", runner_id,
                "mvn", "-o", "-q", "-pl", "peergrab-bench", "exec:java",
                f"-Dexec.mainClass={main_class}", f"-Dexec.args={args}", capture=False)
        if kind == "s5":
            progress = command("docker", "exec", broker["Id"], "sh", "mqadmin", "consumerProgress",
                               "-n", "rmqnamesrv:9876", "-g", "peergrab-timeout-consumer",
                               "-t", "errand-confirm-timeout")
            print("RocketMQ consumer progress after S5 run (single snapshot):\n" + progress)
    finally:
        if runner_id and re.fullmatch(r"[a-f0-9]{64}", runner_id):
            had_error = sys.exc_info()[0] is not None
            try:
                command("docker", "rm", "--force", runner_id)
            except Exception as cleanup_error:
                if not had_error:
                    raise
                print(f"Runner cleanup also failed: {cleanup_error}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="kind", required=True)
    delay = sub.add_parser("delay")
    delay.add_argument("delay_seconds", type=int)
    s5 = sub.add_parser("s5")
    s5.add_argument("count", type=int)
    s5.add_argument("lead_seconds", type=int)
    s5.add_argument("confirm_seconds", type=int)
    s5.add_argument("timeout_seconds", type=int)
    args = parser.parse_args()
    if args.kind == "delay":
        if not 1 <= args.delay_seconds <= 3600:
            parser.error("delay_seconds must be 1..3600")
    else:
        if not (1 <= args.count <= 10_000 and 15 <= args.lead_seconds <= 86_000
                and args.confirm_seconds >= 1 and args.timeout_seconds >= 10):
            parser.error("Invalid S5 probe parameters")
    try:
        run(args.kind, args)
    except Exception as exc:
        print(f"MQ probe refused: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
