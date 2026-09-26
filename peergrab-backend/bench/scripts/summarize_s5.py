#!/usr/bin/env python3
"""Summarize durable S5 completions per second in one verified disposable stack."""

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import preflight


RUN_ID = re.compile(r"^[0-9]{8}-[0-9]{6}(?:-[0-9]+)?$")


def mysql(container_id: str, sql: str) -> str:
    result = subprocess.run(
        ["docker", "exec", "-i", container_id, "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B peer_grab'],
        input=sql, capture_output=True, text=True, timeout=30,
    )
    if result.returncode:
        raise RuntimeError("Verified benchmark MySQL query failed: " + result.stderr.strip())
    return result.stdout.strip()


def summarize(run_id: str, scenario: str, status: str, summary: dict,
              event_times: list[int | None]) -> dict:
    due_ms = int(summary["dueEpochMs"])
    completed = [event - due_ms for event in event_times if event is not None]
    buckets = Counter(delta // 1000 for delta in completed)
    last_ms = max(completed) if completed else None
    elapsed_seconds = max(1.0, last_ms / 1000.0) if last_ms is not None else None
    return {
        "runId": run_id, "scenario": scenario, "status": status,
        "expected": int(summary["expected"]), "itemRows": len(event_times),
        "completed": len(completed), "missing": len(event_times) - len(completed),
        "firstCompletionFromDueMs": min(completed) if completed else None,
        "lastCompletionFromDueMs": last_ms,
        "meanCompletedPerSecondFromDue":
            round(len(completed) / elapsed_seconds, 3) if elapsed_seconds is not None else 0,
        "peakCompletionsInOneSecond": max(buckets.values(), default=0),
        "completedPerSecondFromDue": [
            {"second": second, "count": count} for second, count in sorted(buckets.items())
        ],
        "p50Ms": summary.get("p50Ms"), "p95Ms": summary.get("p95Ms"),
        "p99Ms": summary.get("p99Ms"), "maxMs": summary.get("maxMs"),
        "unprocessed": summary.get("unprocessed"), "duplicate": summary.get("duplicate"),
        "premature": summary.get("premature"), "wrongState": summary.get("wrongState"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_id", help="S5 bench_run ID")
    parser.add_argument("--output", type=Path, help="new JSON file in bench/runs")
    args = parser.parse_args()
    if not RUN_ID.fullmatch(args.run_id):
        parser.error("invalid run ID")
    project = os.getenv("PEERGRAB_BENCH_PROJECT")
    base_url = os.getenv("PEERGRAB_BENCH_BASE_URL")
    db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
    db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
    if not all((project, base_url, db_host, db_port)):
        raise preflight.Refused("Export the disposable benchmark environment first")
    verified = preflight.check(project, base_url, db_host, db_port)
    container_id = verified["mysql_container_id"]
    run_rows = mysql(container_id,
                     f"SELECT scenario,status,CAST(summary AS CHAR) FROM bench_run "
                     f"WHERE run_id='{args.run_id}';").splitlines()
    if len(run_rows) != 1:
        raise RuntimeError("Expected exactly one benchmark run")
    scenario, status, raw_summary = run_rows[0].split("\t", 2)
    if scenario not in {"S5-mq", "S5-fallback"}:
        raise RuntimeError("Run is not an S5 timeline probe")
    summary = json.loads(raw_summary)
    event_rows = mysql(container_id, f"""
        SELECT i.entity_id, ROUND(UNIX_TIMESTAMP(MIN(s.created_at))*1000)
          FROM bench_run_item i
          LEFT JOIN errand_status_log s ON s.errand_id=i.entity_id
               AND s.from_status='LOCKED' AND s.round=1
         WHERE i.run_id='{args.run_id}' AND i.entity_type='ERRAND'
         GROUP BY i.entity_id ORDER BY i.entity_id;
    """).splitlines()
    event_times = []
    for row in event_rows:
        _, raw_time = row.split("\t", 1)
        event_times.append(None if raw_time == "NULL" else int(raw_time))
    result = summarize(args.run_id, scenario, status, summary, event_times)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(output)
        print(args.output)
    else:
        print(output, end="")
    return 0 if status == "PASS" and result["expected"] == result["completed"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"S5 summary refused/failed: {exc}", file=sys.stderr)
        sys.exit(1)
