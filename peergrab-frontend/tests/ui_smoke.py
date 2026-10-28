# -*- coding: utf-8 -*-
"""PeerGrab 前端 mock 验收：用确定性 mock API + 真实浏览器回归 UI 契约。

不依赖后端、数据库或 MQ；默认直接对 `dist/` 起静态服务器，
因此 CI 只需要 `npm run build` + Playwright。真实后端链路见 `live_smoke.py`。

用法：
  python3 tests/ui_smoke.py --dist dist --output verification/local-ui
"""

from __future__ import annotations

import argparse
import json
import traceback
from datetime import datetime, timezone, timedelta
from pathlib import Path

from playwright.sync_api import sync_playwright

import mock_api

TESTS_DIR = Path(__file__).resolve().parent
DEFAULT_DIST = TESTS_DIR.parent / "dist"

RESULTS = []


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def text_of(page, selector, timeout=6000):
    page.wait_for_selector(selector, timeout=timeout)
    return page.locator(selector).first.inner_text().strip()


def body_text(page):
    return page.locator("body").inner_text()


def pending_grab_keys(page):
    return page.evaluate(
        "Object.keys(sessionStorage).filter((key) => key.startsWith('peergrab_grab_request'))"
    )


def first_grab_card(page):
    page.wait_for_selector('[data-testid="errand-card"]', timeout=8000)
    return page.locator('[data-testid="errand-card"]').filter(
        has=page.locator('[data-testid="action-grab"]')
    ).first


def wait_toast(page, contains=None, timeout=8000):
    if contains:
        # 同时可能有多条 toast（旧的一条还没消失），要扫描全部而不是只看第一条。
        page.wait_for_function(
            """(needle) => Array.from(document.querySelectorAll('[data-testid="toast"]'))
                .some((node) => node.textContent.includes(needle))""",
            arg=contains,
            timeout=timeout,
        )
        return contains
    page.wait_for_selector('[data-testid="toast"]', timeout=timeout)
    return page.locator('[data-testid="toast"]').last.inner_text()


def open_app(browser, base, user=1001, viewport=None, ws_broken=False, configure=None):
    state = mock_api.MockState()
    if configure:
        configure(state)
    context, page, state = mock_api.new_page(
        browser, base, state, viewport=viewport, ws_broken=ws_broken
    )
    if user is not None:
        mock_api.login(page, base, user)
    return context, page, state


CASES = []


def case(fn):
    CASES.append(fn)
    return fn


@case
def login_page_renders(browser, base):
    context, page, _ = open_app(browser, base, user=None)
    try:
        expect(page.locator('[data-testid="login-form"]').count() == 1, "登录表单应存在")
        expect(page.locator('[data-testid^="identity-"]').count() == 4, "应有 4 个演示身份")
        expect("PeerGrab" in page.locator("body").inner_text(), "登录页应展示品牌")
    finally:
        context.close()


@case
def login_rejects_wrong_password(browser, base):
    context, page, state = open_app(
        browser, base, user=None, configure=lambda s: s.flags.__setitem__("login_fail_once", True)
    )
    try:
        page.locator('[data-testid="identity-1001"]').click()
        page.locator("input#demo-password").fill("wrong-password")
        page.locator('[data-testid="login-submit"]').click()
        page.wait_for_selector('[role="alert"]', timeout=6000)
        expect("口令" in page.locator('[role="alert"]').first.inner_text(), "错误口令应显示提示")
        expect(page.locator('[data-testid="login-form"]').count() == 1, "失败后仍停留在登录页")
    finally:
        context.close()


@case
def publisher_square_and_nav(browser, base):
    context, page, _ = open_app(browser, base, 1001)
    try:
        expect(page.locator('[data-testid="square-page"]').count() == 1, "应进入任务广场")
        page.wait_for_selector('[data-testid="errand-card"]', timeout=8000)
        cards = page.locator('[data-testid="errand-card"]')
        expect(cards.count() >= 5, f"广场应有至少 5 张任务卡，实际 {cards.count()}")
        expect("¥936.00" in body_text(page), "顶栏应显示发单人余额 ¥936.00")
        expect(page.locator('[data-testid="nav-square"]').get_attribute("aria-current") == "page", "广场应为当前导航")
    finally:
        context.close()


@case
def runner_identity_balance(browser, base):
    context, page, _ = open_app(browser, base, 2001)
    try:
        expect("¥123.45" in body_text(page), "跑腿 2001 余额应为 ¥123.45")
        expect(page.locator('[data-testid="nav-square"]').count() == 1, "跑腿应看到广场导航")
    finally:
        context.close()


@case
def arbitrator_cannot_grab_or_publish(browser, base):
    context, page, _ = open_app(browser, base, 9001)
    try:
        expect(page.locator('[data-testid="nav-publish"]').count() == 0, "仲裁员不应看到发布入口")
        expect(page.locator('[data-testid="action-grab"]').count() == 0, "仲裁员不应看到抢单按钮")
        expect("¥0.00" in body_text(page), "仲裁员零余额账户应展示 ¥0.00")
    finally:
        context.close()


@case
def square_search_filters(browser, base):
    context, page, _ = open_app(browser, base, 1001)
    try:
        page.wait_for_selector('[data-testid="errand-card"]', timeout=8000)
        before = page.locator('[data-testid="errand-card"]').count()
        page.locator('[data-testid="square-search"]').fill("奶茶")
        page.wait_for_timeout(150)
        after = page.locator('[data-testid="errand-card"]').count()
        expect(after == 1, f"搜索“奶茶”应只剩 1 张卡，实际 {after}")
        expect(before > after, "搜索应减少卡片数量")
        page.locator('[data-testid="square-search"]').fill("")
        page.wait_for_timeout(150)
        expect(page.locator('[data-testid="errand-card"]').count() == before, "清空搜索应恢复列表")
    finally:
        context.close()


@case
def square_available_filter(browser, base):
    context, page, _ = open_app(browser, base, 2001)
    try:
        page.wait_for_selector('[data-testid="errand-card"]', timeout=8000)
        page.get_by_role("button", name="我可接单").click()
        page.wait_for_timeout(150)
        cards = page.locator('[data-testid="errand-card"]')
        expect(cards.count() >= 5, f"跑腿“我可接单”应有任务，实际 {cards.count()}")
        expect(page.locator('[data-testid="action-grab"]').count() >= 5, "可接单任务应显示抢单按钮")
    finally:
        context.close()


@case
def square_pagination(browser, base):
    context, page, _ = open_app(
        browser, base, 1001, configure=lambda s: s.flags.__setitem__("next_cursor_once", True)
    )
    try:
        page.wait_for_selector('[data-testid="errand-card"]')
        first_page = page.locator('[data-testid="errand-card"]').count()
        expect(first_page == 2, f"第一页应为 2 张卡，实际 {first_page}")
        page.get_by_role("button", name="加载更多任务").click()
        page.wait_for_function(
            "document.querySelectorAll('[data-testid=\"errand-card\"]').length >= 5", timeout=6000
        )
        expect(page.locator('[data-testid="errand-card"]').count() >= 5, "加载更多后卡片应增加")
    finally:
        context.close()


@case
def square_empty_state(browser, base):
    context, page, _ = open_app(
        browser, base, 1001, configure=lambda s: s.flags.__setitem__("empty_square", True)
    )
    try:
        page.wait_for_selector("text=暂时没有开放任务", timeout=6000)
        expect(page.get_by_role("link", name="发布任务").count() >= 1, "空态应提供发布入口")
    finally:
        context.close()


@case
def square_error_retry(browser, base):
    context, page, _ = open_app(
        browser, base, 1001, configure=lambda s: s.flags.__setitem__("fail_square_once", True)
    )
    try:
        page.wait_for_selector("text=任务加载失败", timeout=6000)
        page.get_by_role("button", name="重新加载").click()
        page.wait_for_selector('[data-testid="errand-card"]', timeout=6000)
        expect(page.locator('[data-testid="errand-card"]').count() >= 5, "重试后应恢复列表")
    finally:
        context.close()


@case
def square_skeleton_then_cards(browser, base):
    state = mock_api.MockState()
    state.holds["list"] = True
    context, page, state = mock_api.new_page(browser, base, state)
    try:
        mock_api.login(page, base, 1001)
        page.wait_for_selector('[data-testid="skeleton"]', timeout=6000)
        expect(page.locator('[data-testid="errand-card"]').count() == 0, "加载中不应已有卡片")
        expect(state.release("list"), "应能释放被挂起的列表响应")
        page.wait_for_selector('[data-testid="errand-card"]', timeout=6000)
    finally:
        context.close()


@case
def publish_amount_validation(browser, base):
    context, page, _ = open_app(browser, base, 1001)
    try:
        mock_api.goto_hash(page, base, "/publish")
        page.locator("#publish-title").fill("帮我带一杯咖啡")
        page.locator("[data-testid=publish-reward]").fill("1.005")
        page.wait_for_timeout(120)
        expect("两位小数" in body_text(page), "超过两位小数应给出明确提示")
        expect(page.locator("[data-testid=publish-submit]").is_disabled(), "非法金额应禁用提交")
        page.locator("[data-testid=publish-reward]").fill("0")
        page.wait_for_timeout(120)
        expect("必须大于 0" in body_text(page), "0 元应给出提示")
        page.locator("[data-testid=publish-reward]").fill("15.5")
        page.wait_for_timeout(120)
        expect("¥15.50" in body_text(page), "预览应显示 ¥15.50")
    finally:
        context.close()


@case
def publish_success_updates_wallet(browser, base):
    context, page, state = open_app(browser, base, 1001)
    try:
        mock_api.goto_hash(page, base, "/publish")
        page.locator("#publish-title").fill("帮我打印课堂讲义")
        page.locator("[data-testid=publish-reward]").fill("12.34")
        page.locator("[data-testid=publish-submit]").click()
        wait_toast(page, "任务已发布并完成资金托管")
        expect(state.last_publish is not None, "mock 应收到发布请求")
        expect(state.last_publish["rewardCents"] == 1234, f"请求分为 {state.last_publish['rewardCents']}")
        mock_api.goto_hash(page, base, "/wallet")
        page.wait_for_selector('[data-testid="wallet-page"]')
        expect("¥923.66" in body_text(page), "发布后余额应为 936.00 - 12.34 = ¥923.66")
    finally:
        context.close()


@case
def grab_confirm_flow(browser, base):
    context, page, _ = open_app(browser, base, 2001)
    try:
        page.wait_for_selector('[data-testid="errand-card"]', timeout=8000)
        card = page.locator('[data-testid="errand-card"]').filter(
            has=page.locator('[data-testid="action-grab"]')
        ).first
        card.locator('[data-testid="action-grab"]').click()
        wait_toast(page, "抢单成功")
        page.wait_for_selector("text=待确认", timeout=6000)
        expect(page.locator('[data-testid="action-confirm"]').count() >= 1, "抢中后应出现确认接单按钮")
    finally:
        context.close()


@case
def grab_v2_persists_request_id_before_post(browser, base):
    state = mock_api.MockState()
    state.holds["grab"] = True
    context, page, state = mock_api.new_page(browser, base, state)
    try:
        mock_api.login(page, base, 2001)
        first_grab_card(page).locator('[data-testid="action-grab"]').click()
        # POST 已被 mock 挂起：请求键必须在此之前落盘，刷新/重试才能用同一 ID 恢复。
        page.wait_for_function(
            "Object.keys(sessionStorage).some((key) => key.startsWith('peergrab_grab_request'))",
            timeout=5000,
        )
        expect(state.release("grab"), "应能释放被挂起的 /grab-v2 响应")
        wait_toast(page, "抢单成功")
        page.wait_for_selector("text=待确认", timeout=6000)
        expect(not pending_grab_keys(page), "终态后应清理持久请求键")
    finally:
        context.close()


@case
def grab_v2_processing_polls_same_request(browser, base):
    context, page, state = open_app(
        browser, base, 2001,
        configure=lambda s: s.flags.__setitem__("grab_processing_polls", 2),
    )
    try:
        first_grab_card(page).locator('[data-testid="action-grab"]').click()
        wait_toast(page, "抢单成功")
        page.wait_for_selector("text=待确认", timeout=6000)
        rows = list(state.grab_requests.values())
        expect(len(rows) == 1, f"202 轮询应只登记 1 个请求，实际 {len(rows)}")
        expect(rows[0]["status"] == "SUCCEEDED", f"请求应收敛为 SUCCEEDED，实际 {rows[0]['status']}")
        expect(not pending_grab_keys(page), "终态后应清理持久请求键")
    finally:
        context.close()


@case
def grab_v2_recovers_from_gateway_error(browser, base):
    context, page, state = open_app(
        browser, base, 2001,
        configure=lambda s: s.flags.__setitem__("grab_gateway_error_once", True),
    )
    try:
        first_grab_card(page).locator('[data-testid="action-grab"]').click()
        wait_toast(page, "抢单成功")
        page.wait_for_selector("text=待确认", timeout=6000)
        expect(len(state.grab_requests) == 1, f"补发后应只有 1 条登记，实际 {len(state.grab_requests)}")
    finally:
        context.close()


@case
def grab_v2_transient_429_keeps_request_id(browser, base):
    context, page, state = open_app(
        browser, base, 2001,
        configure=lambda s: s.flags.__setitem__("grab_rate_limited_once", True),
    )
    try:
        first_grab_card(page).locator('[data-testid="action-grab"]').click()
        wait_toast(page, "当前抢单请求过多")
        expect(pending_grab_keys(page), "登记前的瞬时 429 应保留持久请求键")
        first_id = state.grab_attempts[0]["requestId"]
        expect(state.grab_attempts[0]["expectedRound"] == 0, "POST 必须携带 expectedRound")
        first_grab_card(page).locator('[data-testid="action-grab"]').click()
        wait_toast(page, "抢单成功")
        expect(len(state.grab_attempts) == 2, f"第二次应重试一次，实际 {len(state.grab_attempts)} 次")
        expect(state.grab_attempts[1]["requestId"] == first_id, "瞬时失败重试必须复用同一 requestId")
        expect(not pending_grab_keys(page), "终态后应清理持久请求键")
    finally:
        context.close()


@case
def grab_v2_slot_full_then_new_request_succeeds(browser, base):
    context, page, state = open_app(
        browser, base, 2001,
        configure=lambda s: s.flags.__setitem__("grab_slot_full_once", True),
    )
    try:
        first_grab_card(page).locator('[data-testid="action-grab"]').click()
        wait_toast(page, "名额已满")
        page.wait_for_timeout(300)
        expect(not pending_grab_keys(page), "确定失败后应按契约清理旧请求键")
        first_keys = set(state.grab_requests.keys())
        expect(len(first_keys) == 1, f"应登记 1 条失败请求，实际 {len(first_keys)}")
        # 契约：确定失败后由用户换新 requestId 重新发起
        first_grab_card(page).locator('[data-testid="action-grab"]').click()
        wait_toast(page, "抢单成功")
        second_keys = set(state.grab_requests.keys())
        # 旧请求行作为历史保留，关键是第二次抢单登记了一个全新的 requestId。
        expect(len(second_keys - first_keys) == 1, "第二次抢单应登记新的 requestId")
    finally:
        context.close()


@case
def grab_v2_replay_is_idempotent(browser, base):
    context, page, _ = open_app(browser, base, 2001)
    try:
        result = page.evaluate(
            """async () => {
              const token = localStorage.getItem('peergrab_token');
              const requestId = '00000000-0000-4000-8000-000000000001';
              const url = '/api/errands/215731113107132416/grab-v2';
              const init = {
                method: 'POST',
                headers: {
                  Authorization: 'Bearer ' + token,
                  'Content-Type': 'application/json',
                  'X-Request-Id': requestId,
                },
                body: JSON.stringify({ expectedRound: 0 }),
              };
              const first = await (await fetch(url, init)).json();
              const second = await (await fetch(url, init)).json();
              const card = await (await fetch('/api/errands/215731113107132416', {
                headers: { Authorization: 'Bearer ' + token },
              })).json();
              return { first, second, slotTaken: card.data.slotTaken };
            }"""
        )
        expect(result["first"]["code"] == "OK", f"首次抢单应成功：{result}")
        expect(result["second"]["code"] == "OK", f"重放应返回同一成功：{result}")
        expect(result["second"]["data"]["replayed"] is True, f"重放应标记 replayed：{result}")
        expect(
            result["first"]["data"]["requestId"] == result["second"]["data"]["requestId"],
            f"重放 requestId 应一致：{result}",
        )
        expect(result["slotTaken"] == 1, f"重放不应二次占名额：{result}")
    finally:
        context.close()


@case
def detail_recovers_pending_grab_request(browser, base):
    state = mock_api.MockState()
    request_id = "00000000-0000-4000-8000-000000000002"
    errand_id = "215731113107132416"
    state.register_grab(2001, errand_id, request_id, 0, "PROCESSING",
                        "REQUEST_PROCESSING", 202, pending_polls=1)
    context, page, state = mock_api.new_page(browser, base, state)
    try:
        mock_api.login(page, base, 2001)
        # 模拟"上次抢单 202 后刷新"：持久键还在，详情页应主动用 GET 恢复。
        page.evaluate(
            """([key, value]) => sessionStorage.setItem(key, value)""",
            [f"peergrab_grab_request:2001:{errand_id}",
             json.dumps({"requestId": request_id, "round": 0})],
        )
        mock_api.goto_hash(page, base, f"/detail/{errand_id}")
        wait_toast(page, "上次抢单已成功")
        page.wait_for_selector('[data-testid="action-confirm"]', timeout=6000)
    finally:
        context.close()


@case
def detail_publisher_settle(browser, base):
    context, page, _ = open_app(browser, base, 1001)
    try:
        mock_api.goto_hash(page, base, "/detail/215731113107132421")
        page.wait_for_selector('[data-testid="detail-page"]')
        expect("还回图书馆" in page.locator("h1").first.inner_text(), "详情标题应正确")
        page.locator('[data-testid="action-settle"]').click()
        wait_toast(page, "确认完成（结算）成功")
        page.wait_for_selector("text=已结算", timeout=6000)
    finally:
        context.close()


@case
def detail_runner_actions(browser, base):
    context, page, _ = open_app(browser, base, 2001)
    try:
        mock_api.goto_hash(page, base, "/detail/215731113107132421")
        page.wait_for_selector('[data-testid="detail-page"]')
        expect(page.locator('[data-testid="action-settle"]').count() == 0, "跑腿不应看到结算按钮")
        expect(page.locator('[data-testid="action-dispute"]').count() == 1, "跑腿应能看到争议按钮")
    finally:
        context.close()


@case
def detail_arbitration(browser, base):
    context, page, _ = open_app(browser, base, 9001)
    try:
        mock_api.goto_hash(page, base, "/detail/215731113107132422")
        page.wait_for_selector('[data-testid="detail-page"]')
        page.locator('[data-testid="action-arbitrate"]').click()
        page.get_by_role("button", name="支持跑腿 · 结算悬赏").click()
        wait_toast(page, "仲裁完成")
        page.wait_for_selector("text=已结算", timeout=6000)
    finally:
        context.close()


@case
def detail_late_response_guard(browser, base):
    state = mock_api.MockState()
    state.holds["detail"] = True
    context, page, state = mock_api.new_page(browser, base, state)
    try:
        mock_api.login(page, base, 1001)
        mock_api.goto_hash(page, base, "/detail/215731113107132421")  # held
        page.wait_for_selector('[data-testid="skeleton"]', timeout=6000)
        mock_api.goto_hash(page, base, "/detail/215731113107132416")
        page.wait_for_function(
            "document.querySelector('h1') && document.querySelector('h1').textContent.includes('取一个快递')",
            timeout=6000,
        )
        expect(state.release("detail"), "应能释放被挂起的详情响应")
        page.wait_for_timeout(300)
        title = page.locator("h1").first.inner_text()
        expect("还回图书馆" not in title, f"迟到响应覆盖了当前详情：{title!r}")
    finally:
        context.close()


@case
def wallet_page_and_pagination(browser, base):
    context, page, _ = open_app(browser, base, 1001)
    try:
        mock_api.goto_hash(page, base, "/wallet")
        page.wait_for_selector('[data-testid="wallet-page"]')
        expect("¥936.00" in body_text(page), "钱包应显示可用余额")
        rows = page.locator(".ledger-table tbody tr")
        page.wait_for_selector(".ledger-table tbody tr")
        expect(rows.count() >= 2, f"流水应至少有 2 行，实际 {rows.count()}")
        expect("平台佣金户" in body_text(page), "流水应标注平台账户类型")
        page.get_by_role("button", name="加载更多流水").click()
        page.wait_for_timeout(200)
        expect(page.locator(".ledger-table tbody tr").count() >= 3, "加载更多后流水应增加")
    finally:
        context.close()


@case
def wallet_401_refreshes_session(browser, base):
    context, page, state = open_app(
        browser, base, 2001, configure=lambda s: s.flags.__setitem__("force_401_wallet_once", True)
    )
    try:
        mock_api.goto_hash(page, base, "/wallet")
        page.wait_for_selector('[data-testid="wallet-page"]')
        page.wait_for_selector("text=¥123.45", timeout=6000)
        expect(page.locator('[data-testid="login-form"]').count() == 0, "401 刷新后不应被登出")
        expect(len(state.tokens) >= 2, "应发生过一次 refresh")
    finally:
        context.close()


@case
def credit_page(browser, base):
    context, page, _ = open_app(browser, base, 2001)
    try:
        mock_api.goto_hash(page, base, "/credit")
        page.wait_for_selector('[data-testid="credit-page"]')
        expect("82" in body_text(page), "信用分应显示 82")
        expect("完成结算" in body_text(page), "信用记录应包含结算事件")
        expect(body_text(page).count("我") >= 1, "排行榜应高亮当前用户")
    finally:
        context.close()


@case
def notifications_mark_read(browser, base):
    context, page, _ = open_app(browser, base, 1001)
    try:
        mock_api.goto_hash(page, base, "/notifications")
        page.wait_for_selector('[data-testid="notifications-page"]')
        expect(page.locator(".notice-item").count() == 2, "应显示 2 条消息")
        page.get_by_role("button", name="未读 1").click() if page.get_by_role(
            "button", name="未读 1"
        ).count() else page.locator(".notice-tabs .tab").nth(1).click()
        page.wait_for_timeout(150)
        expect(page.locator(".notice-item.is-unread").count() == 1, "未读筛选应有 1 条未读")
        page.get_by_role("button", name="标为已读").first.click()
        page.wait_for_timeout(200)
        expect(page.locator(".notice-item.is-unread").count() == 0, "标记后应为 0 条未读")
    finally:
        context.close()


@case
def ws_degraded_pill(browser, base):
    context, page, _ = open_app(browser, base, 2001, ws_broken=True)
    try:
        page.wait_for_selector('[data-testid="ws-degraded"]', timeout=6000)
        expect("实时已降级" in text_of(page, '[data-testid="ws-degraded"]'), "降级提示文案")
    finally:
        context.close()


@case
def mobile_no_horizontal_overflow(browser, base):
    context, page, _ = open_app(browser, base, 2001, viewport=mock_api.MOBILE_VIEWPORT)
    try:
        overflow = page.evaluate(
            "document.documentElement.scrollWidth - document.documentElement.clientWidth"
        )
        expect(overflow <= 1, f"390px 广场横向溢出 {overflow}px")
        mock_api.goto_hash(page, base, "/detail/215731113107132416")
        page.wait_for_selector('[data-testid="detail-page"]')
        overflow = page.evaluate(
            "document.documentElement.scrollWidth - document.documentElement.clientWidth"
        )
        expect(overflow <= 1, f"390px 详情横向溢出 {overflow}px")
    finally:
        context.close()


@case
def mobile_drawer_focus_trap(browser, base):
    context, page, _ = open_app(browser, base, 2001, viewport=mock_api.MOBILE_VIEWPORT)
    try:
        page.locator('[data-testid="nav-menu-button"]').click()
        page.wait_for_selector('[data-testid="sidebar"].sidebar-open')
        inside = page.evaluate(
            "(() => { const sb = document.querySelector('[data-testid=sidebar]');"
            " return sb && sb.contains(document.activeElement); })()"
        )
        expect(inside, "抽屉打开后焦点应进入侧栏")
        page.keyboard.press("Escape")
        page.wait_for_function(
            "!document.querySelector('[data-testid=sidebar]').classList.contains('sidebar-open')"
        )
        page.wait_for_function(
            "document.activeElement && document.activeElement.getAttribute('data-testid') === 'nav-menu-button'",
            timeout=3000,
        )
    finally:
        context.close()


@case
def accessibility_baseline(browser, base):
    context, page, _ = open_app(browser, base, 1001)
    try:
        skip = page.locator("a.skip-link")
        expect(skip.count() == 1, "应有跳到主要内容链接")
        expect(skip.get_attribute("href") == "#main-content", "skip-link 应指向主内容")
        expect(page.locator("main#main-content").get_attribute("tabindex") == "-1", "主内容应可聚焦")
        expect(page.locator("main#main-content").count() == 1, "主内容 landmark 应存在")
    finally:
        context.close()


@case
def unknown_route_falls_back_to_square(browser, base):
    context, page, _ = open_app(browser, base, 1001)
    try:
        mock_api.goto_hash(page, base, "/not-a-page")
        page.wait_for_selector('[data-testid="square-page"]', timeout=6000)
    finally:
        context.close()


@case
def console_errors_are_clean(browser, base):
    errors = []
    context, page, _ = open_app(browser, base, 2001)
    try:
        page.on("console", lambda msg: errors.append(msg.text) if msg.type == "error" else None)
        mock_api.goto_hash(page, base, "/square")
        mock_api.goto_hash(page, base, "/detail/215731113107132416")
        page.wait_for_selector('[data-testid="detail-page"]')
        mock_api.goto_hash(page, base, "/wallet")
        page.wait_for_selector('[data-testid="wallet-page"]')
        ignored = ("favicon", "Failed to load resource", "WebSocket")
        unexpected = [text for text in errors if not any(part in text for part in ignored)]
        expect(not unexpected, f"存在 console error: {unexpected[:5]}")
    finally:
        context.close()


def run_case(fn, browser, base):
    name = fn.__name__
    try:
        fn(browser, base)
        RESULTS.append({"name": name, "ok": True, "detail": ""})
        print(f"PASS {name}")
    except Exception as exc:  # noqa: BLE001 - report every failure instead of aborting
        detail = "".join(traceback.format_exception_only(type(exc), exc)).strip()
        RESULTS.append({"name": name, "ok": False, "detail": detail})
        print(f"FAIL {name}: {detail}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", default=str(DEFAULT_DIST), help="前端构建产物目录（默认 peergrab-frontend/dist）")
    parser.add_argument("--base", default="", help="已运行的静态服务器地址；为空时脚本自己起服务器")
    parser.add_argument("--output", default=str(TESTS_DIR.parent / "verification" / "local-ui"))
    parser.add_argument("--only", default="", help="只跑名称包含该子串的用例")
    args = parser.parse_args()

    server = None
    base = args.base
    if not base:
        server, base = mock_api.start_static_server(Path(args.dist))

    selected = [fn for fn in CASES if not args.only or args.only in fn.__name__]
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            for fn in selected:
                run_case(fn, browser, base)
            browser.close()
    finally:
        if server:
            server.shutdown()

    summary = {
        "total": len(RESULTS),
        "passed": sum(1 for result in RESULTS if result["ok"]),
        "failed": sum(1 for result in RESULTS if not result["ok"]),
    }
    report = {
        "suite": "ui-mock",
        "generatedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
        "base": base,
        "results": RESULTS,
        "summary": summary,
    }
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / "ui-mock-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    print(f"report: {report_path}")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
