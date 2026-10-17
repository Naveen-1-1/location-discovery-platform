#!/bin/bash
# Restores the load seed into the new database. The image's entrypoint runs it once, while the server still listens
# only on its socket, so the task turns healthy (pg_isready over TCP) when the data is in.
set -euo pipefail
pg_restore --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --no-owner --exit-on-error /seed/placemap.dump
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -X -q -v ON_ERROR_STOP=1 -c ANALYZE
