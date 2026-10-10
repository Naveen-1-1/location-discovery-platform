#!/usr/bin/env bash
# Cuts a Greater-Boston basemap from the latest Protomaps daily build (ADR 0007) into frontend/public/tiles/.
# Reads only the needed byte ranges of the ~140 GB planet file. Usage: tools/dev.sh tiles
set -euo pipefail
cd "$(dirname "$0")/.."

# Four towns plus a margin, so the map doesn't end at the city limits. Statewide extract is for deployment.
BBOX="${BBOX:--71.27,42.20,-70.80,42.46}"
MAXZOOM="${MAXZOOM:-15}"
OUT=frontend/public/tiles/boston.pmtiles

build=$(curl -sS --fail https://build-metadata.protomaps.dev/builds.json \
  | python3 -I -c 'import json,sys; print(sorted(b["key"] for b in json.load(sys.stdin))[-1])')
echo "Protomaps build: $build  bbox: $BBOX  maxzoom: $MAXZOOM"

mkdir -p "$(dirname "$OUT")"
pmtiles extract "https://build.protomaps.com/$build" "$OUT.tmp" --bbox="$BBOX" --maxzoom="$MAXZOOM"
mv "$OUT.tmp" "$OUT"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1)) from $build" | tee frontend/public/tiles/SOURCE.txt
