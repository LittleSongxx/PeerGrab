#!/usr/bin/env python3
"""Create a private, never-overwritten environment file for one disposable bench stack."""

import argparse
import os
import re
import secrets
import socket
from pathlib import Path


DOCKER_DIR = Path(__file__).resolve().parents[2] / "docker"
PROJECT = re.compile(r"^peergrab-bench-[a-z0-9][a-z0-9_-]*$")


def free_loopback_port(port: int) -> bool:
    if not 1024 <= port <= 65535:
        return False
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="unique peergrab-bench-* project")
    parser.add_argument("--output", type=Path, default=DOCKER_DIR / ".env.bench")
    parser.add_argument("--api-port", type=int, default=38080)
    parser.add_argument("--mysql-port", type=int, default=33307)
    parser.add_argument("--redis-port", type=int, default=36380)
    parser.add_argument("--mq-port", type=int, default=38081)
    parser.add_argument("--mq-enabled", choices=("true", "false"), default="true")
    args = parser.parse_args()
    if not PROJECT.fullmatch(args.project):
        parser.error("project must be a unique peergrab-bench-* identifier")
    ports = (args.api_port, args.mysql_port, args.redis_port, args.mq_port)
    if len(set(ports)) != len(ports) or not all(free_loopback_port(port) for port in ports):
        parser.error("API, MySQL, Redis and MQ ports must be distinct unused loopback ports")

    values = {
        "COMPOSE_PROJECT_NAME": args.project,
        "PEERGRAB_MYSQL_PASSWORD": secrets.token_hex(24),
        "PEERGRAB_AUTH_MODE": "jwt",
        "PEERGRAB_AUTH_JWT_SECRET": secrets.token_hex(32),
        "PEERGRAB_BENCH_MQ_ENABLED": args.mq_enabled,
        "PEERGRAB_API_PORT": str(args.api_port),
        "PEERGRAB_MYSQL_PORT": str(args.mysql_port),
        "PEERGRAB_REDIS_PORT": str(args.redis_port),
        "PEERGRAB_RMQ_PROXY_PORT": str(args.mq_port),
    }
    for user_id in (1001, 2001, 2002, 9001):
        values[f"PEERGRAB_AUTH_DEMO_PASSWORD_{user_id}"] = secrets.token_hex(16)

    lines = []
    for line in (DOCKER_DIR / "bench.env.example").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, current = line.split("=", 1)
            line = f"{key}={values.get(key, current)}"
        lines.append(line)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        parser.error(f"{args.output} already exists; choose a new file")
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write("\n".join(lines) + "\n")
    print(f"Created {args.output} with private permissions and unique credentials")


if __name__ == "__main__":
    main()
