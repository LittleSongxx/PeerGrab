#!/bin/sh
# Runs only on first initialization of the benchmark MySQL volume.
# MySQL sources initdb .sh files in its entrypoint shell. Avoid set -u here:
# it would leak into that shell and break later optional-variable checks.
set -e
case "${COMPOSE_PROJECT_NAME:-}" in
  peergrab-bench-[a-z0-9]*) ;;
  *) echo 'Refusing to initialize benchmark guard without peergrab-bench-* project' >&2; exit 1 ;;
esac
case "$COMPOSE_PROJECT_NAME" in
  *[!a-z0-9_-]*) echo 'Invalid benchmark project characters' >&2; exit 1 ;;
esac
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot peer_grab <<SQL
CREATE TABLE bench_guard (
  guard_key VARCHAR(32) PRIMARY KEY,
  project_name VARCHAR(128) NOT NULL
);
INSERT INTO bench_guard (guard_key, project_name) VALUES ('project', '${COMPOSE_PROJECT_NAME}');
SQL
