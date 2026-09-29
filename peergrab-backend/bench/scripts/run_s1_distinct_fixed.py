#!/usr/bin/env python3
"""Guarded fixed-arrival independent-task grab probe on one fresh benchmark stack.

Fixtures use JMeterFixtureExporter for distinct task IDs and runner JWTs, but the
timed phase uses a bounded monotonic-clock scheduler. This is one finite sample,
not a whole-site capacity or availability claim.
"""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, wait
import csv
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request

import preflight
import run_s6_fault_smoke as s6


BACKEND = Path(__file__).resolve().parents[2]
RUNS = BACKEND / "bench" / "runs"
MAX_TASKS = 900


def validate_config(args):
    if not 1 <= args.rate <= 100 or not 5 <= args.seconds <= 60:
        raise s6.Refused("Rate must be 1..100/s and sample duration 5..60s")
    tasks = args.rate * args.seconds
    if tasks > MAX_TASKS:
        raise s6.Refused("Independent grab fixtures are capped at 900 funded tasks")
    if not 1 <= args.max_in_flight <= 128 or not 1_000 <= args.timeout_ms <= 10_000:
        raise s6.Refused("max-in-flight must be 1..128 and timeout-ms 1000..10000")
    return tasks


def runtime_config(project, seconds, timeout_ms):
    app = s6.inspected_service(project, "app")
    env = dict(item.split("=", 1) for item in app["Config"]["Env"] if "=" in item)
    if env.get("PEERGRAB_AUTH_MODE") != "jwt" or env.get("PEERGRAB_AUTH_ALLOW_HEADER_IDENTITY") != "false":
        raise s6.Refused("Benchmark app must use JWT with header identity disabled")
    confirm = env.get("PEERGRAB_TIMEOUT_CONFIRM_SECONDS", "")
    if not confirm.isdecimal() or int(confirm) < seconds + timeout_ms / 1000 + 60:
        raise s6.Refused("Confirmation timeout is too short for the fixed-arrival sample")
    return app["Id"]


def output_path(value):
    output = Path(value).resolve()
    if (RUNS.is_symlink() or output.parent != RUNS.resolve() or output.exists()
            or not re.fullmatch(r"[a-zA-Z0-9_-]+", output.name)):
        raise s6.Refused("Output must be a new immediate child of bench/runs")
    return output


def fixture_rows(output, expected):
    with (output / "s1_distinct.csv").open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != ["errand_id", "token", "request_id"]:
            raise s6.Refused("Distinct fixture has unexpected columns")
        rows = list(reader)
    if len(rows) != expected or any(None in row for row in rows):
        raise s6.Refused("Distinct fixture count differs from offered requests")
    ids, request_ids = set(), set()
    for row in rows:
        if (not re.fullmatch(r"[1-9][0-9]{0,18}", row["errand_id"])
                or len(row["token"]) < 30
                or not re.fullmatch(r"[a-f0-9-]{36}", row["request_id"])):
            raise s6.Refused("Distinct fixture has an invalid task, token or request ID")
        ids.add(row["errand_id"])
        request_ids.add(row["request_id"])
    if len(ids) != expected or len(request_ids) != expected:
        raise s6.Refused("Distinct fixture IDs are not unique")
    return rows


def grab_request(base, fixture, timeout_ms, scheduled_ns):
    started = time.monotonic_ns()
    request = Request(base.rstrip("/") + "/api/errands/" + fixture["errand_id"] + "/grab",
                      data=b"{}", method="POST",
                      headers={"Authorization": "Bearer " + fixture["token"],
                               "Content-Type": "application/json",
                               "X-Request-Id": fixture["request_id"]})
    status, outcome = None, "transport_error"
    try:
        with s6.DIRECT_HTTP.open(request, timeout=timeout_ms / 1000) as response:
            status = response.status
            payload = json.load(response)
        if isinstance(payload, dict) and status == 200 and payload.get("code") == "OK" \
                and isinstance(payload.get("data"), dict) and payload["data"].get("grabbed") is True:
            outcome = "ok"
        else:
            outcome = "business_error"
    except HTTPError as error:
        status = error.code
        error.close()
        outcome = "http_error"
    except (TimeoutError, socket.timeout):
        outcome = "transport_timeout"
    except URLError as error:
        outcome = ("transport_timeout" if isinstance(error.reason, (TimeoutError, socket.timeout))
                   else "transport_error")
    except ValueError:
        outcome = "business_error"
    except OSError:
        outcome = "transport_error"
    finished = time.monotonic_ns()
    return {"errandId": int(fixture["errand_id"]), "outcome": outcome, "httpStatus": status,
            "latencyMs": round((finished - started) / 1_000_000, 3),
            "startLagMs": round(max(0, started - scheduled_ns) / 1_000_000, 3),
            "startedNs": started, "finishedNs": finished}


def fixed_arrival_phase(base, fixtures, rate, seconds, max_in_flight, timeout_ms):
    s6.local_api_origin(base)
    offered = rate * seconds
    if len(fixtures) != offered:
        raise s6.Refused("Fixture count does not match offered arrivals")
    available = threading.BoundedSemaphore(max_in_flight)
    futures = []
    scheduler_missed = local_rejected = 0
    allowed_lag_ns = max(5_000_000, min(100_000_000, 2_000_000_000 // rate))
    with ThreadPoolExecutor(max_workers=max_in_flight) as pool:
        # Create worker threads before the measured window. No HTTP requests run here.
        barrier = threading.Barrier(max_in_flight + 1)
        warmed = [pool.submit(barrier.wait, timeout=5) for _ in range(max_in_flight)]
        try:
            barrier.wait(timeout=5)
        except threading.BrokenBarrierError as error:
            raise s6.Refused("Fixed-arrival worker pool did not warm within five seconds") from error
        _, unready = wait(warmed, timeout=5)
        if unready:
            raise s6.Refused("Fixed-arrival worker pool did not warm within five seconds")
        for future in warmed:
            future.result()
        phase_start = time.monotonic_ns() + 100_000_000
        window_end = phase_start + seconds * 1_000_000_000
        for index, fixture in enumerate(fixtures):
            target = phase_start + index * 1_000_000_000 // rate
            remaining = (target - time.monotonic_ns()) / 1_000_000_000
            if remaining > 0:
                time.sleep(remaining)
            if time.monotonic_ns() - target > allowed_lag_ns:
                scheduler_missed += 1
                continue
            if not available.acquire(blocking=False):
                local_rejected += 1
                continue
            future = pool.submit(grab_request, base, fixture, timeout_ms, target)
            future.add_done_callback(lambda _done: available.release())
            futures.append(future)
        remaining = (window_end - time.monotonic_ns()) / 1_000_000_000
        if remaining > 0:
            time.sleep(remaining)
        completed_within_window = sum(future.done() for future in futures)
        done, pending = wait(futures, timeout=timeout_ms / 1000 + 5)
        if pending:
            raise s6.Refused("Independent grab requests exceeded bounded drain time")
        results = [future.result() for future in futures]
    outcomes = Counter(item["outcome"] for item in results)
    late_started = sum(item["startLagMs"] * 1_000_000 > allowed_lag_ns for item in results)
    return {"offered": offered, "sent": len(futures), "completed": len(results),
            "completedWithinWindow": completed_within_window,
            "offeredRps": rate, "sentRps": round(len(futures) / seconds, 3),
            "completedRps": round(completed_within_window / seconds, 3),
            "completedWithinWindowRps": round(completed_within_window / seconds, 3),
            "schedulerMissed": scheduler_missed, "localRejected": local_rejected,
            "workerStartLate": late_started, "maxInFlight": max_in_flight,
            "maxWorkerStartLagMs": max((item["startLagMs"] for item in results), default=None),
            "timeoutMs": timeout_ms, "outcomes": dict(sorted(outcomes.items())),
            "p50Ms": s6.percentile([item["latencyMs"] for item in results], 50),
            "p95Ms": s6.percentile([item["latencyMs"] for item in results], 95),
            "p99Ms": s6.percentile([item["latencyMs"] for item in results], 99),
            "clientStartSpanMs": (round((max(item["startedNs"] for item in results)
                                          - min(item["startedNs"] for item in results)) / 1_000_000, 3)
                                  if results else None),
            "results": [{key: value for key, value in item.items()
                         if key not in {"startedNs", "finishedNs"}} for item in results]}


def durable_truth(mysql_id, run_id):
    if not re.fullmatch(r"[0-9]{8}-[0-9]{6}(?:-[0-9]+)?", run_id):
        raise s6.Refused("Unexpected benchmark run ID")
    tracked = s6.scalar(mysql_id, f"SELECT COUNT(*) FROM bench_run_item WHERE run_id='{run_id}'")
    locked = s6.scalar(mysql_id, f"""
        SELECT COUNT(*) FROM bench_run_item i JOIN errand e ON e.id=i.entity_id
         WHERE i.run_id='{run_id}' AND e.status='LOCKED'
           AND e.slot_total=1 AND e.slot_taken=1 AND e.grabber_id IS NOT NULL
        """)
    grabbed = s6.one_row(mysql_id, f"""
        SELECT COUNT(*),COUNT(DISTINCT g.errand_id),
               COALESCE(SUM(g.runner_id <> e.grabber_id),0)
          FROM bench_run_item i JOIN errand e ON e.id=i.entity_id
          JOIN grab_record g ON g.errand_id=e.id AND g.result='GRABBED'
         WHERE i.run_id='{run_id}'
        """, 3)
    return {"tracked": tracked, "locked": locked, "grabbedRows": int(grabbed[0]),
            "distinctGrabbedTasks": int(grabbed[1]), "runnerMismatches": int(grabbed[2])}


def clean_result(phase, truth, expected):
    return (phase["offered"] == expected and phase["sent"] == expected
            and phase["completed"] == expected and phase["schedulerMissed"] == 0
            and phase["localRejected"] == 0 and phase["workerStartLate"] == 0
            and phase["outcomes"] == {"ok": expected}
            and truth == {"tracked": expected, "locked": expected,
                          "grabbedRows": expected, "distinctGrabbedTasks": expected,
                          "runnerMismatches": 0})


def finish_run(mysql_id, run_id, status, summary):
    if not re.fullmatch(r"[0-9]{8}-[0-9]{6}(?:-[0-9]+)?", run_id):
        raise s6.Refused("Unexpected benchmark run ID")
    compact = json.dumps(summary, ensure_ascii=True, separators=(",", ":"))
    if "'" in compact:
        raise s6.Refused("Benchmark summary contains unsafe SQL text")
    s6.mysql(mysql_id, f"UPDATE bench_run SET status='{status}', finished_at=NOW(3), "
            f"summary=CAST('{compact}' AS JSON) WHERE run_id='{run_id}';")


def main(argv=None):
    signal.signal(signal.SIGTERM, s6.interrupted)
    signal.signal(signal.SIGINT, s6.interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--rate", type=int, required=True)
    parser.add_argument("--seconds", type=int, required=True)
    parser.add_argument("--max-in-flight", type=int, default=64)
    parser.add_argument("--timeout-ms", type=int, default=8000)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-project")
    args = parser.parse_args(argv)
    output = None
    mysql_id = None
    run_id = None
    try:
        expected = validate_config(args)
        output = output_path(args.output)
        s6.production_stopped()
        base = os.getenv("PEERGRAB_BENCH_BASE_URL")
        db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
        db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
        stack = preflight.check(args.project, base, db_host, db_port, "true", "true")
        s6.require_unpaused_services(args.project)
        app_id = runtime_config(args.project, args.seconds, args.timeout_ms)
        mysql_id = stack["mysql_container_id"]
        s6.fresh_stack(mysql_id)
        if not args.execute:
            print(json.dumps({"status": "DRY_RUN", "project": args.project,
                              "offered": expected, "offeredRps": args.rate,
                              "seconds": args.seconds, "output": str(output)}, sort_keys=True))
            return 0
        if args.confirm_project != args.project:
            raise s6.Refused("Execution requires exact --confirm-project")
        before = s6.snapshot(mysql_id)
        s6.assert_invariants(before, before)
        subprocess.run(["mvn", "-q", "-ntp", "-pl", "peergrab-bench", "-am",
                        "install", "-DskipTests"], cwd=BACKEND, check=True, timeout=300)
        subprocess.run(["mvn", "-q", "-ntp", "-pl", "peergrab-bench", "exec:java",
                        "-Dexec.mainClass=com.peergrab.bench.JMeterFixtureExporter",
                        "-Dexec.args=" + " ".join(["s1-distinct", base, str(output),
                                                    str(expected), str(args.max_in_flight)])],
                       cwd=BACKEND, check=True, timeout=600)
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        if (manifest.get("project") != args.project or manifest.get("scenario") != "s1-distinct"
                or manifest.get("baseUrl") != base
                or manifest.get("tasks") != expected):
            raise s6.Refused("Fixture manifest differs from requested independent grab load")
        run_id = manifest["runId"]
        fixtures = fixture_rows(output, expected)
        checked = preflight.check(args.project, base, db_host, db_port, "true", "true")
        s6.require_unpaused_services(args.project)
        if (checked["mysql_container_id"] != mysql_id
                or s6.inspected_service(args.project, "app")["Id"] != app_id):
            raise s6.Refused("Benchmark stack identity changed after fixture preparation")
        prepared = s6.snapshot(mysql_id)
        s6.assert_invariants(before, prepared)
        phase = fixed_arrival_phase(base, fixtures, args.rate, args.seconds,
                                    args.max_in_flight, args.timeout_ms)
        truth = durable_truth(mysql_id, run_id)
        after = s6.snapshot(mysql_id)
        s6.assert_invariants(before, after)
        clean = clean_result(phase, truth, expected)
        summary = {key: value for key, value in phase.items() if key != "results"}
        summary.update({"scenario": "S1-DISTINCT-FIXED", "runId": run_id,
                        "project": args.project, "durable": truth,
                        "walletTotalBefore": before["walletTotal"],
                        "walletTotalAfter": after["walletTotal"],
                        "status": "PASS" if clean else "FAIL"})
        (output / "results.json").write_text(json.dumps(phase["results"], indent=2) + "\n")
        (output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
        finish_run(mysql_id, run_id, summary["status"], summary)
        print(json.dumps(summary, sort_keys=True))
        return 0 if clean else 1
    except Exception as error:
        if output is not None and mysql_id is not None and (output / "manifest.json").is_file():
            try:
                failed_run = json.loads((output / "manifest.json").read_text(encoding="utf-8"))["runId"]
                finish_run(mysql_id, failed_run, "FAIL", {"reason": "fixed_arrival_or_postcheck_failed"})
            except Exception:
                pass
        print("S1 fixed-arrival grab refused/failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
