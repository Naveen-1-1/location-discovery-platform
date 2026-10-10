#!/usr/bin/env bash
# One entry point for local development. Every command loads .env first.
#
#   tools/dev.sh migrate   apply database migrations to Neon
#   tools/dev.sh seed      load the temporary Overture places (Phase 1)
#   tools/dev.sh tiles     build the Boston basemap file for the frontend
#   tools/dev.sh api       build and start the api service on :8080
#   tools/dev.sh web       start the map frontend on :5173 (proxies /api to :8080)
#   tools/dev.sh drill     with the api running in drill mode: send failing requests to test alerting
set -euo pipefail
cd "$(dirname "$0")/.."

load_env() {
  if [[ ! -f .env ]]; then
    echo "No .env file. Copy .env.example to .env and fill it in." >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
}

case "${1:-help}" in
  migrate)
    load_env
    ./mvnw -B -ntp -pl db flyway:migrate flyway:info
    ;;
  seed)
    load_env
    tools/seed-overture.sh
    ;;
  tiles)
    tools/build-pmtiles.sh
    ;;
  api)
    load_env
    ./mvnw -q -B -pl services/api -am package -DskipTests
    exec java -jar services/api/target/api.jar
    ;;
  drill)
    # Needs the api started with: PLACEMAP_DRILLS=true tools/dev.sh api
    for i in $(seq 1 "${2:-30}"); do
      curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/_drills/error
      sleep 1
    done
    echo
    ;;
  web)
    cd frontend
    [[ -d node_modules ]] || npm ci
    exec npm run dev
    ;;
  *)
    sed -n '2,9p' "$0"
    ;;
esac
