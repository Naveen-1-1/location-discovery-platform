#!/usr/bin/env bash
# Copies a run's results from the bucket into load/results/aws-<run>/ every minute and shows the driver's latest
# lines, until the run writes DONE.
set -euo pipefail
source "$(dirname "$0")/common.sh"
run="${1:?usage: watch.sh RUN}"
bucket="$(out bucket)"
dest="$MAIN/load/results/aws-$run"
mkdir -p "$dest"
while true; do
  aws s3 sync "s3://$bucket/results/$run/" "$dest/" --only-show-errors || true
  [[ -f "$dest/drive.log" ]] && tail -n 3 "$dest/drive.log"
  [[ -f "$dest/DONE" ]] && { echo "RUN FINISHED: $dest"; break; }
  sleep 60
done
