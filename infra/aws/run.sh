#!/usr/bin/env bash
# Starts a test on the load generator through Session Manager (no SSH) and returns at once; watch.sh follows it.
# "run.sh quick" checks the setup in about 10 minutes; "run.sh full" runs the ramp, the soak to a million requests and
# the cool-down (about 2 hours); "run.sh soak RATE" soaks at RATE without a ramp. Further arguments go to drive.py
# (e.g. --soak-max-minutes 180). Every run but a quick one also parks the environment when it ends (loadgen/park.sh),
# so nothing costs much if this machine is asleep by then. Prints the run's id.
set -euo pipefail
source "$(dirname "$0")/common.sh"
mode="${1:-full}"
run="$(date -u +%Y%m%d-%H%M%S)"
bucket="$(out bucket)"
extra=()
case "$mode" in
  quick) extra=(--quick); run="$run-quick" ;;
  soak) extra=(--soak-rate "${2:?usage: run.sh soak RATE}" "${@:3}") ;;
  full) extra=("${@:2}") ;;
  *) echo "usage: run.sh [quick | full | soak RATE] [drive.py options]"; exit 2 ;;
esac
bundle="$(mktemp -d)"
cp -R "$ROOT/load/k6" "$bundle/k6"
cp "$HERE/loadgen/drive.py" "$HERE/loadgen/park.sh" "$bundle/"
cp "$MAIN/data/load/origins.json" "$bundle/"  # the seed's search origins, as the stack's driver writes them
aws s3 sync "$bundle" "s3://$bucket/bundle/$run/" --only-show-errors
rm -rf "$bundle"
args=(--bundle "/opt/placemap/bundle/$run" --run "$run" --alb "$(out alb)" --cluster "$(out cluster)"
  --bucket "$bucket" --region "$AWS_REGION" --sse-image "$(tf output -json repositories |
  python3 -I -c 'import json,sys; print(json.load(sys.stdin)["sse-client"])'):$(sed -E 's/.*"(.*)"/\1/' \
  "$HERE/images.auto.tfvars")" --alb-suffix "$(out alb_suffix)" --target-groups "$(tf output -json target_groups)"
  ${extra[@]+"${extra[@]}"})  # empty arrays trip set -u in the Mac's bash 3.2
park="$([[ $mode == quick ]] && echo no || echo yes)"
python3 -I - "$(out loadgen)" "$bucket" "$run" "$park" "$(out cluster)" "$AWS_REGION" "${args[@]}" \
  > "$TMPDIR/placemap-run.json" <<'PY'
import json, shlex, sys
loadgen, bucket, run, park, cluster, region, args = *sys.argv[1:7], sys.argv[7:]
commands = [
    f"mkdir -p /opt/placemap/bundle/{run}",
    f"aws s3 sync s3://{bucket}/bundle/{run}/ /opt/placemap/bundle/{run}/ --only-show-errors",
    f"nohup python3 -I /opt/placemap/bundle/{run}/drive.py {shlex.join(args)} "
    f"> /opt/placemap/{run}.log 2>&1 < /dev/null &",
]
if park == "yes":
    commands.append(f"nohup bash /opt/placemap/bundle/{run}/park.sh {run} {cluster} {region} "
                    f"> /opt/placemap/park-{run}.log 2>&1 < /dev/null &")
commands.append("echo started")
print(json.dumps({"InstanceIds": [loadgen], "DocumentName": "AWS-RunShellScript",
                  "Comment": f"placemap load test {run}", "Parameters": {"commands": commands}}))
PY
aws ssm send-command --cli-input-json "file://$TMPDIR/placemap-run.json" --query Command.CommandId --output text > /dev/null
echo "RUN STARTED: $run (follow it with: infra/aws/watch.sh $run)"
