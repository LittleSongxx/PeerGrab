#!/usr/bin/env python3
"""Run the stock S2 JMeter plan on the verified disposable ECS host itself.

Dry run only checks identity, seed data and one real cursor response. --execute
and exact project confirmation are required for load. No Nginx or Docker state
is changed. Results from this same-host generator must not be merged with
external-generator capacity measurements.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import ssl
import statistics
import subprocess
import sys
import threading
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "scripts"))
import preflight  # noqa: E402
import run_s6_fault_smoke as s6  # noqa: E402
import jtl_summary as jtl  # noqa: E402


PLAN = HERE / "cursor-first-pooled.jmx"
PROPERTIES = HERE / "results.properties"
RUNS = HERE.parents[1] / "runs"
WORKLOAD = "/api/errands?campusId=1&cursor=&size=20"
ROUTE = re.compile(r"/__bench_[A-Za-z0-9]{24,128}/\Z")
TEST_START = re.compile(rb"Starting standalone test @[^\n]*\((\d{13})\)")
TAIL_SECONDS = 10
MAX_CONSOLE_BYTES = 2_000_000


def rates(value):
    try:
        result = tuple(int(item) for item in value.split(","))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("rates must be integers") from exc
    if not 1 <= len(result) <= 8 or any(not 1 <= rate <= 5000 for rate in result) \
            or any(a >= b for a, b in zip(result, result[1:])):
        raise argparse.ArgumentTypeError("rates require 1..8 increasing values in 1..5000")
    return result


def bounded(name, lower, upper):
    def convert(value):
        try:
            parsed = int(value)
        except ValueError as exc:
            raise argparse.ArgumentTypeError(f"{name} must be an integer") from exc
        if not lower <= parsed <= upper:
            raise argparse.ArgumentTypeError(f"{name} must be {lower}..{upper}")
        return parsed
    return convert


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--transport", choices=("loopback-api", "local-https"),
                        default="loopback-api")
    parser.add_argument("--https-route", default=os.getenv("PEERGRAB_BENCH_HTTPS_ROUTE"),
                        help="local Nginx path /__bench_<opaque>/; needed for local-https")
    parser.add_argument("--jmeter", type=Path,
                        default=Path(os.getenv("PEERGRAB_JMETER_BIN") or shutil.which("jmeter")
                                     or "/nonexistent/jmeter"))
    parser.add_argument("--rates", type=rates, default=(450, 600, 650))
    parser.add_argument("--warmup", type=bounded("warmup", 1, 120), default=20)
    parser.add_argument("--sample", type=bounded("sample", 1, 180), default=60)
    parser.add_argument("--repeat", type=bounded("repeat", 1, 3), default=1)
    parser.add_argument("--timeout-ms", type=bounded("timeout-ms", 100, 30000), default=5000)
    parser.add_argument("--heap-mb", type=bounded("heap-mb", 512, 4096), default=2048)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-project")
    return parser.parse_args(argv)


def validate(args):
    if not preflight.PROJECT_RE.fullmatch(args.project):
        raise s6.Refused("Project must be peergrab-bench-*")
    if args.transport == "local-https" and not ROUTE.fullmatch(args.https_route or ""):
        raise s6.Refused("local-https requires an exact /__bench_<opaque>/ route")
    if args.transport == "loopback-api" and args.https_route:
        raise s6.Refused("HTTPS route is only valid with local-https transport")
    if any(rate * (args.warmup + args.sample + TAIL_SECONDS) > 500_000
           for rate in args.rates):
        raise s6.Refused("Each JMeter round is limited to 500,000 planned arrivals")
    if args.execute and args.confirm_project != args.project:
        raise s6.Refused("--execute requires matching --confirm-project")
    if args.execute and (not args.jmeter.is_file() or not os.access(args.jmeter, os.X_OK)):
        raise s6.Refused("Stock Apache JMeter executable is unavailable")


def checked_stack(project, original=None):
    if not all(os.getenv(name) for name in ("PEERGRAB_BENCH_BASE_URL", "PEERGRAB_TEST_DB_HOST",
                                               "PEERGRAB_TEST_DB_PORT", "PEERGRAB_TEST_MQ_PORT")):
        raise s6.Refused("Set the disposable benchmark API, MySQL and MQ environment")
    preflight.require_maintenance_window()
    identity = preflight.check(project, os.getenv("PEERGRAB_BENCH_BASE_URL"),
                               os.getenv("PEERGRAB_TEST_DB_HOST"),
                               os.getenv("PEERGRAB_TEST_DB_PORT"))
    s6.require_unpaused_services(project)
    containers = {service: s6.inspected_service(project, service)
                  for service in ("mysql", "redis", "rmqbroker", "app", "worker")}
    ids = tuple(containers[service]["Id"] for service in containers)
    identity["serviceContainerIds"] = ids
    identity["appImageId"] = containers["app"]["Image"]
    identity["workerImageId"] = containers["worker"]["Image"]
    fingerprint = (identity["project"], identity["api"], identity["mysql_container_id"],
                   tuple(identity["volumes"]), ids)
    if original is not None and fingerprint != original["fingerprint"]:
        raise s6.Refused("Disposable stack identity changed during S2 load")
    identity["fingerprint"] = fingerprint
    return identity


def seed_ids(mysql_id):
    count = s6.scalar(mysql_id, """
        SELECT COUNT(*) FROM errand WHERE id>930000000000 AND id<=930000100000
         AND campus_id=1 AND status='PUBLISHED';
        """)
    if count < 10000:
        raise s6.Refused("S2 requires at least 10,000 published seed tasks")
    ids = s6.mysql(mysql_id, """
        SELECT id FROM errand WHERE campus_id=1 AND status='PUBLISHED'
         ORDER BY created_at DESC,id DESC LIMIT 1000;
        """).splitlines()
    if len(ids) < 20 or any(not item.isdecimal() for item in ids):
        raise s6.Refused("S2 seed ID sample was invalid")
    return count, {int(item) for item in ids}


def local_https_json(path, token):
    """TLS and Host use the real domain while TCP is pinned to local Nginx."""
    context = ssl.create_default_context()
    with socket.create_connection(("127.0.0.1", 443), timeout=8) as raw:
        with context.wrap_socket(raw, server_hostname="www.peergrab.cn") as secure:
            secure.settimeout(8)
            request = (f"GET {path} HTTP/1.1\r\nHost: www.peergrab.cn\r\n"
                       f"Authorization: Bearer {token}\r\nAccept: application/json\r\n"
                       "Connection: close\r\n\r\n")
            secure.sendall(request.encode("ascii"))
            response = http.client.HTTPResponse(secure)
            response.begin()
            content = response.read(131073)
            if response.status != 200 or len(content) > 131072:
                raise s6.Refused("Local HTTPS route rejected S2 cursor request")
            return json.loads(content)


def verify_page(payload, expected_ids):
    page = payload.get("data") if isinstance(payload, dict) else None
    rows = page.get("items") if isinstance(page, dict) else None
    if (not isinstance(payload, dict) or payload.get("code") != "OK"
            or not isinstance(rows, list) or len(rows) != 20
            or not isinstance(page.get("nextCursor"), str) or not page["nextCursor"]):
        raise s6.Refused("S2 cursor response did not pass its business assertion")
    ids = []
    for row in rows:
        raw = row.get("id") if isinstance(row, dict) else None
        if type(raw) is int and raw > 0:
            ids.append(raw)
        elif isinstance(raw, str) and raw.isdecimal():
            ids.append(int(raw))
        else:
            raise s6.Refused("S2 cursor response contained an invalid task ID")
    if len(set(ids)) != 20 or not set(ids) <= expected_ids or not any(
            930000000000 < value <= 930000100000 for value in ids):
        raise s6.Refused("S2 response does not belong to the verified benchmark database")


def route_probe(args, identity, expected_ids, token):
    checked_stack(args.project, identity)
    if args.transport == "loopback-api":
        page = s6.api(identity["api"], "GET", WORKLOAD, token)
        payload = {"code": "OK", "data": page}
    else:
        payload = local_https_json(args.https_route.rstrip("/") + WORKLOAD, token)
    verify_page(payload, expected_ids)


def clean_env(token, prefix, heap_mb, hosts_file=None):
    # The launcher needs a small runtime environment, not disposable wallet,
    # database, or demo-login credentials inherited from the Compose env file.
    allowed = ("PATH", "JAVA_HOME", "JRE_HOME", "HOME", "USER", "LANG", "LC_ALL",
               "TMPDIR", "TZ")
    env = {key: os.environ[key] for key in allowed if key in os.environ}
    env["PEERGRAB_BENCH_TOKEN"] = token
    env["PEERGRAB_BENCH_PREFIX"] = prefix
    env["HEAP"] = f"-Xms512m -Xmx{heap_mb}m"
    if hosts_file is not None:
        if not re.fullmatch(r"[A-Za-z0-9_./-]+", str(hosts_file)):
            raise s6.Refused("Local HTTPS hosts file path contains unsafe characters")
        env["JVM_ARGS"] = "-Djdk.net.hosts.file=" + str(hosts_file)
    return env


def jmeter_version(binary):
    import tempfile
    with tempfile.TemporaryDirectory(prefix="peergrab-jmeter-version-") as folder:
        result = subprocess.run([str(binary), "-v"], cwd=folder,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                env=clean_env("", "", 512), timeout=20)
    if result.returncode or not re.search(rb"\b5\.6\.3\b", result.stdout):
        raise s6.Refused("This S2 plan requires stock Apache JMeter 5.6.3")


def stop_group(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, 0)
    except ProcessLookupError:
        return
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=5)


def process_metrics(pid):
    try:
        children = Path(f"/proc/{pid}/task/{pid}/children").read_text().split()
        for child in children:
            if Path(f"/proc/{child}/comm").read_text().strip() == "java":
                pid = int(child)
                break
        stat = Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()
        cpu = (int(stat[11]) + int(stat[12])) / os.sysconf("SC_CLK_TCK")
        status = Path(f"/proc/{pid}/status").read_text()
        rss = re.search(r"^VmRSS:\s+(\d+)\s+kB", status, re.MULTILINE)
        return cpu, int(rss.group(1)) if rss else 0, len(list(Path(f"/proc/{pid}/fd").iterdir()))
    except (OSError, IndexError, ValueError, AttributeError):
        return None


def run_round(args, identity, expected_ids, token, rate, number, folder, hosts_file):
    name = f"rate-{rate}-round-{number}"
    jtl_path, log_path = folder / f"{name}.jtl", folder / f"{name}.log"
    if args.transport == "local-https":
        host, port, scheme, prefix = "www.peergrab.cn", 443, "https", args.https_route.rstrip("/")
    else:
        host, port, scheme, prefix = "127.0.0.1", int(identity["api"].rsplit(":", 1)[1]), "http", ""
    command = [str(args.jmeter), "-n", "-t", str(PLAN), "-l", str(jtl_path),
               "-j", str(log_path), "-q", str(PROPERTIES),
               f"-JbenchHost={host}", f"-JbenchPort={port}", f"-JbenchScheme={scheme}",
               f"-JbenchRate={rate}", f"-JbenchWarmup={args.warmup}",
               f"-JbenchSample={args.sample}", f"-JbenchTimeoutMs={args.timeout_ms}",
               f"-JbenchPerMinute={rate * 60}", "-JbenchUsers=128",
               f"-JbenchTotalSeconds={args.warmup + args.sample + TAIL_SECONDS}",
               "-JbenchPrelude=3", "-Jsample_variables=benchStartMs",
               "-Jjmeterengine.nongui.port=1000", "-Lorg.apache.jmeter.threads.JMeterThread=WARN"]
    env = clean_env(token, prefix, args.heap_mb, hosts_file)
    console, errors, resource = bytearray(), [], []
    stop = threading.Event()
    started_at = datetime.now(timezone.utc).isoformat()
    old_umask = os.umask(0o077)
    try:
        process = subprocess.Popen(command, cwd=folder, env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   start_new_session=True)
    finally:
        os.umask(old_umask)

    def reader():
        while chunk := process.stdout.read(65536):
            if len(console) + len(chunk) <= MAX_CONSOLE_BYTES:
                console.extend(chunk)
            else:
                errors.append("JMeter console exceeded its bounded output limit")

    def watcher():
        while not stop.wait(10):
            try:
                checked_stack(args.project, identity)
            except Exception:
                errors.append("Benchmark identity or maintenance gate changed during load")
                return

    read_thread = threading.Thread(target=reader, daemon=True)
    watch_thread = threading.Thread(target=watcher, daemon=True)
    read_thread.start()
    watch_thread.start()
    deadline = (time.monotonic() + 3 + args.warmup + args.sample + TAIL_SECONDS
                + args.timeout_ms / 1000 + 60)
    try:
        previous = None
        while process.poll() is None:
            if errors or time.monotonic() > deadline:
                raise s6.Refused("JMeter process was stopped by the benchmark watchdog")
            current = process_metrics(process.pid)
            if current:
                cpu, rss, fds = current
                now = time.monotonic()
                resource.append({"rssKiB": rss, "openFds": fds,
                                 "cpuPercentOneCore": round(100 * (cpu - previous[0]) /
                                                            (now - previous[1]), 2)
                                 if previous else None})
                previous = cpu, now
            time.sleep(1)
        read_thread.join(timeout=5)
        if read_thread.is_alive() or errors or process.returncode:
            raise s6.Refused("JMeter exited unsuccessfully or exceeded the console limit")
        match = TEST_START.search(bytes(console))
        if not match:
            raise s6.Refused("JMeter did not provide a test start timestamp")
        rows = jtl.read_jtl(jtl_path, expected_labels={"S2-warmup", "S2-sample", "S2-tail"},
                            max_rows=500_000)
        starts = {row.get("benchStartMs") for row in rows}
        if len(starts) != 1 or None in starts or abs(next(iter(starts)) - int(match.group(1))) > 1000:
            raise s6.Refused("JTL phase start marker did not match JMeter")
        start = next(iter(starts))
        warm = jtl.summarize_rows(rows, label="S2-warmup", duration_seconds=args.warmup,
                                  test_start_ms=start + 3000)
        sample = jtl.summarize_rows(rows, label="S2-sample", duration_seconds=args.sample,
                                    test_start_ms=start + (3 + args.warmup) * 1000)
        for phase, seconds in ((warm, args.warmup), (sample, args.sample)):
            phase["targetCountCheck"] = jtl.paced_target_count_check(
                phase["samplesStarted"], rate, seconds)
            phase["arrivalPacing"] = jtl.per_second_pacing_check(phase, rate)
        route_probe(args, identity, expected_ids, token)
        cpu_samples = [row["cpuPercentOneCore"] for row in resource
                       if row["cpuPercentOneCore"] is not None]
        return {"rateTargetRps": rate, "round": number, "startedAtUtc": started_at,
                "endedAtUtc": datetime.now(timezone.utc).isoformat(),
                "jtlFile": jtl_path.name, "privateLogFile": log_path.name,
                "warmup": warm, "sample": sample,
                "tail": {"samplesStarted": sum(row["label"] == "S2-tail" for row in rows),
                         "failedSamples": sum(row["label"] == "S2-tail" and not row["success"]
                                              for row in rows)},
                "generator": {"sampleCount": len(resource),
                              "maxRssMiB": round(max((row["rssKiB"] for row in resource),
                                                        default=0) / 1024, 2),
                              "maxOpenFds": max((row["openFds"] for row in resource), default=0),
                              "medianCpuPercentOneCore": round(statistics.median(cpu_samples), 2)
                                  if cpu_samples else None,
                              "maxCpuPercentOneCore": max(cpu_samples, default=0)}}
    finally:
        stop.set()
        stop_group(process)
        read_thread.join(timeout=5)
        watch_thread.join(timeout=40)


def degraded(result):
    sample = result["sample"]
    return (result["warmup"]["failedSamples"] > 0 or sample["failedSamples"] > 0
            or sample["outsideScheduledWindowBeyond100Ms"] > 0
            or sample["targetCountCheck"]["grossUnderproduction"]
            or bool(sample["arrivalPacing"]["badSeconds"]))


def save_private(path, manifest, token):
    payload = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    if token and token in payload:
        raise s6.Refused("Private summary unexpectedly contained a bearer token")
    temporary = path.with_suffix(".json.tmp")
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as file:
        file.write(payload)
    os.replace(temporary, path)


def main(argv=None):
    signal.signal(signal.SIGTERM, s6.interrupted)
    signal.signal(signal.SIGINT, s6.interrupted)
    args = parse_args(argv)
    try:
        validate(args)
        identity = checked_stack(args.project)
        count, expected_ids = seed_ids(identity["mysql_container_id"])
        token = s6.login(identity["api"], 1001)
        route_probe(args, identity, expected_ids, token)
        if not args.execute:
            print(f"Verified disposable {args.project}, {count} S2 seeds and {args.transport} cursor; no load.")
            return 0
        jmeter_version(args.jmeter)
        folder = RUNS / ("jmeter-s2-ecs-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
        folder.mkdir(mode=0o700, parents=True, exist_ok=False)
        hosts_file = None
        if args.transport == "local-https":
            hosts_file = folder / "jdk-hosts"
            with os.fdopen(os.open(hosts_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as file:
                file.write("127.0.0.1 localhost www.peergrab.cn\n::1 localhost\n")
        output = folder / "summary.json"
        manifest = {"project": args.project, "generator": "Apache JMeter 5.6.3 on target ECS",
                    "model": "128 persistent users paced by Constant Throughput Timer",
                    "transport": ("ECS local Nginx TLS, loopback TCP with www.peergrab.cn SNI"
                                  if args.transport == "local-https" else "ECS loopback API HTTP"),
                    "workload": WORKLOAD, "seedCount": count,
                    "jmxSha256": hashlib.sha256(PLAN.read_bytes()).hexdigest(),
                    "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    "jmeterLauncherSha256": hashlib.sha256(args.jmeter.read_bytes()).hexdigest(),
                    "appImageId": identity["appImageId"],
                    "workerImageId": identity["workerImageId"],
                    "warmupSeconds": args.warmup, "sampleSeconds": args.sample,
                    "timeoutMs": args.timeout_ms, "status": "RUNNING", "rounds": []}
        save_private(output, manifest, token)
        try:
            for rate in args.rates:
                for number in range(1, args.repeat + 1):
                    checked_stack(args.project, identity)
                    token = s6.login(identity["api"], 1001)
                    route_probe(args, identity, expected_ids, token)
                    result = run_round(args, identity, expected_ids, token, rate, number,
                                       folder, hosts_file)
                    manifest["rounds"].append(result)
                    save_private(output, manifest, token)
                    sample = result["sample"]
                    print(f"{rate} target RPS round {number}: {sample['samplesStarted']} started, "
                          f"{sample['successfulSamples']} successful, P99 {sample['allP99Ms']} ms")
                    if degraded(result):
                        manifest["status"] = "DEGRADED"
                        save_private(output, manifest, token)
                        print("Stopped at first degraded round.")
                        return 0
            manifest["status"] = "COMPLETE"
            save_private(output, manifest, token)
        except BaseException:
            manifest["status"] = "STOPPED"
            save_private(output, manifest, token)
            raise
        print("Saved private JMeter summary " + str(output))
        return 0
    except (s6.Refused, preflight.Refused, jtl.JtlError, OSError, ValueError,
            json.JSONDecodeError, subprocess.SubprocessError, ssl.SSLError,
            http.client.HTTPException, KeyboardInterrupt):
        # Do not print exception details: route strings and private JWTs can be nested.
        print("ECS-native S2 JMeter refused or stopped by a safety check.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
