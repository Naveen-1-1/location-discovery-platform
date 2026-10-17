#!/usr/bin/env bash
# Creates the test environment, in steps:
#   up.sh plan             show what would be made; changes nothing
#   up.sh images           make the registry and bucket (cents a month) and push the images (images.sh)
#   up.sh apply [ARGS...]  make or change the rest (costs start here), start each scaled service at its minimum, and
#                          wait until every service is steady and the load generator answers Session Manager; ARGS go
#                          to terraform, e.g. -var-file=small.tfvars
set -euo pipefail
source "$(dirname "$0")/common.sh"
tf init -input=false > /dev/null
step="${1:-}"
shift || true
case "$step" in
  plan)
    tf plan -input=false -var image_tag=planned "$@" | tail -n 3 ;;
  images)
    tf apply -input=false -auto-approve -target=aws_ecr_repository.this -target=aws_s3_bucket.work
    "$HERE/images.sh" ;;
  apply)
    [[ -f "$HERE/images.auto.tfvars" ]] || { echo "No images yet: run up.sh images first"; exit 1; }
    tf apply -input=false -auto-approve "$@"
    cluster="$(out cluster)"
    # Each scaled service starts a test at its minimum, whatever an earlier run scaled it to.
    while read -r service min; do
      aws ecs update-service --cluster "$cluster" --service "$service" --desired-count "$min" > /dev/null
      echo "$service starts at $min"
    done < <(aws application-autoscaling describe-scalable-targets --service-namespace ecs \
      --query 'ScalableTargets[].[ResourceId, MinCapacity]' --output text | sed 's#^service/[^/]*/##')
    echo "Waiting for every service to be steady (up to 10 minutes)..."
    aws ecs wait services-stable --cluster "$cluster" --services api live-hub valhalla postgis standins photon
    loadgen="$(out loadgen)"
    echo "Waiting for the load generator to answer Session Manager..."
    until [[ "$(aws ssm describe-instance-information --filters "Key=InstanceIds,Values=$loadgen" \
      --query 'InstanceInformationList[0].PingStatus' --output text)" == "Online" ]]; do sleep 10; done
    echo "ENVIRONMENT UP" ;;
  *)
    echo "usage: up.sh plan|images|apply [terraform args]"; exit 2 ;;
esac
