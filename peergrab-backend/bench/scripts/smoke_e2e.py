#!/usr/bin/env python3
"""Exercise the local Docker demo through its public HTTP entry point.

Usage: python3 bench/scripts/smoke_e2e.py --env-file docker/.env
The script creates a few small demo errands in the dedicated local database.
"""

import argparse
import json
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def read_env(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


class Client:
    def __init__(self, base: str):
        self.base = base.rstrip("/")

    def request(self, method: str, path: str, token: str | None = None, data=None, extra_headers=None):
        headers = dict(extra_headers or {})
        if token:
            headers["Authorization"] = f"Bearer {token}"
        payload = None
        if data is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=payload, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                body = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                body = None
            return error.code, body


def expect(status: int, body: dict | None, code: str):
    actual = body.get("code") if isinstance(body, dict) else None
    if actual != code:
        raise AssertionError(f"expected {code}, got HTTP {status} / {actual}: {body}")
    data = body.get("data")
    return data if data is not None else {}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, default=Path("docker/.env"))
    parser.add_argument("--base-url", default=None)
    args = parser.parse_args()
    env = read_env(args.env_file)
    base = args.base_url or f"http://127.0.0.1:{env.get('PEERGRAB_WEB_PORT', '25173')}"
    client = Client(base)
    direct = Client(f"http://127.0.0.1:{env.get('PEERGRAB_API_PORT', '28080')}")

    with urllib.request.urlopen(base + "/", timeout=15) as response:
        if response.status != 200 or b"<html" not in response.read().lower():
            raise AssertionError("frontend did not serve HTML")
    expect(*client.request("GET", "/api/health"), "OK")
    print("PASS frontend + API health")

    users = {}
    for user_id in (1001, 2001, 2002, 9001):
        password = env[f"PEERGRAB_AUTH_DEMO_PASSWORD_{user_id}"]
        users[user_id] = expect(*client.request("POST", "/api/auth/login", data={
            "userId": user_id, "password": password,
        }), "OK")["token"]
    bad_status, bad_body = client.request("POST", "/api/auth/login", data={
        "userId": 9001, "password": env["PEERGRAB_AUTH_DEMO_PASSWORD_2001"],
    })
    expect(bad_status, bad_body, "UNAUTHORIZED")
    print("PASS four distinct credentials + wrong-password rejection")

    publisher, runner_a, runner_b, arbitrator = (users[i] for i in (1001, 2001, 2002, 9001))
    status, _ = client.request("GET", "/api/internal/cache-stats")
    if status != 404:
        raise AssertionError(f"public Nginx must hide internal route, got {status}")
    expect(*direct.request("GET", "/api/internal/cache-stats", runner_a), "UNAUTHORIZED")
    expect(*direct.request("GET", "/api/internal/cache-stats", arbitrator), "OK")
    print("PASS internal endpoint boundary")

    def balance(token: str) -> int:
        return int(expect(*client.request("GET", "/api/wallet", token), "OK")["availableCents"])

    publisher_initial = balance(publisher)
    invalid = client.request("POST", "/api/errands", publisher, {
        "title": "invalid two-slot smoke", "rewardCents": 700, "slotTotal": 2,
    })
    expect(*invalid, "INVALID_ARGUMENT")
    if balance(publisher) != publisher_initial:
        raise AssertionError("invalid multi-slot publish changed the publisher balance")
    print("PASS single-slot validation without money movement")

    request_id = str(uuid.uuid4())
    repeated_payload = {"title": f"e2e_idempotent_{int(time.time())}", "rewardCents": 300, "slotTotal": 1}
    request_headers = {"X-Request-Id": request_id}
    first = expect(*client.request("POST", "/api/errands", publisher, repeated_payload, request_headers), "OK")
    replay = expect(*client.request("POST", "/api/errands", publisher, repeated_payload, request_headers), "OK")
    if str(first["errandId"]) != str(replay["errandId"]) or balance(publisher) != publisher_initial - 300:
        raise AssertionError("same publish request id created another task or charged twice")
    expect(*client.request("POST", f"/api/errands/{first['errandId']}/cancel", publisher), "OK")
    if balance(publisher) != publisher_initial:
        raise AssertionError("idempotent publish test refund did not restore balance")
    print("PASS publish request replay returns original task without duplicate charge")

    stamp = str(int(time.time()))

    def publish(label: str, reward: int = 700) -> str:
        data = expect(*client.request("POST", "/api/errands", publisher, {
            "title": f"e2e_{label}_{stamp}", "rewardCents": reward, "slotTotal": 1,
        }, {"X-Request-Id": str(uuid.uuid4())}), "OK")
        return str(data["errandId"])

    def action(errand_id: str, name: str, token: str, data=None, code="OK") -> dict:
        return expect(*client.request("POST", f"/api/errands/{errand_id}/{name}", token, data), code)

    main_id = publish("settle")
    action(main_id, "settle", publisher, code="ILLEGAL_STATE_TRANSITION")
    action(main_id, "grab", publisher, code="SELF_GRAB_FORBIDDEN")
    if expect(*client.request("GET", f"/api/errands/{main_id}", publisher), "OK")["status"] != "PUBLISHED":
        raise AssertionError("rejected operations changed the errand state")
    action(main_id, "grab", runner_a)
    loser_status, loser_body = client.request("POST", f"/api/errands/{main_id}/grab", runner_b)
    if not isinstance(loser_body, dict) or loser_body.get("code") not in ("SLOT_FULL", "CREDIT_TOO_LOW", "TOO_MANY_ONGOING", "GRAB_CONFLICT"):
        raise AssertionError(f"second runner should be rejected, got HTTP {loser_status} / {loser_body}")
    action(main_id, "confirm", runner_a)
    action(main_id, "pickup", runner_a)
    action(main_id, "deliver", runner_a)
    action(main_id, "settle", runner_a, code="NOT_PUBLISHER")
    runner_before = balance(runner_a)
    action(main_id, "settle", publisher)
    if balance(runner_a) <= runner_before:
        raise AssertionError("runner was not paid")
    if expect(*client.request("GET", f"/api/errands/{main_id}", publisher), "OK")["status"] != "SETTLED":
        raise AssertionError("errand was not settled")
    print("PASS publish -> grab -> confirm -> pickup -> deliver -> settle; invalid operations rejected")

    refund_id = publish("refund", 800)
    action(refund_id, "cancel", publisher)
    if expect(*client.request("GET", f"/api/errands/{refund_id}", publisher), "OK")["status"] != "CANCELLED":
        raise AssertionError("cancel did not update errand")
    if balance(publisher) != publisher_initial - 700:
        raise AssertionError("refund did not restore publisher balance")
    print("PASS publish -> cancel -> refund")

    dispute_id = publish("dispute", 900)
    dispute_grab = client.request("POST", f"/api/errands/{dispute_id}/grab", runner_b)
    dispute_runner = runner_b
    if isinstance(dispute_grab[1], dict) and dispute_grab[1].get("code") in ("CREDIT_TOO_LOW", "TOO_MANY_ONGOING"):
        dispute_runner = runner_a
        dispute_grab = client.request("POST", f"/api/errands/{dispute_id}/grab", dispute_runner)
    expect(*dispute_grab, "OK")
    action(dispute_id, "confirm", dispute_runner)
    action(dispute_id, "dispute", publisher)
    action(dispute_id, "arbitrate", dispute_runner, {"favor": "PUBLISHER"}, code="UNAUTHORIZED")
    action(dispute_id, "arbitrate", arbitrator, {"favor": "PUBLISHER"})
    if expect(*client.request("GET", f"/api/errands/{dispute_id}", publisher), "OK")["status"] != "REFUNDED":
        raise AssertionError("arbitration refund did not update errand")
    if balance(publisher) != publisher_initial - 700:
        raise AssertionError("arbitration refund did not restore publisher balance")
    print("PASS dispute -> authorized arbitration -> refund")

    deadline = time.monotonic() + 25
    notification = None
    while time.monotonic() < deadline:
        items = expect(*client.request("GET", "/api/notifications", publisher), "OK")
        notification = next((item for item in items if str(item["errandId"]) == main_id), None)
        if notification is not None:
            break
        time.sleep(1)
    if notification is None:
        raise AssertionError("fund event did not create a notification")
    before = int(expect(*client.request("GET", "/api/notifications/unread", publisher), "OK")["count"])
    action_path = f"/api/notifications/{notification['id']}/read"
    expect(*client.request("POST", action_path, publisher), "OK")
    after = int(expect(*client.request("GET", "/api/notifications/unread", publisher), "OK")["count"])
    if not notification["read"] and after != before - 1:
        raise AssertionError("mark-read did not reduce unread count")
    print("PASS MQ notification + mark-read")

    print(f"SMOKE PASS base={base} errands={main_id},{refund_id},{dispute_id}")


if __name__ == "__main__":
    main()
