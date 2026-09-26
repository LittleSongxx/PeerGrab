#!/usr/bin/env python3
"""Read-only host and container metrics for one disposable PeerGrab bench project.

Writes timestamped JSONL. It never calls the application or changes Docker state.
"""

import argparse
import json
import os
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


PROJECT = re.compile(r"^peergrab-bench-[a-z0-9][a-z0-9_-]*$")
REPO_ROOT = Path(__file__).resolve().parents[3]
TUNING_KEYS = {
    "PEERGRAB_MQ_ENABLED", "PEERGRAB_CACHE_ENABLED", "PEERGRAB_CACHE_SHARDS",
    "PEERGRAB_GRAB_LIMIT_ENABLED", "PEERGRAB_GRAB_LIMIT_TYPE",
    "PEERGRAB_GRAB_LIMIT_PER_SECOND", "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE",
    "SERVER_TOMCAT_THREADS_MAX", "PEERGRAB_TIMEOUT_CONFIRM_SECONDS",
    "PEERGRAB_TIMEOUT_SCAN_ENABLED",
    "PEERGRAB_TIMEOUT_SCAN_INTERVAL_MS", "PEERGRAB_SETTLE_SCAN_INTERVAL_MS",
}


def command(*argv: str, timeout: float = 15) -> str:
    return subprocess.check_output(argv, text=True, timeout=timeout).strip()


def container_ids(project: str) -> list[str]:
    output = command(
        "docker", "ps", "--filter", f"label=com.docker.compose.project={project}",
        "--format", "{{.ID}}",
    )
    return output.splitlines() if output else []


def container_metadata(ids: list[str], project: str) -> list[dict]:
    containers = json.loads(command("docker", "inspect", *ids, timeout=30))
    result = []
    for item in containers:
        labels = item["Config"].get("Labels") or {}
        if labels.get("com.docker.compose.project") != project:
            raise SystemExit("container label does not match the requested bench project")
        host = item["HostConfig"]
        tuning = dict(value.split("=", 1) for value in item["Config"].get("Env", [])
                      if "=" in value and value.split("=", 1)[0] in TUNING_KEYS)
        result.append({
            "id": item["Id"],
            "name": item["Name"].lstrip("/"),
            "image": item["Config"]["Image"],
            "imageId": item["Image"],
            "memoryLimitBytes": host.get("Memory", 0),
            "nanoCpus": host.get("NanoCpus", 0),
            "cpuQuota": host.get("CpuQuota", 0),
            "cpuPeriod": host.get("CpuPeriod", 0),
            "tuning": tuning,
        })
    return result


def docker_stats(ids: list[str]) -> tuple[list[dict], str | None]:
    if not ids:
        return [], None
    try:
        output = command("docker", "stats", "--no-stream", "--format", "{{json .}}", *ids,
                         timeout=30)
        return [json.loads(line) for line in output.splitlines() if line], None
    except (subprocess.SubprocessError, ValueError) as exc:
        return [], type(exc).__name__


def cpu_counters() -> tuple[int, int, int]:
    fields = Path("/proc/stat").read_text().splitlines()[0].split()[1:]
    values = [int(value) for value in fields]
    return sum(values), values[3] + values[4], values[4]


def available_memory() -> int:
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) * 1024
    raise RuntimeError("MemAvailable is missing from /proc/meminfo")


def default_interface() -> str:
    for line in Path("/proc/net/route").read_text().splitlines()[1:]:
        fields = line.split()
        if len(fields) > 1 and fields[1] == "00000000":
            return fields[0]
    raise RuntimeError("no default network interface found")


def network_bytes(interface: str) -> tuple[int, int]:
    for line in Path("/proc/net/dev").read_text().splitlines():
        name, separator, data = line.partition(":")
        if separator and name.strip() == interface:
            values = data.split()
            return int(values[0]), int(values[8])
    raise RuntimeError(f"network interface not found: {interface}")


def default_disk() -> str:
    for line in Path("/proc/mounts").read_text().splitlines():
        fields = line.split()
        if len(fields) > 1 and fields[1] == "/":
            device = Path(fields[0]).name
            nvme = re.fullmatch(r"(nvme\d+n\d+)p\d+", device)
            return nvme.group(1) if nvme else re.sub(r"\d+$", "", device)
    raise RuntimeError("root filesystem mount not found")


def disk_counters(device: str) -> tuple[int, int, int] | None:
    for line in Path("/proc/diskstats").read_text().splitlines():
        fields = line.split()
        if len(fields) >= 13 and fields[2] == device:
            return int(fields[5]) * 512, int(fields[9]) * 512, int(fields[12])
    return None


def percent_delta(current: tuple[int, int, int], previous: tuple[int, int, int]) -> tuple[float, float]:
    total = current[0] - previous[0]
    if total <= 0:
        return 0.0, 0.0
    busy = total - (current[1] - previous[1])
    iowait = current[2] - previous[2]
    return round(max(0.0, busy / total * 100), 2), round(max(0.0, iowait / total * 100), 2)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="peergrab-bench-* Compose project")
    parser.add_argument("--duration", required=True, type=float, help="seconds to sample")
    parser.add_argument("--interval", type=float, default=2.0, help="sample interval in seconds")
    parser.add_argument("--output", required=True, type=Path, help="new JSONL file")
    parser.add_argument("--interface", help="host network interface; default: route interface")
    parser.add_argument("--disk", help="host block device; default: root device")
    args = parser.parse_args()
    if not PROJECT.fullmatch(args.project) or args.duration <= 0 or args.interval < 0.5:
        parser.error("use a peergrab-bench-* project, positive duration and interval >= 0.5s")

    ids = container_ids(args.project)
    if not ids:
        raise SystemExit("no running containers belong to the requested bench project")
    metadata = container_metadata(ids, args.project)
    interface = args.interface or default_interface()
    disk = args.disk or default_disk()
    git_sha = command("git", "-C", str(REPO_ROOT), "rev-parse", "HEAD")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    previous_cpu = cpu_counters()
    previous_net = network_bytes(interface)
    previous_disk = disk_counters(disk)
    previous_at = start
    samples = 0
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps({
            "kind": "metadata", "project": args.project, "gitSha": git_sha,
            "intervalSeconds": args.interval, "interface": interface, "disk": disk,
            "containers": metadata,
        }) + "\n")
        due = start
        while time.monotonic() - start < args.duration:
            time.sleep(max(0.0, due - time.monotonic()))
            at = time.monotonic()
            cpu = cpu_counters()
            net = network_bytes(interface)
            disk_now = disk_counters(disk)
            elapsed = max(at - previous_at, 0.001)
            cpu_usage, iowait = percent_delta(cpu, previous_cpu)
            running_ids = container_ids(args.project)
            stats, stats_error = docker_stats(running_ids)
            host = {
                "cpuPercent": cpu_usage,
                "ioWaitPercent": iowait,
                "memAvailableBytes": available_memory(),
                "netRxBytesPerSecond": round((net[0] - previous_net[0]) / elapsed),
                "netTxBytesPerSecond": round((net[1] - previous_net[1]) / elapsed),
                "load1": os.getloadavg()[0],
            }
            if disk_now is not None and previous_disk is not None:
                host.update({
                    "diskReadBytesPerSecond": round((disk_now[0] - previous_disk[0]) / elapsed),
                    "diskWriteBytesPerSecond": round((disk_now[1] - previous_disk[1]) / elapsed),
                    "diskBusyPercent": round(max(0.0, (disk_now[2] - previous_disk[2])
                                                       / (elapsed * 1000) * 100), 2),
                })
            stream.write(json.dumps({
                "kind": "sample", "at": datetime.now(timezone.utc).isoformat(),
                "elapsedSeconds": round(at - start, 3), "host": host,
                "runningContainerIds": running_ids, "containers": stats,
                "containerStatsError": stats_error,
            }) + "\n")
            stream.flush()
            samples += 1
            previous_cpu, previous_net, previous_disk, previous_at = cpu, net, disk_now, at
            due += args.interval
            if due < time.monotonic() - args.interval:
                due = time.monotonic()
    print(f"wrote {samples} samples to {args.output}")


if __name__ == "__main__":
    main()
