#!/usr/bin/env bash
# Starts Valhalla on the tiles in /data (tools/build-valhalla-tiles.sh), set up for this app: one origin to up to 200
# targets per matrix call, within the 1 GB memory budget. Used by the load-test stack and by
# tools/build-valhalla-tiles.sh --serve. Usage: serve.sh [THREADS]
#
# With Valhalla's defaults it was killed for memory three seconds into the first load test: a car matrix used the
# two-way algorithm, which keeps a search open per target, and each worker thread kept up to 4 million search labels
# between requests. So matrices use the one-way algorithm (one search from the origin, which suits one origin and many
# targets) and threads keep at most a million labels. (A tile cache shared by all threads crashed it with a segfault
# under 8 calls at once, so each thread keeps its own, smaller one.)
set -euo pipefail
valhalla_build_config \
  --mjolnir-tile-dir /data/tiles --mjolnir-tile-extract /data/valhalla_tiles.tar \
  --mjolnir-admin /data/admins.sqlite --mjolnir-timezone /data/no-timezones.sqlite \
  --mjolnir-max-cache-size 67108864 \
  --thor-source-to-target-algorithm timedistancematrix \
  --thor-max-reserved-labels-count-dijkstras 1000000 --thor-max-reserved-labels-count-bidir-dijkstras 500000 \
  --thor-max-reserved-labels-count-astar 1000000 --thor-max-reserved-labels-count-bidir-astar 500000 \
  > /tmp/valhalla.json
exec valhalla_service /tmp/valhalla.json "${1:-4}"
