#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""汇总 PeerGrab 后端的 Maven Surefire 测试报告，供 CI step summary 直接使用。

扫描 ``<root>/**/target/surefire-reports/TEST-*.xml``，按模块汇总
tests / failures / errors / skipped，并统计文件名含 ``IT`` 的集成测试报告数。
输出 Markdown 表格；``--strict`` 在 failures/errors 非零时退出 1；
``--require-it`` 要求至少一份 IT 报告且 IT 的 skipped 为 0，否则退出 1。

用法::

    python3 peergrab-backend/tools/summarize_tests.py
    python3 peergrab-backend/tools/summarize_tests.py --root peergrab-backend
    python3 peergrab-backend/tools/summarize_tests.py --strict --require-it

默认 root 为脚本所在目录的上一级（即 peergrab-backend）。
退出码：0=通过，1=门禁失败，2=参数或 root 错误。
"""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parent.parent
REPORT_GLOB = "**/target/surefire-reports/TEST-*.xml"


@dataclass
class Stats:
    """一个模块（或全量）的 surefire 计数。"""

    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    reports: int = 0
    it_reports: int = 0
    it_skipped: int = 0

    def add(self, tests: int, failures: int, errors: int, skipped: int, is_it: bool) -> None:
        self.tests += tests
        self.failures += failures
        self.errors += errors
        self.skipped += skipped
        self.reports += 1
        if is_it:
            self.it_reports += 1
            self.it_skipped += skipped


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="汇总 Surefire 报告（Markdown，可直接贴 CI step summary）。",
        epilog="示例: python3 peergrab-backend/tools/summarize_tests.py --strict --require-it",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=DEFAULT_ROOT,
        help=f"扫描根目录，默认脚本上一级 ({DEFAULT_ROOT})",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="存在 failures 或 errors 时退出 1",
    )
    parser.add_argument(
        "--require-it",
        action="store_true",
        help="要求至少一份 IT 报告且 IT skipped == 0，否则退出 1",
    )
    return parser.parse_args(argv)


def report_paths(root: Path) -> list[Path]:
    return sorted(root.glob(REPORT_GLOB))


def module_name(report: Path, root: Path) -> str:
    """从 .../<module>/target/surefire-reports/... 中取出模块名。"""
    parts = report.relative_to(root).parts
    if "target" in parts:
        index = parts.index("target")
        if index > 0:
            return parts[index - 1]
    return root.name


def read_counts(report: Path) -> tuple[int, int, int, int]:
    root = ET.parse(report).getroot()

    def attr(name: str) -> int:
        raw = root.get(name, "0")
        try:
            return int(raw)
        except ValueError:
            return 0

    return attr("tests"), attr("failures"), attr("errors"), attr("skipped")


def collect(root: Path) -> tuple[dict[str, Stats], Stats, list[Path]]:
    by_module: dict[str, Stats] = {}
    total = Stats()
    parse_failures: list[Path] = []
    for report in report_paths(root):
        is_it = "IT" in report.name
        try:
            tests, failures, errors, skipped = read_counts(report)
        except ET.ParseError as exc:
            print(f"WARNING: 跳过无法解析的报告 {report}: {exc}", file=sys.stderr)
            parse_failures.append(report)
            continue
        by_module.setdefault(module_name(report, root), Stats()).add(
            tests, failures, errors, skipped, is_it
        )
        total.add(tests, failures, errors, skipped, is_it)
    return by_module, total, parse_failures


def render(root: Path, by_module: dict[str, Stats], total: Stats) -> str:
    lines = ["### Surefire test summary", ""]
    lines.append(f"_Root: `{root}` — {total.reports} report(s)._")
    lines.append("")
    if total.reports == 0:
        lines.append("_No Surefire reports found._")
        return "\n".join(lines)

    lines.extend([
        "| Module | Tests | Failures | Errors | Skipped | IT reports |",
        "|---|---:|---:|---:|---:|---:|",
    ])
    for name in sorted(by_module):
        stats = by_module[name]
        lines.append(
            f"| {name} | {stats.tests} | {stats.failures} | {stats.errors} | "
            f"{stats.skipped} | {stats.it_reports} |"
        )
    lines.append(
        f"| **Total** | **{total.tests}** | **{total.failures}** | **{total.errors}** | "
        f"**{total.skipped}** | **{total.it_reports}** |"
    )
    lines.append("")
    lines.append(
        f"_IT reports (filename contains `IT`): {total.it_reports} file(s), "
        f"skipped={total.it_skipped}._"
    )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    root = args.root.expanduser().resolve()
    if not root.is_dir():
        print(f"ERROR: root 不是目录: {root}", file=sys.stderr)
        return 2

    by_module, total, parse_failures = collect(root)
    print(render(root, by_module, total))

    failed = False
    if args.strict and (total.failures or total.errors):
        print(
            f"\n**FAILED**: {total.failures} failure(s), {total.errors} error(s) "
            f"under `{root}` (--strict)."
        )
        failed = True
    if args.require_it:
        if total.it_reports == 0:
            print(
                f"\n**FAILED**: --require-it 要求至少一份 IT 报告，但 `{root}` 下"
                "没有文件名含 `IT` 的 TEST-*.xml。"
            )
            failed = True
        elif total.it_skipped:
            print(
                f"\n**FAILED**: --require-it 要求 IT skipped == 0，实际 skipped="
                f"{total.it_skipped}（{total.it_reports} 份 IT 报告）。"
            )
            failed = True
    if parse_failures:
        print(
            f"\nWARNING: {len(parse_failures)} 份报告无法解析，汇总可能不完整。",
            file=sys.stderr,
        )
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
