#!/usr/bin/env bash
# Downloads the coverage-area town polygons (EPSG:4326) from MassGIS into data/area/area.geojson.
# Usage: tools/fetch-area.sh            (towns are read from data/area.yaml)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
layer="https://services1.arcgis.com/hGdibHYSPO59RG1h/arcgis/rest/services/Massachusetts_Municipalities/FeatureServer/1"

# Read the `towns:` list from data/area.yaml without needing a YAML parser.
towns=$(awk '/^towns:/{f=1;next} f&&/^  - /{print $2;next} f&&!/^  /{f=0}' "$root/data/area.yaml")
in_list=$(printf "'%s'," $towns | sed 's/,$//')

out="$root/data/area/area.geojson"
mkdir -p "$(dirname "$out")"
curl -sS --fail -G "$layer/query" \
  --data-urlencode "where=TOWN IN ($in_list)" \
  --data-urlencode "outFields=TOWN,TOWN_ID" \
  --data-urlencode "outSR=4326" \
  --data-urlencode "f=geojson" \
  -o "$out.tmp"

count=$(python3 -I -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["features"]))' "$out.tmp")
expected=$(echo "$towns" | wc -w | tr -d ' ')
if [[ "$count" != "$expected" ]]; then
  echo "expected $expected towns, got $count" >&2
  exit 1
fi
mv "$out.tmp" "$out"
echo "wrote $out ($count towns: $(echo $towns | tr '\n' ' '))"
