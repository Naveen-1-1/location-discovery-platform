#!/usr/bin/env bash
# Asks the load generator to park the environment once a run finishes (loadgen/park.sh), so it need not wait for
# this machine. run.sh does this for every run that is not quick; use this for a run started without it.
# Usage: park.sh RUN
set -euo pipefail
source "$(dirname "$0")/common.sh"
run="${1:?usage: park.sh RUN}"
bucket="$(out bucket)"
aws s3 cp "$HERE/loadgen/park.sh" "s3://$bucket/bundle/$run/park.sh" --only-show-errors
python3 -I - "$(out loadgen)" "$bucket" "$run" "$(out cluster)" "$AWS_REGION" > "$TMPDIR/placemap-park.json" <<'PY'
import json, sys
loadgen, bucket, run, cluster, region = sys.argv[1:]
commands = [
    f"aws s3 cp s3://{bucket}/bundle/{run}/park.sh /opt/placemap/park-{run}.sh --only-show-errors",
    f"nohup bash /opt/placemap/park-{run}.sh {run} {cluster} {region} > /opt/placemap/park-{run}.log 2>&1 < /dev/null &",
    "echo parking armed",
]
print(json.dumps({"InstanceIds": [loadgen], "DocumentName": "AWS-RunShellScript",
                  "Comment": f"park after {run}", "Parameters": {"commands": commands}}))
PY
aws ssm send-command --cli-input-json "file://$TMPDIR/placemap-park.json" --query Command.CommandId --output text > /dev/null
echo "PARKING ARMED for $run"
