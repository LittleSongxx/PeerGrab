#!/usr/bin/env python3
"""Guarded, fixed-arrival settlement load during a disposable Broker pause.

Run only on the Docker host during an approved maintenance window. This probe
never targets a public URL or deletes data. One fresh stack is consumed per run.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, wait
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import sys
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request
import uuid

import preflight
import run_s6_fault_smoke as s6


RUNS = Path(__file__).resolve().parents[1] / "runs"
MAX_IN_FLIGHT = 48


def settlement_request(base, token, errand_id, timeout):
    """Keep failed client responses so they can be reconciled to MySQL truth."""
    s6.local_api_origin(base)
    started = time.monotonic()
    request = Request(base.rstrip("/") + f"/api/errands/{errand_id}/settle",
                      data=b"{}", method="POST",
                      headers={"Accept": "application/json", "Content-Type": "application/json",
                               "Authorization": "Bearer " + token})
    status, code, outcome = None, None, "transport_error"
    try:
        with s6.DIRECT_HTTP.open(request, timeout=timeout) as response:
            status = response.status
            payload = json.load(response)
        code = payload.get("code") if isinstance(payload, dict) else None
        outcome = "ok" if status == 200 and code == "OK" and \
            isinstance(payload.get("data"), dict) and \
            payload["data"].get("result") == "SETTLED" else "business_error"
    except HTTPError as exc:
        status = exc.code
        try:
            payload = json.load(exc)
            code = payload.get("code") if isinstance(payload, dict) else None
        except (ValueError, OSError):
            pass
        finally:
            exc.close()
        outcome = "http_error"
    except (URLError, TimeoutError, OSError):
        pass
    except (ValueError, TypeError):
        outcome = "business_error"
    return {"errandId": errand_id, "outcome": outcome, "httpStatus": status,
            "businessCode": code,
            "latencyMs": round((time.monotonic() - started) * 1000, 3)}


def outbox_sample(mysql_id):
    row = s6.one_row(mysql_id, """
        SELECT COALESCE(SUM(status='PENDING'),0), COALESCE(SUM(status='SENT'),0),
               COALESCE(MAX(CASE WHEN status='PENDING'
                 THEN TIMESTAMPDIFF(MICROSECOND,created_at,NOW(3)) ELSE 0 END),0)
          FROM fund_event_outbox;
        """, 3)
    return {"atUtc": s6.utc_now(), "pending": int(row[0]), "sent": int(row[1]),
            "oldestPendingMs": round(max(0, int(row[2])) / 1000, 3)}


def sample_until_stopped(mysql_id, stopped, samples, errors):
    while not stopped.is_set():
        try:
            samples.append(outbox_sample(mysql_id))
        except Exception as exc:
            errors.append(str(exc)[:200])
            break
        stopped.wait(1)


def fixed_arrival_phase(base, token, task_ids, rate, seconds, timeout):
    """Each task gets one request; no catch-up burst or hidden retries."""
    expected = rate * seconds
    if len(task_ids) != expected:
        raise s6.Refused("Task fixture count does not match the offered request count")
    available = threading.BoundedSemaphore(MAX_IN_FLIGHT)
    futures, missed, rejected = [], 0, 0
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=MAX_IN_FLIGHT) as pool:
        for index, task_id in enumerate(task_ids):
            scheduled = started + index / rate
            remaining = scheduled - time.monotonic()
            if remaining > 0:
                time.sleep(remaining)
            elif -remaining >= 1 / rate:
                missed += 1
                continue
            if not available.acquire(blocking=False):
                rejected += 1
                continue
            future = pool.submit(settlement_request, base, token, task_id, timeout)
            future.add_done_callback(lambda _future: available.release())
            futures.append(future)
        done, pending = wait(futures, timeout=timeout + 1)
        if pending:
            raise s6.Refused("Settlement clients exceeded their bounded drain window")
        results = [future.result() for future in futures]
    outcomes = {name: sum(row["outcome"] == name for row in results)
                for name in ("ok", "http_error", "business_error", "transport_error")}
    return {"offered": expected, "sent": len(futures), "completed": len(results),
            "actualStartedRps": round(len(futures) / seconds, 3),
            "schedulerMissed": missed, "localRejected": rejected, "outcomes": outcomes,
            "p50Ms": s6.percentile([row["latencyMs"] for row in results], 50),
            "p95Ms": s6.percentile([row["latencyMs"] for row in results], 95),
            "p99Ms": s6.percentile([row["latencyMs"] for row in results], 99),
            "results": results}


def task_truth(mysql_id, task_ids):
    ids = ",".join(str(value) for value in task_ids)
    if not ids or any(not isinstance(value, int) or value <= 0 for value in task_ids):
        raise s6.Refused("Invalid fixture task IDs")
    rows = s6.mysql(mysql_id, f"""
        SELECT r.id,r.status,e.status,
               (SELECT COUNT(*) FROM wallet_ledger l WHERE l.biz_no=CONCAT('settle:',r.id)),
               COALESCE(o.status,'MISSING')
          FROM errand r JOIN escrow_order e ON e.errand_id=r.id
          LEFT JOIN fund_event_outbox o ON o.biz_no=CONCAT('settle:',r.id)
         WHERE r.id IN ({ids}) ORDER BY r.id;
        """)
    result = {}
    for line in rows.splitlines():
        fields = line.split("\t")
        if len(fields) != 5:
            raise s6.Refused("Unexpected settlement truth row")
        result[int(fields[0])] = {"taskStatus": fields[1], "escrowStatus": fields[2],
                                   "ledgerLegs": int(fields[3]), "outboxStatus": fields[4]}
    if set(result) != set(task_ids):
        raise s6.Refused("Fixture task disappeared from benchmark MySQL")
    return result


def recovery_truth(mysql_id, task_ids):
    ids = ",".join(str(value) for value in task_ids)
    rows = s6.mysql(mysql_id, f"""
        SELECT o.errand_id,o.status,o.created_at,
               COUNT(n.id),MAX(n.created_at)
          FROM fund_event_outbox o
          LEFT JOIN notification n ON n.errand_id=o.errand_id AND n.type='SETTLED'
           AND ((n.msg_key=CONCAT('settle:',o.errand_id,':pub')
                 AND n.user_id=o.publisher_id)
             OR (n.msg_key=CONCAT('settle:',o.errand_id,':runner')
                 AND n.user_id=o.runner_id))
         WHERE o.errand_id IN ({ids}) AND o.biz_no=CONCAT('settle:',o.errand_id)
         GROUP BY o.errand_id,o.status,o.created_at ORDER BY o.errand_id;
        """)
    result = {}
    for line in rows.splitlines():
        fields = line.split("\t")
        if len(fields) != 5:
            raise s6.Refused("Unexpected outbox recovery row")
        lag_ms = None
        if fields[4] != "NULL":
            format_ = "%Y-%m-%d %H:%M:%S.%f" if "." in fields[2] else "%Y-%m-%d %H:%M:%S"
            start = datetime.strptime(fields[2], format_)
            format_ = "%Y-%m-%d %H:%M:%S.%f" if "." in fields[4] else "%Y-%m-%d %H:%M:%S"
            end = datetime.strptime(fields[4], format_)
            lag_ms = max(0, round((end - start).total_seconds() * 1000, 3))
        result[int(fields[0])] = {"outboxStatus": fields[1], "notificationCount": int(fields[3]),
                                   "createdToLastNotificationMs": lag_ms}
    return result


def check_recovery(rows, task_ids):
    return set(rows) == set(task_ids) and all(
        row["outboxStatus"] == "SENT" and row["notificationCount"] == 2
        for row in rows.values())


def execute(args, mark, stack):
    project, base, mysql_id = args.project, stack["api"], stack["mysql_container_id"]
    before = s6.snapshot(mysql_id)
    s6.assert_invariants(before, before)
    publisher = s6.login(base, 1001)
    runner = s6.login(base, 2001)
    task_ids = []
    for index in range(args.rate * args.load_seconds):
        if index % 10 == 0:
            s6.production_stopped()
            s6.require_unpaused_services(project)
        task_ids.append(s6.prepare_delivered(base, publisher, runner,
                                             "broker_load_" + uuid.uuid4().hex[:12]))
    prepared = task_truth(mysql_id, task_ids)
    if any(row["taskStatus"] != "DELIVERED" or row["escrowStatus"] != "HELD"
           or row["ledgerLegs"] != 0 or row["outboxStatus"] != "MISSING"
           for row in prepared.values()):
        raise s6.Refused("Settlement fixtures were not all delivered and unsettled")
    s6.assert_invariants(before, s6.snapshot(mysql_id))
    checked = preflight.check(project, base, os.getenv("PEERGRAB_TEST_DB_HOST"),
                              os.getenv("PEERGRAB_TEST_DB_PORT"), "true", "true")
    if checked["mysql_container_id"] != mysql_id:
        raise s6.Refused("Benchmark MySQL identity changed during fixture preparation")
    s6.production_stopped()
    s6.require_unpaused_services(project)
    broker = s6.inspected_service(project, "rmqbroker")
    mark("prepared", tasks=len(task_ids), funds=before)

    samples, sample_errors = [], []
    stopped = threading.Event()
    sampler = None
    phase = None
    fault_started = time.monotonic()
    with s6.FaultWindow(project, "rmqbroker", broker["Id"], mark,
                        args.fault_seconds) as fault:
        try:
            sampler = threading.Thread(target=sample_until_stopped,
                                       args=(mysql_id, stopped, samples, sample_errors), daemon=True)
            sampler.start()
            phase = fixed_arrival_phase(base, publisher, task_ids, args.rate,
                                        args.load_seconds, args.request_timeout)
            # Preserve a short observation interval for the pending backlog.
            remaining = args.fault_seconds - 3 - (time.monotonic() - fault_started)
            if remaining > 0:
                time.sleep(remaining)
        finally:
            stopped.set()
    at_unpause = time.monotonic()
    committed_at_unpause = task_truth(mysql_id, task_ids)
    if sampler is not None:
        sampler.join(timeout=1)
        if sampler.is_alive():
            sample_errors.append("Outbox sampler did not finish promptly after unpause")
    if fault.watchdog_fired:
        raise s6.Refused("Broker watchdog reached its hard deadline; load result is invalid")
    mark("load_finished", summary={key: value for key, value in phase.items() if key != "results"},
         pendingPeak=max((row["pending"] for row in samples), default=None),
         oldestPendingPeakMs=max((row["oldestPendingMs"] for row in samples), default=None),
         sampleCount=len(samples), sampleErrors=sample_errors)

    recovery_samples = []
    rows = recovery_truth(mysql_id, task_ids)
    while not check_recovery(rows, task_ids) and time.monotonic() - at_unpause < args.recovery_seconds:
        recovery_samples.append(outbox_sample(mysql_id))
        time.sleep(1)
        rows = recovery_truth(mysql_id, task_ids)
    drained_at = time.monotonic() if check_recovery(rows, task_ids) else None
    committed = task_truth(mysql_id, task_ids)
    committed_ids = {task_id for task_id, row in committed.items()
                     if row["taskStatus"] == "SETTLED" and row["escrowStatus"] == "RELEASED"
                     and row["ledgerLegs"] == 3}
    timed_out_but_committed = [row["errandId"] for row in phase["results"]
                               if row["outcome"] == "transport_error"
                               and row["errandId"] in committed_ids]
    after = s6.snapshot(mysql_id)
    s6.assert_invariants(before, after)
    lags = [row["createdToLastNotificationMs"] for row in rows.values()
            if row["createdToLastNotificationMs"] is not None]
    errors = []
    if phase["schedulerMissed"] or phase["localRejected"] or phase["sent"] != phase["offered"]:
        errors.append("Offered settlement rate was not fully delivered")
    if phase["outcomes"]["ok"] != phase["offered"]:
        errors.append("Some settlement clients did not receive successful responses")
    if len(committed_ids) != len(task_ids):
        errors.append("Some settlements were not durably committed exactly once")
    if not check_recovery(rows, task_ids):
        errors.append("Outbox and notification recovery did not drain in time")
    if sample_errors or not samples or max(row["pending"] for row in samples) == 0:
        errors.append("Pending Outbox backlog was not observed during the pause")
    report = {"client": phase, "committed": len(committed_ids),
              "committedAtUnpause": sum(row["taskStatus"] == "SETTLED"
                                        for row in committed_at_unpause.values()),
              "timedOutButCommitted": len(timed_out_but_committed),
              "timedOutButCommittedIds": timed_out_but_committed,
              "outboxPendingPeak": max((row["pending"] for row in samples), default=None),
              "oldestPendingPeakMs": max((row["oldestPendingMs"] for row in samples), default=None),
              "outboxDrained": drained_at is not None,
              "drainAfterUnpauseMs": round((drained_at - at_unpause) * 1000, 3)
                                      if drained_at is not None else None,
              "notificationEndToEndP95Ms": s6.percentile(lags, 95),
              "notificationEndToEndP99Ms": s6.percentile(lags, 99),
              "faultSamples": samples, "recoverySamples": recovery_samples,
              "recoveryTruth": rows, "fundsAfter": after, "errors": errors}
    mark("recovered", committed=report["committed"],
         timedOutButCommitted=report["timedOutButCommitted"],
         outboxDrained=report["outboxDrained"],
         drainAfterUnpauseMs=report["drainAfterUnpauseMs"],
         notificationEndToEndP99Ms=report["notificationEndToEndP99Ms"], errors=errors)
    return report


def main():
    signal.signal(signal.SIGTERM, s6.interrupted)
    signal.signal(signal.SIGINT, s6.interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-project")
    parser.add_argument("--rate", type=int, default=2, help="settlements per second during Broker pause")
    parser.add_argument("--load-seconds", type=int, default=16)
    parser.add_argument("--fault-seconds", type=int, default=30)
    parser.add_argument("--request-timeout", type=int, default=8)
    parser.add_argument("--recovery-seconds", type=int, default=180)
    args = parser.parse_args()
    if not preflight.PROJECT_RE.fullmatch(args.project):
        parser.error("project must be a peergrab-bench-* name")
    if os.getenv("PEERGRAB_BENCH_PROJECT") != args.project or \
            os.getenv("COMPOSE_PROJECT_NAME") != args.project:
        parser.error("project must match both benchmark environment variables")
    if not (1 <= args.rate <= 5 and 8 <= args.load_seconds <= 18
            and 25 <= args.fault_seconds <= 30 and 3 <= args.request_timeout <= 8
            and args.load_seconds + args.request_timeout + 3 <= args.fault_seconds
            and 30 <= args.recovery_seconds <= 600):
        parser.error("rate 1..5, load 8..18 s, fault 25..30 s, timeout 3..8 s; "
                     "load + timeout + 3 <= fault; recovery 30..600 s")
    run_name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    output = RUNS / f"s6-broker-load-{args.project}-{run_name}.json"
    manifest = {"scenario": "S6-BROKER-SETTLEMENT-LOAD", "project": args.project,
                "status": "RUNNING" if args.execute else "DRY_RUN",
                "rate": args.rate, "loadSeconds": args.load_seconds,
                "faultSeconds": args.fault_seconds, "requestTimeoutSeconds": args.request_timeout,
                "recoverySeconds": args.recovery_seconds, "events": []}
    mark_lock = threading.Lock()

    def mark(name, **fields):
        with mark_lock:
            event = {"event": name, "atUtc": s6.utc_now(), **fields}
            manifest["events"].append(event)
            print(json.dumps(event, sort_keys=True), flush=True)
            if args.execute:
                RUNS.mkdir(mode=0o700, parents=True, exist_ok=True)
                temporary = output.with_suffix(".json.tmp")
                descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "w") as private_file:
                    private_file.write(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
                os.replace(temporary, output)

    try:
        s6.production_stopped()
        stack = preflight.check(args.project, os.getenv("PEERGRAB_BENCH_BASE_URL"),
                                os.getenv("PEERGRAB_TEST_DB_HOST"),
                                os.getenv("PEERGRAB_TEST_DB_PORT"), "true", "true")
        s6.require_unpaused_services(args.project)
        s6.fresh_stack(stack["mysql_container_id"])
        mark("preflight_passed" if args.execute else "dry_run_checked",
             mysqlContainerId=stack["mysql_container_id"])
        if not args.execute:
            print("DRY RUN: no traffic or fault; add --execute --confirm-project=" + args.project)
            return 0
        if args.confirm_project != args.project:
            raise s6.Refused("--execute requires exact --confirm-project")
        report = execute(args, mark, stack)
        manifest["report"] = report
        manifest["status"] = "PASS" if not report["errors"] else "FAIL"
        mark("finished", status=manifest["status"], errors=report["errors"])
        print("S6 Broker load " + manifest["status"] + "; manifest=" + str(output))
        return 0 if not report["errors"] else 1
    except Exception as exc:
        manifest["status"] = "FAIL"
        mark("failed", error=str(exc)[:300])
        print("S6 Broker load failed: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
