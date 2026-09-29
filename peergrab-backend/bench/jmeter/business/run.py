#!/usr/bin/env python3
"""Fail-closed, local disposable-stack runner for S1/S3/S4 JMeter plans.

Fixture creation and SQL verification run on the Docker host. For independent
load generation, use an SSH tunnel to this verified loopback binding and retain
the same JMX/CSV, then run equivalent host-side postchecks before reporting.
"""
import argparse
import csv
from collections import Counter
import csv
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

HERE = Path(__file__).resolve().parent
BACKEND = HERE.parents[2]
RUNS = HERE.parents[1] / "runs"
PREFLIGHT = BACKEND / "bench" / "scripts" / "preflight.py"
sys.path.insert(0, str(BACKEND / "bench" / "scripts"))
import preflight  # noqa: E402 - benchmark safety gate resolved from this checkout
import prewarm_cache  # noqa: E402 - bounded cache warm-up shares the same gate


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise RuntimeError("Benchmark postcheck refused an HTTP redirect")


SAFE_OPENER = build_opener(ProxyHandler({}), NoRedirect())


def check_stack(project, base, db_host, db_port):
    if not all((project, base, db_host, db_port)):
        raise RuntimeError("Missing disposable benchmark stack identity")
    checked = subprocess.run([sys.executable, str(PREFLIGHT), "--project", project,
                              "--base-url", base, "--db-host", db_host, "--db-port", db_port],
                             cwd=BACKEND, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, timeout=60)
    if checked.returncode:
        raise RuntimeError("Disposable benchmark stack preflight failed")
    return json.loads(checked.stdout)


def docker_text(*arguments):
    command = subprocess.run(["docker", *arguments], stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, text=True, timeout=20)
    if command.returncode:
        raise RuntimeError("Benchmark Docker identity command failed")
    return command.stdout.strip()


def clean_jmeter_env():
    excluded = {"java_tool_options", "_java_options", "jdk_java_options", "jvm_args", "jmeter_opts"}
    env = {key: value for key, value in os.environ.items()
           if key.lower() not in excluded
           and not key.lower().endswith("_proxy")
           and not key.startswith("PEERGRAB_")}
    env["HEAP"] = "-Xms512m -Xmx1536m"
    return env


def stop_jmeter_process_group(process):
    """Stop JMeter's launcher shell and any still-running Java child."""
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


def run_jmeter(command, output, base, project, db_host, db_port, jmeter):
    environment = clean_jmeter_env()
    version = subprocess.run([jmeter, "-v"], cwd=output, env=environment,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             timeout=20, check=True)
    if not re.search(rb"\b5\.6\.3\b", version.stdout):
        raise RuntimeError("Only stock Apache JMeter 5.6.3 is supported")
    check_stack(project, base, db_host, db_port)
    deadline = time.monotonic() + 900
    with (output / "jmeter-console.txt").open("w", encoding="utf-8") as console:
        process = subprocess.Popen(command, cwd=output, env=environment,
                                   stdout=console, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            while process.poll() is None:
                if time.monotonic() > deadline:
                    raise RuntimeError("JMeter exceeded the 15-minute watchdog")
                time.sleep(10)
                check_stack(project, base, db_host, db_port)
            if process.returncode:
                raise RuntimeError("JMeter failed; inspect private jmeter-console.txt")
        finally:
            stop_jmeter_process_group(process)


def mysql(container, sql):
    result = subprocess.run(["docker", "exec", "-i", container, "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B '
                             '--default-character-set=utf8mb4 peer_grab'],
                            input=sql, text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Verified benchmark MySQL command failed")
    return result.stdout.strip()


def scalar(container, sql):
    value = mysql(container, sql)
    if not value.isdecimal():
        raise RuntimeError("Benchmark SQL returned a non-numeric scalar")
    return int(value)


def json_count(value, label):
    if isinstance(value, bool) or not (
            isinstance(value, int) and value >= 0
            or isinstance(value, str) and re.fullmatch(r"[0-9]{1,12}", value)):
        raise RuntimeError("Invalid numeric benchmark API field: " + label)
    number = int(value)
    if number > 1_000_000_000_000:
        raise RuntimeError("Out-of-range benchmark API field: " + label)
    return number


def api(base, path, token, method="GET"):
    request = Request(base + path, method=method,
                      data=b"{}" if method == "POST" else None,
                      headers={"Authorization": "Bearer " + token,
                               "Content-Type": "application/json"})
    with SAFE_OPENER.open(request, timeout=30) as response:
        payload = json.load(response)
        if response.status != 200 or payload.get("code") != "OK":
            raise RuntimeError("Benchmark postcheck API failed at " + path)
        return payload.get("data")


def read_token(output):
    with (output / "s3_admin.csv").open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if len(rows) != 1 or not rows[0].get("token"):
        raise RuntimeError("Invalid private S3 admin fixture")
    return rows[0]["token"]


def prewarm_fixture(base_url, output):
    """Warm unique S3 IDs and persist only aggregate evidence."""
    with (output / "s3_read.csv").open(newline="", encoding="utf-8") as handle:
        ids = []
        seen = set()
        for row in csv.DictReader(handle):
            raw = row.get("errand_id", "")
            if raw.isdecimal() and int(raw) not in seen:
                seen.add(int(raw))
                ids.append(int(raw))
    if not ids:
        raise RuntimeError("S3 fixture contains no task IDs for prewarm")
    if len(ids) > prewarm_cache.MAX_IDS:
        raise RuntimeError("S3 fixture has too many unique IDs for one prewarm batch")
    result = prewarm_cache.run(base_url, ids)
    (output / "prewarm.json").write_text(
        json.dumps({k: result[k] for k in
                    ("requested", "distinct", "found", "written", "missing", "elapsedMs")},
                   indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def parse_jtl(output):
    with (output / "results.jtl").open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        required = {"timeStamp", "elapsed", "label", "responseCode", "success", "peergrabOutcome"}
        allowed = required | {"bytes", "sentBytes", "grpThreads", "allThreads", "Latency", "IdleTime", "Connect"}
        if not required <= set(reader.fieldnames or ()) or not set(reader.fieldnames or ()) <= allowed:
            raise RuntimeError("JMeter JTL contains missing or unsafe columns")
        rows = []
        outcomes = {"GRABBED", "SLOT_FULL", "GRAB_CONFLICT", "GRAB_RATE_LIMITED",
                    "DETAIL_OK", "PUBLISHED", "READ_AFTER_WRITE_OK", "SETTLED", "DUPLICATE",
                    "UNCLASSIFIED", "INVALID_JSON", "HTTP_OR_SHAPE_ERROR", "UNEXPECTED",
                    "DETAIL_MISMATCH", "PUBLISH_MISMATCH", "READ_AFTER_WRITE_MISMATCH"}
        labels = {"S1 grab", "S1 distinct grab", "S3 detail", "S3 publish", "S3 read after write",
                  "S4 distinct settle", "S4 same-task settle"}
        for row in reader:
            if len(rows) >= 100_000 or None in row or row["label"] not in labels \
                    or row["success"] not in {"true", "false"} \
                    or row["peergrabOutcome"] not in outcomes:
                raise RuntimeError("JMeter JTL contains an invalid sample")
            try:
                if int(row["timeStamp"]) < 1_000_000_000_000 or int(row["elapsed"]) < 0:
                    raise ValueError
            except ValueError as exc:
                raise RuntimeError("JMeter JTL contains an invalid timestamp/latency") from exc
            # Map only a bounded, script-generated outcome; never retain a raw
            # HTTP response message or exception text in result summaries.
            row["responseMessage"] = row.pop("peergrabOutcome")
            rows.append(row)
    if not rows:
        raise RuntimeError("JMeter JTL has no samples")
    return rows


def percentiles(rows):
    durations = sorted(int(r["elapsed"]) for r in rows)
    def at(p):
        return durations[max(0, (len(durations) * p + 99) // 100 - 1)] if durations else None
    return {"p50Ms": at(50), "p95Ms": at(95), "p99Ms": at(99)}


def batch_duration_ms(rows):
    """First HTTP sample start to final HTTP sample completion in a finite batch."""
    return max(1, max(int(r["timeStamp"]) + int(r["elapsed"]) for r in rows)
               - min(int(r["timeStamp"]) for r in rows))


def batch_start_span_ms(rows):
    """Client sampler start-time spread; it is not server arrival skew."""
    starts = [int(r["timeStamp"]) for r in rows]
    return max(starts) - min(starts)


def by_label(rows, name, expected):
    samples = [r for r in rows if r["label"] == name]
    if len(samples) != expected or any(r["success"].lower() != "true" for r in samples):
        raise RuntimeError(f"{name} expected {expected} successful classified samples; got {len(samples)}")
    return samples


def verify(mode, rows, manifest, mysql_id, base, output):
    run_id = manifest["runId"]
    expected_total = {
        "s1": manifest.get("users"),
        "s1-distinct": manifest.get("tasks"),
        "s3-read": manifest.get("requests"),
        "s3-mixed": manifest.get("writes", 0) + manifest.get("reads", 0),
        "s4": manifest.get("tasks", 0) + manifest.get("sameTaskAttempts", 0),
    }.get(mode)
    if expected_total is None or len(rows) != expected_total:
        raise RuntimeError("JMeter total sample count differs from the fixture")
    status_codes = Counter(row["responseCode"] for row in rows
                           if re.fullmatch(r"[1-5][0-9]{2}", row["responseCode"]))
    result = {"runId": run_id, "scenario": mode, "samples": len(rows),
              "httpStatusCodes": dict(sorted(status_codes.items())),
              "nonHttpSamples": len(rows) - sum(status_codes.values())}
    if mode == "s1":
        samples = by_label(rows, "S1 grab", manifest["users"])
        outcomes = {name: sum(r["responseMessage"] == name for r in samples)
                    for name in ("GRABBED", "SLOT_FULL", "GRAB_CONFLICT", "GRAB_RATE_LIMITED")}
        if outcomes["GRABBED"] != 1 or sum(outcomes.values()) != len(samples):
            raise RuntimeError("S1 application outcomes violate one-slot invariant")
        id_ = manifest["errandId"]
        db = mysql(mysql_id, f"SELECT slot_total,slot_taken FROM errand WHERE id={id_};")
        grabbed = scalar(mysql_id,
                         f"SELECT COUNT(*) FROM grab_record WHERE errand_id={id_} AND result='GRABBED';")
        if db != "1\t1" or grabbed != 1:
            raise RuntimeError("S1 durable DB slot/grab invariant failed")
        result.update(outcomes)
        result.update(percentiles(samples))
        result.update({"samplerStartSpanMs": batch_start_span_ms(samples),
                       "batchCompletionMs": batch_duration_ms(samples),
                       "positiveConnectTimeSamples": sum(int(r.get("Connect") or 0) > 0
                                                         for r in samples)})
    elif mode == "s1-distinct":
        tasks = manifest["tasks"]
        samples = by_label(rows, "S1 distinct grab", tasks)
        if any(r["responseMessage"] != "GRABBED" for r in samples):
            raise RuntimeError("S1 distinct business result mismatch")
        locked = scalar(mysql_id, "SELECT COUNT(*) FROM bench_run_item i JOIN errand e "
                        "ON e.id=i.entity_id WHERE i.run_id='" + run_id + "' "
                        "AND e.status='LOCKED' AND e.slot_total=1 AND e.slot_taken=1;")
        grabbed = scalar(mysql_id, "SELECT COUNT(*) FROM bench_run_item i JOIN grab_record g "
                         "ON g.errand_id=i.entity_id WHERE i.run_id='" + run_id + "' "
                         "AND g.result='GRABBED';")
        if locked != tasks or grabbed != tasks:
            raise RuntimeError("S1 distinct durable grab/slot invariant failed")
        duration = batch_duration_ms(samples)
        result.update({"durableGrabbed": grabbed, "lockedTasks": locked,
                       "batchCompletionMs": duration,
                       "durableGrabTps": round(grabbed * 1000 / duration, 2),
                       "samplerStartSpanMs": batch_start_span_ms(samples)})
        result.update(percentiles(samples))
    elif mode == "s3-read":
        expected = manifest["requests"]
        samples = by_label(rows, "S3 detail", expected)
        if any(r["responseMessage"] != "DETAIL_OK" for r in samples):
            raise RuntimeError("S3 detail business JSON mismatch")
        result.update(percentiles(samples))
    elif mode == "s3-mixed":
        writes = manifest["writes"]
        pubs = by_label(rows, "S3 publish", writes)
        reads = by_label(rows, "S3 read after write", manifest["reads"])
        if any(r["responseMessage"] != "PUBLISHED" for r in pubs) or any(
                r["responseMessage"] != "READ_AFTER_WRITE_OK" for r in reads):
            raise RuntimeError("S3 mixed business JSON mismatch")
        count = scalar(mysql_id,
                       "SELECT COUNT(*) FROM errand WHERE INSTR(title, 'jmeter_" + run_id + "_')=1;")
        if count != writes:
            raise RuntimeError("S3 mixed durable published task count mismatch")
        mysql(mysql_id, "INSERT INTO bench_run_item(run_id,entity_type,entity_id) "
              "SELECT '" + run_id + "','ERRAND',id FROM errand WHERE INSTR(title,'jmeter_"
              + run_id + "_')=1;")
        result.update({"writes": writes, "reads": len(reads)})
        result.update(percentiles(rows))
    elif mode == "s4":
        distinct = by_label(rows, "S4 distinct settle", manifest["tasks"])
        same = by_label(rows, "S4 same-task settle", manifest["sameTaskAttempts"])
        if any(r["responseMessage"] != "SETTLED" for r in distinct):
            raise RuntimeError("S4 distinct settlement business mismatch")
        if sum(r["responseMessage"] == "SETTLED" for r in same) != 1 or sum(
                r["responseMessage"] == "DUPLICATE" for r in same) != len(same) - 1:
            raise RuntimeError("S4 same-task idempotency response mismatch")
        durable = scalar(mysql_id, "SELECT COUNT(*) FROM bench_run_item i JOIN errand e "
                         "ON e.id=i.entity_id WHERE i.run_id='" + run_id + "' AND e.status='SETTLED';")
        released = scalar(mysql_id, "SELECT COUNT(*) FROM bench_run_item i JOIN escrow_order e "
                          "ON e.errand_id=i.entity_id WHERE i.run_id='" + run_id + "' AND e.status='RELEASED';")
        total = scalar(mysql_id, "SELECT COALESCE(SUM(available+frozen),0) FROM wallet_account;")
        debits = scalar(mysql_id, "SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0) "
                         "FROM wallet_ledger;")
        if durable != manifest["tasks"] + 1 or released != durable or total != manifest["walletTotalBefore"] or debits != 0:
            raise RuntimeError("S4 durable settlement/funds invariant failed")
        fund_sql = (BACKEND / "bench" / "scripts" / "verify_fund.sql").read_text(encoding="utf-8")
        fund_output = mysql(mysql_id, fund_sql)
        (output / "verify_fund.tsv").write_text(fund_output + "\n", encoding="utf-8")
        if "FAIL" in fund_output or fund_output.count("PASS") < 5:
            raise RuntimeError("S4 verify_fund.sql failed")
        result.update({"durableSettled": durable, "escrowReleased": released,
                       "runnerWallets": manifest.get("runnerWallets", "shared"),
                       "walletTotalDelta": total - manifest["walletTotalBefore"],
                       "globalDebitCreditDiff": debits,
                       "distinctBatchSeconds": round(batch_duration_ms(distinct) / 1000, 3),
                       "distinctDurableTps": round(manifest["tasks"] * 1000
                                                  / batch_duration_ms(distinct), 2),
                       "distinct": percentiles(distinct), "sameTask": percentiles(same)})
    if mode.startswith("s3"):
        admin = read_token(output)
        stats = api(base, "/api/internal/cache-stats", admin)
        detail_reads = manifest["requests"] if mode == "s3-read" else manifest["reads"]
        safe_stats = {name: json_count(stats.get(name), name)
                      for name in ("requests", "dbLoads", "cacheHits")}
        if safe_stats["requests"] != detail_reads:
            raise RuntimeError("S3 detail counter differs from JMeter read sample count")
        result["cacheStats"] = safe_stats
        if mode == "s3-mixed":
            sampled = api(base, "/api/internal/cache-check", admin, "POST")
            diffs = json_count(sampled.get("diffs"), "diffs")
            result["sampledCacheDiffs"] = diffs
            if diffs != 0:
                raise RuntimeError("S3 sampled cache check found differences")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=("s1", "s1-distinct", "s3-read", "s3-mixed", "s4"))
    parser.add_argument("--base-url", default=os.getenv("PEERGRAB_BENCH_BASE_URL"))
    parser.add_argument("--jmeter", default=os.getenv("JMETER_BIN", "jmeter"))
    parser.add_argument("--output", required=True, type=Path, help="New directory under bench/runs")
    parser.add_argument("--users", type=int, default=16)
    parser.add_argument("--threads", type=int, default=50)
    parser.add_argument("--iterations", type=int, default=100)
    parser.add_argument("--distribution", choices=("uniform", "hot90"), default="uniform")
    parser.add_argument("--tasks", type=int, default=200)
    parser.add_argument("--same-attempts", type=int, default=8)
    parser.add_argument("--runner-wallets", choices=("shared", "distributed"), default="shared",
                        help="S4 runner wallet topology; system escrow/commission wallets remain shared")
    parser.add_argument("--cache-enabled", choices=("true", "false"),
                        help="Required S3 read app setting; validates container env")
    parser.add_argument("--prewarm", action="store_true",
                        help="For S3 read, batch-prewarm fixture IDs before JMeter")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.parent != RUNS.resolve() or output.exists():
        parser.error("--output must be a new immediate child of bench/runs")
    if not args.base_url:
        parser.error("Set --base-url or PEERGRAB_BENCH_BASE_URL")
    project = os.getenv("PEERGRAB_BENCH_PROJECT")
    db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
    db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
    try:
        preflight.require_maintenance_window()
        verified = check_stack(project, args.base_url, db_host, db_port)
        if args.scenario in ("s3-read", "s3-mixed"):
            if args.scenario == "s3-read" and args.cache_enabled is None:
                raise RuntimeError("S3 read requires --cache-enabled true/false")
            if args.scenario == "s3-mixed" and args.cache_enabled not in (None, "true"):
                raise RuntimeError("S3 mixed requires the detail cache enabled")
            required_cache = args.cache_enabled if args.scenario == "s3-read" else "true"
            app_id = docker_text("ps", "-q", "--filter",
                                 f"label=com.docker.compose.project={project}",
                                 "--filter", "label=com.docker.compose.service=app")
            if len(app_id.splitlines()) != 1:
                raise RuntimeError("S3 expected exactly one benchmark app container")
            app = json.loads(docker_text("container", "inspect", app_id))[0]
            env = dict(item.split("=", 1) for item in app["Config"]["Env"] if "=" in item)
            if env.get("PEERGRAB_CACHE_ENABLED", "").lower() != required_cache:
                raise RuntimeError("S3 cache mode differs from the inspected app container")
        fixture_args = {
            "s1": [str(args.users)],
            "s1-distinct": [str(args.tasks), str(args.threads)],
            "s3-read": [str(args.threads), str(args.iterations), args.distribution],
            "s3-mixed": [str(args.threads), str(args.iterations)],
            "s4": [str(args.tasks), str(args.threads), str(args.same_attempts),
                   args.runner_wallets],
        }[args.scenario]
        subprocess.run(["mvn", "-q", "-ntp", "-pl", "peergrab-bench", "-am", "install", "-DskipTests"],
                       cwd=BACKEND, check=True, timeout=300)
        subprocess.run(["mvn", "-q", "-ntp", "-pl", "peergrab-bench", "exec:java",
                        "-Dexec.mainClass=com.peergrab.bench.JMeterFixtureExporter",
                        "-Dexec.args=" + " ".join([args.scenario, args.base_url, str(output), *fixture_args])],
                       cwd=BACKEND, check=True, timeout=600)
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        if args.scenario == "s4" and manifest.get("runnerWallets") != args.runner_wallets:
            raise RuntimeError("S4 fixture runner wallet mode differs from requested mode")
        prewarm_result = None
        if args.prewarm:
            if args.scenario != "s3-read" or args.cache_enabled != "true":
                raise RuntimeError("--prewarm requires s3-read with --cache-enabled=true")
            prewarm_result = prewarm_fixture(args.base_url, output)
        parsed = urlsplit(args.base_url)
        plan = {"s1": "s1.jmx", "s1-distinct": "s1_distinct.jmx", "s3-read": "s3_read.jmx",
                "s3-mixed": "s3_mixed.jmx", "s4": "s4.jmx"}[args.scenario]
        cmd = [args.jmeter, "-n", "-t", str(HERE / plan), "-l", str(output / "results.jtl"),
               "-j", str(output / "jmeter.log"), "-q", str(HERE / "results.properties"),
               "-Jsample_variables=peergrabOutcome",
               "-Jhost=" + parsed.hostname, "-Jport=" + str(parsed.port), "-Jprotocol=http"]
        if args.scenario == "s1":
            cmd += ["-Jusers=" + str(args.users), "-Jerrand_id=" + str(manifest["errandId"]),
                    "-Js1_csv=" + str(output / "s1.csv")]
        elif args.scenario == "s1-distinct":
            cmd += ["-Jthreads=" + str(args.threads),
                    "-Js1_distinct_csv=" + str(output / "s1_distinct.csv")]
        elif args.scenario == "s3-read":
            cmd += ["-Jthreads=" + str(args.threads), "-Jiterations=" + str(args.iterations),
                    "-Js3_read_csv=" + str(output / "s3_read.csv")]
        elif args.scenario == "s3-mixed":
            cmd += ["-Jthreads=" + str(args.threads), "-Jiterations=" + str(args.iterations),
                    "-Jrun_id=" + manifest["runId"],
                    "-Js3_mixed_csv=" + str(output / "s3_mixed.csv")]
        else:
            cmd += ["-Jthreads=" + str(args.threads), "-Jsame_attempts=" + str(args.same_attempts),
                    "-Js4_distinct_csv=" + str(output / "s4_distinct.csv"),
                    "-Js4_same_csv=" + str(output / "s4_same.csv")]
        run_jmeter(cmd, output, args.base_url, project, db_host, db_port, args.jmeter)
        rows = parse_jtl(output)
        summary = verify(args.scenario, rows, manifest, verified["mysql_container_id"], args.base_url, output)
        if prewarm_result is not None:
            summary["prewarm"] = {k: prewarm_result[k] for k in
                                  ("requested", "distinct", "found", "written", "missing", "elapsedMs")}
        mysql(verified["mysql_container_id"], "UPDATE bench_run SET status='PASS', finished_at=NOW(3), "
              "summary=CAST('" + json.dumps(summary, ensure_ascii=True, separators=(",", ":"))
              + "' AS JSON) WHERE run_id='" + manifest["runId"] + "';")
        (output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n",
                                              encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
        return 0
    except Exception as exc:
        if output.exists() and (output / "manifest.json").exists():
            try:
                manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
                mysql(verified["mysql_container_id"], "UPDATE bench_run SET status='FAIL', "
                      "finished_at=NOW(3), summary=JSON_OBJECT('reason','jmeter_or_postcheck_failed') "
                      "WHERE run_id='" + manifest["runId"] + "';")
            except Exception:
                pass
        print("JMeter business benchmark failed/refused: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
