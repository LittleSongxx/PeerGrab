"""Read only the deliberately small, credential-free JMeter CSV JTL schema.

This module is also usable by other benchmark scenarios. A JTL sample's
``success`` already incorporates HTTP status and any JMeter assertions. Never
put responseMessage, URL, request headers, response data, or error text into a
public aggregate.
"""

from collections import Counter
import csv
import math
from pathlib import Path


REQUIRED = {"timeStamp", "elapsed", "label", "responseCode", "success"}
ALLOWED = REQUIRED | {"bytes", "sentBytes", "grpThreads", "allThreads",
                      "Latency", "IdleTime", "Connect", "benchStartMs"}


class JtlError(ValueError):
    pass


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def read_jtl(path: Path, *, expected_labels=None, max_rows=1_000_000):
    """Reject unsafe/unknown columns and malformed or unexpected samples."""
    result = []
    with Path(path).open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        fields = set(reader.fieldnames or ())
        if not REQUIRED <= fields or not fields <= ALLOWED:
            raise JtlError("JTL columns are missing or contain unsafe fields")
        for row in reader:
            if len(result) >= max_rows or None in row:
                raise JtlError("JTL is too large or malformed")
            label = row["label"]
            if expected_labels is not None and label not in expected_labels:
                raise JtlError("JTL contains an unexpected sampler label")
            if row["success"] not in ("true", "false"):
                raise JtlError("JTL has an invalid success value")
            try:
                stamp = int(row["timeStamp"])
                elapsed = int(row["elapsed"])
                if stamp < 1_000_000_000_000 or elapsed < 0:
                    raise ValueError
                counts = {}
                for name in ALLOWED - REQUIRED:
                    if name in fields and row[name] != "":
                        value = int(row[name])
                        if value < 0:
                            raise ValueError
                        counts[name] = value
            except (TypeError, ValueError) as exc:
                raise JtlError("JTL contains an invalid metric") from exc
            code = row["responseCode"]
            # Non-HTTP failures are classified without copying any exception
            # text that a custom HTTP implementation may put in responseCode.
            status = int(code) if code.isdecimal() and 100 <= int(code) <= 599 else None
            result.append({"startMs": stamp, "elapsedMs": elapsed,
                           "label": label, "httpStatus": status,
                           "success": row["success"] == "true", **counts})
    if not result:
        raise JtlError("JTL has no sample rows")
    return result


def summarize_rows(rows, *, label=None, duration_seconds=None, test_start_ms=None):
    """Latency includes successful and failed attempts; label is a phase tag."""
    selected = [row for row in rows if label is None or row["label"] == label]
    if not selected:
        raise JtlError("JTL phase contains no sample rows")
    if duration_seconds is not None and duration_seconds <= 0:
        raise JtlError("Duration must be positive")
    starts = [row["startMs"] for row in selected]
    latencies = [row["elapsedMs"] for row in selected]
    successful = [row["elapsedMs"] for row in selected if row["success"]]
    codes = Counter(str(row["httpStatus"]) for row in selected
                    if row["httpStatus"] is not None)
    errors = sum(not row["success"] for row in selected)
    non_http = sum(row["httpStatus"] is None for row in selected)
    http_non_200 = sum(row["httpStatus"] is not None and row["httpStatus"] != 200
                       for row in selected)
    business_failures = sum(row["httpStatus"] == 200 and not row["success"]
                            for row in selected)
    origin = test_start_ms if test_start_ms is not None else min(starts)
    per_second = Counter((stamp - origin) // 1000 for stamp in starts)
    if duration_seconds is not None:
        per_second = {second: per_second.get(second, 0)
                      for second in range(duration_seconds)}
    else:
        per_second = dict(sorted(per_second.items()))
    summary = {
        "samplesStarted": len(selected),
        "successfulSamples": len(selected) - errors,
        "failedSamples": errors,
        "nonHttpErrors": non_http,
        "httpNon200": http_non_200,
        "http200BusinessFailures": business_failures,
        "httpStatusCodes": dict(sorted(codes.items())),
        "startSpanMs": max(starts) - min(starts),
        "firstStartMs": min(starts),
        "lastStartMs": max(starts),
        "lastCompletionMs": max(row["startMs"] + row["elapsedMs"] for row in selected),
        "allP50Ms": percentile(latencies, .50),
        "allP95Ms": percentile(latencies, .95),
        "allP99Ms": percentile(latencies, .99),
        "successfulP99Ms": percentile(successful, .99),
        "maxElapsedMs": max(latencies),
        "maxAllThreads": max(row.get("allThreads", 0) for row in selected),
        "responseBytes": sum(row.get("bytes", 0) for row in selected),
        "sentBytes": sum(row.get("sentBytes", 0) for row in selected),
        "startsPerSecond": per_second,
    }
    if duration_seconds is not None:
        summary["scheduledWindowSeconds"] = duration_seconds
        summary["actualStartedRps"] = round(len(selected) / duration_seconds, 3)
        summary["successfulRps"] = round((len(selected) - errors) / duration_seconds, 3)
        summary["outsideScheduledWindow"] = sum(
            stamp < origin or stamp >= origin + duration_seconds * 1000
            for stamp in starts)
        summary["outsideScheduledWindowBeyond100Ms"] = sum(
            stamp < origin - 100 or stamp >= origin + duration_seconds * 1000 + 100
            for stamp in starts)
    return summary


def random_arrival_count_check(count, rate, seconds):
    """A loose generator sanity gate, not proof of exact arrival scheduling.

    Apache Open Model's random_arrivals has a stochastic count. A 6-sigma or
    2.5% deficit, whichever is larger, is a useful gross-underproduction flag.
    """
    expected = rate * seconds
    lower = expected - max(6 * math.sqrt(expected), .025 * expected)
    return {"nominalExpected": expected, "samplesStarted": count,
            "minimumPlausible": math.floor(lower),
            "grossUnderproduction": count < lower}


def per_second_pacing_check(phase, rate):
    """Flag large launch gaps/bursts that make a nominal RPS misleading.

    It is intentionally loose: six Poisson standard deviations or 25% of the
    target per second, whichever is larger. It cannot establish exact schedule
    fidelity, but catches generator pauses such as 0 then 2x-target catch-up.
    """
    tolerance = max(6 * math.sqrt(rate), .25 * rate)
    low, high = max(0, rate - tolerance), rate + tolerance
    bad = [second for second, count in phase["startsPerSecond"].items()
           if count < low or count > high]
    return {"lowerPerSecond": round(low, 3), "upperPerSecond": round(high, 3),
            "badSeconds": bad,
            "minStartedPerSecond": min(phase["startsPerSecond"].values()),
            "maxStartedPerSecond": max(phase["startsPerSecond"].values())}
