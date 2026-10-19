# -*- coding: utf-8 -*-
"""PeerGrab 真实浏览器验收：4 个演示身份走完整业务链路。

业务写入全部由 UI 点击产生；API 只做只读核对（状态/余额）。需要：
  - 后端 API（默认 8080）与 worker 已启动，演示登录可用；
  - 前端 Vite dev server（默认 5173）代理 /api 与 /ws；
  - 可丢弃的隔离栈（会创建任务、改变演示账户余额）。

用法：
  python3 tests/live_smoke.py --base http://127.0.0.1:5173 --api-base http://127.0.0.1:8080
"""

from __future__ import annotations

import argparse
import json
import os
import time
import urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path

from playwright.sync_api import sync_playwright

PASSWORDS = {
    1001: os.environ.get("PEERGRAB_LIVE_PASSWORD_1001", "demo1001"),
    2001: os.environ.get("PEERGRAB_LIVE_PASSWORD_2001", "demo2001"),
    2002: os.environ.get("PEERGRAB_LIVE_PASSWORD_2002", "demo2002"),
    9001: os.environ.get("PEERGRAB_LIVE_PASSWORD_9001", "demo9001"),
}

RESULTS = []


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def api_call(api_base, method, path, token=None, body=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(api_base.rstrip("/") + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8") or "{}")


def api_token(api_base, user_id):
    payload = api_call(api_base, "POST", "/api/auth/login",
                       body={"userId": user_id, "password": PASSWORDS[user_id]})
    expect(payload.get("code") == "OK", f"API 登录失败: {payload}")
    return payload["data"]["token"]


def read_detail(api_base, token, errand_id):
    payload = api_call(api_base, "GET", f"/api/errands/{errand_id}", token=token)
    expect(payload.get("code") == "OK", payload)
    return payload["data"]


def read_wallet(api_base, token):
    payload = api_call(api_base, "GET", "/api/wallet", token=token)
    expect(payload.get("code") == "OK", payload)
    return payload["data"]["availableCents"]


def goto(page, base, path):
    page.goto(f"{base.rstrip('/')}/#{path}", wait_until="load")
    page.wait_for_selector("main#main-content, [data-testid=\"login-form\"]", timeout=10000)


def login(page, base, user_id):
    goto(page, base, "/square")
    if page.locator('[data-testid="login-form"]').count():
        page.locator(f'[data-testid="identity-{user_id}"]').click()
        page.locator("input#demo-password").fill(PASSWORDS[user_id])
        page.locator('[data-testid="login-submit"]').click()
    page.wait_for_selector('[data-testid="nav-square"]', state="attached", timeout=12000)


def logout(page, base):
    page.locator('button[aria-label="退出登录"]').first.click()
    page.wait_for_selector('[data-testid="login-form"]', timeout=10000)


def wait_toast(page, contains, timeout=15000):
    """等待"某一类"toast，而不是第一条：上一个动作的 toast 可能还没消失。"""
    import time as _time
    deadline = _time.time() + timeout / 1000
    last = ""
    while _time.time() < deadline:
        for toast_locator in page.locator('[data-testid="toast"]').all():
            text = toast_locator.inner_text()
            last = text
            if contains in text:
                return text
        page.wait_for_timeout(150)
    raise AssertionError(f"toast 期望包含 {contains!r}，最后看到 {last!r}")


def click_action(page, action, toast_contains, timeout=15000):
    page.wait_for_selector(f'[data-testid="action-{action}"]', timeout=timeout)
    page.locator(f'[data-testid="action-{action}"]').first.click()
    return wait_toast(page, toast_contains)


def open_publish(page, base):
    goto(page, base, "/publish")
    page.wait_for_selector('[data-testid="publish-page"]')


def publish_via_ui(page, base, reward="12.34"):
    open_publish(page, base)
    title = f"验收-{datetime.now().strftime('%H%M%S')}-{os.getpid()}"
    page.locator("#publish-title").fill(title)
    page.locator("[data-testid=publish-reward]").fill(reward)
    page.locator("[data-testid=publish-submit]").click()
    wait_toast(page, "任务已发布并完成资金托管")
    page.wait_for_selector('.publish-result a[href^="#/detail/"]', timeout=10000)
    href = page.locator('.publish-result a[href^="#/detail/"]').first.get_attribute("href")
    errand_id = href.split("/")[-1]
    return errand_id, title


def open_detail(page, base, errand_id):
    goto(page, base, f"/detail/{errand_id}")
    page.wait_for_selector('[data-testid="detail-page"]')
    page.wait_for_selector(".detail-summary, .detail-intro", timeout=10000)


def detail_status(page):
    page.wait_for_selector(".detail-intro .status", timeout=10000)
    return page.locator(".detail-intro .status").first.inner_text().strip()


def check(name, func):
    try:
        detail = func() or ""
        RESULTS.append({"name": name, "ok": True, "detail": str(detail)})
        print(f"PASS {name} {detail}")
    except Exception as exc:  # noqa: BLE001
        RESULTS.append({"name": name, "ok": False, "detail": f"{type(exc).__name__}: {exc}"})
        print(f"FAIL {name}: {type(exc).__name__}: {exc}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:5173")
    parser.add_argument("--api-base", default="http://127.0.0.1:8080")
    parser.add_argument("--output", default=str(Path(__file__).resolve().parents[1] / "verification" / "local-live"))
    args = parser.parse_args()

    try:
        with urllib.request.urlopen(args.api_base.rstrip("/") + "/api/health", timeout=5) as response:
            expect(response.status == 200, "API 健康检查失败")
        with urllib.request.urlopen(args.base.rstrip("/") + "/", timeout=5) as response:
            expect(response.status == 200, "前端不可达")
    except Exception as exc:  # noqa: BLE001
        print(f"真实栈不可达（base={args.base}, api={args.api_base}）：{exc}")
        return 2

    state = {}
    console_errors = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN",
                                      timezone_id="Asia/Shanghai")
        page = context.new_page()
        page.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)

        def publisher_publishes():
            login(page, args.base, 1001)
            page.wait_for_selector('[data-testid="square-page"]', timeout=12000)
            expect(page.locator('[data-testid="square-page"]').count() == 1, "登录后应进入广场")
            expect("¥" in page.locator("body").inner_text(), "页面应显示金额")
            errand_id, title = publish_via_ui(page, args.base)
            state["errand_id"] = errand_id
            state["title"] = title
            return errand_id

        def runner_completes_delivery():
            errand_id = state["errand_id"]
            logout(page, args.base)
            login(page, args.base, 2001)
            open_detail(page, args.base, errand_id)
            expect("验收-" in page.locator("h1").first.inner_text(), "详情标题应为刚发布的任务")
            click_action(page, "grab", "抢单成功")
            click_action(page, "confirm", "确认接单成功")
            click_action(page, "pickup", "取货成功")
            click_action(page, "deliver", "送达成功")
            expect(detail_status(page) == "待确认完成", detail_status(page))
            state["runner_token"] = api_token(args.api_base, 2001)
            expect(read_detail(args.api_base, state["runner_token"], errand_id)["status"] == "DELIVERED",
                   "API 侧状态应为 DELIVERED")
            return "delivered"

        def publisher_settles():
            errand_id = state["errand_id"]
            logout(page, args.base)
            login(page, args.base, 1001)
            publisher_token = api_token(args.api_base, 1001)
            before = read_wallet(args.api_base, publisher_token)
            open_detail(page, args.base, errand_id)
            click_action(page, "settle", "确认完成（结算）成功")
            expect(detail_status(page) == "已结算", detail_status(page))
            after = read_wallet(args.api_base, publisher_token)
            expect(before == after, f"结算不应再扣发单人余额：{before}->{after}")
            card = read_detail(args.api_base, publisher_token, errand_id)
            expect(card["status"] == "SETTLED", f"API 状态应为 SETTLED，实际 {card['status']}")
            state["publisher_token"] = publisher_token
            return "settled"

        def wallet_and_timeline_show_settlement():
            errand_id = state["errand_id"]
            goto(page, args.base, "/wallet")
            page.wait_for_selector('[data-testid="wallet-page"]')
            page.wait_for_selector(".ledger-table", timeout=10000)
            wallet_text = page.locator("main#main-content").inner_text()
            expect("结算" in wallet_text, "钱包流水应包含结算记录")
            open_detail(page, args.base, errand_id)
            page.wait_for_selector(".detail-timeline__list", timeout=10000)
            expect("已结算" in page.locator(".detail-timeline__list").inner_text(), "时间线应包含已结算")
            return "wallet+timeline ok"

        def runner_notifications_received():
            logout(page, args.base)
            login(page, args.base, 2001)
            goto(page, args.base, "/notifications")
            page.wait_for_selector('[data-testid="notifications-page"]')
            # 资金事件经 MQ 消费后落库，给异步链路最多 20s。
            page.wait_for_selector(".notice-item", timeout=20000)
            expect(page.locator(".notice-item").count() >= 1, "跑腿应收到结算通知")
            return f"notifications={page.locator('.notice-item').count()}"

        def dispute_and_arbitration():
            logout(page, args.base)
            login(page, args.base, 1001)
            errand_id, _ = publish_via_ui(page, args.base, reward="15")
            logout(page, args.base)
            login(page, args.base, 2001)
            open_detail(page, args.base, errand_id)
            click_action(page, "grab", "抢单成功")
            click_action(page, "confirm", "确认接单成功")
            click_action(page, "dispute", "发起争议成功")
            logout(page, args.base)
            login(page, args.base, 9001)
            open_detail(page, args.base, errand_id)
            page.locator('[data-testid="action-arbitrate"]').click()
            page.wait_for_selector(".arbitrate-zone")
            page.get_by_role("button", name="支持发单人 · 全额退款").click()
            wait_toast(page, "仲裁完成")
            expect(detail_status(page) == "已退款", detail_status(page))
            arbitrator_token = api_token(args.api_base, 9001)
            expect(read_detail(args.api_base, arbitrator_token, errand_id)["status"] == "REFUNDED",
                   "API 状态应为 REFUNDED")
            state["arbitrated_id"] = errand_id
            return "arbitrated to publisher"

        def mobile_detail_no_overflow():
            page.set_viewport_size({"width": 390, "height": 844})
            login(page, args.base, 2001)
            open_detail(page, args.base, state["errand_id"])
            overflow = page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth")
            expect(overflow <= 1, f"390px 详情横向溢出 {overflow}px")
            page.set_viewport_size({"width": 1440, "height": 900})
            return "no overflow"

        def console_errors_are_clean():
            ignored = ("favicon", "Failed to load resource", "WebSocket")
            unexpected = [text for text in console_errors if not any(part in text for part in ignored)]
            expect(len(unexpected) <= 2, f"console error 过多: {unexpected[:5]}")
            return f"console_errors={len(unexpected)}"

        check("publisher_publishes_via_ui", publisher_publishes)
        check("runner_completes_delivery_via_ui", runner_completes_delivery)
        check("publisher_settles_via_ui", publisher_settles)
        check("wallet_and_timeline_show_settlement", wallet_and_timeline_show_settlement)
        check("runner_notifications_received", runner_notifications_received)
        check("dispute_and_arbitration_via_ui", dispute_and_arbitration)
        check("mobile_detail_no_overflow", mobile_detail_no_overflow)
        check("console_errors_are_clean", console_errors_are_clean)

        browser.close()

    summary = {
        "total": len(RESULTS),
        "passed": sum(1 for result in RESULTS if result["ok"]),
        "failed": sum(1 for result in RESULTS if not result["ok"]),
    }
    report = {
        "suite": "live-ui",
        "generatedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
        "base": args.base,
        "apiBase": args.api_base,
        "results": RESULTS,
        "summary": summary,
    }
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / "ui-live-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    print(f"report: {report_path}")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
