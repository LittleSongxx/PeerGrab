#!/usr/bin/env bash
# Logical backup of the authoritative funds database. Run from a trusted host.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <private-backup-directory>" >&2
  exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
backup_root=$1
env_file=${PEERGRAB_COMPOSE_ENV_FILE:-$script_dir/.env.prod}
compose_file=${PEERGRAB_COMPOSE_FILE:-$script_dir/docker-compose.prod.yaml}
test -r "$env_file" || { echo "missing Compose env: $env_file" >&2; exit 1; }
mkdir -p -- "$backup_root"
backup_root=$(cd -- "$backup_root" && pwd)
chmod 700 "$backup_root"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_dir=$backup_root/peergrab-mysql-$stamp
mkdir -m 700 -- "$backup_dir"

compose=(docker compose --env-file "$env_file" -f "$compose_file")
if ! "${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events --triggers --no-tablespaces --set-gtid-purged=OFF peer_grab' \
  | gzip -1 > "$backup_dir/peer_grab.sql.gz"; then
  rm -f -- "$backup_dir/peer_grab.sql.gz"
  echo "MySQL dump failed" >&2
  exit 1
fi
gzip -t "$backup_dir/peer_grab.sql.gz"
test -s "$backup_dir/peer_grab.sql.gz"
cp -p -- "$env_file" "$backup_dir/compose.env"
chmod 600 "$backup_dir/peer_grab.sql.gz" "$backup_dir/compose.env"
(cd -- "$backup_dir" && sha256sum peer_grab.sql.gz compose.env > SHA256SUMS)
echo "$backup_dir"
