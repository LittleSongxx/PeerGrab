# -*- coding: utf-8 -*-
"""Deterministic mock backend + static server for PeerGrab UI acceptance tests.

The mock mirrors the shape of the real `Result{code,message,data}` API closely
enough to exercise the frontend (auth, errands, actions, wallet, credit,
notifications) without a database. It intentionally keeps a small state machine
so tests can assert real transitions (grab -> confirm -> pickup -> deliver ->
settle / cancel / dispute -> arbitrate).

Screenshots and UI smoke tests share this module so the demo data is identical.
"""

from __future__ import annotations

import json
import threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

BASE_TIME = "2026-10-19T09:30:00+08:00"

DEMO_PASSWORDS = {
    1001: "demo1001",
    2001: "demo2001",
    2002: "demo2002",
    9001: "demo9001",
}

DEFAULT_VIEWPORT = {"width": 1440, "height": 900}
MOBILE_VIEWPORT = {"width": 390, "height": 844}

# Keep the WebSocket layer out of UI tests: the app must work when WS is absent.
WS_STUB = """
(() => {
  class MockWebSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;
    constructor() { this.readyState = MockWebSocket.CLOSED; }
    close() {}
    send() {}
  }
  Object.defineProperty(window, "WebSocket", { value: MockWebSocket, configurable: true });
})();
"""

WS_BROKEN = """
(() => {
  function Broken() { throw new Error("websocket blocked by test"); }
  Object.defineProperty(window, "WebSocket", { value: Broken, configurable: true });
})();
"""


def ok(data=None):
    return 200, {"code": "OK", "message": "OK", "data": data}


def fail(code, message=None, data=None):
    return 200, {"code": code, "message": message or code, "data": data}


class MockState:
    def __init__(self):
        self.seq = 0
        self.tokens = {}
        self.refresh_tokens = {}
        self.tasks = {}
        self.publish_requests = {}
        self.ledger = {}
        self.notifications = {}
        self.credit_events = {}
        self.ranking = []
        self.flags = {
            "empty_square": False,
            "fail_square_once": False,
            "login_fail_once": False,
            "next_cursor_once": False,
            "force_401_wallet_once": False,
        }
        self.holds = {}
        self.held = {}
        self.pending_route = {}
        self.last_publish = None
        self.seed()

    # ---------- seed ----------
    def seed(self):
        self.wallets = {
            1001: {"availableCents": 93600, "frozenCents": 0},
            2001: {"availableCents": 12345, "frozenCents": 0},
            2002: {"availableCents": 8400, "frozenCents": 0},
            9001: {"availableCents": 0, "frozenCents": 0},
        }
        self.ledger = {user: [] for user in self.wallets}
        self.notifications = {user: [] for user in self.wallets}
        self.credit_events = {
            2001: [
                {"type": "SETTLE", "description": "完成结算", "delta": 2,
                 "refId": "215731113107132416", "time": "2026-10-18T18:20:00+08:00"},
                {"type": "GRAB_TIMEOUT_REVERT", "description": "抢单后超时未确认", "delta": -5,
                 "refId": "215731113107132417", "time": "2026-10-17T11:05:00+08:00"},
            ],
            1001: [], 2002: [], 9001: [],
        }
        self.ranking = [
            {"userId": "2001", "score": 96},
            {"userId": "2002", "score": 91},
            {"userId": "1001", "score": 82},
        ]
        self.ledger[1001] = [
            {"id": "9001", "time": "2026-10-18T18:20:00+08:00", "direction": "DEBIT",
             "amountCents": 900, "refType": "ESCROW", "refId": "215731113107132416",
             "bizNo": "escrow:...416", "accountType": "USER"},
            {"id": "9002", "time": "2026-10-17T15:02:00+08:00", "direction": "CREDIT",
             "amountCents": 1200, "refType": "REFUND", "refId": "215731113107132418",
             "bizNo": "refund:...418", "accountType": "USER"},
            {"id": "9003", "time": "2026-10-17T14:50:00+08:00", "direction": "DEBIT",
             "amountCents": 1200, "refType": "ESCROW", "refId": "215731113107132418",
             "bizNo": "escrow:...418", "accountType": "ESCROW"},
            {"id": "9004", "time": "2026-10-16T12:00:00+08:00", "direction": "CREDIT",
             "amountCents": 1500, "refType": "SETTLE", "refId": "215731113107132421",
             "bizNo": "settle:...421", "accountType": "USER"},
        ]
        self.ledger[2001] = [
            {"id": "9101", "time": "2026-10-18T18:20:00+08:00", "direction": "CREDIT",
             "amountCents": 1710, "refType": "SETTLE", "refId": "215731113107132421",
             "bizNo": "settle:...421", "accountType": "USER"},
            {"id": "9102", "time": "2026-10-18T18:20:00+08:00", "direction": "CREDIT",
             "amountCents": 90, "refType": "SETTLE", "refId": "215731113107132421",
             "bizNo": "settle:...421", "accountType": "COMMISSION"},
        ]
        self.notifications[1001] = [
            {"id": "9201", "errandId": "215731113107132421", "type": "SETTLED",
             "content": "任务已完成结算，悬赏已支付给跑腿。", "time": "2026-10-18T18:20:00+08:00", "read": False},
            {"id": "9202", "errandId": "215731113107132418", "type": "REFUNDED",
             "content": "任务已取消，悬赏已退回发单人。", "time": "2026-10-17T15:02:00+08:00", "read": True},
        ]
        self.notifications[2001] = [
            {"id": "9301", "errandId": "215731113107132421", "type": "SETTLED",
             "content": "你完成的任务已结算，收入已到账。", "time": "2026-10-18T18:20:00+08:00", "read": False},
        ]
        self.tasks = {}

        def add(task_id, title, status, task_type, reward, publisher, grabber="-1",
                round_no=0, history=None):
            task = {
                "id": task_id,
                "title": title,
                "status": status,
                "type": task_type,
                "rewardCents": reward,
                "slotTotal": 1,
                "slotTaken": 0 if grabber == "-1" else 1,
                "publisherId": str(publisher),
                "grabberId": str(grabber),
                "round": round_no,
                "history": history or self.history_for(status, publisher, grabber),
            }
            self.tasks[task_id] = task

        add("215731113107132416", "帮我到菜鸟驿站取一个快递", "PUBLISHED", "DELIVERY", 900, 1001)
        add("215731113107132417", "帮我代领社团活动物料", "PUBLISHED", "DELIVERY", 2500, 1001)
        add("215731113107132418", "帮我去三食堂带一杯奶茶", "PUBLISHED", "BUY", 1200, 1001)
        add("215731113107132419", "图书馆排队取复习资料", "PUBLISHED", "QUEUE", 2000, 1001)
        add("215731113107132420", "帮我打印一份彩色海报", "PUBLISHED", "OTHER", 600, 1001)
        add("215731113107132421", "把借阅的三本书还回图书馆", "DELIVERED", "DELIVERY", 1800, 1001, 2001)
        add("215731113107132422", "东门快递站代取小件包裹", "DISPUTED", "DELIVERY", 1500, 1001, 2001)
        add("215731113107132423", "帮我带一份早餐到实验楼", "LOCKED", "DELIVERY", 800, 1001, 2001)

    @staticmethod
    def history_for(status, publisher, grabber):
        steps = [
            ("DRAFT", "PUBLISHED", publisher),
            ("PUBLISHED", "LOCKED", grabber if grabber != "-1" else publisher),
        ]
        if status in ("ACCEPTED", "PICKED_UP", "DELIVERED", "DISPUTED"):
            steps.append(("LOCKED", "ACCEPTED", grabber))
        if status in ("PICKED_UP", "DELIVERED", "DISPUTED"):
            steps.append(("ACCEPTED", "PICKED_UP", grabber))
        if status in ("DELIVERED", "DISPUTED"):
            steps.append(("PICKED_UP", "DELIVERED", grabber))
        if status == "DISPUTED":
            steps.append(("DELIVERED", "DISPUTED", grabber))
        if status == "SETTLED":
            steps.append(("DELIVERED", "SETTLED", publisher))
        if status in ("CANCELLED", "REFUNDED"):
            steps.append(("PUBLISHED", status, publisher))
        order = {
            "DRAFT": 0, "PUBLISHED": 1, "LOCKED": 2, "ACCEPTED": 3,
            "PICKED_UP": 4, "DELIVERED": 5, "DISPUTED": 6, "SETTLED": 7,
            "CANCELLED": 7, "REFUNDED": 7,
        }
        steps = sorted(steps, key=lambda step: order.get(step[1], 9))
        return [
            {"time": f"2026-10-1{index % 9}T09:0{index % 10}:00+08:00",
             "from": old, "to": new, "round": 0, "operatorId": str(operator)}
            for index, (old, new, operator) in enumerate(steps)
        ]

    # ---------- helpers ----------
    def issue_login(self, user_id):
        self.seq += 1
        token = f"mock-token-{user_id}-{self.seq}"
        refresh = f"mock-refresh-{user_id}-{self.seq}"
        self.tokens[token] = user_id
        self.refresh_tokens[refresh] = user_id
        return token, refresh

    def role_of(self, user_id):
        return "ARBITRATOR" if user_id == 9001 else "USER"

    def user_for(self, headers):
        auth = headers.get("authorization") or headers.get("Authorization") or ""
        if not auth.startswith("Bearer "):
            return None
        return self.tokens.get(auth[7:])

    def actions_for(self, task, viewer):
        status = task["status"]
        publisher = task["publisherId"] == str(viewer)
        grabber = task["grabberId"] == str(viewer)
        if status == "PUBLISHED":
            if publisher:
                return ["CANCEL"]
            if viewer == 9001:
                return []
            return ["GRAB"]
        if status == "LOCKED":
            return ["CONFIRM"] if grabber else []
        if status == "ACCEPTED":
            actions = ["DISPUTE"] if (publisher or grabber) else []
            if grabber:
                actions.insert(0, "PICKUP")
            return actions
        if status == "PICKED_UP":
            actions = ["DISPUTE"] if (publisher or grabber) else []
            if grabber:
                actions.insert(0, "DELIVER")
            return actions
        if status == "DELIVERED":
            actions = ["DISPUTE"] if (publisher or grabber) else []
            if publisher:
                actions.insert(0, "SETTLE")
            return actions
        if status == "DISPUTED":
            return ["ARBITRATE"] if viewer == 9001 else []
        return []

    def role_for(self, task, viewer):
        if task["publisherId"] == str(viewer):
            return "PUBLISHER"
        if task["grabberId"] == str(viewer):
            return "CURRENT_RUNNER"
        if viewer == 9001:
            return "ARBITRATOR"
        return "VIEWER"

    def card(self, task, viewer):
        return {
            "id": task["id"],
            "title": task["title"],
            "status": task["status"],
            "type": task["type"],
            "rewardCents": task["rewardCents"],
            "slotTotal": task["slotTotal"],
            "slotTaken": task["slotTaken"],
            "publisherId": task["publisherId"],
            "grabberId": task["grabberId"],
            "round": task["round"],
            "role": self.role_for(task, viewer),
            "availableActions": self.actions_for(task, viewer),
        }

    def visible_tasks(self, viewer):
        return [self.card(task, viewer) for task in self.tasks.values()]

    def transition(self, task, new_status, operator):
        task["history"].append({
            "time": BASE_TIME,
            "from": task["status"],
            "to": new_status,
            "round": task["round"],
            "operatorId": str(operator),
        })
        task["status"] = new_status

    def add_ledger(self, user, direction, amount, ref_type, ref_id, account_type="USER", biz_no=None):
        self.seq += 1
        entry = {
            "id": str(self.seq),
            "time": BASE_TIME,
            "direction": direction,
            "amountCents": amount,
            "refType": ref_type,
            "refId": str(ref_id),
            "bizNo": biz_no or f"{ref_type.lower()}:{ref_id}:{self.seq}",
            "accountType": account_type,
        }
        self.ledger.setdefault(user, []).insert(0, entry)
        if direction == "CREDIT" and account_type == "USER":
            self.wallets[user]["availableCents"] += amount
        if direction == "DEBIT" and account_type == "USER":
            self.wallets[user]["availableCents"] -= amount

    def notify(self, user, errand_id, kind, content):
        self.seq += 1
        self.notifications.setdefault(user, []).insert(0, {
            "id": str(self.seq),
            "errandId": errand_id,
            "type": kind,
            "content": content,
            "time": BASE_TIME,
            "read": False,
        })

    # ---------- request dispatch ----------
    def handle(self, method, path, query, headers, body):
        gate = self.hold_for(method, path)
        if gate is not None:
            return gate
        response = self._dispatch(method, path, query, headers, body)
        if response is None:
            return None
        key = self.hold_key(path)
        if key and self.holds.get(key):
            self.holds[key] = False
            self.held[key] = response
            return None
        return response

    def hold_key(self, path):
        if path == "/api/errands":
            return "list"
        if path == "/api/wallet":
            return "wallet"
        parts = [part for part in path.split("/") if part]
        if len(parts) == 3 and parts[0] == "api" and parts[1] == "errands":
            return "detail"
        return None

    def hold_for(self, method, path):
        return None

    def release(self, key):
        held = self.held.pop(key, None)
        if not held:
            return False
        route = self.pending_route.pop(key, None)
        if route is None:
            return False
        status, payload = held
        route.fulfill(status=status, content_type="application/json; charset=utf-8",
                      body=json.dumps(payload, ensure_ascii=False))
        return True

    def _dispatch(self, method, path, query, headers, body):
        if path == "/api/auth/login" and method == "POST":
            payload = body or {}
            user = payload.get("userId")
            password = payload.get("password")
            if self.flags["login_fail_once"]:
                self.flags["login_fail_once"] = False
                return fail("UNAUTHORIZED", "演示口令不正确")
            if DEMO_PASSWORDS.get(user) != password:
                return fail("UNAUTHORIZED", "演示口令不正确")
            token, refresh = self.issue_login(user)
            return ok({"token": token, "accessToken": token, "refreshToken": refresh,
                       "userId": user, "role": self.role_of(user)})
        if path == "/api/auth/refresh" and method == "POST":
            refresh = (body or {}).get("refreshToken")
            user = self.refresh_tokens.pop(refresh, None)
            if not user:
                return fail("UNAUTHORIZED", "refresh token 已失效")
            token, new_refresh = self.issue_login(user)
            return ok({"token": token, "accessToken": token, "refreshToken": new_refresh})
        if path == "/api/auth/logout" and method == "POST":
            auth = headers.get("authorization") or headers.get("Authorization") or ""
            if auth.startswith("Bearer "):
                user = self.tokens.pop(auth[7:], None)
                if user:
                    return ok(None)
            return fail("UNAUTHORIZED", "未登录")
        if path == "/api/health" and method == "GET":
            return ok({"status": "UP"})

        viewer = self.user_for(headers)
        if viewer is None:
            return 401, {"code": "UNAUTHORIZED", "message": "未登录", "data": None}

        if path == "/api/errands" and method == "POST":
            if viewer == 9001:
                return fail("ROLE_NOT_ALLOWED", "仲裁员不能发布任务")
            payload = body or {}
            title = str(payload.get("title") or "").strip()
            reward = int(payload.get("rewardCents") or 0)
            request_id = headers.get("x-request-id") or ""
            if request_id and request_id in self.publish_requests:
                return ok(self.publish_requests[request_id])
            if not title or reward <= 0:
                return fail("INVALID_ARGUMENT", "标题与悬赏金额必须有效")
            if self.wallets[viewer]["availableCents"] < reward:
                return fail("INSUFFICIENT_BALANCE", "可用余额不足")
            self.seq += 1
            errand_id = str(215731113107132500 + self.seq)
            self.tasks[errand_id] = {
                "id": errand_id,
                "title": title,
                "status": "PUBLISHED",
                "type": payload.get("type") or "DELIVERY",
                "rewardCents": reward,
                "slotTotal": 1,
                "slotTaken": 0,
                "publisherId": str(viewer),
                "grabberId": "-1",
                "round": 0,
                "history": [{"time": BASE_TIME, "from": "DRAFT", "to": "PUBLISHED",
                             "round": 0, "operatorId": str(viewer)}],
            }
            self.add_ledger(viewer, "DEBIT", reward, "ESCROW", errand_id)
            self.last_publish = {"title": title, "rewardCents": reward}
            data = {"errandId": errand_id, "status": "PUBLISHED"}
            if request_id:
                self.publish_requests[request_id] = data
            return ok(data)

        if path == "/api/errands" and method == "GET":
            if self.flags["empty_square"]:
                return ok({"items": [], "nextCursor": ""})
            if self.flags["fail_square_once"]:
                self.flags["fail_square_once"] = False
                return fail("ERRAND_NOT_FOUND", "任务列表暂时不可用")
            tasks = self.visible_tasks(viewer)
            status = (query.get("status") or [None])[0]
            if status:
                tasks = [task for task in tasks if task["status"] == status]
            if self.flags["next_cursor_once"]:
                self.flags["next_cursor_once"] = False
                return ok({"items": tasks[:2], "nextCursor": "cursor-page-2"})
            cursor = (query.get("cursor") or [""])[0]
            if cursor == "cursor-page-2":
                return ok({"items": tasks[2:], "nextCursor": ""})
            size = int((query.get("size") or ["20"])[0])
            return ok({"items": tasks[:size], "nextCursor": ""})
        if path == "/api/errands/mine" and method == "GET":
            role = (query.get("role") or ["PUBLISHED_BY_ME"])[0]
            if role == "GRABBED_BY_ME":
                tasks = [task for task in self.tasks.values() if task["grabberId"] == str(viewer)]
            else:
                tasks = [task for task in self.tasks.values() if task["publisherId"] == str(viewer)]
            page = int((query.get("page") or ["0"])[0])
            size = int((query.get("size") or ["20"])[0])
            page_tasks = tasks[page * size:(page + 1) * size]
            return ok([self.card(task, viewer) for task in page_tasks])
        if path.startswith("/api/errands/"):
            parts = [part for part in path.split("/") if part]
            errand_id = parts[2]
            task = self.tasks.get(errand_id)
            if task is None:
                return fail("ERRAND_NOT_FOUND", "任务不存在")
            if len(parts) == 3 and method == "GET":
                return ok(self.card(task, viewer))
            if len(parts) == 4 and parts[3] == "timeline" and method == "GET":
                return ok(task["history"])
            if len(parts) == 4 and method == "POST":
                return self._action(task, parts[3], viewer, body)
        if path == "/api/wallet" and method == "GET":
            if self.flags["force_401_wallet_once"]:
                self.flags["force_401_wallet_once"] = False
                return 401, {"code": "UNAUTHORIZED", "message": "access token 过期", "data": None}
            return ok(dict(self.wallets[viewer]))
        if path == "/api/wallet/ledger" and method == "GET":
            cursor = (query.get("cursor") or [""])[0]
            entries = self.ledger.get(viewer, [])
            if cursor:
                return ok({"items": entries[3:], "nextCursor": ""})
            return ok({"items": entries[:3], "nextCursor": "ledger-page-2" if len(entries) > 3 else ""})
        if path == "/api/credit/self" and method == "GET":
            return ok({"score": 82 if viewer != 9001 else 60, "windowDays": 30,
                       "events": self.credit_events.get(viewer, [])})
        if path == "/api/credit/ranking" and method == "GET":
            return ok(self.ranking)
        if path == "/api/notifications" and method == "GET":
            return ok(self.notifications.get(viewer, []))
        if path == "/api/notifications/unread" and method == "GET":
            count = sum(1 for item in self.notifications.get(viewer, []) if not item["read"])
            return ok({"count": count})
        if path.startswith("/api/notifications/") and path.endswith("/read") and method == "POST":
            notification_id = path.split("/")[3]
            for item in self.notifications.get(viewer, []):
                if item["id"] == notification_id:
                    item["read"] = True
                    return ok(None)
            return fail("NOT_FOUND", "消息不存在")
        return fail("NOT_FOUND", f"未模拟的接口: {method} {path}")

    def _action(self, task, action, viewer, body):
        publisher = task["publisherId"] == str(viewer)
        grabber = task["grabberId"] == str(viewer)
        if action == "grab":
            if viewer == 9001:
                return fail("ROLE_NOT_ALLOWED", "仲裁员不能参与交易")
            if publisher:
                return fail("SELF_GRAB_FORBIDDEN", "不能抢自己发布的任务")
            if task["grabberId"] == str(viewer):
                return fail("ALREADY_GRABBED", "你已经抢中该任务")
            if task["status"] != "PUBLISHED":
                return 200, {"code": "SLOT_FULL", "message": "名额已满", "data": {"code": "SLOT_FULL", "grabbed": False}}
            task["status"] = "LOCKED"
            task["grabberId"] = str(viewer)
            task["slotTaken"] = 1
            self.transition(task, "LOCKED", viewer)
            return ok({"code": "OK", "grabbed": True})
        if action == "confirm":
            if not grabber:
                return fail("NOT_CURRENT_GRABBER", "不是当前跑腿")
            if task["status"] == "ACCEPTED":
                return ok(None)
            if task["status"] != "LOCKED":
                return fail("ILLEGAL_STATE_TRANSITION", "当前状态不能确认接单")
            self.transition(task, "ACCEPTED", viewer)
            return ok(None)
        if action == "pickup":
            if not grabber or task["status"] != "ACCEPTED":
                return fail("ILLEGAL_STATE_TRANSITION", "当前状态不能取货")
            self.transition(task, "PICKED_UP", viewer)
            return ok(None)
        if action == "deliver":
            if not grabber or task["status"] != "PICKED_UP":
                return fail("ILLEGAL_STATE_TRANSITION", "当前状态不能送达")
            self.transition(task, "DELIVERED", viewer)
            return ok(None)
        if action == "settle":
            if not publisher:
                return fail("NOT_PUBLISHER", "只有发单人能确认完成")
            if task["status"] == "SETTLED":
                return ok({"result": "ALREADY_SETTLED"})
            if task["status"] != "DELIVERED":
                return fail("SETTLE_CONFLICT", "当前状态不能结算")
            self.transition(task, "SETTLED", viewer)
            amount = task["rewardCents"]
            commission = amount * 5 // 100
            runner = int(task["grabberId"])
            self.wallets[runner]["availableCents"] += amount - commission
            self.add_ledger(runner, "CREDIT", amount - commission, "SETTLE", task["id"])
            self.add_ledger(9000, "CREDIT", commission, "SETTLE", task["id"], "COMMISSION")
            self.notify(runner, task["id"], "SETTLED", f"任务已完成结算，金额 {amount} 分")
            self.credit_events.setdefault(runner, []).insert(0, {
                "type": "SETTLE", "description": "完成结算", "delta": 2,
                "refId": task["id"], "time": BASE_TIME,
            })
            return ok({"result": "SETTLED"})
        if action == "cancel":
            if not publisher:
                return fail("NOT_PUBLISHER", "只有发单人能取消")
            if task["status"] in ("CANCELLED", "REFUNDED"):
                return ok({"result": "ALREADY_REFUNDED"})
            if task["status"] != "PUBLISHED":
                return fail("REFUND_CONFLICT", "当前状态不能取消")
            self.transition(task, "CANCELLED", viewer)
            self.wallets[viewer]["availableCents"] += task["rewardCents"]
            self.add_ledger(viewer, "CREDIT", task["rewardCents"], "REFUND", task["id"])
            return ok({"result": "REFUNDED"})
        if action == "dispute":
            if not (publisher or grabber):
                return fail("FORBIDDEN", "只有当事人可以发起争议")
            if task["status"] not in ("ACCEPTED", "PICKED_UP", "DELIVERED"):
                return fail("ILLEGAL_STATE_TRANSITION", "当前状态不能发起争议")
            self.transition(task, "DISPUTED", viewer)
            return ok(None)
        if action == "arbitrate":
            if viewer != 9001:
                return fail("ROLE_NOT_ALLOWED", "只有仲裁员可以裁决")
            favor = (body or {}).get("favor")
            if favor not in ("RUNNER", "PUBLISHER"):
                return fail("INVALID_ARGUMENT", "favor 必须是 RUNNER 或 PUBLISHER")
            if task["status"] != "DISPUTED":
                return fail("SETTLE_CONFLICT", "任务不在争议状态")
            runner = int(task["grabberId"])
            if favor == "RUNNER":
                self.transition(task, "SETTLED", viewer)
                amount = task["rewardCents"]
                commission = amount * 5 // 100
                self.wallets[runner]["availableCents"] += amount - commission
                self.add_ledger(runner, "CREDIT", amount - commission, "SETTLE", task["id"])
                return ok({"result": "SETTLED_TO_RUNNER"})
            self.transition(task, "REFUNDED", viewer)
            self.wallets[int(task["publisherId"])]["availableCents"] += task["rewardCents"]
            self.add_ledger(int(task["publisherId"]), "CREDIT", task["rewardCents"], "REFUND", task["id"])
            return ok({"result": "REFUNDED_TO_PUBLISHER"})
        return fail("NOT_FOUND", f"未模拟的动作: {action}")


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def start_static_server(dist: Path):
    dist = Path(dist).resolve()
    index = dist / "index.html"
    if not index.exists():
        raise SystemExit(f"dist/index.html 不存在：{index}；请先运行 npm run build")
    handler = partial(QuietHandler, directory=str(dist))
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


def attach_mock(context, state, ws_broken=False):
    """Register the mock API and a deterministic WebSocket substitute on a context."""
    context.add_init_script(WS_BROKEN if ws_broken else WS_STUB)
    context.route("**/api/**", lambda route, request: _fulfill(route, request, state))
    return state


def _fulfill(route, request, state):
    parsed = urlparse(request.url)
    query = parse_qs(parsed.query)
    headers = {key.lower(): value for key, value in request.headers.items()}
    body = None
    if request.post_data:
        try:
            body = json.loads(request.post_data)
        except json.JSONDecodeError:
            body = None
    key = state.hold_key(parsed.path)
    if key and state.holds.get(key):
        # Compute the response now, but let the test release it later.
        response = state._dispatch(request.method, parsed.path, query, headers, body)
        if response is not None:
            state.holds[key] = False
            state.held[key] = response
            state.pending_route[key] = route
            return
    result = state.handle(request.method, parsed.path, query, headers, body)
    if result is None:
        return
    status, payload = result
    route.fulfill(
        status=status,
        content_type="application/json; charset=utf-8",
        body=json.dumps(payload, ensure_ascii=False),
    )


# The hold bookkeeping lives on the state instance to keep `_fulfill` small.
MockState.pending_route = {}


def new_page(browser, base_url, state=None, viewport=None, ws_broken=False):
    state = state or MockState()
    context = browser.new_context(
        viewport=viewport or DEFAULT_VIEWPORT,
        locale="zh-CN",
        timezone_id="Asia/Shanghai",
        device_scale_factor=1,
    )
    attach_mock(context, state, ws_broken=ws_broken)
    page = context.new_page()
    page.goto(f"{base_url}/#/square", wait_until="load")
    page.wait_for_selector('[data-testid="login-form"], main#main-content', timeout=8000)
    return context, page, state


def login(page, base_url, user_id, password=None):
    """Click through the demo login form (idempotent when already logged in)."""
    password = password or DEMO_PASSWORDS[user_id]
    if page.locator('[data-testid="login-form"]').count() == 0:
        # Already logged in with another identity: sign out first.
        logout(page)
    page.locator(f'[data-testid="identity-{user_id}"]').click()
    page.locator('input#demo-password').fill(password)
    page.locator('[data-testid="login-submit"]').click()
    page.wait_for_selector("main#main-content", timeout=8000)


def logout(page):
    button = page.locator('button[aria-label="退出登录"]')
    if button.count() == 0:
        return
    button.first.click()
    page.wait_for_selector('[data-testid="login-form"]', timeout=8000)


def goto_hash(page, base_url, path):
    page.goto(f"{base_url}/#{path}", wait_until="load")
    page.wait_for_selector('main#main-content, [data-testid="login-form"]', timeout=8000)
