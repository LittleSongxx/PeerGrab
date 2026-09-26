#!/usr/bin/env python3
"""Guarded, finite S2-OPEN first round on the ECS Docker host.

This measures only the disposable loopback benchmark stack. The public site is
queried at stage boundaries with low-frequency GET /api/health requests.
"""

import argparse
from datetime import datetime, timezone
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid

import collect_metrics
import preflight
from seed_bench import mysql


SCRIPTS = Path(__file__).resolve().parent
BACKEND = SCRIPTS.parents[1]
RUNS_ROOT = BACKEND / "bench" / "runs"
DEFAULT_RATES = (5, 10, 20)
ALLOWED_RATES = (5, 10, 20, 40, 80, 160, 320)
WARMUP_SECONDS = 10
SAMPLE_SECONDS = 30
MAX_IN_FLIGHT = 32
ALLOWED_MAX_IN_FLIGHT = (32, 64, 128)
TIMEOUT_MS = 5000
MIN_AVAILABLE_BYTES = 3 * 1024 ** 3
MAX_STAGE_WALL_SECONDS = 75
DEFAULT_PUBLIC_HEALTH = "https://www.peergrab.cn/api/health"
RUN_ID = re.compile(r"[0-9]{8}-[0-9]{6}(?:-[0-9]+)?\Z")
S2_SEED_RANGE = (930000000000, 930000100000)


class Stopped(RuntimeError):
    """A guard or correctness check stopped the next load stage."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


def parse_rates(value):
    try:
        rates = tuple(int(part) for part in value.split(","))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("--rates must be comma-separated integers") from exc
    if (not 1 <= len(rates) <= 3 or any(rate not in ALLOWED_RATES for rate in rates)
            or any(left >= right for left, right in zip(rates, rates[1:]))):
        raise argparse.ArgumentTypeError(
            "--rates must have 1..3 strictly increasing values from 5,10,20,40,80,160,320")
    return rates


def validate_health_url(url):
    parsed = urlsplit(url)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path != "/api/health"):
        raise Stopped("Public health URL must be https://<host>/api/health")
    try:
        address = ipaddress.ip_address(parsed.hostname)
    except ValueError:
        if parsed.hostname in {"localhost", "localhost.localdomain"}:
            raise Stopped("Public health host cannot be local")
    else:
        if not address.is_global:
            raise Stopped("Public health IP must be global")
    return url


def public_health(url, baseline_seconds=None):
    request = Request(url, method="GET", headers={"Accept": "application/json"})
    start = time.monotonic()
    with build_opener(NoRedirect).open(request, timeout=3) as response:
        body = response.read(4097)
        if response.status != 200 or len(body) > 4096:
            raise Stopped("Public health returned a non-200 or oversized response")
    elapsed = time.monotonic() - start
    try:
        health = json.loads(body)
    except (ValueError, UnicodeDecodeError) as exc:
        raise Stopped("Public health returned invalid JSON") from exc
    if health.get("code") != "OK" or health.get("data", {}).get("status") != "UP":
        raise Stopped("Public health is not UP")
    if elapsed > 2.0:
        raise Stopped(f"Public health latency exceeded 2 s ({elapsed:.3f} s)")
    if baseline_seconds is not None and elapsed > max(1.0, 3 * baseline_seconds):
        raise Stopped(f"Public health latency degraded ({elapsed:.3f} s vs {baseline_seconds:.3f} s)")
    return round(elapsed, 3)


def check_memory():
    available = collect_metrics.available_memory()
    if available < MIN_AVAILABLE_BYTES:
        raise Stopped(f"Host MemAvailable below 3 GiB ({available / 1024 ** 3:.2f} GiB)")
    return available


def checked_stack():
    project = os.getenv("PEERGRAB_BENCH_PROJECT")
    base_url = os.getenv("PEERGRAB_BENCH_BASE_URL")
    db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
    db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
    preflight.require(all((project, base_url, db_host, db_port)),
                      "Export the disposable benchmark environment first")
    preflight.require(bool(os.getenv("PEERGRAB_TEST_DB_PASSWORD")),
                      "Missing benchmark DB password")
    return preflight.check(project, base_url, db_host, db_port)


def run_ids(mysql_container):
    rows = mysql(mysql_container, "SELECT run_id FROM bench_run WHERE scenario='S2-OPEN';")
    ids = set(rows.splitlines()) if rows else set()
    if not all(RUN_ID.fullmatch(value) for value in ids):
        raise Stopped("Unexpected benchmark run ID in disposable database")
    return ids


def require_s2_seed(mysql_container):
    lower, upper = S2_SEED_RANGE
    value = mysql(mysql_container, "SELECT COUNT(*) FROM errand "
                  f"WHERE id > {lower} AND id <= {upper} "
                  "AND campus_id=1 AND status='PUBLISHED';")
    if not value.isdecimal() or int(value) < 10_000:
        raise Stopped("S2 seed requires at least 10,000 published benchmark tasks")
    return int(value)


def new_result(mysql_container, prior_ids, rate, max_in_flight):
    added = run_ids(mysql_container) - prior_ids
    if len(added) != 1:
        raise Stopped(f"Expected one new S2-OPEN run at {rate} RPS; found {len(added)}")
    run_id = added.pop()
    row = mysql(mysql_container, "SELECT status, CAST(summary AS CHAR) FROM bench_run "
                f"WHERE run_id='{run_id}' AND scenario='S2-OPEN';")
    fields = row.split("\t", 1)
    if len(fields) != 2:
        raise Stopped(f"Run {run_id} has no status/summary")
    status, raw_summary = fields
    try:
        summary = None if raw_summary in (r"\N", "NULL") else json.loads(raw_summary)
    except ValueError as exc:
        raise Stopped(f"Run {run_id} has invalid summary JSON") from exc
    if summary is not None and not isinstance(summary, dict):
        raise Stopped(f"Run {run_id} summary is not an object")
    result = {"runId": run_id, "status": status, "summary": summary}
    required = {"offeredRps": rate, "warmupSeconds": WARMUP_SECONDS,
                "sampleSeconds": SAMPLE_SECONDS, "maxInFlightLimit": max_in_flight,
                "timeoutMillis": TIMEOUT_MS, "offered": rate * SAMPLE_SECONDS,
                "ok": rate * SAMPLE_SECONDS, "localRejected": 0}
    if status == "PASS" and (summary is None or any(summary.get(key) != value
                                             for key, value in required.items())):
        raise Stopped(f"Run {run_id} summary differs from the requested stage")
    return result


def fail_interrupted_run(project, base_url, mysql_container, run_id, reason):
    """Close only this newly observed RUNNING row in the same verified bench DB."""
    if not RUN_ID.fullmatch(run_id) or not reason:
        raise Stopped("Refusing to finalize an invalid benchmark run")
    current = checked_stack()
    if (current["project"] != project or current["api"] != base_url
            or current["mysql_container_id"] != mysql_container):
        raise Stopped("Benchmark stack identity changed; refusing run finalization")
    # Hex-encode the reason so untrusted exception text cannot change SQL syntax.
    encoded_reason = reason[:1024].encode("utf-8").hex()
    changed = mysql(mysql_container,
                    "UPDATE bench_run SET status='FAIL', finished_at=NOW(3), "
                    "summary=JSON_OBJECT('abortReason', "
                    f"CONVERT(0x{encoded_reason} USING utf8mb4)) "
                    f"WHERE run_id='{run_id}' AND kind='BENCH' "
                    "AND scenario='S2-OPEN' AND status='RUNNING'; "
                    "SELECT ROW_COUNT();")
    if changed != "1":
        raise Stopped(f"Run {run_id} was not a single RUNNING S2-OPEN row at finalization")


def check_resource_samples(path, state):
    if not path.exists():
        return
    lines = path.read_text(encoding="utf-8").splitlines()
    for line in lines[state["seen"]:]:
        try:
            entry = json.loads(line)
        except ValueError:
            break  # The collector may be writing its last line.
        state["seen"] += 1
        if entry.get("kind") != "sample":
            continue
        host = entry.get("host", {})
        if host.get("memAvailableBytes", 0) < MIN_AVAILABLE_BYTES:
            raise Stopped("Metrics sample shows MemAvailable below 3 GiB")
        if host.get("ioWaitPercent", 100) > 10:
            raise Stopped(f"Host iowait exceeded 10% ({host['ioWaitPercent']}%)")
        state["high_cpu"] = state["high_cpu"] + 1 if host.get("cpuPercent", 100) > 85 else 0
        if state["high_cpu"] >= 2:
            raise Stopped("Host CPU exceeded 85% for two consecutive metrics samples")


def terminate_group(process):
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            return
        process.wait(timeout=5)


def run_stage(project, base_url, output_dir, rate, mysql_container, max_in_flight):
    stage_dir = output_dir / f"stage-{rate:02d}rps"
    stage_dir.mkdir()
    metrics = [sys.executable, str(SCRIPTS / "collect_metrics.py"), "--project", project,
               "--duration", str(MAX_STAGE_WALL_SECONDS), "--interval", "2",
               "--output", str(stage_dir / "resources.jsonl")]
    load = ["mvn", "-B", "-ntp", "-q", "-pl", "peergrab-bench", "exec:java",
            "-Dexec.mainClass=com.peergrab.bench.OpenLoopLoadClient",
            f"-Dexec.args={base_url} {rate} {WARMUP_SECONDS} {SAMPLE_SECONDS} "
            f"{max_in_flight} {TIMEOUT_MS}"]
    before = run_ids(mysql_container)
    sample_file = stage_dir / "resources.jsonl"
    resource_state = {"seen": 0, "high_cpu": 0}
    abort_reason = None
    with (stage_dir / "metrics.log").open("x", encoding="utf-8") as metric_log, \
            (stage_dir / "load.log").open("x", encoding="utf-8") as load_log:
        collector = subprocess.Popen(metrics, cwd=BACKEND, stdout=metric_log,
                                     stderr=subprocess.STDOUT, start_new_session=True)
        loader = None
        try:
            # Refuse to send any requests unless the collector has opened its JSONL.
            start_deadline = time.monotonic() + 10
            while not sample_file.exists():
                if collector.poll() is not None:
                    raise Stopped("Metrics collector exited before load started")
                if time.monotonic() > start_deadline:
                    raise Stopped("Metrics collector did not start within 10 s")
                time.sleep(0.2)
            check_resource_samples(sample_file, resource_state)
            loader = subprocess.Popen(load, cwd=BACKEND, stdout=load_log,
                                      stderr=subprocess.STDOUT, start_new_session=True)
            deadline = time.monotonic() + MAX_STAGE_WALL_SECONDS
            while loader.poll() is None:
                check_memory()
                check_resource_samples(sample_file, resource_state)
                if collector.poll() is not None:
                    raise Stopped("Metrics collector exited during load")
                if time.monotonic() > deadline:
                    raise Stopped("Load stage exceeded 75 s wall clock")
                time.sleep(1)
            check_resource_samples(sample_file, resource_state)
            if loader.returncode:
                raise Stopped(f"Load client exited {loader.returncode}; inspect {stage_dir / 'load.log'}")
            check_memory()
        except Exception as exc:
            abort_reason = str(exc)
        finally:
            if loader is not None:
                terminate_group(loader)
            terminate_group(collector)
    try:
        result = new_result(mysql_container, before, rate, max_in_flight)
    except Exception as exc:
        if abort_reason:
            raise Stopped(f"{abort_reason}; benchmark row unavailable: {exc}") from exc
        raise
    if result["status"] == "RUNNING" and not abort_reason:
        abort_reason = "Load client exited without finalizing its benchmark run"
    try:
        if abort_reason and result["status"] == "RUNNING":
            fail_interrupted_run(project, base_url, mysql_container, result["runId"], abort_reason)
            result = new_result(mysql_container, before, rate, max_in_flight)
            if result["status"] != "FAIL":
                raise Stopped(f"Run {result['runId']} did not become FAIL after finalization")
    except Exception as exc:
        result["finalizationError"] = str(exc)
        raise
    finally:
        if abort_reason:
            result["abortReason"] = abort_reason
        (stage_dir / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n",
                                                 encoding="utf-8")
    return result


def run(args):
    # The live Docker identity check is always first, including --dry-run.
    stack = checked_stack()
    rates = parse_rates(args.rates) if isinstance(args.rates, str) else args.rates
    max_in_flight = getattr(args, "max_in_flight", MAX_IN_FLIGHT)
    if (not isinstance(rates, tuple) or not 1 <= len(rates) <= 3
            or any(rate not in ALLOWED_RATES for rate in rates)
            or any(left >= right for left, right in zip(rates, rates[1:]))):
        raise Stopped("Invalid rates; use 1..3 increasing values from 5,10,20,40,80,160,320")
    if max_in_flight not in ALLOWED_MAX_IN_FLIGHT:
        raise Stopped("max-in-flight must be 32, 64 or 128")
    project = stack["project"]
    base_url = stack["api"]
    seed_count = require_s2_seed(stack["mysql_container_id"])
    validate_health_url(args.public_health_url)
    baseline = public_health(args.public_health_url)
    check_memory()
    if args.dry_run:
        print(json.dumps({"dryRun": True, "project": project, "api": base_url,
                          "rates": rates, "warmupSeconds": WARMUP_SECONDS,
                          "sampleSeconds": SAMPLE_SECONDS, "maxInFlight": max_in_flight,
                          "timeoutMillis": TIMEOUT_MS,
                          "seededTasks": seed_count,
                          "publicHealthBaselineSeconds": baseline}, sort_keys=True))
        return
    output_dir = RUNS_ROOT / ("first-round-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
                             + "-" + uuid.uuid4().hex[:8])
    output_dir.mkdir(parents=True)
    manifest = {"project": project, "api": base_url, "publicHealthUrl": args.public_health_url,
                "publicHealthBaselineSeconds": baseline, "rates": rates,
                "warmupSeconds": WARMUP_SECONDS, "sampleSeconds": SAMPLE_SECONDS,
                "maxInFlight": max_in_flight, "timeoutMillis": TIMEOUT_MS,
                "seededTasks": seed_count, "stages": []}
    try:
        for rate in rates:
            # Recheck the actual stack and the public demo at every boundary.
            current = checked_stack()
            if current["mysql_container_id"] != stack["mysql_container_id"]:
                raise Stopped("Benchmark MySQL container changed during the round")
            pre_health = public_health(args.public_health_url, baseline)
            pre_memory = check_memory()
            print(f"Starting {rate} offered RPS on {project}", flush=True)
            stage = {"offeredRps": rate, "preHealthSeconds": pre_health,
                     "preMemAvailableBytes": pre_memory}
            stage_error = None
            try:
                result = run_stage(project, base_url, output_dir, rate,
                                   stack["mysql_container_id"], max_in_flight)
                stage.update(result)
            except Exception as exc:
                stage_error = exc
                stage["error"] = str(exc)
            try:
                stage["postHealthSeconds"] = public_health(args.public_health_url, baseline)
                stage["postMemAvailableBytes"] = check_memory()
            except Exception as exc:
                stage["postCheckError"] = str(exc)
                if stage_error is None:
                    stage_error = exc
            manifest["stages"].append(stage)
            (output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n",
                                                       encoding="utf-8")
            if stage_error is not None:
                raise stage_error
            if result.get("abortReason"):
                raise Stopped(result["abortReason"])
            if result["status"] != "PASS":
                raise Stopped(f"Run {result['runId']} status is {result['status']}")
            print(f"Completed {rate} RPS: runId={result['runId']} status=PASS", flush=True)
        manifest["status"] = "PASS"
    except Exception as exc:
        manifest["status"] = "STOPPED"
        manifest["stopReason"] = str(exc)
        raise
    finally:
        (output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n",
                                                   encoding="utf-8")
        print(f"First-round records: {output_dir}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-health-url", default=DEFAULT_PUBLIC_HEALTH,
                        help="HTTPS /api/health endpoint; only low-frequency GET requests")
    parser.add_argument("--rates", type=parse_rates, default=DEFAULT_RATES,
                        help="1..3 increasing offered RPS values from 5,10,20,40,80,160,320; default 5,10,20")
    parser.add_argument("--max-in-flight", type=int, choices=ALLOWED_MAX_IN_FLIGHT,
                        default=MAX_IN_FLIGHT,
                        help="client in-flight request cap: 32, 64 or 128; default 32")
    parser.add_argument("--dry-run", action="store_true",
                        help="verify stack, public health and memory without sending benchmark traffic")
    args = parser.parse_args()
    try:
        run(args)
    except Exception as exc:
        print(f"First round stopped: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
