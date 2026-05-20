#!/usr/bin/env bash
# Recreate the local Postgres database so a changed schema.sql is re-applied.
#
# WHY: schema.sql runs only ONCE — on the first start of an empty Postgres data
# volume. After editing schema.sql you must drop the volume and start fresh, or
# the running database keeps the old schema.
#
# This resets the whole `core` stack (Postgres, Kafka, MinIO). Kafka topics and
# the MinIO bucket are recreated automatically, so only Postgres data is a real
# loss — and it is local dev data only. Never point this at a shared database.
#
# Usage (from the repo root):
#   .claude/skills/db-schema-change/scripts/recreate-db.sh

set -euo pipefail

COMPOSE="${COMPOSE_FILE:-.docker/docker-compose.yml}"

if [ ! -f "$COMPOSE" ]; then
  echo "Compose file not found: $COMPOSE" >&2
  echo "Run this script from the transform-platform repo root." >&2
  exit 1
fi

echo "This will DESTROY all local data in the 'core' Docker stack:"
echo "  - Postgres database  (schema + all rows)"
echo "  - Kafka topics / messages"
echo "  - MinIO objects"
echo
read -r -p "Recreate the database now? [y/N] " reply
case "$reply" in
  [yY] | [yY][eE][sS]) ;;
  *)
    echo "Aborted — no changes made."
    exit 0
    ;;
esac

echo "-> Stopping the core stack and removing its volumes..."
docker compose -f "$COMPOSE" --profile core down -v

echo "-> Starting the core stack (Postgres re-runs schema.sql on the empty volume)..."
docker compose -f "$COMPOSE" --profile core up -d

echo
echo "Done. Wait for Postgres to report healthy:"
echo "  docker compose -f $COMPOSE ps"
echo "Then restart the app:"
echo "  ./restart-app.sh"
