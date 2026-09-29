#!/usr/bin/env python3
"""Run independent auto-settlement expiry probes on one disposable ECS stack.

scan uses the host-local database and an MQ-disabled worker. combined sends
delayed messages from a one-shot runner on the verified benchmark network;
the always-on auto-settle scan remains active, so that mode is not MQ-only.
"""
import argparse
import os
from pathlib import Path
import re
import sys
import time
import uuid

import preflight
from run_mq_probe import IMAGE, RUNNER_PROJECT_LABEL, command, service_container


ROOT = Path(__file__).resolve().parents[2]
MAIN_CLASS = "com.peergrab.bench.AutoSettleTimelineProbe"


def run(mode, count, lead_seconds, timeout_seconds):
    project = os.getenv("PEERGRAB_BENCH_PROJECT")
    base_url = os.getenv("PEERGRAB_BENCH_BASE_URL")
    db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
    db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
    preflight.require(all((project, base_url, db_host, db_port)),
                      "Export the disposable benchmark environment first")
    preflight.require(bool(os.getenv("PEERGRAB_TEST_DB_PASSWORD")),
                      "Missing disposable benchmark database password")
    preflight.check(project, base_url, db_host, db_port,
                    "true" if mode == "combined" else "false")
    preflight.require_maintenance_window()
    worker = service_container(project, "worker")
    worker_env = dict(item.split("=", 1) for item in worker["Config"]["Env"] if "=" in item)
    preflight.require(worker_env.get("PEERGRAB_MQ_ENABLED", "").lower()
                      == ("true" if mode == "combined" else "false"),
                      "Worker MQ mode differs from requested auto-settle probe mode")
    scan_interval = worker_env.get("PEERGRAB_SETTLE_SCAN_INTERVAL_MS")
    preflight.require(scan_interval is not None and scan_interval.isdecimal()
                      and int(scan_interval) > 0,
                      "Worker automatic-settlement scan interval must be exposed and positive")
    print("Verified auto-settle mode=%s project=%s workerScanIntervalMs=%s" % (
        mode, project, scan_interval),
        flush=True)
    preflight.require((ROOT / "peergrab-bench/target/classes/com/peergrab/bench/"
                       "AutoSettleTimelineProbe.class").is_file(),
                      "Build peergrab-bench before running the probe")
    if mode == "scan":
        env = os.environ.copy()
        env["PEERGRAB_BENCH_VERIFIED_AUTO_SETTLE_SCAN_INTERVAL_MS"] = scan_interval
        command("mvn", "-o", "-q", "-pl", "peergrab-bench", "exec:java",
                f"-Dexec.mainClass={MAIN_CLASS}",
                f"-Dexec.args=scan {count} {lead_seconds} {timeout_seconds}",
                capture=False, env=env)
        return

    app = service_container(project, "app")
    broker = service_container(project, "rmqbroker")
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
    preflight.require(repo_cache.is_dir(), "Host Maven dependency cache is missing")
    runner_name = f"{project}-auto-settle-{uuid.uuid4().hex[:12]}"
    env = os.environ.copy()
    env.update({
        "PEERGRAB_BENCH_RUNNER_CONTEXT": "container",
        "PEERGRAB_BENCH_BASE_URL": "http://app:8080",
        "PEERGRAB_TEST_DB_HOST": "mysql",
        "PEERGRAB_TEST_DB_PORT": "3306",
        "PEERGRAB_TEST_MQ_PORT": "8081",
        "PEERGRAB_BENCH_VERIFIED_MQ_MODE": "true",
        "PEERGRAB_BENCH_VERIFIED_AUTO_SETTLE_SCAN_INTERVAL_MS": scan_interval,
        "PEERGRAB_BENCH_PRODUCTION_STOPPED": "YES",
    })
    passed = (
        "PEERGRAB_BENCH_RUNNER_CONTEXT", "PEERGRAB_BENCH_BASE_URL",
        "PEERGRAB_TEST_DB_HOST", "PEERGRAB_TEST_DB_PORT", "PEERGRAB_TEST_MQ_PORT",
        "PEERGRAB_BENCH_VERIFIED_MQ_MODE", "PEERGRAB_BENCH_PRODUCTION_STOPPED",
        "PEERGRAB_BENCH_VERIFIED_AUTO_SETTLE_SCAN_INTERVAL_MS",
        "PEERGRAB_MAINTENANCE_APPROVED", "PEERGRAB_TEST_DB_PASSWORD",
        "PEERGRAB_BENCH_PROJECT", "COMPOSE_PROJECT_NAME", "PEERGRAB_BENCH_DISPOSABLE",
    )
    runner_id = None
    try:
        runner_id = command(
            "docker", "run", "-d", "--read-only", "--tmpfs", "/tmp:rw,exec,mode=1777",
            "--network", network_name, "--name", runner_name,
            "--label", f"{RUNNER_PROJECT_LABEL}={project}",
            "--label", f"{preflight.BENCH_LABEL}=true",
            "-v", f"{ROOT}:/workspace:ro", "-v", f"{repo_cache}:/m2:ro",
            "-w", "/workspace", "-u", f"{os.getuid()}:{os.getgid()}",
            *[flag for key in passed for flag in ("-e", key)],
            "-e", "HOME=/tmp",
            "-e", "MAVEN_OPTS=-Dmaven.repo.local=/m2/repository -Drocketmq.log.root=/tmp/rocketmq",
            IMAGE, "sleep", str(max(600, lead_seconds + timeout_seconds + 120)), env=env)
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
        command("docker", "exec", "-e", f"PEERGRAB_BENCH_RUNNER_ID={runner_id}",
                "-e", f"PEERGRAB_BENCH_RUNNER_IP={ip}", runner_id,
                "mvn", "-o", "-q", "-pl", "peergrab-bench", "exec:java",
                f"-Dexec.mainClass={MAIN_CLASS}",
                f"-Dexec.args=combined {count} {lead_seconds} {timeout_seconds} rmqbroker:8081",
                capture=False)
        for attempt in range(3):
            try:
                progress = command("docker", "exec", broker["Id"], "sh", "mqadmin",
                                   "consumerProgress", "-n", "rmqnamesrv:9876", "-g",
                                   "peergrab-autosettle-consumer", "-t", "errand-auto-settle")
                break
            except preflight.Refused:
                if attempt == 2:
                    raise
                time.sleep(2)
        print("Auto-settle RocketMQ consumer progress after run (single snapshot):\n" + progress)
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
    parser.add_argument("mode", choices=("scan", "combined"))
    parser.add_argument("count", type=int)
    parser.add_argument("lead_seconds", type=int)
    parser.add_argument("timeout_seconds", type=int)
    args = parser.parse_args()
    if not (1 <= args.count <= 2_000 and 15 <= args.lead_seconds <= 86_000
            and args.timeout_seconds >= 10):
        parser.error("count must be 1..2000, lead >=15, timeout >=10")
    try:
        run(args.mode, args.count, args.lead_seconds, args.timeout_seconds)
    except Exception as exc:
        print(f"Auto-settle probe refused or failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
