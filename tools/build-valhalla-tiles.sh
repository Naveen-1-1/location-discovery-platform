#!/usr/bin/env bash
# Builds Valhalla routing tiles for Massachusetts into data/valhalla/ (git-ignored), for the load-test stack and the
# routing contract test. The same steps run on the Mac and in CI. Needs Docker; while it builds (a few minutes), give it
# 3-4 GB and run nothing else heavy.
#
#   tools/build-valhalla-tiles.sh           download the Geofabrik extract if it changed, then build the tiles
#   tools/build-valhalla-tiles.sh --serve   then serve them on 127.0.0.1:8002 until Ctrl-C
#
# A rebuild is skipped when the tiles were already built from this extract with this image (data/valhalla/BUILT_FROM).
set -euo pipefail
cd "$(dirname "$0")/.."

# The newest Valhalla release at least 14 days old (3.9.0, 2026-09-19), multi-arch, pinned by digest.
IMAGE="ghcr.io/valhalla/valhalla:3.9.0@sha256:511c095b8caf393dccceb8b519ec96b6f85a0166b2288ba014a8a748acc5a63c"
URL=https://download.geofabrik.de/north-america/us/massachusetts-latest.osm.pbf
OUT=data/valhalla
PBF=$OUT/massachusetts-latest.osm.pbf

md5_of() {
  python3 -I -c 'import hashlib, sys
h = hashlib.md5()
with open(sys.argv[1], "rb") as f:
    for chunk in iter(lambda: f.read(1 << 20), b""):
        h.update(chunk)
print(h.hexdigest())' "$1"
}

mkdir -p "$OUT"

# -z with -R: download only when Geofabrik's file is newer than ours (it refreshes daily, about 300 MB).
zflag=()
[[ -f $PBF ]] && zflag=(-z "$PBF")
echo "Checking $URL ..."
code=$(curl -fsSL -R ${zflag[@]+"${zflag[@]}"} -o "$PBF.part" -w '%{http_code}' "$URL")   # macOS bash 3.2 safe
if [[ $code == 200 && -s $PBF.part ]]; then
  expected=$(curl -fsSL "$URL.md5" | cut -d' ' -f1)
  actual=$(md5_of "$PBF.part")
  if [[ $expected != "$actual" ]]; then
    rm -f "$PBF.part"
    echo "MD5 mismatch (Geofabrik may have replaced the file during the download); run again." >&2
    exit 1
  fi
  mv "$PBF.part" "$PBF"
  echo "Downloaded $(du -h "$PBF" | cut -f1), MD5 $actual"
else
  rm -f "$PBF.part"
  echo "The extract is unchanged."
fi

stamp="$IMAGE $(md5_of "$PBF")"
if [[ -f $OUT/valhalla_tiles.tar && -f $OUT/BUILT_FROM && $(cat "$OUT/BUILT_FROM") == "$stamp" ]]; then
  echo "Tiles are up to date ($OUT/valhalla_tiles.tar)."
else
  echo "Building tiles with $IMAGE (a few minutes) ..."
  rm -f "$OUT/BUILT_FROM"
  # Admin areas from the same extract give the state's road rules (the neighbouring states' outlines are cut off, so
  # their warnings are expected); time zones are left out because nothing here asks for a departure time. The
  # finished tiles go into one tar file that the service maps into memory.
  docker run --rm -v "$PWD/$OUT:/data" "$IMAGE" bash -euo pipefail -c '
    valhalla_build_config --mjolnir-tile-dir /data/tiles --mjolnir-tile-extract /data/valhalla_tiles.tar \
      --mjolnir-admin /data/admins.sqlite --mjolnir-timezone /data/no-timezones.sqlite > /data/valhalla.json
    valhalla_build_admins -c /data/valhalla.json /data/massachusetts-latest.osm.pbf
    valhalla_build_tiles -c /data/valhalla.json /data/massachusetts-latest.osm.pbf
    valhalla_build_extract -c /data/valhalla.json --overwrite -v
    rm -rf /data/tiles'
  echo "$stamp" > "$OUT/BUILT_FROM"
  echo "Built $OUT/valhalla_tiles.tar ($(du -h "$OUT/valhalla_tiles.tar" | cut -f1))"
fi

if [[ ${1:-} == --serve ]]; then
  echo "Serving on http://127.0.0.1:8002 (Ctrl-C stops it)"
  exec docker run --rm --name placemap-valhalla -p 127.0.0.1:8002:8002 -v "$PWD/$OUT:/data:ro" \
    -v "$PWD/infra/docker/valhalla/serve.sh:/serve.sh:ro" --memory 1g "$IMAGE" bash /serve.sh 2
fi
