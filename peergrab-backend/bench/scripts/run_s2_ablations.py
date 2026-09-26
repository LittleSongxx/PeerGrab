#!/usr/bin/env python3
"""Guarded S2-OPEN A/B/A sweeps on one disposable, host-local benchmark stack.

Only the benchmark app is recreated. The same verified MySQL container, volumes,
worker and 10k task fixture are retained across configurations. No production
configuration or container is changed.
"""

import argparse
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import uuid

import preflight
import run_first_round as first_round
from seed_bench import mysql


DOCKER_DIR = preflight.ROOT / "docker"
RUNS_ROOT = first_round.RUNS_ROOT
KNOBS = {
    "PEERGRAB_BENCH_HIKARI_POOL_SIZE": ("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", 20),
    "PEERGRAB_BENCH_TOMCAT_THREADS_MAX": ("SERVER_TOMCAT_THREADS_MAX", 200),
}
CONFIGS = ((20, 200), (8, 200), (20, 200), (20, 64), (20, 200))
WARM_RAMP_RATES = (20, 80)
DEFAULT_RATE = 80
ALLOWED_RATES = (80, 160)
MAX_IN_FLIGHT = 64
STATUS_NAMES = (
    "Threads_connected", "Threads_running", "Max_used_connections",
    "Innodb_buffer_pool_reads", "Innodb_buffer_pool_read_requests",
    "Innodb_row_lock_waits", "Innodb_row_lock_time",
)
COUNTER_NAMES = frozenset(STATUS_NAMES[3:])
PROJECT_LABEL = preflight.PROJECT_LABEL
SERVICE_LABEL = preflight.SERVICE_LABEL
BENCH_LABEL = preflight.BENCH_LABEL
VALUE = re.compile(r"[A-Za-z0-9_./:+-]+\Z")


class Stopped(RuntimeError):
    """A safety or correctness gate stopped the experiment."""


def private_env(path):
    path = Path(path)
    if path.is_symlink() or path.parent.resolve() != DOCKER_DIR.resolve():
        raise Stopped("env file must be a regular file directly in the repository docker directory")
    if not path.name.startswith(".env.bench"):
        raise Stopped("env filename must start with .env.bench")
    metadata = path.stat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid():
        raise Stopped("env file must be an owned regular file")
    if stat.S_IMODE(metadata.st_mode) != 0o600:
        raise Stopped("env file must have exact 0600 permissions")
    raw = path.read_bytes()
    try:
        source = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise Stopped("env file is not UTF-8") from exc
    values = {}
    for line in source.splitlines():
        if not line or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            raise Stopped("env file has a non-assignment line")
        key, value = line.split("=", 1)
        if (not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in values
                or not VALUE.fullmatch(value)):
            raise Stopped("env file contains duplicate or unsupported assignment")
        values[key] = value
    needed = {"COMPOSE_PROJECT_NAME", "PEERGRAB_API_PORT", "PEERGRAB_MYSQL_PORT",
              "PEERGRAB_RMQ_PROXY_PORT", "PEERGRAB_MYSQL_PASSWORD", *KNOBS}
    if not needed <= values.keys():
        raise Stopped("env file lacks benchmark identity, port, password or tuning key")
    if not preflight.PROJECT_RE.fullmatch(values["COMPOSE_PROJECT_NAME"]):
        raise Stopped("env file is not for a disposable benchmark project")
    if (int(values["PEERGRAB_BENCH_HIKARI_POOL_SIZE"]) != 20
            or int(values["PEERGRAB_BENCH_TOMCAT_THREADS_MAX"]) != 200):
        raise Stopped("A/B/A requires an initial 20/200 Hikari/Tomcat baseline")
    return raw, values


def setting_bytes(raw, hikari, tomcat):
    replacements = {"PEERGRAB_BENCH_HIKARI_POOL_SIZE": str(hikari),
                    "PEERGRAB_BENCH_TOMCAT_THREADS_MAX": str(tomcat)}
    output = []
    seen = set()
    for line in raw.decode("utf-8").splitlines(keepends=True):
        key = line.split("=", 1)[0]
        if key in replacements:
            suffix = "\n" if line.endswith("\n") else ""
            output.append(f"{key}={replacements[key]}{suffix}")
            seen.add(key)
        else:
            output.append(line)
    if seen != replacements.keys():
        raise Stopped("missing tuning assignment in original env file")
    return "".join(output).encode("utf-8")


def restore_or_replace(path, expected, replacement):
    if path.is_symlink() or path.read_bytes() != expected:
        raise Stopped("env file changed externally; refusing to overwrite it")
    descriptor, name = tempfile.mkstemp(prefix=".env.bench-ablation-", dir=DOCKER_DIR)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(replacement)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def apply_environment(values):
    for key, value in values.items():
        os.environ[key] = value
    os.environ.update({
        "PEERGRAB_BENCH_DISPOSABLE": "YES",
        "PEERGRAB_BENCH_PROJECT": values["COMPOSE_PROJECT_NAME"],
        "PEERGRAB_BENCH_BASE_URL": f'http://127.0.0.1:{values["PEERGRAB_API_PORT"]}',
        "PEERGRAB_TEST_DB_HOST": "127.0.0.1",
        "PEERGRAB_TEST_DB_PORT": values["PEERGRAB_MYSQL_PORT"],
        "PEERGRAB_TEST_DB_PASSWORD": values["PEERGRAB_MYSQL_PASSWORD"],
        "PEERGRAB_TEST_MQ_PORT": values["PEERGRAB_RMQ_PROXY_PORT"],
    })


def project_containers(project):
    ids = preflight.docker("ps", "-aq", "--filter", f"label={PROJECT_LABEL}={project}").splitlines()
    if not ids:
        raise Stopped("no benchmark containers in requested project")
    by_service = {}
    for item in preflight.inspect("container", ids):
        labels = item["Config"].get("Labels") or {}
        service = labels.get(SERVICE_LABEL)
        if (labels.get(PROJECT_LABEL) != project or labels.get(BENCH_LABEL) != "true"
                or str(preflight.COMPOSE_FILES[2]) not in
                labels.get("com.docker.compose.project.config_files", "")
                or not service or service in by_service):
            raise Stopped("container identity changed or duplicate service was found")
        by_service[service] = item
    if not preflight.REQUIRED_RUNNING <= by_service.keys():
        raise Stopped("benchmark project is incomplete")
    return by_service


def compose_files_for_project(containers, overlay):
    sequences = set()
    for item in containers.values():
        labels = item["Config"].get("Labels") or {}
        sequences.add(tuple(labels.get("com.docker.compose.project.config_files", "").split(",")))
    if len(sequences) != 1:
        raise Stopped("project services were created with different Compose file lists")
    recorded = tuple(Path(name).resolve() for name in sequences.pop())
    official = tuple(path.resolve() for path in preflight.COMPOSE_FILES)
    if recorded[:len(official)] != official or len(recorded) not in (len(official), len(official) + 1):
        raise Stopped("live benchmark stack has an unexpected Compose file sequence")
    if len(recorded) == len(official):
        if overlay is not None:
            raise Stopped("overlay was specified but is not in the live app Compose identity")
    else:
        if overlay is None:
            raise Stopped("live app uses an extra Compose overlay; pass --compose-overlay explicitly")
        extra = Path(overlay)
        if (extra.is_symlink() or extra.parent.resolve() != DOCKER_DIR.resolve()
                or not extra.is_file() or extra.suffix not in (".yaml", ".yml")
                or extra.resolve() != recorded[-1]):
            raise Stopped("requested Compose overlay does not match the live app identity")
    return recorded


def app_limits(app):
    host = app["HostConfig"]
    return {key: host.get(key, 0) for key in ("NanoCpus", "Memory", "CpuQuota", "CpuPeriod")}


def verify_app(project, hikari, tomcat, stable_ids, expected_limits=None):
    containers = project_containers(project)
    for service, previous_id in stable_ids.items():
        if containers[service]["Id"] != previous_id:
            raise Stopped(f"{service} container changed during app-only ablation")
    app = containers["app"]
    if not app["State"]["Running"] or app["State"].get("Health", {}).get("Status") != "healthy":
        raise Stopped("benchmark app is not healthy after recreation")
    if expected_limits is not None and app_limits(app) != expected_limits:
        raise Stopped("benchmark app CPU or memory limits changed during ablation")
    values = dict(pair.split("=", 1) for pair in app["Config"]["Env"] if "=" in pair)
    for key, expected in ((KNOBS["PEERGRAB_BENCH_HIKARI_POOL_SIZE"][0], hikari),
                          (KNOBS["PEERGRAB_BENCH_TOMCAT_THREADS_MAX"][0], tomcat)):
        if values.get(key) != str(expected):
            raise Stopped(f"benchmark app did not apply {key}={expected}")
    return app["Id"]


def recreate_app(env_path, project, hikari, tomcat, compose_files):
    command = ["docker", "compose", "--env-file", str(env_path), "-p", project]
    for file in compose_files:
        command += ["-f", str(file)]
    command += ["up", "-d", "--no-deps", "--force-recreate", "--wait",
                "--no-build", "--pull", "never", "app"]
    # --no-deps and the explicit service name are essential: no DB/Worker restart.
    compose_env = os.environ.copy()
    # Shell variables take precedence over --env-file. Force the requested pair
    # explicitly so a stale exported baseline cannot defeat the A/B change.
    compose_env["PEERGRAB_BENCH_HIKARI_POOL_SIZE"] = str(hikari)
    compose_env["PEERGRAB_BENCH_TOMCAT_THREADS_MAX"] = str(tomcat)
    subprocess.run(command, cwd=DOCKER_DIR, env=compose_env, check=True, timeout=240)


def database_status(mysql_id):
    names = ",".join(f"'{name}'" for name in STATUS_NAMES)
    rows = mysql(mysql_id, f"SHOW GLOBAL STATUS WHERE Variable_name IN ({names});")
    result = {}
    for row in rows.splitlines():
        fields = row.split("\t")
        if len(fields) != 2 or fields[0] not in STATUS_NAMES or not fields[1].isdecimal():
            raise Stopped("unexpected MySQL status output")
        result[fields[0]] = int(fields[1])
    if set(result) != set(STATUS_NAMES):
        raise Stopped("MySQL status response is incomplete")
    return result


def status_delta(before, after):
    return {key: after[key] - before[key] for key in COUNTER_NAMES}


def app_cpu_stat(app_id):
    """Read only the identity-checked app container's cgroup v2 CPU counters."""
    try:
        raw = preflight.docker("exec", app_id, "cat", "/sys/fs/cgroup/cpu.stat")
    except preflight.Refused as exc:
        return {"available": False, "reason": str(exc)}
    values = {}
    for line in raw.splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[1].isdecimal():
            values[fields[0]] = int(fields[1])
    if not {"nr_throttled", "throttled_usec"} <= values.keys():
        return {"available": False, "reason": "cgroup v2 CPU throttling counters unavailable"}
    return {"available": True, **values}


def cpu_stat_delta(before, after):
    if not before.get("available") or not after.get("available"):
        return None
    keys = ("usage_usec", "nr_periods", "nr_throttled", "throttled_usec")
    return {key: after[key] - before[key] for key in keys if key in before and key in after}


def warm_ramp(args, project, config_index, stack, stable_ids, hikari, tomcat,
              health_baseline, expected_limits):
    """Build JVM/JIT and pool state before judging the selected A/B rate."""
    current = first_round.checked_stack()
    if current["mysql_container_id"] != stack["mysql_container_id"] or current["volumes"] != stack["volumes"]:
        raise Stopped("benchmark database or volumes changed before warm ramp")
    app_id = verify_app(project, hikari, tomcat, stable_ids, expected_limits)
    first_round.public_health(args.public_health_url, health_baseline)
    first_round.check_memory()
    before_dirs = set(RUNS_ROOT.glob("first-round-*/manifest.json"))
    error = None
    try:
        first_round.run(argparse.Namespace(public_health_url=args.public_health_url,
                                           rates=WARM_RAMP_RATES, max_in_flight=MAX_IN_FLIGHT,
                                           dry_run=False))
    except Exception as exc:
        error = str(exc)
    created = set(RUNS_ROOT.glob("first-round-*/manifest.json")) - before_dirs
    manifest_path = created.pop() if len(created) == 1 else None
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path else {}
    stages = manifest.get("stages", [])
    record = {"configIndex": config_index, "hikariPoolSize": hikari,
              "tomcatThreadsMax": tomcat, "appContainerId": app_id,
              "rates": WARM_RAMP_RATES, "maxInFlight": MAX_IN_FLIGHT,
              "firstRoundManifest": str(manifest_path) if manifest_path else None,
              "status": manifest.get("status", "MISSING_MANIFEST"),
              "stages": [{"offeredRps": stage.get("offeredRps"), "runId": stage.get("runId"),
                          "status": stage.get("status"), "summary": stage.get("summary"),
                          "resourcesJsonl": str(manifest_path.parent /
                                                f'stage-{stage.get("offeredRps", 0):02d}rps' /
                                                "resources.jsonl") if manifest_path else None}
                         for stage in stages]}
    if error:
        record["abortReason"] = error
    if (manifest_path is None or manifest.get("status") != "PASS" or len(stages) != 2
            or tuple(stage.get("offeredRps") for stage in stages) != WARM_RAMP_RATES
            or any(stage.get("status") != "PASS" for stage in stages)):
        record["abortReason"] = record.get("abortReason") or manifest.get(
            "stopReason", "20/80 RPS warm ramp did not pass both stages")
    try:
        first_round.public_health(args.public_health_url, health_baseline)
        first_round.check_memory()
        current = first_round.checked_stack()
        if (current["mysql_container_id"] != stack["mysql_container_id"]
                or current["volumes"] != stack["volumes"]):
            raise Stopped("benchmark database or volumes changed after warm ramp")
        if verify_app(project, hikari, tomcat, stable_ids, expected_limits) != app_id:
            raise Stopped("benchmark app container changed during warm ramp")
    except Exception as exc:
        record["postCheckError"] = str(exc)
        record["abortReason"] = record.get("abortReason") or str(exc)
    return record


def one_measurement(args, output_dir, project, config_index, trial, stack, stable_ids,
                    hikari, tomcat, health_baseline, expected_limits):
    current = first_round.checked_stack()
    if current["mysql_container_id"] != stack["mysql_container_id"] or current["volumes"] != stack["volumes"]:
        raise Stopped("benchmark database or named volumes changed between trials")
    app_id = verify_app(project, hikari, tomcat, stable_ids, expected_limits)
    app = project_containers(project)["app"]
    app_cpu_limit = {key: app["HostConfig"].get(key, 0)
                     for key in ("NanoCpus", "CpuQuota", "CpuPeriod")}
    pre_health = first_round.public_health(args.public_health_url, health_baseline)
    first_round.check_memory()
    before_db = database_status(stack["mysql_container_id"])
    before_cpu = app_cpu_stat(app_id)
    before_dirs = set(RUNS_ROOT.glob("first-round-*/manifest.json"))
    error = None
    try:
        first_round.run(argparse.Namespace(public_health_url=args.public_health_url,
                                           rates=(args.rate,), max_in_flight=MAX_IN_FLIGHT,
                                           dry_run=False))
    except Exception as exc:
        error = str(exc)
    after_dirs = set(RUNS_ROOT.glob("first-round-*/manifest.json"))
    created = after_dirs - before_dirs
    manifest_path = created.pop() if len(created) == 1 else None
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path else {}
    stage = manifest.get("stages", [{}])[0] if manifest.get("stages") else {}
    after_db = database_status(stack["mysql_container_id"])
    after_cpu = app_cpu_stat(app_id)
    record = {"configIndex": config_index, "trial": trial,
              "hikariPoolSize": hikari, "tomcatThreadsMax": tomcat,
              "offeredRps": args.rate,
              "appContainerId": app_id, "appCpuLimit": app_cpu_limit,
              "maxInFlight": MAX_IN_FLIGHT,
              "firstRoundManifest": str(manifest_path) if manifest_path else None,
              "resourcesJsonl": str(manifest_path.parent /
                                    f"stage-{args.rate:02d}rps" / "resources.jsonl")
                                if manifest_path else None,
              "runId": stage.get("runId"), "summary": stage.get("summary"),
              "status": manifest.get("status", "MISSING_MANIFEST"),
              "publicHealthBeforeSeconds": pre_health,
              "mysqlStatusBefore": before_db, "mysqlStatusAfter": after_db,
              "mysqlCounterDelta": status_delta(before_db, after_db),
              "appCpuStatBefore": before_cpu, "appCpuStatAfter": after_cpu,
              "appCpuStatDelta": cpu_stat_delta(before_cpu, after_cpu)}
    if error:
        record["abortReason"] = error
    if manifest_path is None:
        record["abortReason"] = (error or f"expected one manifest; found {len(created)}")
    if manifest.get("status") != "PASS" and not record.get("abortReason"):
        record["abortReason"] = manifest.get("stopReason", "first-round status is not PASS")
    if manifest.get("status") == "PASS" and stage.get("offeredRps") != args.rate:
        record["abortReason"] = "first-round offered RPS differs from requested A/B rate"
    try:
        record["publicHealthAfterSeconds"] = first_round.public_health(
            args.public_health_url, health_baseline)
        first_round.check_memory()
        current = first_round.checked_stack()
        if (current["mysql_container_id"] != stack["mysql_container_id"]
                or current["volumes"] != stack["volumes"]):
            raise Stopped("benchmark database or named volumes changed after trial")
        if verify_app(project, hikari, tomcat, stable_ids, expected_limits) != app_id:
            raise Stopped("benchmark app container changed during trial")
    except Exception as exc:
        record["postCheckError"] = str(exc)
        record["abortReason"] = record.get("abortReason") or str(exc)
    if manifest_path and manifest.get("status") == "PASS" and not Path(record["resourcesJsonl"]).exists():
        record["abortReason"] = "first-round resource JSONL missing"
    return record


def write_manifest(path, manifest):
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run(args):
    if args.rate not in ALLOWED_RATES:
        raise Stopped("A/B rate must be 80 or 160 RPS")
    # Read the original private env before touching Docker. Its values must identify
    # the live project that preflight proves is disposable.
    env_path = Path(args.env_file)
    original, values = private_env(env_path)
    # Compose runs with cwd=DOCKER_DIR; keep its --env-file absolute even when
    # the caller supplied a path relative to the repository root.
    env_path = env_path.resolve()
    apply_environment(values)
    stack = first_round.checked_stack()
    project = stack["project"]
    if project != values["COMPOSE_PROJECT_NAME"]:
        raise Stopped("env file and live benchmark project differ")
    first_round.require_s2_seed(stack["mysql_container_id"])
    first_round.validate_health_url(args.public_health_url)
    health_baseline = first_round.public_health(args.public_health_url)
    first_round.check_memory()
    containers = project_containers(project)
    compose_files = compose_files_for_project(containers, args.compose_overlay)
    stable_ids = {name: container["Id"] for name, container in containers.items() if name != "app"}
    expected_limits = app_limits(containers["app"])
    verify_app(project, 20, 200, stable_ids, expected_limits)
    if args.dry_run:
        print(json.dumps({"dryRun": True, "project": project, "configs": CONFIGS,
                          "trialsPerConfig": args.trials, "warmRampRates": WARM_RAMP_RATES,
                          "warmRoundPerConfig": args.warm_round,
                          "rate": args.rate, "maxInFlight": MAX_IN_FLIGHT,
                          "mysqlContainerId": stack["mysql_container_id"],
                          "volumes": stack["volumes"],
                          "composeFiles": [str(path) for path in compose_files],
                          "appLimits": expected_limits,
                          "publicHealthBaselineSeconds": health_baseline}, sort_keys=True))
        return

    output_dir = RUNS_ROOT / ("s2-ablations-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
                             + "-" + uuid.uuid4().hex[:8])
    output_dir.mkdir(parents=True)
    manifest_path = output_dir / "manifest.json"
    manifest = {"project": project, "api": stack["api"], "rate": args.rate,
                "maxInFlight": MAX_IN_FLIGHT,
                "warmupSeconds": first_round.WARMUP_SECONDS,
                "sampleSeconds": first_round.SAMPLE_SECONDS,
                "trialsPerConfig": args.trials, "warmRampRates": WARM_RAMP_RATES,
                "warmRoundPerConfig": args.warm_round,
                "configSequence": [{"hikariPoolSize": h, "tomcatThreadsMax": t} for h, t in CONFIGS],
                "mysqlContainerId": stack["mysql_container_id"], "volumes": stack["volumes"],
                "composeFiles": [str(path) for path in compose_files],
                "appLimits": expected_limits,
                "publicHealthBaselineSeconds": health_baseline,
                "warmRamps": [], "measurements": [], "status": "RUNNING"}
    write_manifest(manifest_path, manifest)
    current_bytes = original
    app_recreated = False
    stopped = None
    try:
        for config_index, (hikari, tomcat) in enumerate(CONFIGS, 1):
            current = first_round.checked_stack()
            if current["mysql_container_id"] != stack["mysql_container_id"] or current["volumes"] != stack["volumes"]:
                raise Stopped("benchmark database or volumes changed before configuration")
            previous = CONFIGS[config_index - 2] if config_index > 1 else CONFIGS[0]
            verify_app(project, previous[0], previous[1], stable_ids, expected_limits)
            target = setting_bytes(original, hikari, tomcat)
            restore_or_replace(env_path, current_bytes, target)
            current_bytes = target
            app_recreated = True
            recreate_app(env_path, project, hikari, tomcat, compose_files)
            current = first_round.checked_stack()
            if current["mysql_container_id"] != stack["mysql_container_id"] or current["volumes"] != stack["volumes"]:
                raise Stopped("benchmark database or volumes changed after app recreation")
            verify_app(project, hikari, tomcat, stable_ids, expected_limits)
            ramp = warm_ramp(args, project, config_index, stack, stable_ids, hikari, tomcat,
                             health_baseline, expected_limits)
            manifest["warmRamps"].append(ramp)
            write_manifest(manifest_path, manifest)
            if ramp.get("abortReason") or ramp["status"] != "PASS":
                raise Stopped(f"config {config_index} warm ramp stopped: "
                              f"{ramp.get('abortReason') or ramp['status']}")
            trials = ([0] if args.warm_round else []) + list(range(1, args.trials + 1))
            for trial in trials:
                entry = one_measurement(args, output_dir, project, config_index, trial,
                                        stack, stable_ids, hikari, tomcat, health_baseline,
                                        expected_limits)
                entry["warmRound"] = trial == 0
                manifest["measurements"].append(entry)
                write_manifest(manifest_path, manifest)
                if entry.get("abortReason") or entry.get("status") != "PASS":
                    raise Stopped(f"trial {config_index}.{trial} stopped: "
                                  f"{entry.get('abortReason') or entry.get('status')}")
        manifest["status"] = "PASS"
    except BaseException as exc:
        stopped = exc
        manifest["status"] = "STOPPED"
        manifest["stopReason"] = str(exc)
    finally:
        try:
            if current_bytes != original:
                restore_or_replace(env_path, current_bytes, original)
            if app_recreated:
                # Recreate once more from the original 20/200 config, then prove
                # that no other project service or volume was touched.
                recreate_app(env_path, project, 20, 200, compose_files)
                restored = first_round.checked_stack()
                if (restored["mysql_container_id"] != stack["mysql_container_id"]
                        or restored["volumes"] != stack["volumes"]):
                    raise Stopped("database or volumes changed during app restoration")
                verify_app(project, 20, 200, stable_ids, expected_limits)
            manifest["restoredOriginalEnvAndApp"] = True
        except BaseException as exc:
            manifest["status"] = "RESTORE_FAILED"
            manifest["restoredOriginalEnvAndApp"] = False
            manifest["restoreError"] = str(exc)
            stopped = Stopped(f"ablation restoration failed: {exc}")
        write_manifest(manifest_path, manifest)
        print(f"S2 ablation records: {output_dir}", flush=True)
    if stopped is not None:
        raise stopped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True, help="private docker/.env.bench* file")
    parser.add_argument("--compose-overlay", type=Path,
                        help="required for the exact extra Compose file in the live bench app identity")
    parser.add_argument("--trials", type=int, choices=(2, 3), default=2)
    parser.add_argument("--rate", type=int, choices=ALLOWED_RATES, default=DEFAULT_RATE,
                        help="measured offered RPS; default 80, optional 160")
    parser.add_argument("--no-warm-round", action="store_false", dest="warm_round",
                        help="skip the extra full-length warm round per configuration")
    parser.add_argument("--public-health-url", default=first_round.DEFAULT_PUBLIC_HEALTH)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    def handle_signal(signum, _frame):
        raise Stopped(f"signal {signum} requested stop")
    signal.signal(signal.SIGTERM, handle_signal)
    try:
        _, values = private_env(args.env_file)
        RUNS_ROOT.mkdir(parents=True, exist_ok=True)
        lock_path = RUNS_ROOT / f'.{values["COMPOSE_PROJECT_NAME"]}.s2-ablations.lock'
        descriptor = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            run(args)
        finally:
            os.close(descriptor)
    except BaseException as exc:
        print(f"S2 ablations stopped: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
