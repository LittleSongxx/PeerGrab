#!/usr/bin/env python3
"""Lint benchmark/report wording for over-claims and incomplete metric statements.

This is a documentation-side guard, not a benchmark runner. It scans Markdown
files (reports, READMEs, methodology) and flags phrases that need a denominator,
a tool/path/window qualifier, or a caveat before they can be quoted to someone
outside the project.

Example::

    python3 peergrab-backend/bench/scripts/lint_metrics.py \
        peergrab-backend/bench/reports/peergrab-current/README.md

Exit code is 0 unless ``--strict`` is passed and at least one warning is found.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


CAVEAT = re.compile(
    r"不能|禁止|不要|不得|反例|黑名单|不构成|不是|不等于|不代表|未验证|"
    r"不可外推|边界|错误说法|非 SLA|无 HA|lint_metrics|拦截")
FENCE = re.compile(r"^\s*(```|~~~)")


@dataclass(frozen=True)
class Rule:
    name: str
    pattern: re.Pattern[str]
    message: str
    check: str | None = None


def _has_zero_error_denominator(line: str) -> bool:
    return bool(re.search(r"\d[\d,]*\s*(?:次|个|请求)|分母|/\s*\d", line))


def _has_percent_baseline(line: str) -> bool:
    return bool(re.search(
        r"同工具|同路径|同镜像|同配置|同口径|对照|基线|相比|相对", line))


def _has_p99_unit(line: str) -> bool:
    return bool(re.search(
        r"P99(?!\d)\s*(?:=|:|为|约)?\s*\d[\d,]*(?:\.\d+)?\s*"
        r"(?:ms|毫秒|s\b|秒|%)", line))


def _has_tps_context(line: str) -> bool:
    return bool(re.search(r"短批次|稳态|批次|200\s*单|/批", line))


def _has_qps_context(line: str) -> bool:
    return bool(re.search(
        r"单机|单接口|隔离|目标|offered|窗口|公网|实测|服务端|客户端",
        line, re.IGNORECASE))


RULES: tuple[Rule, ...] = (
    Rule("extreme-claim", re.compile(r"极限(?:性能|压测|容量)|性能极限"),
         "禁止宣称『极限性能/极限压测』：现有证据是有限窗口容量摸底"),
    Rule("full-site-claim", re.compile(r"全站(?:容量|QPS|吞吐|性能)|整体容量"),
         "只有单机/单接口数据，不能写全站容量"),
    Rule("sla-claim", re.compile(r"(?<![A-Za-z])SLA(?![A-Za-z])|SLO\s*99"),
         "没有生产流量与长期观测，不能写 SLA/SLO"),
    Rule("big-number", re.compile(
        r"百万级|十万级|10w\+|10万\+|QPS\s*10\s*万|百万\s*QPS"),
         "无口径的大数字：给出工具、路径、窗口与分母"),
    Rule("zero-error", re.compile(r"零错误"),
         "『零错误』必须同时给出请求数和分母",
         check="zero-error"),
    Rule("p99-unit", re.compile(
        r"P99(?!\d)\s*(?:=|:|为|约)?\s*\d[\d,]*(?:\.\d+)?"),
         "P99 需要单位（ms/毫秒）与样本口径",
         check="p99-unit"),
    Rule("cache-gain", re.compile(
        r"缓存[^。；\n]{0,12}(?:提升|提高)[^。；\n]{0,8}\d+(?:\.\d+)?%"),
         "缓存『提升 X%』口径错误：应写回源量或 Com_select 降幅"),
    Rule("microservice", re.compile(r"微服务|服务拆分|注册中心|Kubernetes|\bK8s\b"),
         "项目是模块化单体 + Docker Compose，未拆微服务/未上 K8s"),
    Rule("sharding", re.compile(
        r"分库分表|已分片|ShardingSphere[^。；\n]{0,16}(?:生产|上线)"),
         "分片只在 peergrab-sharding-lab 验证，运行链路未分片"),
    Rule("tx-message", re.compile(r"RocketMQ\s*事务消息|事务消息"),
         "生产用本地消息表 + outbox；事务消息只在离线 lab 仿真"),
    Rule("percent-gain", re.compile(r"(?:提升|降低|下降)\s*\d+(?:\.\d+)?%"),
         "百分比必须给出同工具/同路径/同窗口的基线",
         check="percent-gain"),
    Rule("tps-context", re.compile(
        r"(?:\d[\d,]*\s*TPS|TPS\s*(?:=|:|为|约)?\s*\d)"),
         "TPS 需注明批次/稳态口径（如 200 单短批次）",
         check="tps-context"),
    Rule("qps-context", re.compile(
        r"(?:\d[\d,]*\s*QPS|QPS\s*(?:=|:|为|约)?\s*\d)"),
         "QPS 需注明工具、路径与窗口，避免被当成容量承诺",
         check="qps-context"),
    Rule("production-traffic", re.compile(
        r"生产(?:级|环境)?\s*(?:流量|用户|QPS|容量)"),
         "生产为演示站、几乎无真实流量，不能写生产流量/容量"),
)


def check_line(line: str, rules: tuple[Rule, ...] = RULES) -> list[tuple[str, str]]:
    hits: list[tuple[str, str]] = []
    if not line.strip() or CAVEAT.search(line):
        return hits
    # Table rows carry their qualifier in the table header; applying the
    # context-sensitive rules per cell would flag every structured metric row.
    table_row = line.lstrip().startswith("|")
    skip_in_table = {"qps-context", "tps-context", "percent-gain", "p99-unit"}
    for rule in rules:
        if table_row and rule.name in skip_in_table:
            continue
        if not rule.pattern.search(line):
            continue
        if rule.check == "zero-error" and _has_zero_error_denominator(line):
            continue
        if rule.check == "percent-gain" and _has_percent_baseline(line):
            continue
        if rule.check == "tps-context" and _has_tps_context(line):
            continue
        if rule.check == "qps-context" and _has_qps_context(line):
            continue
        if rule.check == "p99-unit" and _has_p99_unit(line):
            continue
        hits.append((rule.name, rule.message))
    return hits


def lint_text(text: str, path: str = "<text>",
              rules: tuple[Rule, ...] = RULES) -> list[str]:
    warnings: list[str] = []
    in_fence = False
    for number, line in enumerate(text.splitlines(), start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        # A line quoting several claims is usually a "do not write" example
        # list; keep it out of the assertive-claim scan.
        if line.count("“") >= 2:
            continue
        for name, message in check_line(line, rules):
            warnings.append(f"{path}:{number}: [{name}] {message} :: {line.strip()}")
    return warnings


def discover(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for raw in paths:
        path = raw.expanduser()
        if not path.exists():
            files.append(path)
            continue
        if path.is_file():
            files.append(path)
        else:
            files.extend(sorted(path.rglob("*.md")))
    return sorted(dict.fromkeys(files))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path,
                        help="Markdown file or directory to lint")
    parser.add_argument("--strict", action="store_true",
                        help="exit 1 when any warning is found")
    args = parser.parse_args(argv)
    warnings: list[str] = []
    for path in discover(args.paths):
        if not path.exists():
            warnings.append(f"{path}: does not exist")
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            warnings.append(f"{path}: not readable ({exc})")
            continue
        warnings.extend(lint_text(text, str(path)))
    for warning in warnings:
        print(warning)
    if warnings:
        print(f"\n{len(warnings)} warning(s); see rules in lint_metrics.py")
    return 1 if args.strict and warnings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 - operator-facing message
        print(f"lint_metrics refused/failed: {exc}", file=sys.stderr)
        sys.exit(1)
