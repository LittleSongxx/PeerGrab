#!/usr/bin/env bash
# Destructively remove one verified, disposable benchmark stack. Never delete
# business rows by run_id: that left wallet/credit state inconsistent.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: PEERGRAB_BENCH_DISPOSABLE=YES PEERGRAB_BENCH_PROJECT=peergrab-bench-... \
       COMPOSE_PROJECT_NAME=peergrab-bench-... PEERGRAB_BENCH_BASE_URL=http://127.0.0.1:PORT \
       PEERGRAB_TEST_DB_HOST=127.0.0.1 PEERGRAB_TEST_DB_PORT=PORT \
       cleanup.sh --env-file=/path/to/.env.bench --destroy --confirm=peergrab-bench-...

The command removes that project's containers, network, and its three named
volumes after checking their live Docker labels, port bindings and DB marker.
Export any reports you need first. Per-run and orphan deletion are disabled.
EOF
}

env_file=""
destroy=false
confirm=""
for arg in "$@"; do
  case "$arg" in
    --env-file=*) env_file=${arg#*=} ;;
    --destroy) destroy=true ;;
    --confirm=*) confirm=${arg#*=} ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unsupported option $arg" >&2; usage >&2; exit 1 ;;
  esac
done
project=${PEERGRAB_BENCH_PROJECT:-}
if [[ "$destroy" != true || -z "$env_file" || ! -f "$env_file" || "$confirm" != "$project" || -z "$project" ]]; then
  echo "Refusing cleanup: explicit --destroy, --env-file and matching --confirm are required" >&2
  exit 1
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
docker_dir=$(cd "$script_dir/../../docker" && pwd)
python3 "$script_dir/preflight.py" --project "$project" \
  --base-url "${PEERGRAB_BENCH_BASE_URL:-}" \
  --db-host "${PEERGRAB_TEST_DB_HOST:-}" --db-port "${PEERGRAB_TEST_DB_PORT:-}"

compose=(docker compose -p "$project" --env-file "$env_file"
  -f "$docker_dir/docker-compose.yaml"
  -f "$docker_dir/docker-compose.full.yaml"
  -f "$docker_dir/docker-compose.bench.yaml")
# Verify the exact resolved model before allowing Compose's volume removal.
"${compose[@]}" config --format json | python3 -c '
import json, os, sys
c = json.load(sys.stdin)
p = os.environ["PEERGRAB_BENCH_PROJECT"]
expected = {"peergrab-mysql-data", "peergrab-redis-data", "peergrab-rmq-broker-store"}
assert c["name"] == p and set(c["volumes"]) == expected
for name, v in c["volumes"].items():
    assert v["name"] == p + "_" + name and not v.get("external")
    assert v["labels"]["org.peergrab.bench.disposable"] == "true"
print("Resolved Compose model matches disposable project and three owned volumes")
'
"${compose[@]}" down --volumes
