#!/usr/bin/env python3
"""Fixed-arrival S2 probe from another machine to a verified disposable stack.

Traffic uses an SSH loopback tunnel or an identity-checked temporary HTTPS
allowlist route. The benchmark API port remains bound to loopback on the ECS.
"""

import argparse
import asyncio
from collections import Counter
from datetime import datetime, timezone
import http.client
import json
import math
import os
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import time

import aiohttp


HOST = os.environ.get("PEERGRAB_BENCH_SSH_HOST")
KEY = Path(os.environ.get("PEERGRAB_BENCH_SSH_KEY", "~/.ssh/id_ed25519")).expanduser()
SSH_HOST = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.-]*\Z")
RUNS = Path(__file__).resolve().parents[1] / "runs"
PROJECT = re.compile(r"peergrab-bench-[a-z0-9][a-z0-9_-]*\Z")
ENV_NAME = re.compile(r"\.env\.bench\.[a-z0-9][a-z0-9_-]*\Z")
SAFE_PATH = re.compile(r"/[a-zA-Z0-9._/-]+\Z")
LIST_PATH = "/api/errands?campusId=1&status=PUBLISHED&size=20"
REQUIRED_SERVICES = {"mysql", "redis", "rmqnamesrv", "rmqbroker", "app", "worker"}
DIRECT_URL = re.compile(r"https://www\.peergrab\.cn/__bench_[A-Za-z0-9]{24,128}/\Z")


class Refused(RuntimeError):
    pass


def positive_int(value, name, lower, upper):
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise argparse.ArgumentTypeError(f"{name} must be an integer") from exc
    if not lower <= parsed <= upper:
        raise argparse.ArgumentTypeError(f"{name} must be in {lower}..{upper}")
    return parsed


def parse_rates(value):
    parts = value.split(",")
    if not 1 <= len(parts) <= 8:
        raise argparse.ArgumentTypeError("--rates requires 1..8 stages")
    rates = tuple(positive_int(p, "rate", 1, 5000) for p in parts)
    if any(a >= b for a, b in zip(rates, rates[1:])):
        raise argparse.ArgumentTypeError("--rates must be strictly increasing")
    return rates


def validate_config(args):
    if not PROJECT.fullmatch(args.project):
        raise Refused("Project must be peergrab-bench-*")
    if not getattr(args, "ssh_host", None) or not SSH_HOST.fullmatch(args.ssh_host):
        raise Refused("Set --ssh-host to a user@host SSH target")
    if not ENV_NAME.fullmatch(args.remote_env):
        raise Refused("Remote environment must be a .env.bench.* basename")
    root = Path(args.remote_backend)
    if (not root.is_absolute() or not SAFE_PATH.fullmatch(str(root))
            or ".." in root.parts or root.name != "peergrab-backend"):
        raise Refused("Remote backend must be an absolute peergrab-backend directory")
    if not args.key.is_file():
        raise Refused("SSH identity file is unavailable")
    if any(rate * max(args.warmup, args.sample) > 500_000 for rate in args.rates):
        raise Refused("Each phase is limited to 500,000 offered requests")
    direct = getattr(args, "direct", False)
    direct_base = getattr(args, "direct_base_url", None)
    if direct != bool(direct_base):
        raise Refused("--direct and --direct-base-url must be provided together")
    if direct and not DIRECT_URL.fullmatch(direct_base):
        raise Refused("Direct URL must be https://www.peergrab.cn/__bench_<24-128 alphanumeric>/")


def ssh_base(args):
    return ["ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "IdentitiesOnly=yes", "-o", "ConnectTimeout=8", "-o", "ConnectionAttempts=1",
            "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2",
            "-i", str(args.key), args.ssh_host]


def remote_script(args, *, password=False):
    root = shlex.quote(args.remote_backend)
    env = shlex.quote("docker/" + args.remote_env)
    project = shlex.quote(args.project)
    lines = ["set -euo pipefail", f"cd {root}", "set -a", f"source {env}", "set +a",
             f"[[ \"$COMPOSE_PROJECT_NAME\" == {project} ]] || exit 42"]
    if password:
        lines += ["[[ -n \"${PEERGRAB_AUTH_DEMO_PASSWORD_1001:-}\" ]] || exit 43",
                  "printf '%s' \"$PEERGRAB_AUTH_DEMO_PASSWORD_1001\""]
    else:
        lines += ["export PEERGRAB_BENCH_DISPOSABLE=YES",
                  "export PEERGRAB_BENCH_PROJECT=\"$COMPOSE_PROJECT_NAME\"",
                  "export PEERGRAB_TEST_MQ_PORT=\"$PEERGRAB_RMQ_PROXY_PORT\"",
                  "python3 bench/scripts/preflight.py --project \"$COMPOSE_PROJECT_NAME\" "
                  "--base-url \"http://127.0.0.1:$PEERGRAB_API_PORT\" "
                  "--db-host 127.0.0.1 --db-port \"$PEERGRAB_MYSQL_PORT\""]
    return "\n".join(lines) + "\n"


def ssh_read(args, script):
    try:
        result = subprocess.run(ssh_base(args) + ["bash -s"], input=script,
                                capture_output=True, text=True, timeout=35)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Refused("SSH identity check failed or timed out") from exc
    if result.returncode:
        # Never echo remote stderr: it may include shell diagnostics from a private env file.
        raise Refused("Remote benchmark identity check was rejected")
    return result.stdout


def remote_preflight(args):
    output = ssh_read(args, remote_script(args))
    try:
        identity = json.loads(output)
        assert isinstance(identity, dict)
        assert identity["project"] == args.project
        assert identity["api"].startswith("http://127.0.0.1:")
        port = int(identity["api"].rsplit(":", 1)[1])
        assert 1024 <= port <= 65535
        assert identity["api"] == f"http://127.0.0.1:{port}"
        assert REQUIRED_SERVICES <= set(identity["services"])
        assert len(identity["volumes"]) == 3
        assert all(v.startswith(args.project + "_") for v in identity["volumes"])
        assert re.fullmatch(r"[0-9a-f]{64}", identity["mysql_container_id"])
    except (AssertionError, KeyError, TypeError, ValueError, IndexError) as exc:
        raise Refused("Remote preflight returned an invalid benchmark identity") from exc
    identity["api_port"] = port
    return identity


def identity_fingerprint(identity):
    return (identity["project"], identity["api"], identity["mysql_container_id"],
            tuple(identity["volumes"]))


def verify_seed(args, identity):
    container = identity["mysql_container_id"]
    if not re.fullmatch(r"[0-9a-f]{64}", container):
        raise Refused("Invalid benchmark MySQL identity")
    sql = ("SELECT COUNT(*) FROM errand WHERE id > 930000000000 "
           "AND id <= 930000100000 AND campus_id=1 AND status='PUBLISHED';")
    mysql_command = ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B peer_grab '
                     f'-e {shlex.quote(sql)}')
    script = ("set -euo pipefail\n"
              f"docker exec {container} sh -ec {shlex.quote(mysql_command)}\n")
    count = ssh_read(args, script).strip()
    if not count.isdecimal() or int(count) < 10000:
        raise Refused("S2 benchmark stack requires at least 10,000 published seed tasks")
    return int(count)


def remote_list_ids(args, identity):
    """Read benchmark DB identities for a one-request direct-route guard."""
    container = identity["mysql_container_id"]
    if not re.fullmatch(r"[0-9a-f]{64}", container):
        raise Refused("Invalid benchmark MySQL identity")
    sql = ("SELECT id FROM errand WHERE campus_id=1 AND status='PUBLISHED' "
           "ORDER BY created_at DESC, id DESC LIMIT 1000;")
    mysql_command = ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B peer_grab '
                     f'-e {shlex.quote(sql)}')
    script = "set -euo pipefail\n" + f"docker exec {container} sh -ec {shlex.quote(mysql_command)}\n"
    values = ssh_read(args, script).splitlines()
    if len(values) < 20 or any(not value.isdecimal() for value in values):
        raise Refused("Benchmark DB list sample is invalid")
    return {int(value) for value in values}


def verify_direct_list(payload, expected_ids):
    if not isinstance(payload, dict) or payload.get("code") != "OK":
        raise Refused("Direct route did not return the benchmark list")
    rows = payload.get("data")
    if not isinstance(rows, list) or len(rows) != 20:
        raise Refused("Direct route returned an unexpected benchmark list")
    raw_ids = [row.get("id") for row in rows if isinstance(row, dict)]
    if len(raw_ids) != 20 or any(
            not ((type(value) is int and value > 0)
                 or (isinstance(value, str) and value.isdecimal())) for value in raw_ids):
        raise Refused("Direct route does not match the isolated S2 benchmark data")
    ids = [int(value) for value in raw_ids]
    if (len(set(ids)) != 20 or not set(ids) <= expected_ids
            or not any(930000000000 < value <= 930000100000 for value in ids)):
        raise Refused("Direct route does not match the isolated S2 benchmark data")


async def verify_direct_target(args, identity, base_url):
    """Fail before load if HTTPS does not reach the SSH-verified disposable DB."""
    expected_ids = remote_list_ids(args, identity)
    timeout = aiohttp.ClientTimeout(total=min(args.timeout_ms / 1000, 10))
    async with aiohttp.ClientSession(timeout=timeout, trust_env=False) as session:
        token = await login(session, base_url, demo_password(args))
        try:
            async with session.get(base_url + LIST_PATH,
                                   headers={"Authorization": "Bearer " + token},
                                   allow_redirects=False) as response:
                if response.status != 200:
                    raise Refused("Direct benchmark route did not return HTTP 200")
                payload = await response.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as exc:
            raise Refused("Direct benchmark route probe failed") from exc
        verify_direct_list(payload, expected_ids)


def demo_password(args):
    # Read the disposable stack's private demo password over authenticated SSH.
    # It is used only in memory and never written into a result file or exception.
    password = ssh_read(args, remote_script(args, password=True))
    if not password or len(password) > 256 or "\n" in password or "\r" in password:
        raise Refused("Disposable demo password was unavailable")
    return password


def local_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def start_tunnel(args, remote_port):
    port = local_port()
    command = ssh_base(args)[:-1] + ["-N", "-o", "ExitOnForwardFailure=yes", "-L",
            f"127.0.0.1:{port}:127.0.0.1:{remote_port}", args.ssh_host]
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise Refused("SSH tunnel exited before accepting connections")
        try:
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
            connection.request("GET", "/api/health")
            response = connection.getresponse()
            body = response.read(4097)
            connection.close()
            health = json.loads(body) if len(body) <= 4096 else {}
            if response.status == 200 and health.get("code") == "OK" \
                    and health.get("data", {}).get("status") == "UP":
                return process, port
        except (OSError, ValueError, http.client.HTTPException):
            pass
        time.sleep(0.2)
    stop_tunnel(process)
    raise Refused("SSH tunnel did not reach the verified benchmark API")


def stop_tunnel(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


async def login(session, base_url, password):
    try:
        async with session.post(base_url + "/api/auth/login",
                                json={"userId": 1001, "password": password},
                                allow_redirects=False) as response:
            body = await response.json(content_type=None)
            if response.status != 200 or body.get("code") != "OK":
                raise Refused("Disposable demo login was rejected")
            token = body.get("data", {}).get("token")
            if not isinstance(token, str) or not token or len(token) > 8192:
                raise Refused("Disposable demo login returned no usable token")
            return token
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as exc:
        raise Refused("Disposable demo login failed") from exc


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * fraction) - 1)], 3)


def ssh_cpu_seconds(tunnel):
    """Linux child-process CPU; SSH encryption cost is separate from Python load."""
    pid = getattr(tunnel, "pid", None)
    if not isinstance(pid, int):
        return None
    try:
        fields = Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()
        return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")
    except (OSError, IndexError, ValueError):
        return None


async def run_phase(session, base_url, token, rate, duration, max_in_flight,
                    tunnel, identity_error, timeout_ms=None):
    started_at_utc = datetime.now(timezone.utc).isoformat()
    started = time.monotonic()
    deadline = started + duration
    cpu_started = time.process_time()
    seconds = [Counter() for _ in range(duration)]
    totals = Counter()
    latencies_ms = []
    all_attempt_latencies_ms = []
    tasks = set()
    active = 0
    peak = 0

    async def sample_cpu():
        previous_cpu = time.process_time()
        previous_ssh_cpu = ssh_cpu_seconds(tunnel)
        previous_wall = started
        for index in range(duration):
            await asyncio.sleep(max(0, started + index + 1 - time.monotonic()))
            now_cpu = time.process_time()
            now_wall = time.monotonic()
            seconds[index]["generatorCpuPercentOneCore"] = round(
                100 * (now_cpu - previous_cpu) / max(0.001, now_wall - previous_wall), 2)
            current_ssh_cpu = ssh_cpu_seconds(tunnel)
            if previous_ssh_cpu is not None and current_ssh_cpu is not None:
                seconds[index]["sshCpuPercentOneCore"] = round(
                    100 * (current_ssh_cpu - previous_ssh_cpu)
                    / max(0.001, now_wall - previous_wall), 2)
            previous_ssh_cpu = current_ssh_cpu
            previous_cpu, previous_wall = now_cpu, now_wall

    cpu_task = asyncio.create_task(sample_cpu())

    async def request():
        nonlocal active
        began = time.monotonic()
        try:
            async with session.get(base_url + LIST_PATH,
                                   headers={"Authorization": "Bearer " + token},
                                   allow_redirects=False) as response:
                raw = await response.read()
                elapsed = time.monotonic() - began
                all_attempt_latencies_ms.append(elapsed * 1000)
                slot = int(time.monotonic() - started)
                bucket = seconds[slot] if 0 <= slot < duration else None
                if bucket is not None:
                    bucket["completed"] += 1
                if 0 <= slot < duration:
                    totals["completedWithinWindow"] += 1
                totals["completed"] += 1
                wire_size = response.content_length if response.content_length is not None else len(raw)
                totals["responseBytesApprox"] += wire_size
                if bucket is not None:
                    bucket["responseBytesApprox"] += wire_size
                if response.status >= 500:
                    totals["http5xx"] += 1
                    if bucket is not None:
                        bucket["http5xx"] += 1
                elif response.status != 200:
                    totals["httpOther"] += 1
                    if bucket is not None:
                        bucket["httpOther"] += 1
                    if response.status in (401, 403):
                        identity_error.append("Benchmark API authentication failed")
                else:
                    try:
                        payload = json.loads(raw)
                    except ValueError:
                        payload = {}
                    if isinstance(payload, dict) and payload.get("code") == "OK":
                        totals["ok"] += 1
                        if bucket is not None:
                            bucket["ok"] += 1
                        latencies_ms.append(elapsed * 1000)
                    else:
                        totals["businessErrors"] += 1
                        if bucket is not None:
                            bucket["businessErrors"] += 1
        except asyncio.TimeoutError:
            totals["timeouts"] += 1
            all_attempt_latencies_ms.append((time.monotonic() - began) * 1000)
            slot = int(time.monotonic() - started)
            if 0 <= slot < duration:
                seconds[slot]["timeouts"] += 1
        except aiohttp.ClientError:
            totals["networkErrors"] += 1
            all_attempt_latencies_ms.append((time.monotonic() - began) * 1000)
            slot = int(time.monotonic() - started)
            if 0 <= slot < duration:
                seconds[slot]["networkErrors"] += 1
        except Exception:
            identity_error.append("Generator request processing failed")
        finally:
            active -= 1

    planned = rate * duration
    allowed_lag = max(0.001, min(0.1, 2 / rate))
    for index in range(planned):
        target = started + index / rate
        await asyncio.sleep(max(0, target - time.monotonic()))
        if identity_error or (tunnel is not None and tunnel.poll() is not None):
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            cpu_task.cancel()
            await asyncio.gather(cpu_task, return_exceptions=True)
            raise Refused(identity_error[0] if identity_error else "SSH tunnel closed during load")
        slot = min(duration - 1, max(0, int(time.monotonic() - started)))
        totals["offered"] += 1
        seconds[slot]["offered"] += 1
        if time.monotonic() - target > allowed_lag:
            totals["schedulerMissed"] += 1
            seconds[slot]["schedulerMissed"] += 1
        elif active >= max_in_flight:
            totals["capacityRejected"] += 1
            seconds[slot]["capacityRejected"] += 1
        else:
            active += 1
            peak = max(peak, active)
            totals["sent"] += 1
            seconds[slot]["sent"] += 1
            task = asyncio.create_task(request())
            tasks.add(task)
            task.add_done_callback(tasks.discard)

    await asyncio.sleep(max(0, deadline - time.monotonic()))
    cpu_window = time.process_time() - cpu_started
    await asyncio.gather(*tasks, return_exceptions=True)
    await cpu_task
    if identity_error or (tunnel is not None and tunnel.poll() is not None):
        raise Refused(identity_error[0] if identity_error else "SSH tunnel closed during load")
    summary = dict(totals)
    ok_within_window = sum(row["ok"] for row in seconds)
    summary.update({"startedAtUtc": started_at_utc,
                    "endedAtUtc": datetime.now(timezone.utc).isoformat(),
                    "rateOfferedPerSec": rate, "durationSeconds": duration,
                    "timeoutMillis": timeout_ms,
                    "offeredPerSecActual": round(totals["offered"] / duration, 3),
                    "completedPerSecInWindow": round(totals["completedWithinWindow"] / duration, 3),
                    "okWithinWindow": ok_within_window,
                    "okPerSecInWindow": round(ok_within_window / duration, 3),
                    "okPerSecTotal": round(totals["ok"] / duration, 3),
                    "p50Ms": percentile(latencies_ms, 0.50),
                    "p95Ms": percentile(latencies_ms, 0.95),
                    "p99Ms": percentile(latencies_ms, 0.99),
                    "allAttemptP50Ms": percentile(all_attempt_latencies_ms, 0.50),
                    "allAttemptP95Ms": percentile(all_attempt_latencies_ms, 0.95),
                    "allAttemptP99Ms": percentile(all_attempt_latencies_ms, 0.99),
                    "latencySamplesOk": len(latencies_ms),
                    "latencySamplesAllAttempts": len(all_attempt_latencies_ms),
                    "peakInFlight": peak,
                    "generatorCpuPercentOneCore": round(100 * cpu_window / duration, 2),
                    "responseMbpsInWindowApprox": round(
                        sum(row["responseBytesApprox"] for row in seconds) * 8 / duration / 1e6, 3),
                    "perSecond": [dict({"second": i, "offered": 0, "sent": 0,
                                        "completed": 0, "ok": 0, "http5xx": 0,
                                        "httpOther": 0, "businessErrors": 0,
                                        "timeouts": 0, "networkErrors": 0,
                                        "schedulerMissed": 0, "capacityRejected": 0,
                                        "responseBytesApprox": 0}, **dict(counter))
                                  for i, counter in enumerate(seconds)]})
    return summary


async def identity_watch(args, expected, tunnel, errors, stop):
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=10)
            return
        except asyncio.TimeoutError:
            pass
        if tunnel is not None and tunnel.poll() is not None:
            errors.append("SSH tunnel closed during load")
            return
        try:
            current = await asyncio.to_thread(remote_preflight, args)
            if identity_fingerprint(current) != identity_fingerprint(expected):
                raise Refused("Remote benchmark identity changed during load")
        except Exception:
            errors.append("Remote benchmark identity check failed during load")
            return


def degraded(phase, scheduler_limit=0.005):
    # Rare event-loop timer misses on the remote generator are measured, not
    # silently relabeled as server failures. Above 0.5%, stop and treat the
    # generator as saturated before interpreting the server curve.
    misses = phase.get("schedulerMissed", 0)
    sent = phase.get("sent", phase["offered"] - misses)
    return (any(phase.get(key, 0) for key in
                ("http5xx", "httpOther", "businessErrors", "timeouts", "networkErrors",
                 "capacityRejected"))
            or misses / max(1, phase["offered"]) > scheduler_limit
            or phase["ok"] != sent
            or ("completedWithinWindow" in phase
                and phase["completedWithinWindow"] < 0.95 * sent))


async def run(args, identity, tunnel, base_url, on_stage):
    timeout = aiohttp.ClientTimeout(total=args.timeout_ms / 1000)
    connector = aiohttp.TCPConnector(limit=args.max_in_flight,
                                     limit_per_host=args.max_in_flight)
    password = demo_password(args)
    stages = []
    stopped_on_degradation = False
    async with aiohttp.ClientSession(connector=connector, timeout=timeout, trust_env=False) as session:
        for rate in args.rates:
            current = await asyncio.to_thread(remote_preflight, args)
            if identity_fingerprint(current) != identity_fingerprint(identity):
                raise Refused("Remote benchmark identity changed before stage")
            token = await login(session, base_url, password)
            errors = []
            stop = asyncio.Event()
            watcher = asyncio.create_task(identity_watch(args, identity, tunnel, errors, stop))
            try:
                warm = await run_phase(session, base_url, token, rate, args.warmup,
                                       args.max_in_flight, tunnel, errors, args.timeout_ms)
                sample = None
                # A brief pacing hiccup during warmup is recorded, while the
                # separate sample retains the stricter generator quality gate.
                if not degraded(warm, scheduler_limit=0.02):
                    current = await asyncio.to_thread(remote_preflight, args)
                    if identity_fingerprint(current) != identity_fingerprint(identity):
                        raise Refused("Remote benchmark identity changed after warmup")
                    sample = await run_phase(session, base_url, token, rate, args.sample,
                                             args.max_in_flight, tunnel, errors, args.timeout_ms)
            finally:
                stop.set()
                await watcher
            current = await asyncio.to_thread(remote_preflight, args)
            if identity_fingerprint(current) != identity_fingerprint(identity):
                raise Refused("Remote benchmark identity changed after stage")
            stage = {"offeredRps": rate, "warmup": warm, "sample": sample}
            stages.append(stage)
            on_stage(stage)
            if sample is None:
                print(f"{rate} offered RPS: warmup degraded; stopping before sample.")
                stopped_on_degradation = True
                break
            print(f"{rate} offered RPS: completed {sample['completedPerSecInWindow']} RPS, "
                  f"successful API QPS {sample['okPerSecInWindow']}, "
                  f"success P99 {sample['p99Ms']} ms, all-attempt P99 {sample['allAttemptP99Ms']} ms, "
                  f"5xx {sample.get('http5xx', 0)}, "
                  f"timeouts {sample.get('timeouts', 0)}, "
                  f"local rejects {sample.get('schedulerMissed', 0) + sample.get('capacityRejected', 0)}")
            if degraded(sample):
                print("Stopping at first degraded stage; refine the rate grid around this point.")
                stopped_on_degradation = True
                break
    return stages, stopped_on_degradation


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--remote-backend", required=True,
                        help="absolute path to remote peergrab-backend checkout")
    parser.add_argument("--remote-env", required=True, help="remote .env.bench.* basename")
    parser.add_argument("--rates", type=parse_rates, default=(20, 40, 80))
    parser.add_argument("--warmup", type=lambda s: positive_int(s, "warmup", 1, 120), default=10)
    parser.add_argument("--sample", type=lambda s: positive_int(s, "sample", 1, 180), default=30)
    parser.add_argument("--max-in-flight", type=lambda s: positive_int(s, "max-in-flight", 1, 1024), default=64)
    parser.add_argument("--timeout-ms", type=lambda s: positive_int(s, "timeout-ms", 100, 30000), default=5000)
    parser.add_argument("--ssh-host", default=HOST,
                        help="user@host of the verified disposable benchmark server")
    parser.add_argument("--key", type=Path, default=KEY)
    parser.add_argument("--output", type=Path, help="new JSON result file, default under bench/runs")
    parser.add_argument("--direct", action="store_true",
                        help="use an explicitly verified temporary HTTPS proxy instead of SSH forwarding")
    parser.add_argument("--direct-base-url",
                        help="exact https://www.peergrab.cn/__bench_<opaque>/ prefix")
    parser.add_argument("--dry-run", action="store_true", help="verify remote stack without tunnel/login/load")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    tunnel = None
    try:
        validate_config(args)
        identity = remote_preflight(args)
        seed_count = verify_seed(args, identity)
        print(f"Verified disposable {identity['project']} API on remote 127.0.0.1:{identity['api_port']}")
        if args.dry_run:
            print(f"Verified {seed_count} published S2 seed tasks. Dry run: no tunnel, login or load was started.")
            return 0
        if args.direct:
            base_url = args.direct_base_url.rstrip("/")
            asyncio.run(verify_direct_target(args, identity, base_url))
            current = remote_preflight(args)
            if identity_fingerprint(current) != identity_fingerprint(identity):
                raise Refused("Remote benchmark identity changed before direct load")
        else:
            tunnel, local = start_tunnel(args, identity["api_port"])
            base_url = f"http://127.0.0.1:{local}"
        output = args.output or RUNS / ("external-s2-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json")
        output.parent.mkdir(parents=True, exist_ok=True)
        manifest = {"project": args.project, "host": args.ssh_host,
                    "remoteApiPort": identity["api_port"], "s2SeedCount": seed_count,
                    "transport": "verified HTTPS temporary proxy" if args.direct else "SSH local forward",
                    "directBaseUrl": args.direct_base_url if args.direct else None,
                    "workload": LIST_PATH,
                    "generator": "separate host", "status": "RUNNING", "stages": []}
        with os.fdopen(os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            json.dump(manifest, stream, ensure_ascii=False, indent=2)
            stream.write("\n")

        def save(stage=None, status="RUNNING"):
            if stage is not None:
                manifest["stages"].append(stage)
            manifest["status"] = status
            with output.open("w", encoding="utf-8") as stream:
                json.dump(manifest, stream, ensure_ascii=False, indent=2)
                stream.write("\n")

        try:
            _, stopped_on_degradation = asyncio.run(run(args, identity, tunnel, base_url, save))
        except BaseException:
            save(status="STOPPED")
            raise
        save(status="DEGRADED" if stopped_on_degradation else "COMPLETE")
        print(f"Saved {output}")
        return 0
    except (Refused, OSError, aiohttp.ClientError, asyncio.TimeoutError, KeyboardInterrupt) as exc:
        # Do not leak private login or token data from nested exception messages.
        print(f"External benchmark stopped: {type(exc).__name__}", file=sys.stderr)
        return 1
    finally:
        if tunnel is not None:
            stop_tunnel(tunnel)


if __name__ == "__main__":
    sys.exit(main())
