#!/usr/bin/env python3
"""Fail-closed cache prewarm for a disposable PeerGrab benchmark stack.

The production API exposes the same bounded operation for a maintenance window,
but this runner only accepts a loopback disposable stack identity. It deliberately
prints only aggregate counts, never JWTs or cache payloads.
"""
import argparse
import json
import os
from pathlib import Path
import sys
from urllib.request import Request, urlopen

import preflight

MAX_IDS = 1000


def post_json(base_url, path, payload, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(base_url.rstrip("/") + path, data=json.dumps(payload).encode(),
                      headers=headers, method="POST")
    with urlopen(request, timeout=30) as response:
        body = json.load(response)
        if response.status != 200 or body.get("code") != "OK":
            raise RuntimeError("Benchmark API operation failed")
        return body.get("data")


def arbitrator_token(base_url):
    password = os.getenv("PEERGRAB_AUTH_DEMO_PASSWORD_9001")
    if not password:
        raise RuntimeError("Set PEERGRAB_AUTH_DEMO_PASSWORD_9001 for cache prewarm")
    result = post_json(base_url, "/api/auth/login", {"userId": 9001, "password": password})
    token = result.get("token") if isinstance(result, dict) else None
    if not token:
        raise RuntimeError("Benchmark arbitrator login returned no token")
    return token


def load_ids(path):
    ids = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        value = line.strip()
        if not value:
            continue
        if not value.isdecimal() or int(value) <= 0:
            raise RuntimeError("ID file contains a non-positive numeric ID")
        ids.append(int(value))
    if not ids or len(ids) > MAX_IDS:
        raise RuntimeError(f"ID file must contain 1..{MAX_IDS} IDs")
    return list(dict.fromkeys(ids))


def run(base_url, ids):
    project = os.getenv("PEERGRAB_BENCH_PROJECT")
    db_host = os.getenv("PEERGRAB_TEST_DB_HOST")
    db_port = os.getenv("PEERGRAB_TEST_DB_PORT")
    if not all((project, db_host, db_port, base_url)):
        raise preflight.Refused("Set project, API URL, DB host and DB port explicitly")
    preflight.check(project, base_url, db_host, db_port)
    token = arbitrator_token(base_url)
    result = post_json(base_url, "/api/internal/cache-prewarm",
                       {"errandIds": ids}, token)
    if not isinstance(result, dict) or result.get("distinct") != len(ids):
        raise RuntimeError("Prewarm response count does not match the verified ID set")
    if result.get("written") != result.get("found") or not result.get("cacheHealthy"):
        raise RuntimeError("Prewarm did not report a healthy complete cache write")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("PEERGRAB_BENCH_BASE_URL"), required=False)
    parser.add_argument("--id-file", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.base_url, load_ids(args.id_file))
        print(json.dumps({k: result[k] for k in
                          ("requested", "distinct", "found", "written", "missing", "elapsedMs")},
                         sort_keys=True))
    except Exception as exc:
        print(f"Benchmark cache prewarm refused/failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
