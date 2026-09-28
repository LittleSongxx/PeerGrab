#!/usr/bin/env python3
"""Guarded, external JMeter S2 first-cursor benchmark for a disposable ECS stack.

Default mode checks identity and data, without sending load. ``--execute`` plus
an exact project confirmation is needed to run the stock JMeter 5.6.3 plan.
The bearer stays in the JMeter process environment, never in argv or results.
"""

import argparse
import asyncio
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time

import aiohttp

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import run_remote_s2 as guard

import jtl_summary as jtl


ROOT = Path(__file__).resolve().parent
PLAN = ROOT / "cursor-first.jmx"
PROPERTIES = ROOT / "results.properties"
WORKLOAD = "/api/errands?campusId=1&cursor=&size=20"
WORKLOAD_VERSION = "square-cursor-first-jmeter-v1"
VERSION = "5.6.3"
TEST_START = re.compile(rb"Starting standalone test @[^\n]*\((\d{13})\)")
MAX_CONSOLE_BYTES = 2_000_000


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--remote-backend", required=True)
    parser.add_argument("--remote-env", required=True)
    parser.add_argument("--direct-base-url", required=True,
                        help="identity-checked temporary public HTTPS route")
    parser.add_argument("--ssh-host", default=guard.HOST)
    parser.add_argument("--key", type=Path, default=guard.KEY)
    parser.add_argument("--jmeter", type=Path,
                        default=Path(os.environ.get("PEERGRAB_BENCH_JMETER")
                                     or shutil.which("jmeter") or "jmeter"))
    parser.add_argument("--rates", type=guard.parse_rates, default=(450, 600, 650))
    parser.add_argument("--warmup", type=lambda value: guard.positive_int(value, "warmup", 1, 120),
                        default=20)
    parser.add_argument("--sample", type=lambda value: guard.positive_int(value, "sample", 1, 180),
                        default=60)
    parser.add_argument("--repeat", type=lambda value: guard.positive_int(value, "repeat", 1, 3),
                        default=1)
    parser.add_argument("--timeout-ms",
                        type=lambda value: guard.positive_int(value, "timeout-ms", 100, 30000),
                        default=5000)
    parser.add_argument("--heap-mb", type=lambda value: guard.positive_int(value, "heap-mb", 512, 4096),
                        default=2048)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-project", help="must exactly match --project")
    return parser.parse_args(argv)


def validate(args):
    args.direct = True
    guard.validate_config(args)
    if any(rate * (args.warmup + args.sample) > 500_000 for rate in args.rates):
        raise guard.Refused("Each JMeter round is limited to 500,000 nominal arrivals")
    if args.execute and args.confirm_project != args.project:
        raise guard.Refused("--execute requires --confirm-project matching --project")
    if args.execute and not args.jmeter.is_file():
        raise guard.Refused("JMeter executable is unavailable")


def check_stack(args, original):
    latest = guard.remote_preflight(args)
    if guard.identity_fingerprint(latest) != guard.identity_fingerprint(original):
        raise guard.Refused("Disposable stack identity changed")
    return latest


async def cursor_probe(args, identity):
    expected_ids = guard.remote_list_ids(args, identity)
    base = args.direct_base_url.rstrip("/")
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10),
                                     trust_env=False) as session:
        token = await guard.login(session, base, guard.demo_password(args))
        try:
            async with session.get(base + WORKLOAD,
                                   headers={"Authorization": "Bearer " + token},
                                   allow_redirects=False) as response:
                if response.status != 200:
                    raise guard.Refused("Cursor route did not return HTTP 200")
                payload = await response.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as exc:
            raise guard.Refused("Cursor route probe failed") from exc
    page = payload.get("data") if isinstance(payload, dict) else None
    if (not isinstance(payload, dict) or payload.get("code") != "OK" or not isinstance(page, dict)
            or not isinstance(page.get("nextCursor"), str) or not page["nextCursor"]):
        raise guard.Refused("Cursor route did not return a valid first page")
    guard.verify_direct_list({"code": "OK", "data": page.get("items")}, expected_ids)


def check_route(args, identity):
    check_stack(args, identity)
    asyncio.run(cursor_probe(args, identity))


def login_token(args):
    async def login():
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10),
                                         trust_env=False) as session:
            return await guard.login(session, args.direct_base_url.rstrip("/"),
                                     guard.demo_password(args))
    return asyncio.run(login())


def clean_env(token, prefix, heap_mb):
    # aiohttp's route probes use trust_env=False. Match that in JMeter and
    # remove ambient JVM/proxy knobs that could redirect the load path.
    excluded = {"http_proxy", "https_proxy", "all_proxy", "no_proxy", "java_tool_options",
                "_java_options", "jdk_java_options", "jvm_args", "jmeter_opts"}
    env = {key: value for key, value in os.environ.items() if key.lower() not in excluded}
    env["PEERGRAB_BENCH_TOKEN"] = token
    env["PEERGRAB_BENCH_PREFIX"] = prefix
    env["HEAP"] = f"-Xms512m -Xmx{heap_mb}m"
    return env


def jmeter_version(binary):
    # -v can create a local jmeter.log; run it in a private temporary folder.
    import tempfile
    with tempfile.TemporaryDirectory(prefix="peergrab-jmeter-version-") as directory:
        result = subprocess.run([str(binary), "-v"], cwd=directory,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                env=clean_env("", "", 512), timeout=20)
    if result.returncode or not re.search(rb"\b5\.6\.3\b", result.stdout):
        raise guard.Refused("This plan requires stock Apache JMeter 5.6.3")


def process_metrics(pid):
    try:
        # The stock bin/jmeter launcher is a shell that waits for its Java
        # child; sample the actual generator JVM rather than the idle shell.
        children = Path(f"/proc/{pid}/task/{pid}/children").read_text().split()
        for child in children:
            try:
                if Path(f"/proc/{child}/comm").read_text().strip() == "java":
                    pid = int(child)
                    break
            except OSError:
                pass
        stat = Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()
        cpu = (int(stat[11]) + int(stat[12])) / os.sysconf("SC_CLK_TCK")
        status = Path(f"/proc/{pid}/status").read_text()
        rss = re.search(r"^VmRSS:\s+(\d+)\s+kB", status, re.MULTILINE)
        fds = len(list(Path(f"/proc/{pid}/fd").iterdir()))
        return cpu, int(rss.group(1)) if rss else 0, fds
    except (OSError, IndexError, ValueError, AttributeError):
        return None


def run_jmeter(args, identity, token, rate, round_number, folder):
    prefix = args.direct_base_url.removeprefix("https://www.peergrab.cn").rstrip("/")
    if not re.fullmatch(r"/__bench_[A-Za-z0-9]{24,128}", prefix):
        raise guard.Refused("Temporary HTTPS route changed")
    base = f"rate-{rate}-round-{round_number}"
    jtl_path = folder / f"{base}.jtl"
    log_path = folder / f"{base}.log"
    command = [str(args.jmeter), "-n", "-t", str(PLAN), "-l", str(jtl_path),
               "-j", str(log_path), "-q", str(PROPERTIES),
               "-JbenchHost=www.peergrab.cn", "-JbenchPort=443", "-JbenchScheme=https",
               f"-JbenchRate={rate}", f"-JbenchWarmup={args.warmup}",
               f"-JbenchSample={args.sample}", f"-JbenchTimeoutMs={args.timeout_ms}",
               "-JbenchPrelude=3", "-Jsample_variables=benchStartMs",
               "-Jjmeterengine.nongui.port=1000", "-Lorg.apache.jmeter.threads.JMeterThread=WARN"]
    env = clean_env(token, prefix, args.heap_mb)
    console = bytearray()
    too_large = threading.Event()
    stop = threading.Event()
    errors = []
    resource = []
    started_at = datetime.now(timezone.utc).isoformat()
    old_umask = os.umask(0o077)
    try:
        process = subprocess.Popen(command, cwd=folder, env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    finally:
        os.umask(old_umask)

    def read_console():
        while chunk := process.stdout.read(65536):
            if len(console) + len(chunk) <= MAX_CONSOLE_BYTES:
                console.extend(chunk)
            else:
                too_large.set()

    def watch_stack():
        while not stop.wait(10):
            try:
                check_stack(args, identity)
            except Exception:
                errors.append("Disposable identity check failed during JMeter load")
                return

    reader = threading.Thread(target=read_console, daemon=True)
    watcher = threading.Thread(target=watch_stack, daemon=True)
    reader.start()
    watcher.start()
    deadline = time.monotonic() + 3 + args.warmup + args.sample + args.timeout_ms / 1000 + 60
    try:
        previous = None
        while process.poll() is None:
            if errors or too_large.is_set() or time.monotonic() > deadline:
                raise guard.Refused("JMeter load was stopped by its watchdog")
            metrics = process_metrics(process.pid)
            if metrics:
                cpu, rss, fd = metrics
                now = time.monotonic()
                resource.append({"atUtc": datetime.now(timezone.utc).isoformat(),
                                 "rssKiB": rss, "openFds": fd,
                                 "cpuPercentOneCore":
                                     round(100 * (cpu - previous[0]) / (now - previous[1]), 2)
                                     if previous else None})
                previous = cpu, now
            time.sleep(1)
        reader.join(timeout=5)
        if reader.is_alive() or too_large.is_set() or process.returncode:
            raise guard.Refused("JMeter CLI failed or exceeded output limit")
        match = TEST_START.search(bytes(console))
        if not match:
            raise guard.Refused("JMeter did not report a test start timestamp")
        console_start_ms = int(match.group(1))
        rows = jtl.read_jtl(jtl_path, expected_labels={"S2-warmup", "S2-sample"},
                            max_rows=500_000)
        exact_starts = {row.get("benchStartMs") for row in rows}
        if (len(exact_starts) != 1 or None in exact_starts
                or abs(next(iter(exact_starts)) - console_start_ms) > 1000):
            raise guard.Refused("JMeter phase start marker was missing or inconsistent")
        test_start_ms = next(iter(exact_starts))
        warm = jtl.summarize_rows(rows, label="S2-warmup",
                                  duration_seconds=args.warmup,
                                  test_start_ms=test_start_ms + 3000)
        sample = jtl.summarize_rows(rows, label="S2-sample",
                                    duration_seconds=args.sample,
                                    test_start_ms=test_start_ms + (3 + args.warmup) * 1000)
        warm["arrivalCountCheck"] = jtl.random_arrival_count_check(
            warm["samplesStarted"], rate, args.warmup)
        sample["arrivalCountCheck"] = jtl.random_arrival_count_check(
            sample["samplesStarted"], rate, args.sample)
        warm["arrivalPacing"] = jtl.per_second_pacing_check(warm, rate)
        sample["arrivalPacing"] = jtl.per_second_pacing_check(sample, rate)
        check_route(args, identity)
        return {"rateTargetRps": rate, "round": round_number,
                "startedAtUtc": started_at, "endedAtUtc": datetime.now(timezone.utc).isoformat(),
                "jtlFile": jtl_path.name, "privateLogFile": log_path.name,
                "warmup": warm, "sample": sample,
                "generator": {"sampleCount": len(resource),
                              "maxRssMiB": round(max((x["rssKiB"] for x in resource), default=0) / 1024, 2),
                              "maxOpenFds": max((x["openFds"] for x in resource), default=0),
                              "maxCpuPercentOneCore": max((x["cpuPercentOneCore"] or 0 for x in resource),
                                                          default=0)}}
    finally:
        stop.set()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        reader.join(timeout=5)
        watcher.join(timeout=40)


def degraded(phase):
    return (phase["failedSamples"] > 0 or phase["outsideScheduledWindowBeyond100Ms"] > 0
            or phase["arrivalCountCheck"]["grossUnderproduction"]
            or bool(phase["arrivalPacing"]["badSeconds"]))


def main(argv=None):
    args = parse_args(argv)
    try:
        validate(args)
        identity = guard.remote_preflight(args)
        count = guard.verify_seed(args, identity)
        check_route(args, identity)
        if not args.execute:
            print(f"Verified {args.project}, {count} seeded tasks and exact HTTPS cursor route; no load started.")
            return 0
        jmeter_version(args.jmeter)
        folder = guard.RUNS / ("jmeter-s2-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
        folder.mkdir(mode=0o700, parents=True, exist_ok=False)
        manifest_path = folder / "summary.json"
        manifest = {"project": args.project, "generator": "Apache JMeter 5.6.3",
                    "model": "experimental Open Model random arrivals",
                    "transport": "verified public HTTPS to disposable stack",
                    "workload": WORKLOAD, "workloadVersion": WORKLOAD_VERSION,
                    "seedCount": count,
                    "jmxSha256": hashlib.sha256(PLAN.read_bytes()).hexdigest(),
                    "jmeterLauncherSha256": hashlib.sha256(args.jmeter.read_bytes()).hexdigest(),
                    "warmupSeconds": args.warmup, "sampleSeconds": args.sample,
                    "timeoutMs": args.timeout_ms, "status": "RUNNING", "rounds": []}

        def save():
            payload = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
            if token and token in payload:
                raise guard.Refused("Summary included a private credential")
            manifest_path.write_text(payload)

        token = ""
        try:
            for rate in args.rates:
                for number in range(1, args.repeat + 1):
                    check_route(args, identity)
                    token = login_token(args)
                    result = run_jmeter(args, identity, token, rate, number, folder)
                    manifest["rounds"].append(result)
                    save()
                    sample = result["sample"]
                    print(f"{rate} target RPS round {number}: started {sample['samplesStarted']}, "
                          f"successful {sample['successfulSamples']}, "
                          f"all-attempt P99 {sample['allP99Ms']} ms")
                    if degraded(result["warmup"]) or degraded(sample):
                        manifest["status"] = "DEGRADED"
                        save()
                        print("Stopped at first degraded round; inspect private evidence.")
                        return 0
            manifest["status"] = "COMPLETE"
            save()
        except BaseException:
            manifest["status"] = "STOPPED"
            save()
            raise
        print(f"Saved private summary {manifest_path}")
        return 0
    except (guard.Refused, jtl.JtlError, OSError, ValueError, subprocess.SubprocessError,
            aiohttp.ClientError, asyncio.TimeoutError, KeyboardInterrupt):
        # Intentionally do not include exception text: third-party errors or
        # shell diagnostics could contain the temporary route or bearer token.
        print("JMeter S2 benchmark stopped by safety, identity, or process guard.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
