#!/usr/bin/env python3
"""Small, guarded S6 Redis/MQ outage smoke on one disposable Compose stack.

This is a correctness/recovery probe, not a throughput or failover-SLA test.
It never stops a container, deletes a volume, or targets a public URL.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, wait
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
import uuid

import preflight


BACKEND = Path(__file__).resolve().parents[2]
RUNS = BACKEND / "bench" / "runs"
TARGET = {"redis": "redis", "mq": "rmqbroker"}
DETAIL_RATE = 10
DETAIL_MAX_IN_FLIGHT = 32
DETAIL_TIMEOUT_SECONDS = 3
PROJECT_LABEL = preflight.PROJECT_LABEL
SERVICE_LABEL = preflight.SERVICE_LABEL
BENCH_LABEL = preflight.BENCH_LABEL


class RefuseRedirect(HTTPRedirectHandler):
    def redirect_request(self, _request, _response, _code, _message, _headers, _new_url):
        # urllib's default redirect handler may carry Authorization to another host.
        raise Refused("JWT request redirect refused")


# Never consult HTTP(S)_PROXY / ALL_PROXY / NO_PROXY for bearer-token traffic.
DIRECT_HTTP = build_opener(ProxyHandler({}), RefuseRedirect())


class Refused(RuntimeError):
    pass


def interrupted(_signal, _frame):
    # Convert SIGTERM/SIGINT to an exception so FaultWindow.__exit__ can unpause.
    raise Refused("Interrupted by signal; restoring the paused benchmark service")


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def command(*args, input_text=None, timeout=20):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True,
                            timeout=timeout, encoding="utf-8", errors="replace")
    if result.returncode:
        raise Refused(f"{args[0]} {args[1]} failed: {result.stderr.strip()[:300]}")
    return result.stdout.strip()


def inspected_service(project, service):
    ids = command("docker", "ps", "-aq", "--no-trunc", "--filter",
                  f"label={PROJECT_LABEL}={project}", "--filter",
                  f"label={SERVICE_LABEL}={service}", timeout=5).splitlines()
    if len(ids) != 1 or not re.fullmatch(r"[a-f0-9]{64}", ids[0]):
        raise Refused(f"Expected exactly one full-ID {service} in {project}")
    container = json.loads(command("docker", "container", "inspect", ids[0], timeout=5))[0]
    labels = container["Config"].get("Labels") or {}
    if (container["Id"] != ids[0] or labels.get(PROJECT_LABEL) != project
            or labels.get(SERVICE_LABEL) != service or labels.get(BENCH_LABEL) != "true"
            or str(preflight.COMPOSE_FILES[2]) not in labels.get("com.docker.compose.project.config_files", "")):
        raise Refused(f"{service} lost its disposable project identity")
    return container


def production_stopped():
    if os.getenv("PEERGRAB_MAINTENANCE_APPROVED") != "YES":
        raise Refused("Explicit maintenance opt-in PEERGRAB_MAINTENANCE_APPROVED=YES is required")
    live = command("docker", "ps", "-q", "--filter", "label=com.docker.compose.project=peergrab-prod")
    if live:
        raise Refused("Production containers are running; S6 injection is refused")


def require_unpaused_services(project):
    for service in ("mysql", "redis", "rmqbroker", "app", "worker"):
        state = inspected_service(project, service)["State"]
        if not state["Running"] or state["Paused"]:
            raise Refused(f"Benchmark {service} is not running and unpaused")


def mysql(container_id, sql):
    # Password is expanded only inside the preflight-verified benchmark MySQL container.
    return command("docker", "exec", "-i", container_id, "sh", "-c",
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot '
                   '--default-character-set=utf8mb4 -N -B peer_grab',
                   input_text=sql, timeout=20)


def scalar(container_id, sql):
    result = mysql(container_id, sql)
    if not re.fullmatch(r"-?[0-9]+", result):
        raise Refused("Unexpected numeric MySQL result")
    return int(result)


def one_row(container_id, sql, fields):
    values = mysql(container_id, sql).split("\t")
    if len(values) != fields:
        raise Refused("Unexpected MySQL result width")
    return values


def local_api_origin(base_url):
    parsed = urlsplit(base_url)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}
            or parsed.port is None or parsed.username or parsed.password
            or parsed.path.rstrip("/") or parsed.query or parsed.fragment):
        raise Refused("JWT requests require a direct host-local HTTP origin")


def fresh_stack(mysql_id):
    values = one_row(mysql_id, """
        SELECT (SELECT COUNT(*) FROM errand), (SELECT COUNT(*) FROM grab_record),
               (SELECT COUNT(*) FROM escrow_order), (SELECT COUNT(*) FROM wallet_ledger),
               (SELECT COUNT(*) FROM fund_event_outbox), (SELECT COUNT(*) FROM notification),
               (SELECT COUNT(*) FROM local_message),
               (SELECT COUNT(*) FROM worker_scan_retry),
               (SELECT COUNT(*) FROM bench_run);
        """, 9)
    if any(v != "0" for v in values):
        raise Refused("S6 smoke requires a fresh disposable stack with empty business/run tables")
    balance = scalar(mysql_id, """
        SELECT COALESCE((SELECT available FROM wallet_account
                         WHERE owner_id=1001 AND owner_type='USER'),-1)
        """)
    runner = scalar(mysql_id, """
        SELECT COUNT(*) FROM wallet_account WHERE owner_id=2001 AND owner_type='USER'
        """)
    system_accounts = scalar(mysql_id, """
        SELECT COUNT(*) FROM wallet_account WHERE owner_type IN ('ESCROW','COMMISSION')
        """)
    if balance < 100 or runner != 1 or system_accounts != 2:
        raise Refused("Expected seeded publisher, runner and system wallets")


def snapshot(mysql_id):
    values = one_row(mysql_id, """
        SELECT
          (SELECT COALESCE(SUM(available+frozen),0) FROM wallet_account),
          (SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0)
             FROM wallet_ledger),
          (SELECT COUNT(*) FROM wallet_account a WHERE a.owner_type IN ('ESCROW','COMMISSION')
             AND a.available+a.frozen <> COALESCE((SELECT SUM(CASE WHEN l.direction='CREDIT'
                 THEN l.amount ELSE -l.amount END) FROM wallet_ledger l WHERE l.account_id=a.id),0)),
          (SELECT COUNT(*) FROM escrow_order e WHERE
             (e.status='RELEASED' AND NOT EXISTS
                (SELECT 1 FROM wallet_ledger l WHERE l.biz_no=CONCAT('settle:',e.errand_id)))
             OR (e.status='REFUNDED' AND NOT EXISTS
                (SELECT 1 FROM wallet_ledger l WHERE l.biz_no=CONCAT('refund:',e.errand_id)))
             OR (e.status='HELD' AND EXISTS
                (SELECT 1 FROM errand r WHERE r.id=e.errand_id
                 AND r.status IN ('SETTLED','REFUNDED','CANCELLED')))),
          (SELECT COUNT(*) FROM errand WHERE slot_taken > slot_total OR slot_taken < 0);
        """, 5)
    if any(not re.fullmatch(r"-?[0-9]+", value) for value in values):
        raise Refused("Invalid funds/slot snapshot")
    return dict(zip(("walletTotal", "debitMinusCredit", "systemSnapshotDiffs",
                     "escrowClosureDiffs", "badSlotRows"), map(int, values)))


def assert_invariants(before, after):
    if after["walletTotal"] != before["walletTotal"]:
        raise Refused("Global wallet total changed during S6 smoke")
    for name in ("debitMinusCredit", "systemSnapshotDiffs", "escrowClosureDiffs", "badSlotRows"):
        if after[name] != 0:
            raise Refused(f"S6 invariant failed: {name}={after[name]}")


def api(base_url, method, path, token=None, body=None, request_id=None, timeout=8):
    local_api_origin(base_url)
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    if request_id:
        headers["X-Request-Id"] = request_id
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = Request(base_url.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with DIRECT_HTTP.open(req, timeout=timeout) as response:
            status = response.status
            payload = json.load(response)
    except HTTPError as e:
        status = e.code
        try:
            payload = json.load(e)
        finally:
            e.close()
    except (URLError, TimeoutError) as e:
        raise Refused(f"{method} {path} transport failure: {e}") from e
    if status != 200 or payload.get("code") != "OK":
        raise Refused(f"{method} {path} returned HTTP {status}, code={payload.get('code')}")
    return payload.get("data")


def login(base_url, user_id):
    password = os.getenv(f"PEERGRAB_AUTH_DEMO_PASSWORD_{user_id}")
    if not password:
        raise Refused(f"Missing disposable demo credential for {user_id}")
    data = api(base_url, "POST", "/api/auth/login",
               body={"userId": user_id, "password": password})
    token = data.get("accessToken") or data.get("token")
    if not isinstance(token, str) or len(token) < 30:
        raise Refused("Benchmark JWT login did not yield a token")
    return token


def parse_errand_id(value):
    # Public JSON renders Snowflake IDs as decimal strings to preserve JS precision.
    if isinstance(value, bool):
        raise Refused("Publish returned an invalid errandId")
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(value, str) and re.fullmatch(r"[1-9][0-9]*", value):
        parsed = int(value)
        if parsed <= 9_223_372_036_854_775_807:
            return parsed
    raise Refused("Publish returned an invalid errandId")


def publish(base_url, publisher, run_key):
    data = api(base_url, "POST", "/api/errands", publisher,
               {"type": "DELIVERY", "title": "s6_" + run_key,
                "rewardCents": 100, "slotTotal": 1},
               request_id=str(uuid.uuid4()))
    return parse_errand_id(data.get("errandId") if isinstance(data, dict) else None)


def prepare_delivered(base_url, publisher, runner, run_key):
    errand_id = publish(base_url, publisher, run_key)
    api(base_url, "POST", f"/api/errands/{errand_id}/grab", runner, {}, str(uuid.uuid4()))
    for action in ("confirm", "pickup", "deliver"):
        api(base_url, "POST", f"/api/errands/{errand_id}/{action}", runner, {})
    return errand_id


def detail_probe(base_url, token, errand_id):
    local_api_origin(base_url)
    started = time.monotonic()
    request = Request(base_url.rstrip("/") + f"/api/errands/{errand_id}",
                      headers={"Accept": "application/json", "Authorization": "Bearer " + token},
                      method="GET")
    try:
        with DIRECT_HTTP.open(request, timeout=DETAIL_TIMEOUT_SECONDS) as response:
            status = response.status
            payload = json.load(response)
        outcome = "ok" if status == 200 and payload.get("code") == "OK" else "application_error"
        state = payload.get("data", {}).get("status") if outcome == "ok" else None
    except HTTPError as e:
        e.close()
        outcome, state = "http_error", None
    except (URLError, TimeoutError, OSError):
        outcome, state = "transport_error", None
    except (ValueError, AttributeError, Refused):
        outcome, state = "application_error", None
    return {"outcome": outcome, "state": state,
            "latencyMs": round((time.monotonic() - started) * 1000, 3)}


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, (len(ordered) * p + 99) // 100 - 1)]


def detail_phase(base_url, token, errand_id, seconds, phase):
    """Open-arrival 10/s probe with bounded in-flight and explicit local rejection."""
    offered = seconds * DETAIL_RATE
    phase_start = time.monotonic()
    started_at = utc_now()
    available = threading.BoundedSemaphore(DETAIL_MAX_IN_FLIGHT)
    futures = []
    rejected = 0
    scheduler_missed = 0
    pool = ThreadPoolExecutor(max_workers=DETAIL_MAX_IN_FLIGHT)
    try:
        for index in range(offered):
            target = phase_start + index / DETAIL_RATE
            now = time.monotonic()
            if target > now:
                time.sleep(target - now)
            elif now - target >= 1 / DETAIL_RATE:
                # Never catch up with a burst and mislabel it fixed-rate traffic.
                scheduler_missed += 1
                continue
            if not available.acquire(blocking=False):
                rejected += 1
                continue
            future = pool.submit(detail_probe, base_url, token, errand_id)
            future.add_done_callback(lambda _future: available.release())
            futures.append(future)
        # The last request has a 3-second socket timeout. This wait must fit in the
        # five-second drain allowance reserved before Redis is unpaused.
        done, pending = wait(futures, timeout=4)
        if pending:
            raise Refused(f"{phase} detail probes exceeded the bounded drain window")
        results = [future.result() for future in futures]
    finally:
        # Never let executor shutdown extend the paused fault window. Unfinished
        # requests are a failed phase; the watchdog and outer finally restore Redis.
        pool.shutdown(wait=False, cancel_futures=True)
    outcomes = {name: sum(item["outcome"] == name for item in results)
                for name in ("ok", "application_error", "http_error", "transport_error")}
    states = {name: sum(item["state"] == name for item in results)
              for name in ("PUBLISHED", "LOCKED")}
    return {"phase": phase, "startedAtUtc": started_at,
            "offered": offered, "sent": len(futures), "completed": len(results),
            "ok": outcomes["ok"], "transportErrors": outcomes["transport_error"],
            "httpErrors": outcomes["http_error"],
            "applicationErrors": outcomes["application_error"],
            "localRejected": rejected, "schedulerMissed": scheduler_missed,
            "outcomes": outcomes, "states": states,
            "p95Ms": percentile([item["latencyMs"] for item in results], 95),
            "maxMs": max((item["latencyMs"] for item in results), default=None)}


class FaultWindow:
    """Pause/unpause only the exact, repeatedly identified disposable container."""

    def __init__(self, project, service, container_id, mark, max_seconds=30):
        self.project, self.service, self.container_id, self.mark = project, service, container_id, mark
        self.attempted = False
        self.max_seconds = max_seconds
        self.watchdog_fired = False
        self.restore_error = None
        self._restore_lock = threading.Lock()
        self._watchdog = None

    def __enter__(self):
        production_stopped()
        current = inspected_service(self.project, self.service)
        if current["Id"] != self.container_id or not current["State"]["Running"] or current["State"]["Paused"]:
            raise Refused("Target changed, stopped, or was already paused before injection")
        self.attempted = True
        pause_command_started = time.monotonic()
        try:
            command("docker", "pause", self.container_id, timeout=5)
            # Count the pause command itself against the hard limit.
            watchdog_delay = max(0, self.max_seconds - 1
                                 - (time.monotonic() - pause_command_started))
            self._watchdog = threading.Timer(watchdog_delay, self._deadline_restore)
            # If normal cleanup fails, keep the process alive for this independent
            # exact-ID recovery attempt rather than exiting with Redis/MQ paused.
            self._watchdog.daemon = False
            self._watchdog.start()
            paused = inspected_service(self.project, self.service)
            if paused["Id"] != self.container_id or not paused["State"]["Paused"]:
                raise Refused("Target was not paused after injection")
            self.mark("fault_started", service=self.service, containerId=self.container_id)
            return self
        except Exception:
            self._restore_with_retries()
            if self._watchdog is not None:
                self._watchdog.cancel()
            raise

    def _deadline_restore(self):
        self.watchdog_fired = True
        try:
            self._restore_with_retries()
        except Exception as e:
            self.restore_error = str(e)
            print("S6 watchdog could not verify restoration: " + str(e), file=sys.stderr, flush=True)

    def _restore_with_retries(self):
        last_error = None
        for attempt in range(3):
            try:
                self.restore()
                return
            except Exception as e:
                last_error = e
                if attempt < 2:
                    time.sleep(0.2)
        raise Refused("Could not verify exact-ID unpause after three attempts") from last_error

    def restore(self):
        with self._restore_lock:
            if not self.attempted:
                return
            current = inspected_service(self.project, self.service)
            if current["Id"] != self.container_id:
                raise Refused("Target identity changed; refusing to unpause a replacement")
            if current["State"]["Paused"]:
                command("docker", "unpause", self.container_id, timeout=5)
            restored = inspected_service(self.project, self.service)
            if (restored["Id"] != self.container_id or not restored["State"]["Running"]
                    or restored["State"]["Paused"]):
                raise Refused("Target could not be verified unpaused")
            self.attempted = False
            self.mark("fault_ended", service=self.service, containerId=self.container_id)

    def __exit__(self, _type, _value, _traceback):
        # Do not cancel the independent deadline guard until restoration is
        # confirmed. A transient first unpause failure must never strand a pause.
        self._restore_with_retries()
        if self._watchdog is not None:
            self._watchdog.cancel()
        if self.restore_error:
            raise Refused("S6 watchdog restoration failed: " + self.restore_error)


def wait_until(seconds, condition):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if condition():
                return True
        except (Refused, preflight.Refused, URLError):
            pass
        time.sleep(2)
    try:
        return bool(condition())
    except (Refused, preflight.Refused, URLError):
        return False


def execute(args, mark):
    project = args.project
    production_stopped()
    stack = preflight.check(project, os.getenv("PEERGRAB_BENCH_BASE_URL"),
                            os.getenv("PEERGRAB_TEST_DB_HOST"), os.getenv("PEERGRAB_TEST_DB_PORT"),
                            "true", "true")
    require_unpaused_services(project)
    mysql_id = stack["mysql_container_id"]
    app = inspected_service(project, "app")
    worker = inspected_service(project, "worker")
    app_env = dict(item.split("=", 1) for item in app["Config"]["Env"] if "=" in item)
    if (app_env.get("PEERGRAB_AUTH_MODE") != "jwt"
            or app_env.get("PEERGRAB_AUTH_ALLOW_HEADER_IDENTITY") != "false"):
        raise Refused("Benchmark app must use JWT with header identity disabled")
    worker_env = dict(item.split("=", 1) for item in worker["Config"]["Env"] if "=" in item)
    app_confirm = app_env.get("PEERGRAB_TIMEOUT_CONFIRM_SECONDS", "")
    worker_confirm = worker_env.get("PEERGRAB_TIMEOUT_CONFIRM_SECONDS", "")
    if (not app_confirm.isdecimal() or app_confirm != worker_confirm
            or (args.fault == "redis" and int(app_confirm) <
                args.fault_seconds + args.recovery_seconds + 30)):
        raise Refused("Benchmark confirmation deadline is too short or app/worker values differ")
    fresh_stack(mysql_id)
    before = snapshot(mysql_id)
    if any(before[key] != 0 for key in ("debitMinusCredit", "systemSnapshotDiffs",
                                         "escrowClosureDiffs", "badSlotRows")):
        raise Refused("The fresh stack already violates an invariant")
    base = stack["api"]
    publisher = login(base, 1001)
    runner = login(base, 2001)
    run_key = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S") + "_" + uuid.uuid4().hex[:8]
    if args.fault == "redis":
        errand_id = publish(base, publisher, run_key)
        detail = api(base, "GET", f"/api/errands/{errand_id}", publisher)
        if detail.get("status") != "PUBLISHED":
            raise Refused("Redis prewarm did not see a published task")
        pre_reads = detail_phase(base, publisher, errand_id, 5, "before")
        mark("detail_phase_before", **pre_reads)
        if (pre_reads["outcomes"]["ok"] != pre_reads["offered"]
                or pre_reads["localRejected"] != 0 or pre_reads["schedulerMissed"] != 0):
            raise Refused("Redis detail baseline was not fully delivered and successful")
    else:
        errand_id = prepare_delivered(base, publisher, runner, run_key)
        if one_row(mysql_id, f"SELECT status FROM errand WHERE id={errand_id}", 1)[0] != "DELIVERED":
            raise Refused("MQ precondition task is not DELIVERED")
    prepared = snapshot(mysql_id)
    assert_invariants(before, prepared)
    checked = preflight.check(project, base, os.getenv("PEERGRAB_TEST_DB_HOST"),
                              os.getenv("PEERGRAB_TEST_DB_PORT"), "true", "true")
    require_unpaused_services(project)
    if (checked["mysql_container_id"] != mysql_id
            or inspected_service(project, "app")["Id"] != app["Id"]
            or inspected_service(project, "worker")["Id"] != worker["Id"]):
        raise Refused("Benchmark stack identity changed during S6 preparation")
    mark("prepared", errandId=errand_id, fundsBefore=before, fundsPrepared=prepared)

    target = inspected_service(project, TARGET[args.fault])
    started = time.monotonic()
    workload_error = None
    with FaultWindow(project, TARGET[args.fault], target["Id"], mark,
                     args.fault_seconds) as fault_window:
        try:
            if args.fault == "redis":
                with ThreadPoolExecutor(max_workers=1) as grab_pool:
                    grab = grab_pool.submit(api, base, "POST", f"/api/errands/{errand_id}/grab",
                                           runner, {}, str(uuid.uuid4()))
                    during_reads = detail_phase(base, publisher, errand_id,
                                                args.fault_seconds - 5, "paused")
                    mark("detail_phase_paused", **during_reads)
                    grab.result(timeout=1)
                if during_reads["outcomes"]["ok"] == 0:
                    raise Refused("No detail request succeeded while Redis was paused")
                if during_reads["localRejected"] != 0 or during_reads["schedulerMissed"] != 0:
                    raise Refused("Redis-outage 10 RPS phase was not fully offered to the API")
            else:
                result = api(base, "POST", f"/api/errands/{errand_id}/settle", publisher, {})
                if result.get("result") != "SETTLED":
                    raise Refused("MQ-outage settlement did not return SETTLED")
                pending = one_row(mysql_id,
                                  f"SELECT status FROM fund_event_outbox WHERE biz_no='settle:{errand_id}'", 1)[0]
                if pending != "PENDING":
                    raise Refused("Expected a PENDING outbox row while broker is paused")
            mark("fault_workload_done", errandId=errand_id)
        except Exception as e:
            workload_error = str(e)
            mark("fault_workload_failed", error=workload_error)
        if workload_error is None:
            remaining = args.fault_seconds - 1 - (time.monotonic() - started)
            if remaining > 0:
                time.sleep(remaining)
    during = snapshot(mysql_id)
    mark("after_unpause_invariants", funds=during)
    assert_invariants(before, during)
    if fault_window.watchdog_fired:
        raise Refused("S6 workload reached the hard pause deadline; phase is invalid")
    if workload_error:
        raise Refused(workload_error)

    if not wait_until(60, lambda: preflight.check(project, base,
            os.getenv("PEERGRAB_TEST_DB_HOST"), os.getenv("PEERGRAB_TEST_DB_PORT"), "true", "true")):
        raise Refused("Disposable stack did not pass preflight after unpause")
    if args.fault == "redis":
        post_reads = detail_phase(base, publisher, errand_id, 5, "after")
        mark("detail_phase_after", **post_reads)
        if (post_reads["outcomes"]["ok"] != post_reads["offered"]
                or post_reads["localRejected"] != 0 or post_reads["schedulerMissed"] != 0):
            raise Refused("Redis detail did not recover to a fully successful read phase")
        state = one_row(mysql_id,
                        f"SELECT status,slot_taken,grabber_id FROM errand WHERE id={errand_id}", 3)
        grabbed = scalar(mysql_id,
                         f"SELECT COUNT(*) FROM grab_record WHERE errand_id={errand_id} AND result='GRABBED'")
        if state != ["LOCKED", "1", "2001"] or grabbed != 1:
            raise Refused(f"Redis-outage grab invariant failed: state={state}, grabbed={grabbed}")
        if not wait_until(args.recovery_seconds, lambda: api(
                base, "GET", f"/api/errands/{errand_id}", publisher).get("status") == "LOCKED"):
            raise Refused("Detail cache did not recover to LOCKED within observation window")
    else:
        status = one_row(mysql_id,
                         f"SELECT status FROM errand WHERE id={errand_id}", 1)[0]
        legs = scalar(mysql_id,
                      f"SELECT COUNT(*) FROM wallet_ledger WHERE biz_no='settle:{errand_id}'")
        if status != "SETTLED" or legs != 3:
            raise Refused(f"MQ-outage funds not durably settled: status={status}, legs={legs}")
        if not wait_until(args.recovery_seconds,
                          lambda: one_row(mysql_id,
                              f"SELECT status FROM fund_event_outbox WHERE biz_no='settle:{errand_id}'", 1)[0]
                          == "SENT" and scalar(mysql_id,
                              f"SELECT COUNT(*) FROM notification WHERE errand_id={errand_id} "
                              f"AND msg_key IN ('settle:{errand_id}:pub','settle:{errand_id}:runner')") == 2):
            raise Refused("Fund outbox/notifications did not drain within observation window")
    after = snapshot(mysql_id)
    assert_invariants(before, after)
    mark("recovered", errandId=errand_id, fundsAfter=after)


def main():
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--fault", choices=sorted(TARGET), required=True)
    parser.add_argument("--execute", action="store_true", help="actually run the outage")
    parser.add_argument("--confirm-project", help="repeat the exact disposable project for --execute")
    parser.add_argument("--fault-seconds", type=int, default=30)
    parser.add_argument("--recovery-seconds", type=int, default=180)
    args = parser.parse_args()
    if not 20 <= args.fault_seconds <= 30 or not 30 <= args.recovery_seconds <= 600:
        parser.error("fault-seconds must be 20..30; recovery-seconds must be 30..600")
    if not preflight.PROJECT_RE.fullmatch(args.project):
        parser.error("project must be a peergrab-bench-* name")
    if os.getenv("PEERGRAB_BENCH_PROJECT") != args.project or os.getenv("COMPOSE_PROJECT_NAME") != args.project:
        parser.error("project must match both benchmark environment variables")
    manifest = {"scenario": "S6-" + args.fault, "project": args.project,
                "status": "RUNNING" if args.execute else "DRY_RUN", "faultSeconds": args.fault_seconds,
                "recoverySeconds": args.recovery_seconds, "events": []}
    run_name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    output = RUNS / f"s6-{args.project}-{run_name}.json"
    mark_lock = threading.Lock()

    def mark(name, **fields):
        with mark_lock:
            entry = {"atUtc": utc_now(), "event": name, **fields}
            manifest["events"].append(entry)
            print(json.dumps(entry, sort_keys=True), flush=True)
            if args.execute:
                RUNS.mkdir(parents=True, exist_ok=True)
                temporary = output.with_suffix(".json.tmp")
                temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
                os.replace(temporary, output)

    try:
        production_stopped()
        stack = preflight.check(args.project, os.getenv("PEERGRAB_BENCH_BASE_URL"),
                                os.getenv("PEERGRAB_TEST_DB_HOST"), os.getenv("PEERGRAB_TEST_DB_PORT"),
                                "true", "true")
        require_unpaused_services(args.project)
        target = inspected_service(args.project, TARGET[args.fault])
        if not target["State"]["Running"] or target["State"]["Paused"]:
            raise Refused("Fault target is not running/unpaused")
        fresh_stack(stack["mysql_container_id"])
        mark("preflight_passed" if args.execute else "dry_run_checked",
             target=TARGET[args.fault], targetId=target["Id"])
        if not args.execute:
            print("DRY RUN: no traffic or fault injected; add --execute --confirm-project=" + args.project)
            return 0
        if args.confirm_project != args.project:
            raise Refused("--execute requires exact --confirm-project")
        execute(args, mark)
        manifest["status"] = "PASS"
        mark("finished")
        print("S6 smoke PASS; manifest=" + str(output))
        return 0
    except Exception as e:
        manifest["status"] = "FAIL"
        mark("failed", reason=str(e)[:500])
        print("S6 smoke failed/refused: " + str(e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
