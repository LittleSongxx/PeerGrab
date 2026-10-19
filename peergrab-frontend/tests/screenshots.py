# -*- coding: utf-8 -*-
"""Regenerate PeerGrab README screenshots from the real UI with deterministic data.

The script serves the current `dist/` build, mocks `/api/**` with the same
fixtures used by `ui_smoke.py`, and captures:

  docs/assets/screenshots/*.png           desktop 1440x900 (7 pages)
  docs/assets/screenshots/mobile/*.png    mobile  390x844  (7 pages)
  docs/assets/screenshots/overview.png    one-page overview of the mobile set

The data is a fixed fixture (not production data) and the README links here, so
the images are reproducible instead of hand-made.
"""

from __future__ import annotations

import argparse
import html
import tempfile
from pathlib import Path

from playwright.sync_api import sync_playwright

import mock_api

TESTS_DIR = Path(__file__).resolve().parent
DEFAULT_DIST = TESTS_DIR.parent / "dist"
DEFAULT_OUT = TESTS_DIR.parent.parent / "docs" / "assets" / "screenshots"

PAGES = [
    ("square", 1001, "/square", "任务广场"),
    ("publish", 1001, "/publish", "发布任务"),
    ("detail", 1001, "/detail/215731113107132421", "任务详情"),
    ("wallet", 1001, "/wallet", "我的钱包"),
    ("credit", 2001, "/credit", "信用中心"),
    ("notifications", 1001, "/notifications", "消息通知"),
    ("arbitration", 9001, "/detail/215731113107132422", "争议仲裁"),
]


def prepare(page, base, name, user):
    state = mock_api.MockState()
    mock_api.goto_hash(page, base, "/square")
    mock_api.login(page, base, user)
    if name == "publish":
        mock_api.goto_hash(page, base, "/publish")
        page.wait_for_selector('[data-testid="publish-page"]')
        page.locator("#publish-title").fill("帮我从三食堂带一份饭到 6 号楼")
        page.locator("#publish-type").select_option("DELIVERY")
        page.locator("[data-testid=publish-reward]").fill("15")
        page.wait_for_timeout(120)
        return state
    mock_api.goto_hash(page, base, pages_path(name))
    if name == "arbitration":
        page.wait_for_selector('[data-testid="detail-page"]')
        page.locator('[data-testid="action-arbitrate"]').click()
        page.wait_for_selector(".arbitrate-zone")
    if name == "detail":
        page.wait_for_selector('[data-testid="detail-page"]')
    if name == "square":
        page.wait_for_selector('[data-testid="errand-card"]')
    return state


def pages_path(name):
    for key, _user, path, _label in PAGES:
        if key == name:
            return path
    raise KeyError(name)


def capture(browser, base, output: Path, viewport, suffix_dir: Path, dsf: float):
    for name, user, _path, _label in PAGES:
        context = browser.new_context(
            viewport=viewport,
            locale="zh-CN",
            timezone_id="Asia/Shanghai",
            device_scale_factor=dsf,
        )
        state = mock_api.MockState()
        mock_api.attach_mock(context, state)
        page = context.new_page()
        page.emulate_media(reduced_motion="reduce")
        page.goto(f"{base}/#/square", wait_until="load")
        page.wait_for_selector('[data-testid="login-form"], main#main-content', timeout=8000)
        prepare(page, base, name, user)
        page.wait_for_timeout(220)
        target = suffix_dir / f"{name}.png"
        page.screenshot(path=str(target))
        print(f"[shot] {target.relative_to(output)}")
        context.close()


def build_overview(output: Path, browser, base, viewport):
    frames = []
    for name, _user, _path, label in PAGES:
        image = (output / "mobile" / f"{name}.png").resolve()
        frames.append(
            f'<figure><img src="{image.as_uri()}" alt="{html.escape(label)}"/>'
            f"<figcaption>{html.escape(label)}</figcaption></figure>"
        )
    document = f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<style>
  body{{margin:0;padding:34px;background:#f4f5f7;color:#16233a;
       font-family:"Microsoft YaHei","PingFang SC",sans-serif}}
  h1{{margin:0 0 6px;font-size:28px;letter-spacing:.02em}}
  .lead{{margin:0 0 26px;color:#5f6f86;font-size:13px}}
  .grid{{display:grid;grid-template-columns:repeat(4,1fr);gap:22px;align-items:start}}
  figure{{margin:0;background:#fff;border:1px solid #dbe2ed;border-radius:18px;padding:14px;
          box-shadow:0 10px 26px rgba(26,43,72,.08)}}
  img{{display:block;width:100%;border-radius:12px;border:1px solid #e6ebf3}}
  figcaption{{margin-top:9px;text-align:center;font-size:13px;font-weight:700;color:#33455f}}
  .note{{margin-top:26px;color:#7b8aa0;font-size:11px}}
</style></head><body>
<h1>PeerGrab 功能页总览</h1>
<p class="lead">7 个页面 · 390 × 844 移动视口 · 固定演示数据</p>
<div class="grid">{''.join(frames)}</div>
<p class="note">由 peergrab-frontend/tests/screenshots.py 于 2026-10-19 生成；数据来自确定性 mock 夹具，非生产数据。</p>
</body></html>"""
    with tempfile.TemporaryDirectory() as tmp:
        page_file = Path(tmp) / "overview.html"
        page_file.write_text(document, encoding="utf-8")
        context = browser.new_context(viewport=viewport, locale="zh-CN", device_scale_factor=1.5)
        page = context.new_page()
        page.goto(page_file.as_uri(), wait_until="load")
        page.wait_for_timeout(250)
        page.screenshot(path=str(output / "overview.png"), full_page=True)
        context.close()
    print("[shot] overview.png")


def write_readme(output: Path):
    text = """# 功能截图

本目录截图由 `peergrab-frontend/tests/screenshots.py` 生成，数据来自确定性 mock
夹具（固定演示任务/账户），不是生产数据。

```bash
cd peergrab-frontend
npm run build
python3 tests/screenshots.py --dist dist
```

- 桌面 1440×900：`square.png` / `publish.png` / `detail.png` / `wallet.png` /
  `credit.png` / `notifications.png` / `arbitration.png`
- 移动 390×844：`mobile/` 下同名 7 张
- `overview.png`：移动端 7 页总览（README 首图）
- 生成日期：2026-10-19；重新生成后请同步检查 README 中的图片引用。
"""
    (output / "README.md").write_text(text, encoding="utf-8")
    print("[write]", output / "README.md")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", default=str(DEFAULT_DIST))
    parser.add_argument("--output", default=str(DEFAULT_OUT))
    args = parser.parse_args()

    output = Path(args.output)
    (output / "mobile").mkdir(parents=True, exist_ok=True)
    server, base = mock_api.start_static_server(Path(args.dist))
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            capture(browser, base, output, mock_api.DEFAULT_VIEWPORT, output, 1.0)
            capture(browser, base, output, mock_api.MOBILE_VIEWPORT, output / "mobile", 2.0)
            build_overview(output, browser, base, {"width": 1500, "height": 1200})
            browser.close()
    finally:
        server.shutdown()
    write_readme(output)
    print(f"done: {output}")


if __name__ == "__main__":
    raise SystemExit(main())
