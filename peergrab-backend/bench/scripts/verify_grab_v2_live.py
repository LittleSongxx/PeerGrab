#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Live acceptance for durable grab-request v2 against a running PeerGrab API.

Verifies the contract that unit/integration tests cannot fully cover:
  1. publisher publishes a one-slot errand (funds escrowed);
  2. runner grabs via /grab-v2 and gets a deterministic terminal result;
  3. the same requestId replays without creating a second business fact;
  4. the query endpoint returns the persisted result;
  5. another runner gets SLOT_FULL;
  6. repeated attempts by the same runner hit the per-task rate limit (429).

Requires the API to run with demo login enabled. Example:
  python3 bench/scripts/verify_grab_v2_live.py --base http://127.0.0.1:18080 \
      --password-1001 demo1001 --password-2001 demo2001 --password-2002 demo2002
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid


def call_raw(base: str, method: str, path: str, body=None, token: str | None = None,
             request_id: str | None = None, timeout: float = 15.0):
    """Return (status, parsed_body, headers) even for non-2xx responses."""
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if request_id:
        req.add_header("X-Request-Id", request_id)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else None), resp.headers
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = raw
        return e.code, parsed, e.headers


def call(base: str, method: str, path: str, body=None, token: str | None = None,
         request_id: str | None = None, timeout: float = 15.0):
    status, payload, _ = call_raw(base, method, path, body, token, request_id, timeout)
    return status, payload


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def login(base: str, user_id: int, password: str) -> str:
    status, payload = call(base, "POST", "/api/auth/login",
                            {"userId": user_id, "password": password})
    expect(status == 200 and payload and payload.get("code") == "OK",
           f"login failed for {user_id}: {status} {payload}")
    data = payload["data"]
    token = data.get("accessToken") or data.get("token")
    expect(bool(token), f"login response has no token: {payload}")
    return token


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:18080")
    parser.add_argument("--password-1001", default="demo1001")
    parser.add_argument("--password-2001", default="demo2001")
    parser.add_argument("--password-2002", default="demo2002")
    parser.add_argument("--json-out", default="/tmp/pg-grab-v2-live-report.json")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    report: dict = {"base": base, "steps": []}
    try:
        health_status, _ = call(base, "GET", "/api/health")
        expect(health_status == 200, f"health check failed: {health_status}")
        report["steps"].append("health:200")

        publisher = login(base, 1001, args.password_1001)
        runner = login(base, 2001, args.password_2001)
        runner2 = login(base, 2002, args.password_2002)
        report["steps"].append("login:1001/2001/2002")

        publish_id = str(uuid.uuid4())
        status, payload = call(base, "POST", "/api/errands", {
            "campusId": 1, "type": "DELIVERY",
            "title": "live-v2-" + uuid.uuid4().hex[:8],
            "rewardCents": 800, "slotTotal": 1,
        }, token=publisher, request_id=publish_id)
        expect(status == 200 and payload.get("code") == "OK",
               f"publish failed: {status} {payload}")
        errand_id = payload["data"]["errandId"]
        report["errandId"] = errand_id
        report["steps"].append("publish:OK")

        request_id = str(uuid.uuid4())
        status, payload = call(base, "POST", f"/api/errands/{errand_id}/grab-v2",
                               {"expectedRound": 0}, token=runner, request_id=request_id)
        if status == 429:
            # 与 live_api_smoke 共用演示身份时预算可能尚未恢复：按 Retry-After 等后用同一 ID 重试。
            wait_ms = int(payload.get("data", {}).get("retryAfterMillis", 1000))
            time.sleep(min(wait_ms, 5000) / 1000.0 + 0.1)
            status, payload = call(base, "POST", f"/api/errands/{errand_id}/grab-v2",
                                   {"expectedRound": 0}, token=runner, request_id=request_id)
        expect(status == 200 and payload.get("code") == "OK",
               f"grab-v2 first attempt failed: {status} {payload}")
        expect(payload["data"]["grabbed"] is True, f"not grabbed: {payload}")
        expect(payload["data"]["replayed"] is False, f"first attempt should not be replay: {payload}")
        report["steps"].append("grab-v2:grabbed")

        status, payload = call(base, "POST", f"/api/errands/{errand_id}/grab-v2",
                               {"expectedRound": 0}, token=runner, request_id=request_id)
        expect(status == 200 and payload.get("code") == "OK", f"replay failed: {status} {payload}")
        expect(payload["data"]["replayed"] is True, f"replay flag missing: {payload}")
        report["steps"].append("grab-v2:replayed")

        status, payload = call(base, "GET",
                               f"/api/errands/{errand_id}/grab-requests/{request_id}", token=runner)
        expect(status == 200 and payload.get("code") == "OK", f"query failed: {status} {payload}")
        expect(payload["data"]["status"] == "SUCCEEDED", f"query status wrong: {payload}")
        report["steps"].append("query:SUCCEEDED")

        loser_id = str(uuid.uuid4())
        status, payload = call(base, "POST", f"/api/errands/{errand_id}/grab-v2",
                               {"expectedRound": 0}, token=runner2, request_id=loser_id)
        if status == 429:
            # 重复运行脚本时 runner2 的预算可能尚未恢复：按服务端 Retry-After 等待后
            # 用同一 requestId 重试（幂等重放不会二次占名额）。
            wait_ms = int(payload.get("data", {}).get("retryAfterMillis", 1000))
            time.sleep(min(wait_ms, 5000) / 1000.0 + 0.1)
            status, payload = call(base, "POST", f"/api/errands/{errand_id}/grab-v2",
                                   {"expectedRound": 0}, token=runner2, request_id=loser_id)
        expect(status == 409 and payload.get("code") == "SLOT_FULL",
               f"loser should get SLOT_FULL: {status} {payload}")
        report["steps"].append("loser:SLOT_FULL")

        limited = False
        codes: list[str] = []
        for _ in range(8):
            status, payload, headers = call_raw(base, "POST", f"/api/errands/{errand_id}/grab-v2",
                                                {"expectedRound": 0}, token=runner2,
                                                request_id=str(uuid.uuid4()))
            codes.append(f"{status}:{payload.get('code') if payload else '?'}")
            if status == 429:
                limited = True
                retry_after = headers.get("Retry-After")
                expect(retry_after is not None and retry_after.isdigit() and int(retry_after) >= 1,
                       f"429 must carry numeric Retry-After header, got={retry_after!r}")
                retry_ms = int(payload.get("data", {}).get("retryAfterMillis", 0))
                expect(retry_ms > 0, f"429 body must carry retryAfterMillis, got={payload}")
                report["steps"].append(f"rate-limit:429 Retry-After={retry_after}s")
                break
            time.sleep(0.05)
        expect(limited, f"per-task rate limit did not trigger, attempts={codes}")

        report["ok"] = True
    except AssertionError as e:
        report["ok"] = False
        report["error"] = str(e)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1

    with open(args.json_out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
