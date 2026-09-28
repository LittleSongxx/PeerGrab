#!/usr/bin/env python3
"""Cross-check external S2 arrival rates with Vegeta and the verified HTTPS route.

The default is a read-only route identity probe. An attack requires --execute
and --confirm-project. Workloads are an exact allowlist, and the current Square
cursor-first request is the default. Raw Vegeta records and bearer tokens are
never saved; periodic reporter snapshots are sanitized before persistence.
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

import run_remote_s2 as guard


WORKLOADS = {
    "cursor-first": ("square-cursor-first-v1", "/api/errands?campusId=1&cursor=&size=20"),
    "legacy-first": ("legacy-page-first-v1", guard.LIST_PATH),
}
ANSI_CLEAR = b"\x1b[2J\x1b[0;0H"
MAX_PERIODIC_REPORT_BYTES = 2_000_000


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--remote-backend", required=True)
    parser.add_argument("--remote-env", required=True)
    parser.add_argument("--direct-base-url", required=True)
    parser.add_argument("--workload", choices=tuple(WORKLOADS), default="cursor-first",
                        help="exact allowlisted first-page request; default matches the Square UI")
    parser.add_argument("--rates", type=guard.parse_rates, default=(700, 800, 900))
    parser.add_argument("--warmup", type=lambda s: guard.positive_int(s, "warmup", 1, 120), default=10)
    parser.add_argument("--sample", type=lambda s: guard.positive_int(s, "sample", 1, 180), default=30)
    parser.add_argument("--timeout-ms", type=lambda s: guard.positive_int(s, "timeout-ms", 100, 30000), default=5000)
    parser.add_argument("--ssh-host", default=guard.HOST,
                        help="user@host of the verified disposable benchmark server")
    parser.add_argument("--key", type=Path, default=guard.KEY)
    parser.add_argument("--vegeta", type=Path,
                        default=Path(os.environ.get("PEERGRAB_BENCH_VEGETA")
                                     or shutil.which("vegeta") or "vegeta"))
    parser.add_argument("--execute", action="store_true", help="start fixed-rate HTTP load after identity checks")
    parser.add_argument("--confirm-project", help="must exactly match --project for --execute")
    return parser.parse_args(argv)


def clean_proxy_env():
    # Match aiohttp's trust_env=False; a local VPN/proxy must not redirect load.
    return {key: value for key, value in os.environ.items()
            if key.lower() not in {"http_proxy", "https_proxy", "all_proxy", "no_proxy"}}


def inspect_report(report, rate, seconds):
    """Retain aggregate data only; drop Vegeta's raw errors and any target data."""
    requests = report.get("requests")
    codes = report.get("status_codes")
    latencies = report.get("latencies")
    if not isinstance(requests, int) or requests < 0 or not isinstance(codes, dict) \
            or not isinstance(latencies, dict):
        raise guard.Refused("Vegeta returned invalid aggregate metrics")
    status = {str(k): int(v) for k, v in codes.items()
              if str(k).isdigit() and isinstance(v, int) and v >= 0}
    if len(status) != len(codes) or sum(status.values()) > requests:
        raise guard.Refused("Vegeta returned inconsistent HTTP status counts")
    ok = sum(v for k, v in status.items() if 200 <= int(k) < 300)
    no_response = status.get("0", 0) + requests - sum(status.values())
    # Vegeta may include URLs in raw error strings. Retain only known error
    # classes, never the strings themselves or request headers.
    error_kinds = set()
    for error in report.get("errors", []):
        if not isinstance(error, str):
            error_kinds.add("other")
        elif "deadline exceeded" in error.lower() or "timeout" in error.lower():
            error_kinds.add("timeout")
        elif "connection reset" in error.lower():
            error_kinds.add("connection-reset")
        elif "connection refused" in error.lower():
            error_kinds.add("connection-refused")
        elif "eof" in error.lower():
            error_kinds.add("eof")
        else:
            error_kinds.add("other")
    expected = rate * seconds
    responses = requests - no_response
    return {"offered": expected, "resultRecords": requests,
            "completed": responses, "completedQps": round(responses / seconds, 3),
            "http2xx": ok, "http2xxQps": round(ok / seconds, 3),
            "httpOther": requests - ok - no_response,
            "transportErrors": no_response,
            "transportErrorKinds": sorted(error_kinds),
            "statusCodes": status, "actualIssueRate": round(float(report.get("rate", 0)), 3),
            "successfulThroughput": round(float(report.get("throughput", 0)), 3),
            "p50Ms": round(int(latencies.get("50th", 0)) / 1_000_000, 3),
            "p95Ms": round(int(latencies.get("95th", 0)) / 1_000_000, 3),
            "p99Ms": round(int(latencies.get("99th", 0)) / 1_000_000, 3),
            "totalDurationNs": int(report.get("duration", 0)) + int(report.get("wait", 0)),
            "tailWaitMs": round(int(report.get("wait", 0)) / 1_000_000, 3)}


def inspect_periodic_reports(output, rate, seconds):
    """Keep only count/latency snapshots; raw URLs and errors stay in memory.

    Vegeta's -every=1s snapshots are cumulative and printed with ANSI cursor
    controls. Their deltas are results received between reporter ticks, not
    exact request issue-time buckets or a measurement of scheduled offers.
    """
    try:
        lines = output.replace(ANSI_CLEAR, b"").decode("utf-8").splitlines()
        reports = [json.loads(line) for line in lines if line.strip()]
    except (UnicodeDecodeError, ValueError) as exc:
        raise guard.Refused("Vegeta periodic report was invalid") from exc
    if not reports or len(reports) > seconds + 2:
        raise guard.Refused("Vegeta periodic report had an unexpected snapshot count")
    if seconds >= 10 and len(reports) < seconds - 2:
        raise guard.Refused("Vegeta periodic report did not cover the full phase")
    final_issue_span_ns = reports[-1].get("duration")
    if (seconds >= 10 and (not isinstance(final_issue_span_ns, int)
                           or final_issue_span_ns < (seconds - 2) * 1_000_000_000)):
        raise guard.Refused("Vegeta requests did not span the full phase")

    snapshots = []
    previous_requests = 0
    previous_ok = 0
    previous_transport = 0
    previous_other = 0
    previous_latency_ns = 0
    previous_status = {}
    for index, report in enumerate(reports):
        aggregate = inspect_report(report, rate, seconds)
        latency_ns = report["latencies"].get("total")
        latest = report.get("latest")
        if (not isinstance(latency_ns, int) or latency_ns < previous_latency_ns
                or not isinstance(latest, str)
                or not re.fullmatch(r"\d{4}-\d\d-\d\dT[0-9:.]+(?:Z|[+-]\d\d:\d\d)", latest)):
            raise guard.Refused("Vegeta periodic report had invalid timing data")
        status = aggregate["statusCodes"]
        if (aggregate["resultRecords"] < previous_requests
                or aggregate["http2xx"] < previous_ok
                or aggregate["transportErrors"] < previous_transport
                or aggregate["httpOther"] < previous_other
                or any(count < previous_status.get(code, 0) for code, count in status.items())
                or any(code not in status for code in previous_status)):
            raise guard.Refused("Vegeta periodic counters decreased")
        delta_requests = aggregate["resultRecords"] - previous_requests
        delta_latency_ns = latency_ns - previous_latency_ns
        snapshots.append({
            "tick": index + 1,
            "latestIssuedAt": latest,
            "cumulativeResultRecords": aggregate["resultRecords"],
            "resultRecordsSincePrevious": delta_requests,
            "http2xxSincePrevious": aggregate["http2xx"] - previous_ok,
            "transportErrorsSincePrevious": aggregate["transportErrors"] - previous_transport,
            "httpOtherSincePrevious": aggregate["httpOther"] - previous_other,
            "statusCodesSincePrevious": {code: count - previous_status.get(code, 0)
                                          for code, count in status.items()
                                          if count - previous_status.get(code, 0)},
            "meanLatencyMsSincePrevious":
                round(delta_latency_ns / delta_requests / 1_000_000, 3)
                if delta_requests else None,
            "cumulativeP99Ms": aggregate["p99Ms"],
        })
        previous_requests = aggregate["resultRecords"]
        previous_ok = aggregate["http2xx"]
        previous_transport = aggregate["transportErrors"]
        previous_other = aggregate["httpOther"]
        previous_latency_ns = latency_ns
        previous_status = status
    return inspect_report(reports[-1], rate, seconds), snapshots


def degraded(result):
    return (result["completed"] < result["offered"] * 0.995
            or result["httpOther"] or result["transportErrors"]
            or result["http2xx"] < result["offered"] * 0.95)


def check_stack_identity(args, identity):
    latest = guard.remote_preflight(args)
    if guard.identity_fingerprint(latest) != guard.identity_fingerprint(identity):
        raise guard.Refused("Disposable benchmark identity changed")
    return latest


def check_identity(args, identity):
    latest = check_stack_identity(args, identity)
    base_url = args.direct_base_url.rstrip("/")
    asyncio.run(guard.verify_direct_target(args, latest, base_url))
    if args.workload == "cursor-first":
        asyncio.run(verify_cursor_target(args, latest, base_url))


def verify_cursor_payload(payload, expected_ids):
    if not isinstance(payload, dict) or payload.get("code") != "OK":
        raise guard.Refused("Cursor route did not return the benchmark list")
    page = payload.get("data")
    if (not isinstance(page, dict) or not isinstance(page.get("items"), list)
            or not isinstance(page.get("nextCursor"), str) or not page["nextCursor"]):
        raise guard.Refused("Cursor route did not return the expected first page")
    guard.verify_direct_list({"code": "OK", "data": page["items"]}, expected_ids)


async def verify_cursor_target(args, identity, base_url):
    """Probe the exact current Square request against the isolated DB."""
    import aiohttp

    expected_ids = guard.remote_list_ids(args, identity)
    timeout = aiohttp.ClientTimeout(total=min(args.timeout_ms / 1000, 10))
    async with aiohttp.ClientSession(timeout=timeout, trust_env=False) as session:
        token = await guard.login(session, base_url, guard.demo_password(args))
        try:
            async with session.get(base_url + WORKLOADS["cursor-first"][1],
                                   headers={"Authorization": "Bearer " + token},
                                   allow_redirects=False) as response:
                if response.status != 200:
                    raise guard.Refused("Cursor benchmark route did not return HTTP 200")
                payload = await response.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as exc:
            raise guard.Refused("Cursor benchmark route probe failed") from exc
    verify_cursor_payload(payload, expected_ids)


def run_phase(args, identity, token, rate, seconds):
    started_at_utc = datetime.now(timezone.utc).isoformat()
    url = args.direct_base_url.rstrip("/") + WORKLOADS[args.workload][1]
    target = json.dumps({"method": "GET", "url": url,
                         "header": {"Authorization": ["Bearer " + token]}},
                        separators=(",", ":")).encode() + b"\n"
    command = [str(args.vegeta), "attack", "-format=json", f"-rate={rate}/s",
               f"-duration={seconds}s", f"-timeout={args.timeout_ms}ms",
               "-http2=false", "-redirects=-1", "-max-body=0",
               "-workers=64", "-max-workers=4096", "-connections=512",
               "-max-connections=4096"]
    errors = []
    stop = threading.Event()

    def watch():
        while not stop.wait(10):
            try:
                # The route is checked before and after each phase. During an
                # overloaded phase, the extra login/list probe can time out
                # and invalidate an otherwise complete load sample.
                check_stack_identity(args, identity)
            except guard.Refused as exc:
                errors.append(f"Benchmark identity probe stopped: {exc}")
                return
            except Exception:
                errors.append("Benchmark identity probe failed unexpectedly")
                return

    attack = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, env=clean_proxy_env())
    report = None
    watcher = None
    reader = None
    report_bytes = bytearray()
    report_too_large = threading.Event()
    try:
        report = subprocess.Popen([str(args.vegeta), "report", "-type=json", "-every=1s"],
                                  stdin=attack.stdout, stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, env=clean_proxy_env())
        attack.stdout.close()

        def read_report():
            # Drain while the attack runs: a 180-s snapshot stream is larger
            # than a pipe buffer. Never copy raw reporter output to disk.
            while chunk := report.stdout.read(65536):
                if len(report_bytes) + len(chunk) <= MAX_PERIODIC_REPORT_BYTES:
                    report_bytes.extend(chunk)
                else:
                    report_too_large.set()

        reader = threading.Thread(target=read_report, daemon=True)
        reader.start()
        watcher = threading.Thread(target=watch, daemon=True)
        watcher.start()
        attack.stdin.write(target)
        attack.stdin.close()
        deadline = time.monotonic() + seconds + args.timeout_ms / 1000 + 20
        while attack.poll() is None:
            if errors:
                raise guard.Refused(errors[0])
            if time.monotonic() > deadline:
                raise guard.Refused("Vegeta attack exceeded the phase time limit")
            time.sleep(0.2)
        if attack.returncode:
            raise guard.Refused(f"Vegeta attack process exited with status {attack.returncode}")
        report.wait(timeout=15)
        reader.join(timeout=5)
        if reader.is_alive() or report.returncode or report_too_large.is_set():
            raise guard.Refused("Vegeta periodic report failed")
        result, snapshots = inspect_periodic_reports(bytes(report_bytes), rate, seconds)
        result["periodicSnapshots"] = snapshots
        result["periodicSnapshotCadenceSeconds"] = 1
        result["periodicSnapshotSemantics"] = "cumulative reporter snapshots; deltas count completed results, not scheduled offers"
        result["startedAtUtc"] = started_at_utc
        result["endedAtUtc"] = datetime.now(timezone.utc).isoformat()
        check_identity(args, identity)
        if errors:
            raise guard.Refused("Benchmark route or stack identity changed during load")
        return result
    finally:
        stop.set()
        if attack.poll() is None:
            attack.kill()
        attack.wait()
        if report is not None:
            if report.poll() is None:
                report.kill()
            report.wait()
        if reader is not None:
            reader.join(timeout=5)
        if watcher is not None:
            watcher.join(timeout=40)


def main(argv=None):
    args = parse_args(argv)
    args.direct = True
    try:
        guard.validate_config(args)
        if args.execute and args.confirm_project != args.project:
            raise guard.Refused("--execute requires --confirm-project matching --project")
        if args.execute and not args.vegeta.is_file():
            raise guard.Refused("Vegeta executable is unavailable")
        identity = guard.remote_preflight(args)
        count = guard.verify_seed(args, identity)
        check_identity(args, identity)
        if not args.execute:
            print(f"Verified {args.project}, {count} seed tasks, and exact HTTPS route; no load started.")
            return 0

        # A new private aggregate file is created only after every target check passes.
        output = guard.RUNS / ("external-s2-vegeta-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json")
        output.parent.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256(args.vegeta.read_bytes()).hexdigest()
        evidence = {"project": args.project, "transport": "verified direct HTTPS HTTP/1.1",
                    "directBaseUrl": args.direct_base_url,
                    "workload": WORKLOADS[args.workload][1],
                    "workloadVersion": WORKLOADS[args.workload][0],
                    "seedCount": count, "generator": "Vegeta", "vegetaSha256": digest,
                    "maxBodyCapture": 0, "status": "RUNNING", "stages": []}

        def save():
            payload = json.dumps(evidence, indent=2, ensure_ascii=False) + "\n"
            if token in payload:
                raise guard.Refused("Aggregate output contained private credentials")
            output.write_text(payload)

        with os.fdopen(os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            stream.write(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n")
        token = ""
        try:
            for rate in args.rates:
                check_identity(args, identity)
                password = guard.demo_password(args)
                async def get_token():
                    import aiohttp
                    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10),
                                                     trust_env=False) as session:
                        return await guard.login(session, args.direct_base_url.rstrip("/"), password)
                token = asyncio.run(get_token())
                warm = run_phase(args, identity, token, rate, args.warmup)
                sample = None if degraded(warm) else run_phase(args, identity, token, rate, args.sample)
                evidence["stages"].append({"offeredRps": rate, "warmup": warm, "sample": sample})
                save()
                print(f"{rate} offered QPS: warmup {warm['http2xxQps']} successful QPS"
                      + (f", sample {sample['http2xxQps']} successful QPS, P99 {sample['p99Ms']} ms"
                         if sample else "; degraded before sample"))
                if sample is None or degraded(sample):
                    evidence["status"] = "DEGRADED"
                    break
            else:
                evidence["status"] = "COMPLETE"
        except BaseException:
            evidence["status"] = "STOPPED"
            save()
            raise
        save()
        print(f"Saved {output}")
        return 0
    except guard.Refused as exc:
        print(f"Vegeta benchmark stopped: {exc}", file=sys.stderr)
        return 1
    except (OSError, ValueError, subprocess.SubprocessError, KeyboardInterrupt):
        # Avoid exception text: third-party errors can contain private headers.
        print("Vegeta benchmark stopped by a safety or process guard.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
