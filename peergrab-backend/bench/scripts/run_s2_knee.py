#!/usr/bin/env python3
"""Guarded S2 capacity-knee sweep on the ECS Docker host, never on the public API.

Offers are scheduled at a fixed rate. Every stage has at least 30 s of warmup and
60 s of measurement. The public site receives only sparse health GET requests.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, wait
from datetime import datetime, timezone
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid

import collect_metrics
import preflight
import run_first_round as first


RUNS_ROOT = first.RUNS_ROOT
RATES = (50, 100, 200, 400, 800)
PATH = "/api/errands?campusId=1&status=PUBLISHED&size=20"
MIN_WARMUP = 30
MIN_SAMPLE = 60
MIN_MEMORY = 3 * 1024 ** 3
MIN_DOCKER_FREE = 5 * 1024 ** 3
SAMPLE_INTERVAL = 2.0
PUBLIC_HEALTH_INTERVAL = 10.0
PROD_PROJECT = "peergrab-prod"
RUN_ID = re.compile(r"[0-9]{8}-[0-9]{6}-[0-9]{6}\Z")
_LOCAL = threading.local()


class Stopped(RuntimeError):
    """The experiment was stopped by an identity, health, or resource gate."""


def parse_rates(text):
    try:
        rates = tuple(int(part) for part in text.split(","))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("rates must be comma-separated integers") from exc
    if not rates or len(rates) > len(RATES) or any(rate not in RATES for rate in rates) \
            or any(left >= right for left, right in zip(rates, rates[1:])):
        raise argparse.ArgumentTypeError("choose increasing rates from 50,100,200,400,800")
    return rates


def verify_configuration(args):
    if (args.warmup_seconds < MIN_WARMUP or args.warmup_seconds > 120
            or args.sample_seconds < MIN_SAMPLE or args.sample_seconds > 300
            or args.max_in_flight not in (32, 64, 128, 256)
            or not 1000 <= args.timeout_ms <= 10000):
        raise Stopped("warmup 30..120 s, sample 60..300 s, in-flight 32/64/128/256, timeout 1..10 s")
    return parse_rates(args.rates) if isinstance(args.rates, str) else parse_rates(
        ",".join(str(rate) for rate in args.rates))


def full_container_ids(project):
    """Docker's default ps IDs are abbreviated; use full IDs for identity pinning."""
    output = collect_metrics.command(
        "docker", "ps", "--no-trunc", "-q", "--filter",
        f"label={preflight.PROJECT_LABEL}={project}", timeout=8)
    ids = output.splitlines() if output else []
    if not ids or len(ids) != len(set(ids)) or any(not re.fullmatch(r"[a-f0-9]{64}", item)
                                                  for item in ids):
        raise Stopped("benchmark Docker ps did not return unique full container IDs")
    return ids


def stack_identity(project):
    """Pin every running container ID after the full preflight has checked the stack."""
    ids = full_container_ids(project)
    containers = preflight.inspect("container", ids)
    services = {}
    for item in containers:
        labels = item["Config"].get("Labels") or {}
        service = labels.get(preflight.SERVICE_LABEL)
        if labels.get(preflight.PROJECT_LABEL) != project or labels.get(preflight.BENCH_LABEL) != "true":
            raise Stopped("container lost disposable benchmark labels")
        if service in services:
            raise Stopped("duplicate benchmark service")
        services[service] = item["Id"]
    if not {"app", "mysql", "worker"} <= services.keys():
        raise Stopped("required benchmark services are missing")
    return services


def assert_same_stack(project, base_url, initial, ids):
    if set(full_container_ids(project)) != set(ids):
        raise Stopped("benchmark container set changed during the sweep")
    current = first.checked_stack()
    if (current["project"] != project or current["api"] != base_url
            or current["mysql_container_id"] != initial["mysql_container_id"]
            or current["volumes"] != initial["volumes"]):
        raise Stopped("benchmark stack identity or named volumes changed")


def token_for_bench(base_url, timeout_ms):
    token = os.getenv("PEERGRAB_BENCH_TOKEN")
    if token:
        return token.removeprefix("Bearer ").strip()
    password = os.getenv("PEERGRAB_AUTH_DEMO_PASSWORD_1001")
    if not password:
        raise Stopped("set benchmark demo password or PEERGRAB_BENCH_TOKEN")
    body = json.dumps({"userId": 1001, "password": password}).encode()
    request = Request(base_url + "/api/auth/login", data=body,
                      headers={"Content-Type": "application/json"}, method="POST")
    with build_opener(ProxyHandler({})).open(request, timeout=timeout_ms / 1000) as response:
        payload = json.load(response)
        if response.status != 200 or payload.get("code") != "OK":
            raise Stopped("benchmark demo login failed")
    token = payload.get("data", {}).get("token")
    if not isinstance(token, str) or not token:
        raise Stopped("benchmark demo login lacks token")
    return token


def database_status(mysql_id):
    raw = mysql(mysql_id, "SHOW GLOBAL STATUS WHERE Variable_name IN "
                "('Threads_running','Threads_connected','Max_used_connections');")
    status = {}
    for line in raw.splitlines():
        fields = line.split("\t")
        if len(fields) != 2 or not fields[1].isdecimal():
            raise Stopped("invalid MySQL status row")
        status[fields[0]] = int(fields[1])
    if set(status) != {"Threads_running", "Threads_connected", "Max_used_connections"}:
        raise Stopped("incomplete MySQL status")
    return status


def mysql(container_id, sql):
    result = subprocess.run(
        ["docker", "exec", "-i", container_id, "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B peer_grab'],
        input=sql, text=True, capture_output=True, timeout=8)
    if result.returncode:
        raise Stopped("benchmark MySQL status/query failed")
    return result.stdout.strip()


def cgroup_cpu(container_id):
    raw = subprocess.run(["docker", "exec", container_id, "cat", "/sys/fs/cgroup/cpu.stat"],
                         capture_output=True, text=True, timeout=8, check=True).stdout
    values = {}
    for line in raw.splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[1].isdecimal():
            values[fields[0]] = int(fields[1])
    if not {"usage_usec", "nr_periods", "nr_throttled", "throttled_usec"} <= values.keys():
        raise Stopped("cgroup v2 CPU throttle counters unavailable")
    return {key: values[key] for key in ("usage_usec", "nr_periods", "nr_throttled", "throttled_usec")}


def verify_maintenance():
    if os.getenv("PEERGRAB_MAINTENANCE_APPROVED") != "YES":
        raise Stopped("maintenance mode requires PEERGRAB_MAINTENANCE_APPROVED=YES")
    running = preflight.docker("ps", "-q", "--filter",
                               f"label={preflight.PROJECT_LABEL}={PROD_PROJECT}").splitlines()
    if running:
        raise Stopped("peergrab-prod containers are still running; maintenance load refused")


def bench_health(base_url):
    request = Request(base_url + "/api/health", method="GET",
                      headers={"Accept": "application/json"})
    with build_opener(ProxyHandler({})).open(request, timeout=3) as response:
        body = response.read(4097)
        if response.status != 200 or len(body) > 4096:
            raise Stopped("benchmark API health is not 200 or response is oversized")
    payload = json.loads(body)
    if payload.get("code") != "OK" or payload.get("data", {}).get("status") != "UP":
        raise Stopped("benchmark API health is not UP")


def gate_resource(host, consecutive_high_cpu, maintenance=False):
    if host["memAvailableBytes"] < MIN_MEMORY:
        raise Stopped("host MemAvailable below 3 GiB")
    if host["dockerDataFreeBytes"] < MIN_DOCKER_FREE:
        raise Stopped("Docker data filesystem has less than 5 GiB free")
    if host["ioWaitPercent"] > 10:
        raise Stopped("host iowait above 10%")
    consecutive_high_cpu = consecutive_high_cpu + 1 if host["cpuPercent"] > 85 else 0
    if consecutive_high_cpu >= 2 and not maintenance:
        raise Stopped("host CPU above 85% for two consecutive samples")
    return consecutive_high_cpu


class Monitor:
    def __init__(self, project, base_url, public_url, baseline, services, output, maintenance):
        self.project = project
        self.base_url = base_url
        self.public_url = public_url
        self.baseline = baseline
        self.services = services
        self.output = output
        self.maintenance = maintenance
        self.abort = threading.Event()
        self.finished = threading.Event()
        self.ready = threading.Event()
        self.error = None
        self.thread = threading.Thread(target=self._run, daemon=True, name="s2-knee-monitor")

    def start(self):
        self.thread.start()

    def wait_ready(self):
        deadline = time.monotonic() + 20
        while not self.ready.is_set() and not self.abort.is_set() and time.monotonic() < deadline:
            self.ready.wait(timeout=.2)
        if not self.ready.is_set():
            self.check()
            raise Stopped("resource monitor did not complete its first sample before load")
        self.check()

    def stop(self):
        self.finished.set()
        self.thread.join(timeout=45)
        if self.thread.is_alive():
            self.abort.set()
            raise Stopped("resource monitor did not stop")
        self.check()

    def check(self):
        if self.error is not None:
            raise Stopped(str(self.error)) from self.error
        if self.abort.is_set():
            raise Stopped("resource monitor aborted")
        if not self.thread.is_alive() and not self.finished.is_set():
            raise Stopped("resource monitor exited unexpectedly")

    def _run(self):
        try:
            previous_cpu = collect_metrics.cpu_counters()
            previous_net = collect_metrics.network_bytes(collect_metrics.default_interface())
            disk = collect_metrics.default_disk()
            docker_root = Path(collect_metrics.command("docker", "info", "--format",
                                                       "{{.DockerRootDir}}", timeout=8))
            if not docker_root.is_absolute() or not docker_root.is_dir():
                raise Stopped("Docker data root cannot be verified")
            previous_disk = collect_metrics.disk_counters(disk)
            previous_at = time.monotonic()
            previous_process_cpu = time.process_time()
            next_health = previous_at
            high_cpu = 0
            with self.output.open("x", encoding="utf-8") as stream:
                stream.write(json.dumps({"kind": "metadata", "project": self.project,
                                         "intervalSeconds": SAMPLE_INTERVAL,
                                         "containers": self.services}) + "\n")
                stream.flush()
                while not self.finished.is_set():
                    began = time.monotonic()
                    process_cpu = time.process_time()
                    if self.maintenance:
                        verify_maintenance()
                    ids = full_container_ids(self.project)
                    if set(ids) != set(self.services.values()):
                        raise Stopped("benchmark container set changed during measurement")
                    cpu = collect_metrics.cpu_counters()
                    interface = collect_metrics.default_interface()
                    network = collect_metrics.network_bytes(interface)
                    disk_now = collect_metrics.disk_counters(disk)
                    elapsed = max(began - previous_at, .001)
                    cpu_pct, iowait = collect_metrics.percent_delta(cpu, previous_cpu)
                    host = {"cpuPercent": cpu_pct, "ioWaitPercent": iowait,
                            "memAvailableBytes": collect_metrics.available_memory(),
                            "dockerDataFreeBytes": shutil.disk_usage(docker_root).free,
                            "generatorCpuPercentOfOneCore": round(max(0.0,
                                (process_cpu - previous_process_cpu) / elapsed * 100), 2),
                            "netRxBytesPerSecond": round((network[0] - previous_net[0]) / elapsed),
                            "netTxBytesPerSecond": round((network[1] - previous_net[1]) / elapsed),
                            "load1": os.getloadavg()[0]}
                    if disk_now is not None and previous_disk is not None:
                        host.update({"diskReadBytesPerSecond": round((disk_now[0] - previous_disk[0]) / elapsed),
                                     "diskWriteBytesPerSecond": round((disk_now[1] - previous_disk[1]) / elapsed),
                                     "diskBusyPercent": round(max(0, (disk_now[2] - previous_disk[2])
                                                                  / (elapsed * 1000) * 100), 2)})
                    stats, stats_error = collect_metrics.docker_stats(ids)
                    if stats_error or len(stats) != len(ids):
                        raise Stopped("Docker stats collection failed")
                    if began >= next_health:
                        bench_health(self.base_url)
                        if not self.maintenance:
                            first.public_health(self.public_url, self.baseline)
                        next_health = began + PUBLIC_HEALTH_INTERVAL
                    entry = {"kind": "sample", "at": datetime.now(timezone.utc).isoformat(),
                             "host": host, "dockerStats": stats,
                             "cgroupCpu": {name: cgroup_cpu(self.services[name])
                                           for name in ("app", "mysql", "worker")},
                             "mysql": database_status(self.services["mysql"])}
                    stream.write(json.dumps(entry, separators=(",", ":")) + "\n")
                    stream.flush()
                    high_cpu = gate_resource(host, high_cpu, self.maintenance)
                    self.ready.set()
                    previous_cpu, previous_net, previous_disk, previous_at = (cpu, network, disk_now, began)
                    previous_process_cpu = process_cpu
                    self.finished.wait(max(0.0, SAMPLE_INTERVAL - (time.monotonic() - began)))
        except BaseException as exc:
            self.error = exc
            self.abort.set()


class Timeline:
    def __init__(self, seconds):
        self.seconds = seconds
        self.started = time.monotonic()
        self.buckets = [{"second": index, "offered": 0, "sent": 0, "completed": 0,
                         "ok": 0, "errors": 0, "localRejected": 0,
                         "schedulerMissed": 0, "capacityRejected": 0, "serverRejected": 0}
                        for index in range(seconds)]
        self.tail = {"completed": 0, "ok": 0, "errors": 0, "serverRejected": 0}
        self.latencies_ms = []
        self.error_kinds = {}
        self.lock = threading.Lock()

    def offer(self, index, sent=False, rejected=None):
        with self.lock:
            bucket = self.buckets[index]
            bucket["offered"] += 1
            bucket["sent"] += int(sent)
            bucket["localRejected"] += int(rejected is not None)
            if rejected == "scheduler":
                bucket["schedulerMissed"] += 1
            elif rejected == "capacity":
                bucket["capacityRejected"] += 1

    def complete(self, ok, latency_ms, error_kind=None):
        second = int(time.monotonic() - self.started)
        with self.lock:
            bucket = self.buckets[second] if 0 <= second < self.seconds else self.tail
            bucket["completed"] += 1
            bucket["ok"] += int(ok)
            bucket["errors"] += int(not ok)
            bucket["serverRejected"] += int(error_kind in ("HTTP_429", "HTTP_503"))
            if error_kind:
                self.error_kinds[error_kind] = self.error_kinds.get(error_kind, 0) + 1
            self.latencies_ms.append(latency_ms)

    def summary(self, rate):
        with self.lock:
            values = sorted(self.latencies_ms)
            def percentile(p):
                return round(values[max(0, (len(values) * p + 99) // 100 - 1)], 3) if values else None
            return {"offeredRps": rate, "offered": sum(row["offered"] for row in self.buckets),
                    "sent": sum(row["sent"] for row in self.buckets),
                    "completedWithinWindow": sum(row["completed"] for row in self.buckets),
                    "completed": sum(row["completed"] for row in self.buckets) + self.tail["completed"],
                    "ok": sum(row["ok"] for row in self.buckets) + self.tail["ok"],
                    "errors": sum(row["errors"] for row in self.buckets) + self.tail["errors"],
                    "localRejected": sum(row["localRejected"] for row in self.buckets),
                    "schedulerMissed": sum(row["schedulerMissed"] for row in self.buckets),
                    "capacityRejected": sum(row["capacityRejected"] for row in self.buckets),
                    "serverRejected": sum(row["serverRejected"] for row in self.buckets)
                    + self.tail["serverRejected"],
                    "errorKinds": dict(sorted(self.error_kinds.items())),
                    "completedRps": round(sum(row["completed"] for row in self.buckets) / self.seconds, 3),
                    "p50Ms": percentile(50), "p95Ms": percentile(95), "p99Ms": percentile(99),
                    "maxMs": round(values[-1], 3) if values else None,
                    "tail": dict(self.tail), "perSecond": [dict(row) for row in self.buckets]}


def request_once(host, port, token, timeout_ms):
    connection = getattr(_LOCAL, "connection", None)
    if connection is None or connection.host != host or connection.port != port:
        connection = http.client.HTTPConnection(host, port, timeout=timeout_ms / 1000)
        _LOCAL.connection = connection
    try:
        connection.request("GET", PATH, headers={"Authorization": "Bearer " + token,
                                                  "Accept": "application/json"})
        response = connection.getresponse()
        body = response.read(262145)
        if len(body) > 262144:
            connection.close()
            _LOCAL.connection = None
            return False, "OVERSIZED_RESPONSE"
        if response.status != 200:
            return False, f"HTTP_{response.status}"
        payload = json.loads(body)
        return (True, None) if payload.get("code") == "OK" else (False, "BUSINESS_CODE")
    except Exception as exc:
        connection.close()
        _LOCAL.connection = None
        return False, type(exc).__name__


def run_phase(executor, host, port, token, rate, seconds, max_in_flight, timeout_ms, monitor):
    timeline = Timeline(seconds)
    permits = threading.Semaphore(max_in_flight)
    futures = []

    def operation():
        began = time.monotonic()
        try:
            good, error_kind = request_once(host, port, token, timeout_ms)
            timeline.complete(good, (time.monotonic() - began) * 1000, error_kind)
        finally:
            permits.release()

    for index in range(rate * seconds):
        monitor.check()
        target = timeline.started + index / rate
        delay = target - time.monotonic()
        if delay > 0 and monitor.abort.wait(delay):
            monitor.check()
        second = min(index // rate, seconds - 1)
        lag = time.monotonic() - target
        if lag > max(.001, min(.1, 2 / rate)):
            timeline.offer(second, rejected="scheduler")
            continue
        if not permits.acquire(blocking=False):
            timeline.offer(second, rejected="capacity")
            continue
        timeline.offer(second, sent=True)
        futures.append(executor.submit(operation))
    remaining = timeline.started + seconds - time.monotonic()
    if remaining > 0 and monitor.abort.wait(remaining):
        monitor.check()
    done, pending = wait(futures, timeout=timeout_ms / 1000 + 10)
    if pending:
        raise Stopped(f"{len(pending)} benchmark requests failed to drain")
    for future in done:
        future.result()
    monitor.check()
    return timeline.summary(rate)


def register_run(mysql_id, rate, max_in_flight):
    run_id = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S") + "-" + f"{uuid.uuid4().int % 1000000:06d}"
    if not RUN_ID.fullmatch(run_id):
        raise Stopped("invalid generated run ID")
    sql = ("INSERT INTO bench_run(run_id,kind,scenario,started_at,status,concurrency,env_note) "
           f"VALUES('{run_id}','BENCH','S2-OPEN',NOW(3),'RUNNING',{max_in_flight},"
           f"'knee sweep; offered={rate}/s; guarded host-local');")
    mysql(mysql_id, sql)
    return run_id


def finish_run(stack, run_id, status, summary):
    if not RUN_ID.fullmatch(run_id) or status not in ("PASS", "FAIL"):
        raise Stopped("refusing to finalize an invalid run")
    # The app may be unhealthy at the knee. Pin the DB container, its disposable
    # labels/volume and its in-database marker instead of requiring API health.
    mysql_id = stack["mysql_container_id"]
    container = preflight.inspect("container", [mysql_id])[0]
    labels = container["Config"].get("Labels") or {}
    expected_volume = next((name for name in stack["volumes"]
                            if name.startswith(stack["project"] + "_") and "mysql" in name), None)
    mounts = [item for item in container["Mounts"] if item["Destination"] == "/var/lib/mysql"]
    if (container["Id"] != mysql_id or not container["State"]["Running"]
            or labels.get(preflight.PROJECT_LABEL) != stack["project"]
            or labels.get(preflight.SERVICE_LABEL) != "mysql"
            or labels.get(preflight.BENCH_LABEL) != "true"
            or len(mounts) != 1 or mounts[0].get("Name") != expected_volume):
        raise Stopped("benchmark MySQL identity changed; refusing run finalization")
    marker = mysql(mysql_id, "SELECT project_name FROM bench_guard WHERE guard_key='project';")
    if marker != stack["project"]:
        raise Stopped("benchmark DB marker changed; refusing run finalization")
    payload = json.dumps(summary, ensure_ascii=False, separators=(",", ":")).encode().hex()
    result = mysql(mysql_id,
                   f"UPDATE bench_run SET status='{status}',finished_at=NOW(3),"
                   f"summary=CONVERT(0x{payload} USING utf8mb4) "
                   f"WHERE run_id='{run_id}' AND kind='BENCH' AND scenario='S2-OPEN' "
                   "AND status='RUNNING'; SELECT ROW_COUNT();")
    if result != "1":
        raise Stopped("expected exactly one RUNNING benchmark row at finalization")


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def run_stage(stack, services, output, rate, args, token, baseline):
    stage_dir = output / f"stage-{rate}rps"
    stage_dir.mkdir()
    run_id = register_run(stack["mysql_container_id"], rate, args.max_in_flight)
    record = {"runId": run_id, "offeredRps": rate, "status": "RUNNING"}
    monitor = Monitor(stack["project"], stack["api"], args.public_health_url, baseline,
                      services, stage_dir / "resources.jsonl", args.maintenance)
    parsed = urlsplit(stack["api"])
    error = None
    monitor.start()
    try:
        monitor.wait_ready()
        with ThreadPoolExecutor(max_workers=args.max_in_flight, thread_name_prefix="s2-knee") as executor:
            warmup = run_phase(executor, parsed.hostname, parsed.port, token, rate,
                               args.warmup_seconds, args.max_in_flight, args.timeout_ms, monitor)
            sample = run_phase(executor, parsed.hostname, parsed.port, token, rate,
                               args.sample_seconds, args.max_in_flight, args.timeout_ms, monitor)
        record.update({"warmup": {key: value for key, value in warmup.items() if key != "perSecond"},
                       "sample": {key: value for key, value in sample.items() if key != "perSecond"}})
        record.update({"warmupSeconds": args.warmup_seconds,
                       "sampleSeconds": args.sample_seconds,
                       "maxInFlightLimit": args.max_in_flight,
                       "timeoutMillis": args.timeout_ms,
                       "offered": sample["offered"],
                       "completedWithinWindow": sample["completedWithinWindow"],
                       "completedRps": sample["completedRps"],
                       "ok": sample["ok"],
                       "localRejected": sample["localRejected"],
                       "p99Ms": sample["p99Ms"]})
        with (stage_dir / "per-second.jsonl").open("x", encoding="utf-8") as stream:
            for row in sample["perSecond"]:
                stream.write(json.dumps(row, separators=(",", ":")) + "\n")
        error_rate = sample["errors"] / max(1, sample["sent"])
        record["errorRate"] = round(error_rate, 6)
        record["zeroError"] = sample["errors"] == 0
        good = (sample["offered"] == rate * args.sample_seconds
                and sample["sent"] == sample["offered"]
                and sample["completed"] == sample["sent"]
                and sample["localRejected"] == 0 and error_rate <= .05)
        record["status"] = "PASS" if good else "FAIL"
    except BaseException as exc:
        error = exc
        record["status"] = "FAIL"
        record["abortReason"] = str(exc)
    finally:
        try:
            monitor.stop()
        except BaseException as exc:
            record["status"] = "FAIL"
            record["abortReason"] = str(exc)
            error = error or exc
        try:
            assert_same_stack(stack["project"], stack["api"], stack, services.values())
            if args.maintenance:
                verify_maintenance()
            else:
                first.public_health(args.public_health_url, baseline)
        except BaseException as exc:
            record["status"] = "FAIL"
            record["postCheckError"] = str(exc)
            error = error or exc
        try:
            finish_run(stack, run_id, record["status"], record)
        except BaseException as exc:
            record["finalizationError"] = str(exc)
            error = error or exc
        write_json(stage_dir / "result.json", record)
    if error:
        raise Stopped(f"stage {rate} RPS stopped: {error}") from error
    return record


def run(args):
    # Identity and disposable opt-in are checked before any benchmark login or load.
    stack = first.checked_stack()
    rates = verify_configuration(args)
    first.require_s2_seed(stack["mysql_container_id"])
    if args.maintenance:
        verify_maintenance()
        baseline = None
    else:
        first.validate_health_url(args.public_health_url)
        baseline = first.public_health(args.public_health_url)
    first.check_memory()
    services = stack_identity(stack["project"])
    if services["mysql"] != stack["mysql_container_id"]:
        raise Stopped("MySQL service ID changed after full preflight")
    metadata = collect_metrics.container_metadata(list(services.values()), stack["project"])
    git_sha = collect_metrics.command("git", "-C", str(collect_metrics.REPO_ROOT), "rev-parse", "HEAD")
    if args.dry_run:
        print(json.dumps({"dryRun": True, "project": stack["project"], "api": stack["api"],
                          "rates": rates, "warmupSeconds": args.warmup_seconds,
                          "sampleSeconds": args.sample_seconds,
                          "maxInFlight": args.max_in_flight, "timeoutMillis": args.timeout_ms,
                          "containers": services, "gitSha": git_sha,
                          "maintenance": args.maintenance,
                          "publicHealthBaselineSeconds": baseline}))
        return
    token = token_for_bench(stack["api"], args.timeout_ms)
    output = RUNS_ROOT / ("s2-knee-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
                         + "-" + uuid.uuid4().hex[:8])
    output.mkdir(parents=True)
    manifest = {"project": stack["project"], "api": stack["api"], "rates": rates,
                "warmupSeconds": args.warmup_seconds, "sampleSeconds": args.sample_seconds,
                "maxInFlight": args.max_in_flight, "timeoutMillis": args.timeout_ms,
                "maintenance": args.maintenance,
                "publicHealthUrl": args.public_health_url,
                "publicHealthBaselineSeconds": baseline, "containers": services,
                "containerMetadata": metadata, "gitSha": git_sha,
                "stages": [], "status": "RUNNING"}
    write_json(output / "manifest.json", manifest)
    try:
        for rate in rates:
            assert_same_stack(stack["project"], stack["api"], stack, services.values())
            if args.maintenance:
                verify_maintenance()
            else:
                first.public_health(args.public_health_url, baseline)
            first.check_memory()
            try:
                record = run_stage(stack, services, output, rate, args, token, baseline)
            except BaseException:
                result_path = output / f"stage-{rate}rps" / "result.json"
                if result_path.exists():
                    manifest["stages"].append(json.loads(result_path.read_text(encoding="utf-8")))
                    write_json(output / "manifest.json", manifest)
                raise
            manifest["stages"].append(record)
            write_json(output / "manifest.json", manifest)
            assert_same_stack(stack["project"], stack["api"], stack, services.values())
            if args.maintenance:
                verify_maintenance()
            else:
                first.public_health(args.public_health_url, baseline)
            if record["status"] != "PASS":
                raise Stopped(f"stage {rate} RPS failed; later rates skipped")
        manifest["status"] = "PASS"
    except BaseException as exc:
        manifest["status"] = "STOPPED"
        manifest["stopReason"] = str(exc)
        raise
    finally:
        write_json(output / "manifest.json", manifest)
        print(f"S2 knee records: {output}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rates", type=parse_rates, default=RATES,
                        help="increasing subset of 50,100,200,400,800")
    parser.add_argument("--warmup-seconds", type=int, default=30)
    parser.add_argument("--sample-seconds", type=int, default=60)
    parser.add_argument("--max-in-flight", type=int, default=128)
    parser.add_argument("--timeout-ms", type=int, default=5000)
    parser.add_argument("--public-health-url", default=first.DEFAULT_PUBLIC_HEALTH)
    parser.add_argument("--maintenance", action="store_true",
                        help="requires approved opt-in and verified stopped peergrab-prod project")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    def stop_on_signal(number, _frame):
        raise Stopped(f"received signal {number}; stopping benchmark")

    signal.signal(signal.SIGTERM, stop_on_signal)
    try:
        run(args)
    except BaseException as exc:
        print(f"S2 knee sweep stopped: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
