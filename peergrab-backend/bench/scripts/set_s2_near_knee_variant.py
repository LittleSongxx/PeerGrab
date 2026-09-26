#!/usr/bin/env python3
"""Switch one isolated S2 benchmark app between guarded A/B/A tuning variants.

Run this only between load stages during an approved maintenance window. The
benchmark database, broker, worker and their named volumes are never recreated.
On failure, the original private env bytes and app configuration are restored.
"""

import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import sys

import run_s2_ablations as ablation


VARIANTS = {"baseline": (20, 200), "hikari8": (8, 200), "tomcat64": (20, 64)}
PROD_PROJECT = "peergrab-prod"


def require_maintenance():
    if os.getenv("PEERGRAB_MAINTENANCE_APPROVED") != "YES":
        raise ablation.Stopped("approved maintenance opt-in is required")
    running = ablation.preflight.docker(
        "ps", "-q", "--filter", f"label={ablation.PROJECT_LABEL}={PROD_PROJECT}")
    if running:
        raise ablation.Stopped("production containers are running")


def verified_stack(project, stable_ids, volumes, mysql_id, hikari, tomcat, limits):
    require_maintenance()
    stack = ablation.first_round.checked_stack()
    if (stack["project"] != project or stack["mysql_container_id"] != mysql_id
            or stack["volumes"] != volumes):
        raise ablation.Stopped("benchmark project, database or volumes changed")
    app_id = ablation.verify_app(project, hikari, tomcat, stable_ids, limits)
    return stack, app_id


def change(args):
    require_maintenance()
    env_path = Path(args.env_file)
    original, values = ablation.private_env(env_path, require_baseline=False)
    env_path = env_path.resolve()
    current_pair = (int(values["PEERGRAB_BENCH_HIKARI_POOL_SIZE"]),
                    int(values["PEERGRAB_BENCH_TOMCAT_THREADS_MAX"]))
    if current_pair not in VARIANTS.values():
        raise ablation.Stopped("current benchmark tuning is not a supported variant")
    target_pair = VARIANTS[args.variant]
    ablation.apply_environment(values)
    os.environ["PEERGRAB_TEST_DB_PASSWORD"] = values["PEERGRAB_MYSQL_PASSWORD"]
    # checked_stack verifies the disposable project labels, loopback ports,
    # database guard row, cross-service targets and local API health.
    stack = ablation.first_round.checked_stack()
    project = stack["project"]
    if project != values["COMPOSE_PROJECT_NAME"]:
        raise ablation.Stopped("env and live benchmark project differ")
    ablation.first_round.require_s2_seed(stack["mysql_container_id"])
    ablation.first_round.check_memory()
    containers = ablation.project_containers(project)
    compose_files = ablation.compose_files_for_project(containers, args.compose_overlay)
    stable_ids = {service: container["Id"] for service, container in containers.items()
                  if service != "app"}
    limits = ablation.app_limits(containers["app"])
    _, old_app = verified_stack(project, stable_ids, stack["volumes"],
                                stack["mysql_container_id"], *current_pair, limits)
    report = {"project": project, "fromVariant": next(name for name, pair in VARIANTS.items()
                                                         if pair == current_pair),
              "toVariant": args.variant, "oldAppId": old_app,
              "mysqlContainerId": stack["mysql_container_id"], "volumes": stack["volumes"],
              "composeFiles": [str(path) for path in compose_files], "appLimits": limits}
    if args.dry_run or target_pair == current_pair:
        return {**report, "changed": False, "dryRun": args.dry_run}

    target_bytes = ablation.setting_bytes(original, *target_pair)
    replaced = False
    try:
        require_maintenance()
        ablation.restore_or_replace(env_path, original, target_bytes)
        replaced = True
        ablation.recreate_app(env_path, project, *target_pair, compose_files)
        _, new_app = verified_stack(project, stable_ids, stack["volumes"],
                                    stack["mysql_container_id"], *target_pair, limits)
        ablation.first_round.check_memory()
        return {**report, "changed": True, "newAppId": new_app}
    except BaseException as failure:
        if not replaced:
            raise
        try:
            ablation.restore_or_replace(env_path, target_bytes, original)
            ablation.recreate_app(env_path, project, *current_pair, compose_files)
            verified_stack(project, stable_ids, stack["volumes"],
                           stack["mysql_container_id"], *current_pair, limits)
        except BaseException as rollback:
            raise ablation.Stopped(
                f"variant change failed; automatic rollback failed: {rollback}") from failure
        raise ablation.Stopped(f"variant change failed; original app restored: {failure}") from failure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--compose-overlay", type=Path, required=True,
                        help="exact fourth Compose file recorded on live benchmark containers")
    parser.add_argument("--variant", choices=VARIANTS, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    def handle_signal(signum, _frame):
        raise ablation.Stopped(f"signal {signum} requested stop")

    signal.signal(signal.SIGTERM, handle_signal)
    try:
        # The lock also excludes the older, whole-sequence S2 ablation runner.
        _, values = ablation.private_env(args.env_file, require_baseline=False)
        ablation.RUNS_ROOT.mkdir(parents=True, exist_ok=True)
        lock = ablation.RUNS_ROOT / f'.{values["COMPOSE_PROJECT_NAME"]}.s2-ablations.lock'
        descriptor = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            print(json.dumps(change(args), sort_keys=True))
        finally:
            os.close(descriptor)
    except BaseException as exc:
        print(f"S2 variant switch stopped: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
