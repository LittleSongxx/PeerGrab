#!/usr/bin/env python3
"""Guarded S1/S4 follow-up on the existing ECS S2 disposable stack.

Run only after the S2 capacity sweep has finished. The script never changes
Compose configuration or deletes data. It stops at the first failed gate and
keeps its logs and manifest in bench/runs/ for later inspection.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

import preflight


BACKEND = Path(__file__).resolve().parents[2]
SCRIPTS = Path(__file__).resolve().parent
RUNS = BACKEND / "bench" / "runs"
DEFAULT_PROJECT = "peergrab-bench-max0926"
BENCH_PROJECT = re.compile(r"peergrab-bench-[a-z0-9][a-z0-9_-]*\Z")
SEED_FIRST = 930000000001
SEED_LAST = 930000010000
RUN_ID = re.compile(r"\brunId=(\d{8}-\d{6}(?:-\d+)?)\b")
STAGES = (
    # S4 goes first because each S1 round leaves runner 2001 holding an
    # ACCEPTED task. The shared demo runner has a max-ongoing business limit.
    ("s4-20-4", "com.peergrab.bench.FundsHttpLoadClient", (20, 4, 8, 30000), 1200),
    ("s4-50-8", "com.peergrab.bench.FundsHttpLoadClient", (50, 8, 8, 30000), 1200),
    ("s4-100-16", "com.peergrab.bench.FundsHttpLoadClient", (100, 16, 8, 30000), 1200),
    ("s1-16", "com.peergrab.bench.SpikeLoadClient", (16, 1), 600),
    ("s1-64", "com.peergrab.bench.SpikeLoadClient", (64, 1), 600),
    ("s1-128", "com.peergrab.bench.SpikeLoadClient", (128, 1), 600),
)


class Stopped(RuntimeError):
    """A benchmark guard or correctness invariant failed."""


def command(*args, input_text=None):
    result = subprocess.run(args, input=input_text, text=True, capture_output=True,
                            encoding="utf-8", errors="replace", timeout=60)
    if result.returncode:
        raise Stopped(f"{args[0]} command failed: {result.stderr.strip()[:400]}")
    return result.stdout.strip()


def mysql(container_id, sql):
    # The password is read inside the verified disposable DB container. It is
    # never present in command arguments, logs, or the result manifest.
    return command("docker", "exec", "-i", container_id, "sh", "-c",
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot '
                   '--default-character-set=utf8mb4 -N -B peer_grab',
                   input_text=sql)


def production_stopped():
    if os.getenv("PEERGRAB_MAINTENANCE_APPROVED") != "YES":
        raise Stopped("Set PEERGRAB_MAINTENANCE_APPROVED=YES for the approved outage")
    ids = command("docker", "ps", "-q", "--filter",
                  "label=com.docker.compose.project=peergrab-prod")
    if ids:
        raise Stopped("Production containers are running; stop S1/S4 load")


def available_memory_bytes():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) * 1024
    raise Stopped("Cannot read MemAvailable")


def resource_gate():
    if available_memory_bytes() < 3 * 1024**3:
        raise Stopped("Available host memory is below 3 GiB")
    docker_root = command("docker", "info", "--format", "{{.DockerRootDir}}")
    if not docker_root or shutil.disk_usage(docker_root).free < 5 * 1024**3:
        raise Stopped("Docker data disk has less than 5 GiB free")


def selected_project():
    project = os.environ.get("PEERGRAB_BENCH_PROJECT", DEFAULT_PROJECT)
    if not BENCH_PROJECT.fullmatch(project):
        raise Stopped("PEERGRAB_BENCH_PROJECT must be a peergrab-bench-* project name")
    return project


def identity(project):
    ids = command("docker", "ps", "-aq", "--no-trunc", "--filter",
                  f"label=com.docker.compose.project={project}").splitlines()
    if not ids or not all(re.fullmatch(r"[a-f0-9]{64}", value) for value in ids):
        raise Stopped("Benchmark container IDs are absent or abbreviated")
    return sorted(ids)


def checked_stack(base_url, initial_ids=None):
    production_stopped()
    resource_gate()
    project = selected_project()
    host = os.getenv("PEERGRAB_TEST_DB_HOST")
    port = os.getenv("PEERGRAB_TEST_DB_PORT")
    if os.getenv("COMPOSE_PROJECT_NAME") != project:
        raise Stopped("COMPOSE_PROJECT_NAME must match PEERGRAB_BENCH_PROJECT")
    if not all((os.getenv("PEERGRAB_TEST_DB_PASSWORD"),
                os.getenv("PEERGRAB_AUTH_JWT_SECRET"))):
        raise Stopped("Benchmark database password or JWT secret is missing")
    stack = preflight.check(project, base_url, host, port)
    ids = identity(project)
    if initial_ids is not None and ids != initial_ids:
        raise Stopped("Benchmark container set changed during the run")
    return stack, ids


def snapshot(container_id):
    rows = mysql(container_id, f"SELECT * FROM errand WHERE id BETWEEN {SEED_FIRST} "
                 f"AND {SEED_LAST} ORDER BY id;")
    count = len(rows.splitlines())
    fields = mysql(container_id, f"""
        SELECT
          (SELECT COUNT(*) FROM errand WHERE id BETWEEN {SEED_FIRST} AND {SEED_LAST}
             AND status='PUBLISHED'),
          (SELECT COALESCE(SUM(available+frozen),0) FROM wallet_account),
          (SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0)
             FROM wallet_ledger),
          (SELECT available FROM wallet_account WHERE owner_type='USER' AND owner_id=1001),
          (SELECT COUNT(*) FROM grab_record WHERE errand_id BETWEEN {SEED_FIRST} AND {SEED_LAST}),
          (SELECT COUNT(*) FROM escrow_order WHERE errand_id BETWEEN {SEED_FIRST} AND {SEED_LAST}),
          (SELECT COUNT(*) FROM errand),
          (SELECT COUNT(*) FROM grab_record),
          (SELECT COUNT(*) FROM escrow_order),
          (SELECT COUNT(*) FROM wallet_account),
          (SELECT COUNT(*) FROM wallet_ledger),
          (SELECT COUNT(*) FROM bench_run);
    """).split("\t")
    if len(fields) != 12 or any(not value.isdecimal() for value in fields):
        raise Stopped("Unexpected S2 fixture/fund snapshot")
    return {"s2Rows": count, "s2Published": int(fields[0]),
            "s2Sha256": hashlib.sha256(rows.encode()).hexdigest(),
            "walletTotal": int(fields[1]), "ledgerDifference": int(fields[2]),
            "publisherAvailable": int(fields[3]), "s2GrabRows": int(fields[4]),
            "s2EscrowRows": int(fields[5]), "errandRows": int(fields[6]),
            "grabRows": int(fields[7]), "escrowRows": int(fields[8]),
            "walletAccountRows": int(fields[9]), "ledgerRows": int(fields[10]),
            "benchRunRows": int(fields[11])}


def fixture_gate(baseline, current):
    if baseline["s2Rows"] != 10000 or baseline["s2Published"] != 10000:
        raise Stopped("The S2 fixture is not exactly 10,000 published tasks")
    for key in ("s2Rows", "s2Published", "s2Sha256", "s2GrabRows", "s2EscrowRows"):
        if current[key] != baseline[key]:
            raise Stopped(f"S2 fixture changed: {key}")
    if current["walletTotal"] != baseline["walletTotal"]:
        raise Stopped("Global wallet total changed")
    if current["ledgerDifference"] != 0:
        raise Stopped("Wallet ledger debit/credit difference is nonzero")


def stage_counts_gate(before, after, stage):
    count = 31 if stage.startswith("s1-") else int(stage.split("-")[1]) + 3
    if after["errandRows"] - before["errandRows"] != count:
        raise Stopped(f"{stage} created an unexpected number of task rows")
    if after["escrowRows"] - before["escrowRows"] != count:
        raise Stopped(f"{stage} created an unexpected number of escrow rows")
    if after["benchRunRows"] - before["benchRunRows"] != 1:
        raise Stopped(f"{stage} created an unexpected number of bench_run rows")
    minimum_grabs = 31 if stage.startswith("s1-") else count - 1
    if after["grabRows"] - before["grabRows"] < minimum_grabs:
        raise Stopped(f"{stage} persisted too few grab records")


def run_id_from_log(contents):
    values = set(RUN_ID.findall(contents))
    if len(values) != 1:
        raise Stopped(f"Expected one runId in Maven log, found {len(values)}")
    return values.pop()


def verify_sql(container_id, filename, output, run_id=None):
    sql = (SCRIPTS / filename).read_text(encoding="utf-8")
    if run_id is not None:
        if not re.fullmatch(r"\d{8}-\d{6}(?:-\d+)?", run_id):
            raise Stopped("Invalid run ID")
        sql = sql.replace("__RUN_ID__", run_id)
    result = mysql(container_id, sql)
    output.write_text(result + "\n", encoding="utf-8")
    if re.search(r"(?:^|\t)FAIL(?:-|\t|$)", result, re.MULTILINE):
        raise Stopped(f"{filename} reported a failed invariant")


def run_invariants(container_id, run_id, stage):
    # verify_run.sql intentionally prints duplicate-grab rows as an empty-set
    # check. Assert them numerically as well so a nonempty result cannot pass.
    values = mysql(container_id, f"""
        SELECT
          (SELECT COUNT(*) FROM bench_run_item i JOIN errand e ON e.id=i.entity_id
             WHERE i.run_id='{run_id}' AND i.entity_type='ERRAND'
               AND e.slot_taken>e.slot_total),
          (SELECT COUNT(*) FROM (
             SELECT g.errand_id,g.runner_id FROM bench_run_item i
               JOIN grab_record g ON g.errand_id=i.entity_id AND g.result='GRABBED'
               WHERE i.run_id='{run_id}' AND i.entity_type='ERRAND'
               GROUP BY g.errand_id,g.runner_id HAVING COUNT(*)>1) duplicate_grabs),
          (SELECT COUNT(*) FROM bench_run_item i JOIN errand e ON e.id=i.entity_id
             WHERE i.run_id='{run_id}' AND i.entity_type='ERRAND'
               AND e.slot_taken>0 AND e.grabber_id IS NULL),
          (SELECT COALESCE(SUM(CASE WHEN l.direction='DEBIT' THEN l.amount ELSE -l.amount END),0)
             FROM bench_run_item i JOIN wallet_ledger l ON l.ref_id=i.entity_id
             WHERE i.run_id='{run_id}' AND i.entity_type='ERRAND'),
          (SELECT COUNT(*) FROM bench_run_item
             WHERE run_id='{run_id}' AND entity_type='ERRAND'),
          (SELECT COUNT(*) FROM bench_run_item i JOIN errand e ON e.id=i.entity_id
             WHERE i.run_id='{run_id}' AND i.entity_type='ERRAND'
               AND e.slot_total=1 AND e.slot_taken=1);
    """).split("\t")
    if len(values) != 6 or any(not value.isdecimal() for value in values):
        raise Stopped("Unexpected per-run invariant query result")
    metrics = dict(zip(("oversold", "duplicateGrabs", "badGrabberState",
                        "ledgerDifference", "trackedErrands", "filledSingleSlot"),
                       map(int, values)))
    expected = 31 if stage.startswith("s1-") else int(stage.split("-")[1]) + 3
    if any(metrics[key] for key in ("oversold", "duplicateGrabs", "badGrabberState",
                                    "ledgerDifference")) or metrics["trackedErrands"] != expected:
        raise Stopped(f"{stage} failed durable per-run invariants")
    if stage.startswith("s1-") and metrics["filledSingleSlot"] != 31:
        raise Stopped(f"{stage} did not persist all 31 single-slot grabs")
    return metrics


def run_record(container_id, run_id, stage):
    sql = ("SELECT scenario,status,summary FROM bench_run "
           f"WHERE run_id='{run_id}';")
    rows = mysql(container_id, sql).splitlines()
    if len(rows) != 1:
        raise Stopped("Missing or duplicated bench_run row")
    fields = rows[0].split("\t", 2)
    if len(fields) != 3 or fields[1] != "PASS":
        raise Stopped("Benchmark run did not finish PASS")
    summary = json.loads(fields[2])
    if stage.startswith("s1-"):
        if fields[0] != "S1" or any(summary.get(k) != v for k, v in
                                      {"success": 1, "slotTotal": 1, "errors": 0,
                                       "oversold": 0}.items()):
            raise Stopped("S1 durable summary failed the correctness gate")
    else:
        count = int(stage.split("-")[1])
        if fields[0] != "S4-HTTP" or summary.get("dbSettled") != count + 1 \
                or summary.get("refunded") != 2 \
                or any(summary.get(k) != 0 for k in
                       ("badLedger", "globalDebitCredit", "systemSnapshotDiffs", "totalDelta")) \
                or summary.get("distinct", {}).get("durableSettled") != count:
            raise Stopped("S4 durable summary failed the correctness gate")
    return summary


def stop_process(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)


def run_stage(stage, base_url, initial_ids, baseline, output):
    name, main_class, args, timeout_seconds = stage
    stack, _ = checked_stack(base_url, initial_ids)
    before = snapshot(stack["mysql_container_id"])
    fixture_gate(baseline, before)
    minimum = 3100 if name.startswith("s1-") else (args[0] + 3) * 100
    if before["publisherAvailable"] < minimum:
        raise Stopped(f"Publisher funds too low for {name}")

    log_path = output / f"{name}.log"
    command_line = ["mvn", "-q", "-pl", "peergrab-bench", "exec:java",
                    f"-Dexec.mainClass={main_class}",
                    f"-Dexec.args={base_url} {' '.join(map(str, args))}"]
    print(f"Running {name}; log={log_path}", flush=True)
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command_line, cwd=BACKEND, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        started = time.monotonic()
        try:
            while process.poll() is None:
                if time.monotonic() - started > timeout_seconds:
                    raise Stopped(f"{name} exceeded {timeout_seconds} seconds")
                checked_stack(base_url, initial_ids)
                time.sleep(5)
        except BaseException:
            stop_process(process)
            raise
        if process.returncode:
            raise Stopped(f"{name} Maven client failed; see {log_path}")

    run_id = run_id_from_log(log_path.read_text(encoding="utf-8", errors="replace"))
    (output / f"{name}.run-id.txt").write_text(run_id + "\n", encoding="utf-8")
    stack, _ = checked_stack(base_url, initial_ids)
    mysql_id = stack["mysql_container_id"]
    verify_sql(mysql_id, "verify_run.sql", output / f"{name}.verify-run.txt", run_id)
    verify_sql(mysql_id, "verify_fund.sql", output / f"{name}.verify-fund.txt")
    invariants = run_invariants(mysql_id, run_id, name)
    summary = run_record(mysql_id, run_id, name)
    after = snapshot(mysql_id)
    fixture_gate(baseline, after)
    stage_counts_gate(before, after, name)
    return {"stage": name, "runId": run_id, "summary": summary,
            "invariants": invariants,
            "snapshotBefore": before, "snapshotAfter": after,
            "clientLog": log_path.name}


def write_manifest(path, manifest):
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                    encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only", choices=("all", "s1", "s4"), default="all")
    args = parser.parse_args()
    os.umask(0o077)
    base_url = os.getenv("PEERGRAB_BENCH_BASE_URL", "")
    try:
        project = selected_project()
        stack, initial_ids = checked_stack(base_url)
        baseline = snapshot(stack["mysql_container_id"])
        fixture_gate(baseline, baseline)
        if args.dry_run:
            print(json.dumps({"project": project, "baseUrl": base_url,
                              "s2Rows": baseline["s2Rows"],
                              "s2Sha256": baseline["s2Sha256"],
                              "walletTotal": baseline["walletTotal"],
                              "publisherAvailable": baseline["publisherAvailable"],
                              "stages": [s[0] for s in STAGES if args.only == "all"
                                         or s[0].startswith(args.only)]}, sort_keys=True))
            return 0
        output = RUNS / ("s1-s4-maint-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
        output.mkdir(parents=True, mode=0o700)
        manifest = {"project": project, "baseUrl": base_url,
                    "startedAtUtc": datetime.now(timezone.utc).isoformat(),
                    "status": "RUNNING", "baseline": baseline, "stages": []}
        write_manifest(output / "manifest.json", manifest)
        try:
            for stage in STAGES:
                if args.only != "all" and not stage[0].startswith(args.only):
                    continue
                manifest["stages"].append(run_stage(stage, base_url, initial_ids,
                                                   baseline, output))
                write_manifest(output / "manifest.json", manifest)
            manifest["status"] = "PASS"
        except Exception as exc:
            manifest["status"] = "STOPPED"
            manifest["reason"] = str(exc)
            raise
        finally:
            manifest["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
            write_manifest(output / "manifest.json", manifest)
            print(f"S1/S4 evidence: {output}", flush=True)
    except Exception as exc:
        print(f"S1/S4 stopped: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
