#!/usr/bin/env python3
"""ECS maintenance smoke: one grab while an isolated benchmark Broker is paused.

This is a single-request correctness and recovery probe, not a throughput test.
It only addresses the host-local API of a fresh, labeled disposable Compose stack.
"""

import argparse
from datetime import datetime, timezone
import json
import os
import re
import signal
import socket
import sys
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request
import uuid

import preflight
import run_s6_fault_smoke as s6


RUNS = s6.RUNS
GRAB_TIMEOUT_SECONDS = 8
BROKER_SERVICE = "rmqbroker"


def runtime_config(project, fault_seconds, recovery_seconds):
    app = s6.inspected_service(project, "app")
    worker = s6.inspected_service(project, "worker")
    app_env = dict(item.split("=", 1) for item in app["Config"]["Env"] if "=" in item)
    worker_env = dict(item.split("=", 1) for item in worker["Config"]["Env"] if "=" in item)
    if (app_env.get("PEERGRAB_AUTH_MODE") != "jwt"
            or app_env.get("PEERGRAB_AUTH_ALLOW_HEADER_IDENTITY") != "false"):
        raise s6.Refused("Benchmark app must use JWT with header identity disabled")
    app_confirm = app_env.get("PEERGRAB_TIMEOUT_CONFIRM_SECONDS", "")
    worker_confirm = worker_env.get("PEERGRAB_TIMEOUT_CONFIRM_SECONDS", "")
    if (not app_confirm.isdecimal() or app_confirm != worker_confirm
            or int(app_confirm) < fault_seconds + recovery_seconds + 30):
        raise s6.Refused("App/worker confirmation deadline must exceed fault and recovery windows")
    return app["Id"], worker["Id"]


def grab_probe(base_url, token, errand_id, request_id):
    """Record the client result without logging credentials or any response body."""
    s6.local_api_origin(base_url)
    if not isinstance(errand_id, int) or errand_id <= 0:
        raise s6.Refused("Invalid benchmark errand ID")
    if not re.fullmatch(r"[a-f0-9-]{36}", request_id):
        raise s6.Refused("Invalid benchmark request ID")
    request = Request(
        base_url.rstrip("/") + f"/api/errands/{errand_id}/grab", data=b"{}",
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                 "X-Request-Id": request_id}, method="POST")
    started = time.monotonic()
    status = None
    outcome = "transport_error"
    code = None
    try:
        with s6.DIRECT_HTTP.open(request, timeout=GRAB_TIMEOUT_SECONDS) as response:
            status = response.status
            payload = json.load(response)
        if isinstance(payload, dict):
            raw_code = payload.get("code")
            if isinstance(raw_code, str) and re.fullmatch(r"[A-Z_]{2,40}", raw_code):
                code = raw_code
            data = payload.get("data")
            outcome = ("ok" if status == 200 and code == "OK"
                       and isinstance(data, dict) and data.get("grabbed") is True
                       else "application_error")
        else:
            outcome = "application_error"
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
        outcome = "application_error"
    except OSError:
        outcome = "transport_error"
    return {"outcome": outcome, "httpStatus": status, "businessCode": code,
            "elapsedMs": round((time.monotonic() - started) * 1000, 3),
            "clientTimeoutSeconds": GRAB_TIMEOUT_SECONDS}


def durable_state(mysql_id, errand_id):
    row = s6.one_row(mysql_id, f"""
        SELECT status, slot_total, slot_taken, COALESCE(grabber_id,-1), round
          FROM errand WHERE id={errand_id}
        """, 5)
    grabbed = s6.scalar(mysql_id, f"""
        SELECT COUNT(*) FROM grab_record WHERE errand_id={errand_id} AND result='GRABBED'
        """)
    escrow = s6.one_row(mysql_id, f"""
        SELECT status FROM escrow_order WHERE errand_id={errand_id}
        """, 1)[0]
    escrow_legs = s6.scalar(mysql_id, f"""
        SELECT COUNT(*) FROM wallet_ledger WHERE biz_no='escrow:{errand_id}'
        """)
    return {"status": row[0], "slotTotal": int(row[1]), "slotTaken": int(row[2]),
            "grabberId": int(row[3]), "round": int(row[4]), "grabbedRecords": grabbed,
            "escrowStatus": escrow, "escrowLedgerLegs": escrow_legs}


def assert_durable_grab(state):
    if (state["status"] != "LOCKED" or state["slotTotal"] != 1
            or state["slotTaken"] != 1 or state["grabberId"] != 2001
            or state["round"] != 0 or state["grabbedRecords"] != 1
            or state["escrowStatus"] != "HELD" or state["escrowLedgerLegs"] != 2):
        raise s6.Refused("Broker-pause grab did not satisfy durable slot/escrow invariants")


def timeout_message(mysql_id, errand_id):
    msg_key = f"timeout:{errand_id}:0"
    rows = s6.mysql(mysql_id, f"""
        SELECT status, topic, retry_count FROM local_message
         WHERE msg_key='{msg_key}'
        """).splitlines()
    if len(rows) != 1:
        raise s6.Refused("Expected exactly one timeout local_message row")
    fields = rows[0].split("\t")
    if len(fields) != 3 or fields[1] != "errand-confirm-timeout":
        raise s6.Refused("Invalid timeout local_message row")
    return {"status": fields[0], "topic": fields[1], "retryCount": int(fields[2])}


def execute(args, mark):
    s6.production_stopped()
    base = os.getenv("PEERGRAB_BENCH_BASE_URL")
    db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
    db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
    stack = preflight.check(args.project, base, db_host, db_port, "true", "true")
    s6.require_unpaused_services(args.project)
    mysql_id = stack["mysql_container_id"]
    app_id, worker_id = runtime_config(args.project, args.fault_seconds,
                                       args.recovery_seconds)
    s6.fresh_stack(mysql_id)
    before = s6.snapshot(mysql_id)
    s6.assert_invariants(before, before)

    publisher = s6.login(base, 1001)
    runner = s6.login(base, 2001)
    run_key = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S") + "_" + uuid.uuid4().hex[:8]
    errand_id = s6.publish(base, publisher, run_key)
    prepared_task = durable_state(mysql_id, errand_id)
    if (prepared_task["status"] != "PUBLISHED" or prepared_task["slotTaken"] != 0
            or prepared_task["grabbedRecords"] != 0 or prepared_task["escrowStatus"] != "HELD"
            or prepared_task["escrowLedgerLegs"] != 2):
        raise s6.Refused("Published task or escrow is not ready for Broker-pause grab")
    if s6.scalar(mysql_id, f"SELECT COUNT(*) FROM local_message WHERE msg_key='timeout:{errand_id}:0'"):
        raise s6.Refused("Timeout message existed before grab")
    prepared = s6.snapshot(mysql_id)
    s6.assert_invariants(before, prepared)
    checked = preflight.check(args.project, base, db_host, db_port, "true", "true")
    s6.require_unpaused_services(args.project)
    if (checked["mysql_container_id"] != mysql_id
            or s6.inspected_service(args.project, "app")["Id"] != app_id
            or s6.inspected_service(args.project, "worker")["Id"] != worker_id):
        raise s6.Refused("Benchmark stack identity changed during task preparation")
    target = s6.inspected_service(args.project, BROKER_SERVICE)
    mark("prepared", errandId=errand_id, task=prepared_task,
         fundsBefore=before, fundsPrepared=prepared)

    started = time.monotonic()
    client = None
    during_state = None
    pending = None
    workload_error = None
    with s6.FaultWindow(args.project, BROKER_SERVICE, target["Id"], mark,
                        args.fault_seconds) as window:
        try:
            client = grab_probe(base, runner, errand_id, str(uuid.uuid4()))
            mark("paused_grab_client", **client)
            during_state = durable_state(mysql_id, errand_id)
            pending = timeout_message(mysql_id, errand_id)
            mark("paused_durable_state", errandId=errand_id, task=during_state,
                 timeoutMessage=pending)
            assert_durable_grab(during_state)
            if pending["status"] != "PENDING":
                raise s6.Refused("Timeout message was not PENDING while Broker was paused")
            if client["outcome"] != "ok":
                raise s6.Refused("Grab client failed or timed out despite a durable result")
        except Exception as error:
            workload_error = str(error)
            mark("paused_workload_failed", reason=workload_error[:300])
        if workload_error is None:
            remaining = args.fault_seconds - 3 - (time.monotonic() - started)
            if remaining > 0:
                time.sleep(remaining)

    after_unpause = s6.snapshot(mysql_id)
    s6.assert_invariants(before, after_unpause)
    mark("after_unpause", funds=after_unpause)
    if window.watchdog_fired:
        raise s6.Refused("Broker-pause grab reached the hard fault deadline")
    if not s6.wait_until(60, lambda: preflight.check(args.project, base, db_host,
                                                       db_port, "true", "true")):
        raise s6.Refused("Disposable stack did not recover readiness after Broker unpause")
    if not s6.wait_until(args.recovery_seconds,
                         lambda: timeout_message(mysql_id, errand_id)["status"] == "SENT"):
        raise s6.Refused("Timeout local_message did not become SENT during recovery")
    final_state = durable_state(mysql_id, errand_id)
    assert_durable_grab(final_state)
    sent = timeout_message(mysql_id, errand_id)
    after = s6.snapshot(mysql_id)
    s6.assert_invariants(before, after)
    mark("recovered", errandId=errand_id, task=final_state,
         timeoutMessage=sent, fundsAfter=after)
    if workload_error:
        raise s6.Refused(workload_error)


def main(argv=None):
    signal.signal(signal.SIGTERM, s6.interrupted)
    signal.signal(signal.SIGINT, s6.interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-project")
    parser.add_argument("--fault-seconds", type=int, default=30)
    parser.add_argument("--recovery-seconds", type=int, default=180)
    args = parser.parse_args(argv)
    if not 20 <= args.fault_seconds <= 30 or not 30 <= args.recovery_seconds <= 600:
        parser.error("fault-seconds must be 20..30; recovery-seconds must be 30..600")
    if not preflight.PROJECT_RE.fullmatch(args.project):
        parser.error("project must be a peergrab-bench-* name")
    if (os.getenv("PEERGRAB_BENCH_PROJECT") != args.project
            or os.getenv("COMPOSE_PROJECT_NAME") != args.project):
        parser.error("project must match both benchmark environment variables")
    manifest = {"scenario": "S6-broker-grab", "project": args.project,
                "status": "RUNNING" if args.execute else "DRY_RUN",
                "faultSeconds": args.fault_seconds,
                "recoverySeconds": args.recovery_seconds, "events": []}
    run_name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    output = RUNS / f"s6-grab-{args.project}-{run_name}.json"
    mark_lock = threading.Lock()

    def mark(name, **fields):
        with mark_lock:
            entry = {"atUtc": s6.utc_now(), "event": name, **fields}
            manifest["events"].append(entry)
            print(json.dumps(entry, sort_keys=True), flush=True)
            if args.execute:
                RUNS.mkdir(parents=True, exist_ok=True)
                temporary = output.with_suffix(".json.tmp")
                temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
                os.replace(temporary, output)

    try:
        s6.production_stopped()
        stack = preflight.check(args.project, os.getenv("PEERGRAB_BENCH_BASE_URL"),
                                os.getenv("PEERGRAB_TEST_DB_HOST"),
                                os.getenv("PEERGRAB_TEST_DB_PORT"), "true", "true")
        s6.require_unpaused_services(args.project)
        runtime_config(args.project, args.fault_seconds, args.recovery_seconds)
        s6.fresh_stack(stack["mysql_container_id"])
        broker = s6.inspected_service(args.project, BROKER_SERVICE)
        if not broker["State"]["Running"] or broker["State"]["Paused"]:
            raise s6.Refused("Benchmark Broker is not running and unpaused")
        mark("preflight_passed" if args.execute else "dry_run_checked",
             brokerId=broker["Id"])
        if not args.execute:
            print("DRY RUN: no business writes or fault injected; add --execute --confirm-project="
                  + args.project)
            return 0
        if (args.confirm_project != args.project
                or os.getenv("PEERGRAB_BENCH_ECS_MAINTENANCE") != "YES"):
            raise s6.Refused("Execution requires exact project confirmation and ECS maintenance opt-in")
        execute(args, mark)
        manifest["status"] = "PASS"
        mark("finished")
        print("Broker-pause grab smoke PASS; manifest=" + str(output))
        return 0
    except Exception as error:
        manifest["status"] = "FAIL"
        mark("failed", reason=str(error)[:500])
        print("Broker-pause grab smoke failed/refused: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
