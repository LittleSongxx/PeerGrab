#!/usr/bin/env python3
"""Guarded, one-shot S2/S3 fixture creation in a disposable PeerGrab stack."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.request import Request, urlopen

import preflight

SCRIPTS = Path(__file__).resolve().parent
SCENARIOS = {
    "s2": ("seed_s2.sql", 930000000000, 100000),
    "s3": ("seed_s3.sql", 920000000000, 1000),
}


def mysql(container_id, sql):
    result = subprocess.run(
        ["docker", "exec", "-i", container_id, "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B peer_grab'],
        input=sql, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"Benchmark MySQL command failed: {result.stderr.strip()}")
    return result.stdout.strip()


def scalar(container_id, sql):
    value = mysql(container_id, sql).splitlines()
    if len(value) != 1 or not value[0].isdecimal():
        raise RuntimeError("Unexpected benchmark MySQL scalar response")
    return int(value[0])


def post_json(base_url, path, payload, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(base_url.rstrip("/") + path, data=json.dumps(payload).encode(),
                      headers=headers, method="POST")
    with urlopen(request, timeout=30) as response:
        data = json.load(response)
        if response.status != 200 or data.get("code") != "OK":
            raise RuntimeError(f"Benchmark API operation {path} failed")
        return data.get("data")


def arbitrator_token(base_url):
    password = os.getenv("PEERGRAB_AUTH_DEMO_PASSWORD_9001")
    if not password:
        raise RuntimeError("Set PEERGRAB_AUTH_DEMO_PASSWORD_9001 for S3 Bloom rebuild")
    result = post_json(base_url, "/api/auth/login", {"userId": 9001, "password": password})
    token = result.get("token") if isinstance(result, dict) else None
    if not token:
        raise RuntimeError("Benchmark arbitrator login returned no token")
    return token


def seed(scenario, count, base_url):
    if scenario not in SCENARIOS or (scenario == "s2" and not 1 <= count <= 100000):
        raise ValueError("Unsupported seed scenario or S2 count")
    project = os.getenv("PEERGRAB_BENCH_PROJECT")
    db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
    db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
    if not all((project, db_host, db_port, base_url)):
        raise preflight.Refused("Set project, API URL, DB host and DB port explicitly")
    verified = preflight.check(project, base_url, db_host, db_port)
    container_id = verified["mysql_container_id"]
    guard = mysql(container_id, "SELECT project_name FROM bench_guard WHERE guard_key='project';")
    if guard != project:
        raise preflight.Refused("Benchmark DB marker changed since preflight")

    filename, base, range_size = SCENARIOS[scenario]
    if scalar(container_id, "SELECT COUNT(*) FROM bench_run;") != 0:
        raise preflight.Refused("Seed requires a fresh stack with no benchmark runs")
    if scalar(container_id, "SELECT COUNT(*) FROM errand;") != 0:
        raise preflight.Refused("Seed requires a fresh stack with an empty task table")
    existing = scalar(container_id, f"SELECT COUNT(*) FROM errand WHERE id > {base} "
                            f"AND id <= {base + range_size};")
    if existing:
        raise preflight.Refused("Reserved seed ID range is already occupied; use a fresh stack")

    # Authenticate before mutating S3 data, then rebuild from the verified API.
    token = arbitrator_token(base_url) if scenario == "s3" else None
    sql = (f"SET @bench_seed_count = {count};\n" if scenario == "s2" else "")
    sql += (SCRIPTS / filename).read_text(encoding="utf-8")
    mysql(container_id, sql)
    expected = count if scenario == "s2" else 100
    actual = scalar(container_id, f"SELECT COUNT(*) FROM errand WHERE id > {base} "
                            f"AND id <= {base + range_size} AND campus_id=1 AND status='PUBLISHED';")
    if actual != expected:
        raise RuntimeError(f"Seed count mismatch: expected {expected}, got {actual}")
    if scenario == "s3":
        result = post_json(base_url, "/api/internal/bloom-rebuild", {}, token)
        if not isinstance(result, dict) or not isinstance(result.get("registered"), int) \
                or result["registered"] < expected:
            raise RuntimeError("Bloom rebuild did not register seeded S3 tasks")
    return {"project": project, "scenario": scenario.upper(), "seeded": actual,
            "id_from": base + 1, "id_to": base + expected,
            "bloom_rebuilt": scenario == "s3"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=SCENARIOS)
    parser.add_argument("--count", type=int, default=10000,
                        help="S2 list size, 1..100000 (default: 10000); unavailable for S3")
    parser.add_argument("--base-url", default=os.getenv("PEERGRAB_BENCH_BASE_URL"))
    args = parser.parse_args()
    if args.scenario == "s2" and not 1 <= args.count <= 100000:
        parser.error("--count must be 1..100000")
    if args.scenario == "s3" and args.count != 10000:
        parser.error("--count applies only to S2")
    try:
        print(json.dumps(seed(args.scenario, args.count, args.base_url), sort_keys=True))
    except Exception as exc:
        print(f"Benchmark seed refused/failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
