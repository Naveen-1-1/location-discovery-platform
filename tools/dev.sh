#!/usr/bin/env bash
# One entry point for local development. Every command loads .env first.
#
#   tools/dev.sh migrate   apply database migrations to Neon
#   tools/dev.sh tiles     build the Boston basemap file for the frontend
#   tools/dev.sh api       build and start the api service on :8080
#   tools/dev.sh live      build and start live-hub on :8081 (live bikes over SSE; PLACEMAP_LIVE_THREADS=pool to compare)
#   tools/dev.sh web       start the map frontend on :5173 (proxies /api to :8080 and /live to :8081)
#   tools/dev.sh drill     with the api running in drill mode: send failing requests to test alerting
#   tools/dev.sh ingest    start the importer: imports each source on its cadence, resumes unfinished runs
#   tools/dev.sh ingest-once SOURCE... [--force]   import (or resume) these sources now, in order, then exit
#   tools/dev.sh sizes     show the size of every table in the database (ADR 0034 asks to measure it)
#   tools/dev.sh proofs c1|c2|backpressure   crash tests / backpressure benchmark on a throwaway local database
#   tools/dev.sh match-sample     draw the matching evaluation's sample from the database (data/eval/match-sample.csv)
#   tools/dev.sh match-evaluate   precision and recall from the labelled sample, and the owner's blind check
#   tools/dev.sh coverage         write docs/data/coverage.md: places, sources, facts and geocodes in the database
set -euo pipefail
cd "$(dirname "$0")/.."

# JDK 25 warns about libraries that load native code (H3, DuckDB) or use sun.misc.Unsafe (protobuf, inside the
# OpenTelemetry exporter). Both are expected here, so allow them explicitly instead of printing warnings every run.
JAVA_FLAGS=(--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow)

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

# If our own service (matched by its jar path) is already listening on the port, e.g. still running in another
# terminal tab, stop it so the new build can start. Anything else on the port is left alone.
stop_previous() {
  local port=$1 jar=$2 pid
  pid=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -1 || true)
  [[ -z $pid ]] && return 0
  if ps -o command= -p "$pid" | grep -q -- "$jar"; then
    echo "Stopping the previous instance on :$port (pid $pid)..."
    kill "$pid"
    for _ in $(seq 1 30); do
      lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 || return 0
      sleep 0.5
    done
    echo "pid $pid did not exit; stop it yourself (kill $pid)" >&2
    exit 1
  fi
  echo "Port $port is used by another program (pid $pid): $(ps -o command= -p "$pid")" >&2
  exit 1
}

case "${1:-help}" in
  migrate)
    load_env
    ./mvnw -B -ntp -pl db flyway:migrate flyway:info
    ;;
  tiles)
    tools/build-pmtiles.sh
    ;;
  api)
    load_env
    ./mvnw -q -B -pl services/api -am package -DskipTests
    stop_previous 8080 services/api/target/api.jar
    exec java "${JAVA_FLAGS[@]}" -jar services/api/target/api.jar
    ;;
  live)
    load_env
    ./mvnw -q -B -pl services/live-hub -am package -DskipTests
    stop_previous 8081 services/live-hub/target/live-hub.jar
    exec java "${JAVA_FLAGS[@]}" -jar services/live-hub/target/live-hub.jar
    ;;
  drill)
    # Needs the api started with: PLACEMAP_DRILLS=true tools/dev.sh api
    # Default: one failing request per second for 2 minutes, enough to hold the 5xx alert's 1-minute pending period.
    for i in $(seq 1 "${2:-120}"); do
      curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/_drills/error
      sleep 1
    done
    echo
    ;;
  ingest)
    load_env
    ./mvnw -q -B -pl services/ingest -am package -DskipTests
    exec java "${JAVA_FLAGS[@]}" -jar services/ingest/target/ingest.jar
    ;;
  ingest-once)
    load_env
    shift
    force=false
    sources=()
    for arg in "$@"; do
      if [[ $arg == --force ]]; then force=true; else sources+=("$arg"); fi
    done
    (( ${#sources[@]} )) || { echo "usage: tools/dev.sh ingest-once SOURCE... [--force]" >&2; exit 1; }
    ./mvnw -q -B -pl services/ingest -am package -DskipTests
    # One process per source, in the order given: later sources match against the places earlier ones created.
    for source_id in "${sources[@]}"; do
      java "${JAVA_FLAGS[@]}" -jar services/ingest/target/ingest.jar --placemap.ingest.once="$source_id" --placemap.ingest.force="$force"
    done
    ;;
  sizes)
    load_env
    psql -X -v ON_ERROR_STOP=1 -c "
      SELECT n.nspname || '.' || c.relname AS table_name, c.reltuples::bigint AS approx_rows,
             pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE c.relkind = 'r' AND n.nspname IN ('place', 'ingest', 'app')
      ORDER BY pg_total_relation_size(c.oid) DESC;" \
      -c "SELECT pg_size_pretty(pg_database_size(current_database())) AS database_size;"
    ;;
  proofs)
    shift
    exec python3 -I tools/ingest-proofs.py "$@"
    ;;
  match-sample|match-evaluate)
    load_env
    ./mvnw -q -B -pl services/ingest -am package -DskipTests
    exec java "${JAVA_FLAGS[@]}" -jar services/ingest/target/ingest.jar --placemap.ingest.tool.name="$1"
    ;;
  coverage)
    load_env
    exec python3 -I tools/coverage-report.py docs/data/coverage.md
    ;;
  web)
    cd frontend
    [[ -d node_modules ]] || npm ci
    exec npm run dev
    ;;
  *)
    sed -n '2,13p' "$0"
    ;;
esac
