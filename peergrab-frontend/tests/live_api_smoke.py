# -*- coding: utf-8 -*-
"""PeerGrab 真实 API/MQ 验收（标准库 urllib + ThreadPoolExecutor）。

覆盖：并发抢单恰好 1 人成功、发布/结算/退款/仲裁幂等、越权拒绝、
超时递补（空候选回退）与自动结算（MQ 主通道）。

只能在可丢弃的隔离栈上运行（会创建任务并改变演示账户余额）。

用法：
  python3 tests/live_api_smoke.py --base http://127.0.0.1:8080 \
      --timeout-flow --auto-settle-flow --output verification/local-api
"""

from __future__ import annotations

import argparse
import json
import os
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
from pathlib import Path

DEFAULT_PASSWORDS = {
    1001: os.environ.get("PEERGRAB_LIVE_PASSWORD_1001", "demo1001"),
    2001: os.environ.get("PEERGRAB_LIVE_PASSWORD_2001", "demo2001"),
    2002: os.environ.get("PEERGRAB_LIVE_PASSWORD_2002", "demo2002"),
    9001: os.environ.get("PEERGRAB_LIVE_PASSWORD_9001", "demo9001"),
}

RESULTS = []


class ApiError(RuntimeError):
    def __init__(self, code, message):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class Client:
    def __init__(self, base, timeout=10):
        self.base = base.rstrip("/")
        self.timeout = timeout
        self.tokens = {}

    def _request(self, method, path, token=None, body=None, headers=None, expect_http_200=True):
        data = None
        hdrs = {"Content-Type": "application/json"}
        if headers:
            hdrs.update(headers)
        if token:
            hdrs["Authorization"] = f"Bearer {token}"
        if body is not None:
            data = json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                status = response.status
                payload = json.loads(response.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as exc:
            status = exc.code
            raw = exc.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                payload = {"code": f"HTTP_{status}", "message": raw[:200], "data": None}
        if expect_http_200 and status != 200:
            raise ApiError(payload.get("code", f"HTTP_{status}"), payload.get("message", ""))
        return status, payload

    def login(self, user_id, password=None):
        password = password or DEFAULT_PASSWORDS[user_id]
        _, payload = self._request("POST", "/api/auth/login", body={"userId": user_id, "password": password})
        if payload.get("code") != "OK":
            raise ApiError(payload.get("code"), payload.get("message"))
        token = payload["data"]["token"]
        self.tokens[user_id] = token
        return token

    def call(self, user_id, method, path, body=None, headers=None):
        _, payload = self._request(method, path, token=self.tokens[user_id], body=body, headers=headers)
        return payload

    def ok(self, user_id, method, path, body=None, headers=None):
        payload = self.call(user_id, method, path, body, headers)
        if payload.get("code") != "OK":
            raise ApiError(payload.get("code"), payload.get("message"))
        return payload.get("data")

    def balance(self, user_id):
        # JacksonConfig 把 long 统一序列化为字符串（雪花 ID 防精度丢失的副作用），
        # 金额也是 long，比较/做差前必须显式转 int。
        return int(self.ok(user_id, "GET", "/api/wallet")["availableCents"])

    def detail(self, user_id, errand_id):
        return self.ok(user_id, "GET", f"/api/errands/{errand_id}")


def check(name, func):
    try:
        detail = func() or ""
        RESULTS.append({"name": name, "ok": True, "detail": str(detail)})
        print(f"PASS {name} {detail}")
    except Exception as exc:  # noqa: BLE001
        RESULTS.append({"name": name, "ok": False, "detail": f"{type(exc).__name__}: {exc}"})
        print(f"FAIL {name}: {type(exc).__name__}: {exc}")


def wait_until(predicate, timeout_seconds, interval=0.5, what="条件"):
    deadline = time.time() + timeout_seconds
    last = None
    while time.time() < deadline:
        last = predicate()
        if last:
            return last
        time.sleep(interval)
    raise TimeoutError(f"等待 {what} 超时（{timeout_seconds}s），最后结果={last!r}")


def publish(client, publisher=1001, reward=1500, title_prefix="acceptance"):
    title = f"{title_prefix}-{datetime.now().strftime('%H%M%S')}-{uuid.uuid4().hex[:6]}"
    data = client.ok(
        publisher,
        "POST",
        "/api/errands",
        {"campusId": 1, "type": "DELIVERY", "title": title, "rewardCents": reward, "slotTotal": 1},
        {"X-Request-Id": str(uuid.uuid4())},
    )
    return data["errandId"], title


def finish_task(client, errand_id, publisher=1001, runner=2001):
    """Best-effort cleanup so demo accounts do not accumulate ongoing tasks."""
    try:
        card = client.detail(publisher, errand_id)
        actions = card.get("availableActions") or []
        if "SETTLE" in actions:
            client.ok(publisher, "POST", f"/api/errands/{errand_id}/settle")
        elif "CANCEL" in actions:
            client.ok(publisher, "POST", f"/api/errands/{errand_id}/cancel")
        elif "CONFIRM" in actions:
            client.ok(runner, "POST", f"/api/errands/{errand_id}/confirm")
            client.ok(runner, "POST", f"/api/errands/{errand_id}/pickup")
            client.ok(runner, "POST", f"/api/errands/{errand_id}/deliver")
            client.ok(publisher, "POST", f"/api/errands/{errand_id}/settle")
    except Exception:  # noqa: BLE001
        pass


def grab_once(client, user_id, errand_id, request_id):
    payload = client.call(
        user_id, "POST", f"/api/errands/{errand_id}/grab", headers={"X-Request-Id": request_id}
    )
    return payload.get("code"), payload.get("data") or {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.environ.get("PEERGRAB_LIVE_API_BASE", "http://127.0.0.1:8080"))
    parser.add_argument("--output", default=str(Path(__file__).resolve().parents[1] / "verification" / "local-api"))
    parser.add_argument("--timeout-flow", action="store_true")
    parser.add_argument("--auto-settle-flow", action="store_true")
    parser.add_argument("--confirm-seconds", type=int, default=3)
    parser.add_argument("--auto-settle-seconds", type=int, default=8)
    parser.add_argument("--worker-log", default="")
    args = parser.parse_args()

    client = Client(args.base)
    try:
        status, payload = client._request("GET", "/api/health", expect_http_200=False)
        if status != 200:
            raise RuntimeError(payload)
    except Exception as exc:  # noqa: BLE001
        print(f"API 不可达（{args.base}）：{exc}")
        print("请先启动隔离后端（compose mysql/redis/rmq + init-mq + bootstrap/worker jar）")
        return 2

    for user in DEFAULT_PASSWORDS:
        client.login(user)

    state = {}

    def health():
        status, payload = client._request("GET", "/api/health", expect_http_200=False)
        assert status == 200 and payload["code"] == "OK", payload
        return payload["data"].get("project")

    def unauthenticated_401():
        status, _ = client._request("GET", "/api/wallet", expect_http_200=False)
        assert status == 401, f"期望 401，实际 {status}"
        return "401"

    def publish_idempotent():
        before = int(client.balance(1001))
        request_id = str(uuid.uuid4())
        body = {"campusId": 1, "type": "DELIVERY", "title": f"幂等-{uuid.uuid4().hex[:6]}",
                "rewardCents": 1500, "slotTotal": 1}
        first = client.ok(1001, "POST", "/api/errands", body, {"X-Request-Id": request_id})
        second = client.ok(1001, "POST", "/api/errands", body, {"X-Request-Id": request_id})
        assert first["errandId"] == second["errandId"], (first, second)
        after = int(client.balance(1001))
        assert before - after == 1500, f"重复发布不应重复扣款：{before}->{after}"
        state["idempotent_task"] = first["errandId"]
        finish_task(client, first["errandId"])
        return first["errandId"]

    def concurrent_grab_single_winner():
        errand_id, _ = publish(client, reward=1000)
        request_ids = {user: str(uuid.uuid4()) for user in (2001, 2002)}
        results = []
        lock = threading.Lock()

        def task(user):
            code, data = grab_once(client, user, errand_id, request_ids[user])
            with lock:
                results.append((user, code, data))

        with ThreadPoolExecutor(max_workers=2) as pool:
            for user in (2001, 2002):
                pool.submit(task, user)
        wins = [item for item in results if item[1] == "OK"]
        assert len(wins) == 1, f"同单并发应恰好 1 人成功，实际 {results}"
        losers = [item for item in results if item[1] != "OK"]
        assert losers and losers[0][1] in ("SLOT_FULL", "GRAB_CONFLICT"), losers
        state["flow_task"] = errand_id
        state["winner"] = wins[0][0]
        return f"winner={wins[0][0]} loser={losers[0][1]}"

    def grab_replay_safe():
        errand_id = state["flow_task"]
        winner = state["winner"]
        replay_id = str(uuid.uuid4())
        first = grab_once(client, winner, errand_id, replay_id)
        second = grab_once(client, winner, errand_id, replay_id)
        assert first[0] in ("OK", "ALREADY_GRABBED"), first
        assert second[0] in ("OK", "GRAB_CONFLICT", "ALREADY_GRABBED", "SLOT_FULL"), second
        detail = client.detail(1001, errand_id)
        assert detail["slotTaken"] == 1, detail
        return f"first={first[0]} replay={second[0]}"

    def lifecycle_and_settlement():
        errand_id = state["flow_task"]
        winner = state["winner"]
        client.ok(winner, "POST", f"/api/errands/{errand_id}/confirm")
        client.ok(winner, "POST", f"/api/errands/{errand_id}/pickup")
        client.ok(winner, "POST", f"/api/errands/{errand_id}/deliver")
        before_runner = int(client.balance(winner))
        before_publisher = int(client.balance(1001))
        result = client.ok(1001, "POST", f"/api/errands/{errand_id}/settle")
        assert result.get("result") == "SETTLED", result
        after_runner = int(client.balance(winner))
        assert int(client.balance(1001)) == before_publisher, "结算不应再扣发单人余额"
        reward = 1000
        commission = reward * 5 // 100
        assert after_runner - before_runner == reward - commission, (before_runner, after_runner)
        assert client.detail(1001, errand_id)["status"] == "SETTLED"
        state["settled_runner"] = winner
        return f"runner += {reward - commission}"

    def settle_idempotent():
        errand_id = state["flow_task"]
        before = int(client.balance(state["winner"]))
        payload = client.call(1001, "POST", f"/api/errands/{errand_id}/settle")
        assert payload["code"] == "OK" and payload["data"].get("result") in ("ALREADY_SETTLED", "SETTLED"), payload
        assert int(client.balance(state["winner"])) == before, "重复结算不应重复入账"
        return str(payload["data"].get("result"))

    def cancel_and_refund():
        before = int(client.balance(1001))
        errand_id, _ = publish(client, reward=800)
        payload = client.ok(1001, "POST", f"/api/errands/{errand_id}/cancel")
        assert payload.get("result") == "REFUNDED", payload
        assert int(client.balance(1001)) == before, "取消应全额退款"
        replay = client.call(1001, "POST", f"/api/errands/{errand_id}/cancel")
        assert replay["code"] == "OK" and replay["data"].get("result") in ("ALREADY_REFUNDED", "REFUNDED"), replay
        return "refunded and replay-safe"

    def arbitration_refund_and_permission():
        before_publisher = int(client.balance(1001))
        errand_id, _ = publish(client, reward=1200)
        client.ok(2001, "POST", f"/api/errands/{errand_id}/grab", headers={"X-Request-Id": str(uuid.uuid4())})
        client.ok(2001, "POST", f"/api/errands/{errand_id}/confirm")
        client.ok(2001, "POST", f"/api/errands/{errand_id}/dispute")
        unauthorized = client.call(1001, "POST", f"/api/errands/{errand_id}/arbitrate", {"favor": "PUBLISHER"})
        assert unauthorized["code"] != "OK", "发单人不应能自己仲裁"
        result = client.ok(9001, "POST", f"/api/errands/{errand_id}/arbitrate", {"favor": "PUBLISHER"})
        assert result.get("result") == "REFUNDED_TO_PUBLISHER", result
        assert int(client.balance(1001)) == before_publisher, "仲裁退款金额不对"
        replay = client.call(9001, "POST", f"/api/errands/{errand_id}/arbitrate", {"favor": "PUBLISHER"})
        assert replay["code"] == "OK" and replay["data"].get("result") in ("ALREADY_DONE", "REFUNDED_TO_PUBLISHER"), replay
        return "refunded, replay-safe, permission-checked"

    def runner_cannot_settle():
        errand_id, _ = publish(client, reward=500)
        payload = client.call(2002, "POST", f"/api/errands/{errand_id}/settle")
        assert payload["code"] != "OK", "非发单人不应能结算"
        finish_task(client, errand_id)
        return payload["code"]

    def credit_and_notification():
        runner = state["settled_runner"]
        credit = client.ok(runner, "GET", "/api/credit/self")
        assert credit["score"] >= 0, credit
        assert any(event["type"] == "SETTLE" for event in credit["events"]), credit["events"][:3]
        notifications = client.ok(1001, "GET", "/api/notifications")
        assert isinstance(notifications, list), notifications
        return f"credit_events={len(credit['events'])} notifications={len(notifications)}"

    def timeout_transfer():
        errand_id, _ = publish(client, reward=600)
        before = int(client.balance(1001))
        code, _ = grab_once(client, 2001, errand_id, str(uuid.uuid4()))
        assert code == "OK", code
        wait_until(
            lambda: client.detail(1001, errand_id)["status"] == "PUBLISHED",
            args.confirm_seconds + 25,
            what="确认超时回退 PUBLISHED",
        )
        card = client.detail(1001, errand_id)
        assert card["grabberId"] in ("-1", None, ""), card
        assert int(client.balance(1001)) == before, "超时回退不应动发单人余额"
        client.ok(1001, "POST", f"/api/errands/{errand_id}/cancel")
        return "reverted without candidates"

    def auto_settle():
        errand_id, _ = publish(client, reward=900)
        before_publisher = int(client.balance(1001))
        client.ok(2001, "POST", f"/api/errands/{errand_id}/grab", headers={"X-Request-Id": str(uuid.uuid4())})
        client.ok(2001, "POST", f"/api/errands/{errand_id}/confirm")
        client.ok(2001, "POST", f"/api/errands/{errand_id}/pickup")
        client.ok(2001, "POST", f"/api/errands/{errand_id}/deliver")
        wait_until(
            lambda: client.detail(1001, errand_id)["status"] == "SETTLED",
            args.auto_settle_seconds + 30,
            what="送达后自动结算",
        )
        assert int(client.balance(1001)) == before_publisher, "自动结算不应再扣发单人"
        return "auto settled via MQ"

    check("health", health)
    check("unauthenticated_wallet_is_401", unauthenticated_401)
    check("publish_idempotent_charge_once", publish_idempotent)
    check("concurrent_grab_single_winner", concurrent_grab_single_winner)
    check("grab_request_replay_safe", grab_replay_safe)
    check("lifecycle_and_settlement", lifecycle_and_settlement)
    check("settle_idempotent", settle_idempotent)
    check("cancel_refund_idempotent", cancel_and_refund)
    check("arbitration_refund_idempotent_and_permission", arbitration_refund_and_permission)
    check("runner_cannot_settle", runner_cannot_settle)
    check("credit_and_notification_after_settle", credit_and_notification)
    if args.timeout_flow:
        check("timeout_transfer_via_mq", timeout_transfer)
    if args.auto_settle_flow:
        check("auto_settle_via_mq", auto_settle)

    mq_evidence = {"provided": bool(args.worker_log), "matches": []}
    if args.worker_log and Path(args.worker_log).exists():
        text = Path(args.worker_log).read_text(encoding="utf-8", errors="replace")
        for needle in ("定时消息", "SETTLED", "TimeoutTransferConsumer", "AutoSettleConsumer"):
            if needle in text:
                mq_evidence["matches"].append(needle)

    summary = {
        "total": len(RESULTS),
        "passed": sum(1 for result in RESULTS if result["ok"]),
        "failed": sum(1 for result in RESULTS if not result["ok"]),
    }
    report = {
        "suite": "live-api",
        "generatedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
        "base": args.base,
        "results": RESULTS,
        "mqEvidence": mq_evidence,
        "summary": summary,
    }
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / "api-live-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    print(f"report: {report_path}")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
