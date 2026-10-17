#!/usr/bin/env bash
# Builds the test's images and pushes them to the test's ECR repositories (up.sh creates them first): for the services,
# Graviton (linux/arm64, the Mac's own architecture); for the stream client, the load generator's platform. The api,
# live-hub and the stream client come from their jars; PostGIS with the
# load seed; Valhalla with the Massachusetts tiles; the feeds' and Photon's stand-ins. Writes the tag into
# images.auto.tfvars for Terraform. Needs Docker, Java 25, the seed (tools/dev.sh load seed) and the tiles
# (tools/dev.sh valhalla). The first push sends about 2 GB.
set -euo pipefail
source "$(dirname "$0")/common.sh"
DATA="$MAIN/data"
for f in "$DATA/load/placemap.dump" "$DATA/load/stations.txt" "$DATA/valhalla/valhalla_tiles.tar"; do
  [[ -f $f ]] || { echo "Missing $f: make the seed and tiles first (tools/dev.sh load seed, tools/dev.sh valhalla)"; exit 1; }
done
tag="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
if [[ -n "$(git -C "$ROOT" status --porcelain -- services libs load infra)" ]]; then
  tag="$tag-$(date -u +%Y%m%d%H%M%S)"  # uncommitted changes: never reuse a tag for different contents
fi
repositories="$(tf output -json repositories)"  # made by up.sh's first, partial apply
loadgen_platform="$(tf output -raw loadgen_platform 2>/dev/null || echo linux/amd64)"
repo() { python3 -I -c 'import json, sys; print(json.loads(sys.argv[1])[sys.argv[2]])' "$repositories" "$1"; }
aws ecr get-login-password | docker login --username AWS --password-stdin "$(repo api | cut -d/ -f1)"

echo "Building the jars..."
(cd "$ROOT" && ./mvnw -q -B -pl services/api,services/live-hub,load/sse-idle-client -am package -DskipTests)
docker build -q --platform linux/arm64 -t placemap/postgis-test "$ROOT/infra/docker/postgis" > /dev/null

build() {
  local name=$1; shift
  local image="$(repo "$name"):$tag"
  local platform=linux/arm64
  [[ $name != sse-client ]] || platform="$loadgen_platform"  # only copies a jar onto a JRE: no emulation needed
  echo "Building and pushing $name ($platform)..."
  docker build -q --platform "$platform" -t "$image" "$@" > /dev/null
  docker push -q "$image" > /dev/null
}
java_service() { build "$1" -f "$ROOT/infra/docker/java-service/Dockerfile" --build-arg "JAR=$2" "$ROOT/$3"; }
java_service api api.jar services/api/target
java_service live-hub live-hub.jar services/live-hub/target
java_service sse-client sse-idle-client.jar load/sse-idle-client/target
build postgis --build-context "seed=$DATA/load" "$HERE/images/postgis"
build valhalla --build-context "tiles=$DATA/valhalla" -f "$HERE/images/valhalla/Dockerfile" "$ROOT/infra/docker/valhalla"
build standins --build-context "seed=$DATA/load" -f "$HERE/images/standins/Dockerfile" "$ROOT/load/standins"
build photon -f "$HERE/images/photon/Dockerfile" "$ROOT/load/standins"
echo "image_tag = \"$tag\"" > "$HERE/images.auto.tfvars"
echo "IMAGES PUSHED: $tag"
