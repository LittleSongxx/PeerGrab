#!/usr/bin/env python3
"""Canonical multi-round summary for PeerGrab benchmark evidence.

Read-only, offline tool. It reads JSON evidence produced by the current
benchmark entry points:

* JMeter S2 summaries with a top-level ``rounds`` array (one entry per round,
  each carrying a ``sample`` object);
* Vegeta stage summaries with a top-level ``stages`` array and a ``sample``
  object per stage;
* the S2 capacity-knee runner, either its ``manifest.json`` or one
  ``stage-<rate>rps/result.json``;
* a legacy ``result.json`` whose payload is under ``summary``.

It never starts Docker, JMeter, or a load generator. Its job is to stop a
single round, a warm-up outlier, or a client-side ceiling from being quoted as
a capacity number: the output always carries the round count, the median and
the min-max range, the error taxonomy, and warnings about missing resources or
unpaired client/server latency.

Example::

    python3 peergrab-backend/bench/scripts/canonicalize.py \
        --input peergrab-backend/bench/runs/codex-maintenance/s2-evidence \
        --format markdown
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import statistics
import sys
from typing import Any


SCHEMA_VERSION = 1
DEFAULT_MIN_ROUNDS = 3

# Only files whose names look like run summaries are auto-discovered in a
# directory. Private evidence indexes are not run summaries.
SUMMARY_NAME = re.compile(
    r"(summary|manifest|result|vegeta|s[1-6])", re.IGNORECASE)
IGNORED_NAMES = re.compile(
    r"(private-evidence-index|evidence\.json$|^ASSET_CATALOG)", re.IGNORECASE)

SCENARIO_HINTS = (
    ("s1", "S1"), ("s2", "S2"), ("s3", "S3"),
    ("s4", "S4"), ("s5", "S5"), ("s6", "S6"),
)


@dataclass
class Round:
    source: str
    tool: str
    scenario: str
    transport: str | None
    rate: float | None
    round_index: int | None
    window_seconds: float | None
    requests: float | None
    success: float | None
    http_errors: float | None
    transport_errors: float | None
    business_errors: float | None
    client_rejected: float | None
    p50_ms: float | None
    p95_ms: float | None
    p99_ms: float | None
    p999_ms: float | None
    max_ms: float | None
    actual_rps: float | None
    generator: dict[str, Any] = field(default_factory=dict)
    resources: dict[str, Any] = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)


def _number(value: Any) -> float | None:
    """Return a finite float, or None for booleans/missing/bad values."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        value = float(value)
        return value if math.isfinite(value) else None
    if isinstance(value, str):
        try:
            value = float(value)
        except ValueError:
            return None
        return value if math.isfinite(value) else None
    return None


def _dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _sum(*values: Any) -> float | None:
    present = [value for value in values if _number(value) is not None]
    return float(sum(present)) if present else None


def _scenario_from_path(path: Path) -> str:
    text = path.as_posix().lower()
    for hint, name in SCENARIO_HINTS:
        if re.search(rf"(^|[/_\-]){hint}([/_\-.]|$)", text):
            return name
    return "unknown"


def _round(value: float | None, digits: int = 3) -> float | None:
    return None if value is None else round(value, digits)


def _round_from_jmeter(path: Path, data: dict[str, Any]) -> list[Round]:
    rounds: list[Round] = []
    transport = data.get("transport") if isinstance(data.get("transport"), str) else None
    for index, item in enumerate(data.get("rounds") or [], start=1):
        if not isinstance(item, dict):
            continue
        sample = _dict(item.get("sample"))
        requests = _number(sample.get("samplesStarted"))
        window = _number(sample.get("scheduledWindowSeconds"))
        actual = _number(sample.get("actualStartedRps")) or _number(
            sample.get("successfulRps"))
        if actual is None and requests and window:
            actual = requests / window
        rounds.append(Round(
            source=str(path), tool="jmeter",
            scenario=_scenario_from_path(path), transport=transport,
            rate=_number(item.get("rateTargetRps")),
            round_index=_number(item.get("round")) or index,
            window_seconds=window, requests=requests,
            success=_number(sample.get("successfulSamples")),
            http_errors=_sum(sample.get("httpNon200"),
                             sample.get("http200BusinessFailures")),
            transport_errors=_number(sample.get("nonHttpErrors")),
            business_errors=_number(sample.get("http200BusinessFailures")),
            client_rejected=None,
            p50_ms=_number(sample.get("allP50Ms")),
            p95_ms=_number(sample.get("allP95Ms")),
            p99_ms=_number(sample.get("allP99Ms")),
            p999_ms=None, max_ms=_number(sample.get("maxElapsedMs")),
            actual_rps=actual, generator=_dict(item.get("generator")),
            resources=_load_resources(path.parent / "resources.jsonl"),
        ))
    return rounds


def _round_from_vegeta(path: Path, data: dict[str, Any]) -> list[Round]:
    rounds: list[Round] = []
    transport = data.get("transport") if isinstance(data.get("transport"), str) else None
    for index, stage in enumerate(data.get("stages") or [], start=1):
        if not isinstance(stage, dict):
            continue
        sample = _dict(stage.get("sample"))
        requests = _number(sample.get("offered")) or _number(sample.get("resultRecords"))
        actual = _number(sample.get("actualIssueRate")) or _number(
            sample.get("successfulThroughput"))
        window = (requests / actual) if requests and actual else None
        rounds.append(Round(
            source=str(path), tool="vegeta",
            scenario=_scenario_from_path(path), transport=transport,
            rate=_number(stage.get("offeredRps")),
            round_index=index, window_seconds=_round(window), requests=requests,
            success=_number(sample.get("http2xx")),
            http_errors=_number(sample.get("httpOther")),
            transport_errors=_number(sample.get("transportErrors")),
            business_errors=None, client_rejected=None,
            p50_ms=_number(sample.get("p50Ms")),
            p95_ms=_number(sample.get("p95Ms")),
            p99_ms=_number(sample.get("p99Ms")),
            p999_ms=None, max_ms=None, actual_rps=actual,
        ))
    return rounds


def _load_resources(path: Path) -> dict[str, Any]:
    """Median/max host and app container counters from a resources.jsonl."""
    if not path.is_file():
        return {}
    host_cpu: list[float] = []
    host_io: list[float] = []
    host_mem: list[float] = []
    app_cpu: list[float] = []
    mysql_running: list[float] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            item = json.loads(line)
        except ValueError:
            continue
        if not isinstance(item, dict) or item.get("kind") != "sample":
            continue
        host = _dict(item.get("host"))
        if (value := _number(host.get("cpuPercent"))) is not None:
            host_cpu.append(value)
        if (value := _number(host.get("ioWaitPercent"))) is not None:
            host_io.append(value)
        if (value := _number(host.get("memAvailableBytes"))) is not None:
            host_mem.append(value)
        for container in item.get("dockerStats") or []:
            if not isinstance(container, dict):
                continue
            if "-app-" not in str(container.get("Name")):
                continue
            raw = str(container.get("CPUPerc") or "").rstrip("%")
            if (value := _number(raw)) is not None:
                app_cpu.append(value)
        mysql = _dict(item.get("mysql"))
        if (value := _number(mysql.get("Threads_running"))) is not None:
            mysql_running.append(value)
    if not any((host_cpu, host_io, host_mem, app_cpu, mysql_running)):
        return {}
    return {
        "samples": max(len(host_cpu), len(app_cpu), len(mysql_running)),
        "hostCpuPercentMedian": _round(statistics.median(host_cpu)) if host_cpu else None,
        "hostCpuPercentMax": _round(max(host_cpu)) if host_cpu else None,
        "hostIoWaitPercentMax": _round(max(host_io)) if host_io else None,
        "hostMemAvailableBytesMin": min(host_mem) if host_mem else None,
        "appCpuPercentMedian": _round(statistics.median(app_cpu)) if app_cpu else None,
        "mysqlThreadsRunningMax": max(mysql_running) if mysql_running else None,
    }


def _round_from_knee_record(path: Path, record: dict[str, Any],
                            run_dir: Path) -> list[Round]:
    sample = _dict(record.get("sample"))
    rate = _number(record.get("offeredRps"))
    window = _number(record.get("sampleSeconds"))
    requests = _number(sample.get("offered")) or _number(sample.get("sent"))
    actual = _number(sample.get("completedRps"))
    if actual is None and requests and window:
        actual = requests / window
    resource_file = (run_dir / f"stage-{int(rate)}rps" / "resources.jsonl"
                     if rate is not None else run_dir / "resources.jsonl")
    return [Round(
        source=str(path), tool="java-client",
        scenario=_scenario_from_path(path), transport=None,
        rate=rate, round_index=None, window_seconds=window,
        requests=requests, success=_number(sample.get("ok")),
        http_errors=_number(sample.get("serverRejected")),
        transport_errors=_number(sample.get("errors")),
        business_errors=None,
        client_rejected=_sum(sample.get("localRejected"),
                             sample.get("schedulerMissed"),
                             sample.get("capacityRejected")),
        p50_ms=_number(sample.get("p50Ms")),
        p95_ms=_number(sample.get("p95Ms")),
        p99_ms=_number(sample.get("p99Ms")),
        p999_ms=None, max_ms=_number(sample.get("maxMs")),
        actual_rps=actual, resources=_load_resources(resource_file),
    )]


def _round_from_knee(path: Path, data: dict[str, Any]) -> list[Round]:
    """Parse a knee manifest, a stage result.json, or a legacy result.json."""
    if isinstance(data.get("stages"), list):
        rounds: list[Round] = []
        run_dir = path.parent
        for stage in data["stages"]:
            if isinstance(stage, dict):
                rounds.extend(_round_from_knee_record(path, stage, run_dir))
        return rounds
    if isinstance(data.get("sample"), dict):
        return _round_from_knee_record(path, data, path.parent)
    if isinstance(data.get("summary"), dict):
        summary = _dict(data.get("summary"))
        return [Round(
            source=str(path), tool="legacy", scenario=_scenario_from_path(path),
            transport=None, rate=_number(summary.get("offeredRps")),
            round_index=1, window_seconds=_number(summary.get("sampleSeconds")),
            requests=_number(summary.get("sent")) or _number(summary.get("offered")),
            success=_number(summary.get("ok")),
            http_errors=_sum(_dict(summary.get("httpErrors")).values()),
            transport_errors=_number(summary.get("exceptionErrors")),
            business_errors=_number(summary.get("businessErrors")),
            client_rejected=_sum(summary.get("localRejected"),
                                 summary.get("schedulerMissed")),
            p50_ms=_number(summary.get("p50Ms")),
            p95_ms=_number(summary.get("p95Ms")),
            p99_ms=_number(summary.get("p99Ms")),
            p999_ms=None, max_ms=_number(summary.get("maxMs")),
            actual_rps=_number(summary.get("offeredRps")),
        )]
    return []


def _parse_file(path: Path) -> tuple[list[Round], list[str]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return [], [f"{path}: not readable JSON ({exc})"]
    if not isinstance(data, dict):
        return [], [f"{path}: top-level JSON is not an object; skipped"]
    if isinstance(data.get("rounds"), list):
        return _round_from_jmeter(path, data), []
    if isinstance(data.get("stages"), list):
        stages = data["stages"]
        probe = next((stage for stage in stages if isinstance(stage, dict)), {})
        sample = _dict(probe.get("sample"))
        if any(key in sample for key in ("http2xx", "transportErrors", "actualIssueRate")):
            return _round_from_vegeta(path, data), []
        if any(key in sample for key in ("ok", "completedRps", "localRejected")):
            return _round_from_knee(path, data), []
        return [], [f"{path}: stages are not a recognized Vegeta/knee shape; skipped"]
    if isinstance(data.get("sample"), dict) and "offeredRps" in data:
        return _round_from_knee(path, data), []
    if isinstance(data.get("summary"), dict):
        return _round_from_knee(path, data), []
    return [], [f"{path}: not a recognized run summary; skipped"]


def _iter_json(paths: list[Path], warnings: list[str]) -> list[Path]:
    found: set[Path] = set()
    for raw in paths:
        path = raw.expanduser()
        if not path.exists():
            warnings.append(f"{path}: does not exist")
            continue
        if path.is_file():
            found.add(path.resolve())
            continue
        manifests = sorted(path.rglob("manifest.json"))
        manifest_dirs = {manifest.parent.resolve() for manifest in manifests}
        stage_dirs = {stage.parent.resolve() for stage in path.rglob("result.json")
                      if stage.parent.name.startswith("stage-")}
        for manifest in manifests:
            found.add(manifest.resolve())
        for candidate in path.rglob("*.json"):
            resolved = candidate.resolve()
            if resolved in found:
                continue
            # A knee manifest already contains every stage record; do not add
            # the stage result.json a second time.
            if resolved.parent in stage_dirs and any(
                    parent in manifest_dirs for parent in resolved.parents):
                continue
            if not SUMMARY_NAME.search(candidate.name):
                continue
            if IGNORED_NAMES.search(candidate.name):
                continue
            found.add(resolved)
    return sorted(found)


def _stats(values: list[float | None]) -> dict[str, float] | None:
    present = [value for value in values if value is not None]
    if not present:
        return None
    return {
        "n": len(present),
        "median": _round(statistics.median(present)),
        "min": _round(min(present)),
        "max": _round(max(present)),
    }


def _group(rounds: list[Round]) -> list[dict[str, Any]]:
    groups: dict[tuple, list[Round]] = {}
    for item in rounds:
        key = (item.scenario, item.tool, item.transport or "", item.rate)
        groups.setdefault(key, []).append(item)
    result = []
    for (scenario, tool, transport, rate), items in groups.items():
        items.sort(key=lambda item: (item.round_index or 0))
        payload = {
            "scenario": scenario, "tool": tool, "transport": transport,
            "rate": rate,
            "rounds": [item.__dict__ for item in items],
            "actualRps": _stats([item.actual_rps for item in items]),
            "p50Ms": _stats([item.p50_ms for item in items]),
            "p95Ms": _stats([item.p95_ms for item in items]),
            "p99Ms": _stats([item.p99_ms for item in items]),
            "p999Ms": _stats([item.p999_ms for item in items]),
            "requests": _sum(*(item.requests for item in items)),
            "success": _sum(*(item.success for item in items)),
            "httpErrors": _sum(*(item.http_errors for item in items)),
            "transportErrors": _sum(*(item.transport_errors for item in items)),
            "businessErrors": _sum(*(item.business_errors for item in items)),
            "clientRejected": _sum(*(item.client_rejected for item in items)),
            "hostCpuPercentMedian": _stats(
                [_number(item.resources.get("hostCpuPercentMedian")) for item in items]),
            "hostCpuPercentMax": _stats(
                [_number(item.resources.get("hostCpuPercentMax")) for item in items]),
            "appCpuPercentMedian": _stats(
                [_number(item.resources.get("appCpuPercentMedian")) for item in items]),
            "warnings": [],
        }
        result.append(payload)
    result.sort(key=lambda item: (
        item["scenario"], item["tool"], item["rate"] is None, item["rate"] or 0))
    return result


def _add_warnings(groups: list[dict[str, Any]], min_rounds: int) -> list[str]:
    warnings: list[str] = []
    for group in groups:
        rounds = group["rounds"]
        label = f"{group['scenario']} {group['tool']} {_fmt_rate(group['rate'])}"
        if len(rounds) < min_rounds:
            group["warnings"].append(
                f"{len(rounds)} round(s) < {min_rounds}; do not quote as stable")
        if any(any(item.get(key) for key in (
                "http_errors", "transport_errors", "business_errors",
                "client_rejected")) for item in rounds):
            group["warnings"].append(
                "errors present; tier is not zero-error (see per-round detail)")
        requests = _number(group.get("requests"))
        success = _number(group.get("success"))
        if requests is not None and success is not None and success > requests:
            group["warnings"].append(
                "success count exceeds request denominator; check off-by-one, "
                "tail samples or aborted rounds")
        if any(item.get("p99_ms") is not None and item.get("window_seconds") is None
               for item in rounds):
            group["warnings"].append(
                "sample window is missing for at least one round; rate is not "
                "reproducible from the payload")
        for item in rounds:
            rate = _number(item.get("rate"))
            actual = _number(item.get("actual_rps"))
            if rate and actual:
                delta = (actual - rate) / rate
                if abs(delta) > 0.05:
                    group["warnings"].append(
                        f"round {item.get('round_index')} actual {actual:.3f} "
                        f"vs target {rate:.3f} ({delta:+.1%})")
        p99s = [_number(item.get("p99_ms")) for item in rounds]
        p99s = [value for value in p99s if value is not None]
        if len(p99s) >= 2 and min(p99s) > 0 and max(p99s) / min(p99s) >= 2:
            group["warnings"].append(
                f"P99 spread {max(p99s) / min(p99s):.1f}x "
                f"({min(p99s):.3f}..{max(p99s):.3f} ms); cold first round or tool "
                "state must be reported separately")
        if not any(item.get("resources") for item in rounds):
            group["warnings"].append(
                "no host resource samples; CPU/IO claim is unsupported")
        if group["tool"] == "jmeter":
            group["warnings"].append(
                "JMeter client latency only; pair with server $request_time")
        group["warnings"] = list(dict.fromkeys(group["warnings"]))
        warnings.extend(f"{label}: {line}" for line in group["warnings"])
    by_tier: dict[tuple, set[str]] = {}
    for group in groups:
        rate = group["rate"]
        if rate is None:
            continue
        by_tier.setdefault((group["scenario"], rate), set()).add(group["tool"])
    for (scenario, rate), tools in by_tier.items():
        if len(tools) > 1:
            warnings.append(
                f"{scenario} {_fmt_rate(rate)} measured by {sorted(tools)}; "
                "do not merge or compare across tools")
    return warnings


def _fmt_rate(rate: float | None) -> str:
    if rate is None:
        return "rate?"
    return f"{rate:,.0f} RPS"


def _fmt(value: Any, digits: int = 1) -> str:
    if value is None:
        return "-"
    if isinstance(value, float):
        if value.is_integer():
            return f"{int(value):,}"
        return f"{value:,.{digits}f}"
    return f"{value:,}"


def _short(value: Any, limit: int = 34) -> str:
    text = str(value) if value else "-"
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _fmt_stat(stat: dict[str, float] | None, digits: int = 1) -> str:
    if not stat:
        return "-"
    return f"{_fmt(stat['median'], digits)} ({_fmt(stat['min'], digits)}-{_fmt(stat['max'], digits)})"


def _markdown(groups: list[dict[str, Any]], warnings: list[str],
              inputs: list[Path]) -> str:
    lines = [
        "## Canonical bench summary",
        "",
        f"- generated: {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
        f"- inputs: {len(inputs)} file(s)",
        "- medians are across valid sample windows; min-max is the across-round "
        "range; warm-up windows are excluded",
        "",
        "| Scenario | Tool | Transport | Offered RPS | Rounds | "
        "Actual RPS median (min-max) | Requests | Success | "
        "HTTP / transport / business / client-rejected | P50 ms | P95 ms | "
        "P99 ms median (min-max) | Host CPU median (min-max) | Warnings |",
        "| --- | --- | --- | ---: | ---: | --- | ---: | ---: | --- | ---: | "
        "---: | --- | --- | --- |",
    ]
    for group in groups:
        lines.append(
            "| {scenario} | {tool} | {transport} | {rate} | {rounds} | {actual} | "
            "{requests} | {success} | {errors} | {p50} | {p95} | {p99} | {cpu} | "
            "{warnings} |".format(
                scenario=group["scenario"], tool=group["tool"],
                transport=_short(group["transport"]),
                rate=_fmt_rate(group["rate"]), rounds=len(group["rounds"]),
                actual=_fmt_stat(group["actualRps"]),
                requests=_fmt(group["requests"]),
                success=_fmt(group["success"]),
                errors="/".join(_fmt(group[key]) for key in (
                    "httpErrors", "transportErrors", "businessErrors",
                    "clientRejected")),
                p50=_fmt_stat(group["p50Ms"]),
                p95=_fmt_stat(group["p95Ms"]),
                p99=_fmt_stat(group["p99Ms"]),
                cpu=_fmt_stat(group["hostCpuPercentMedian"]),
                warnings="; ".join(group["warnings"]) or "-",
            ))
    lines.extend(["", "### Warnings", ""])
    lines.extend(f"- {warning}" for warning in warnings) if warnings else lines.append(
        "- none")
    return "\n".join(lines) + "\n"


def collect(paths: list[Path], min_rounds: int = DEFAULT_MIN_ROUNDS
            ) -> tuple[dict[str, Any], list[str]]:
    """Parse paths, aggregate groups, and return (canonical, warnings)."""
    discovery_warnings: list[str] = []
    files = _iter_json(paths, discovery_warnings)
    rounds: list[Round] = []
    warnings: list[str] = list(discovery_warnings)
    for path in files:
        parsed, issues = _parse_file(path)
        warnings.extend(issues)
        rounds.extend(parsed)
    groups = _group(rounds)
    warnings.extend(_add_warnings(groups, min_rounds))
    canonical = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "minRounds": min_rounds,
        "inputs": [str(path) for path in files],
        "groups": groups,
        "warnings": warnings,
    }
    return canonical, warnings


def _detail_markdown(groups: list[dict[str, Any]]) -> str:
    lines = ["", "### Per-round detail", "",
             "| Scenario | Tool | Round | Rate | Actual RPS | Requests | Success | "
             "P99 ms | Source |",
             "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
    for group in groups:
        for item in group["rounds"]:
            lines.append(
                "| {scenario} | {tool} | {round} | {rate} | {actual} | {requests} | "
                "{success} | {p99} | {source} |".format(
                    scenario=group["scenario"], tool=group["tool"],
                    round=_fmt(item.get("round_index"), 0),
                    rate=_fmt_rate(item.get("rate")),
                    actual=_fmt(item.get("actual_rps"), 2),
                    requests=_fmt(item.get("requests")),
                    success=_fmt(item.get("success")),
                    p99=_fmt(item.get("p99_ms"), 2),
                    source=Path(item.get("source", "")).name,
                ))
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", action="append", type=Path, required=True,
                        help="JSON file or directory; repeatable")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--out", type=Path, help="write output here instead of stdout")
    parser.add_argument("--min-rounds", type=int, default=DEFAULT_MIN_ROUNDS,
                        help=f"warn below this round count (default {DEFAULT_MIN_ROUNDS})")
    parser.add_argument("--strict", action="store_true",
                        help="exit 1 when any warning is reported")
    parser.add_argument("--details", action="store_true",
                        help="include per-round detail in the Markdown output")
    args = parser.parse_args(argv)
    if args.min_rounds < 1:
        parser.error("--min-rounds must be positive")

    canonical, warnings = collect(args.input, args.min_rounds)
    if args.format == "json":
        output = json.dumps(canonical, ensure_ascii=False, indent=2) + "\n"
    else:
        output = _markdown(canonical["groups"], warnings,
                           [Path(item) for item in canonical["inputs"]])
        if args.details:
            output += _detail_markdown(canonical["groups"])
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(output, encoding="utf-8")
        print(args.out)
    else:
        print(output, end="")
    return 1 if args.strict and warnings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001 - operator-facing message
        print(f"canonicalize refused/failed: {exc}", file=sys.stderr)
        sys.exit(1)
