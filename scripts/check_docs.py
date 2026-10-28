#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate PeerGrab engineering docs: relative links + single index.

Local-only material (`docs/tutorial/`, `docs/assets/screenshots/`, `docs/面经/`) is intentionally
not committed; it is ignored here so a clean checkout still passes.

Usage: python3 scripts/check_docs.py
Exit code: 0 when clean, 1 when violations are found.
"""

from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
IGNORED_PREFIXES = ("tutorial/", "assets/screenshots/", "面经/")

PRIMARY_DOCS = [
    ROOT / "README.md",
    ROOT / "peergrab-backend" / "README.md",
    ROOT / "peergrab-frontend" / "README.md",
    ROOT / "peergrab-frontend" / "tests" / "README.md",
]
PRIMARY_DOCS += [
    path for path in sorted((ROOT / "docs").rglob("*.md"))
    if not path.relative_to(ROOT / "docs").as_posix().startswith(IGNORED_PREFIXES)
]
PRIMARY_DOCS += sorted((ROOT / "peergrab-backend" / "docker").glob("*.md"))

LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)\)")
problems: list[str] = []


def check_links(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    for match in LINK_RE.finditer(text):
        target = match.group(1).strip()
        if not target or target.startswith(("#", "http://", "https://", "mailto:", "tel:")):
            continue
        target = target.split("#", 1)[0].strip()
        if not target or "<" in target or ">" in target:
            continue
        candidate = Path(unquote(target))
        resolved = candidate if candidate.is_absolute() else (path.parent / candidate)
        if not resolved.exists():
            problems.append(f"{path.relative_to(ROOT)}: 链接不存在 -> {target}")


def check_docs_index() -> None:
    index = ROOT / "docs" / "README.md"
    if not index.exists():
        problems.append("docs/README.md 缺失（文档单一入口）")
        return
    index_text = index.read_text(encoding="utf-8")
    for md in sorted((ROOT / "docs").rglob("*.md")):
        relative_posix = md.relative_to(ROOT / "docs").as_posix()
        if md == index or relative_posix.startswith(IGNORED_PREFIXES):
            continue
        relative = relative_posix
        if relative not in index_text:
            problems.append(f"docs/README.md 未登记: docs/{relative}")


def main() -> int:
    for path in PRIMARY_DOCS:
        if not path.exists():
            problems.append(f"主文档缺失: {path.relative_to(ROOT)}")
            continue
        check_links(path)
    check_docs_index()

    if problems:
        print(f"FAIL: {len(problems)} 个文档问题")
        for problem in problems:
            print(" -", problem)
        return 1
    print(f"OK: {len(PRIMARY_DOCS)} 篇主文档链接检查通过；docs 索引覆盖完整")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
