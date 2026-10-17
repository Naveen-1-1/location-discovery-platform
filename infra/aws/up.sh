#!/usr/bin/env bash
# Creates the test environment. "up.sh plan" shows what would be made and changes nothing; "up.sh apply" makes the
# registry and bucket, pushes the images (images.sh), then makes the rest and waits until every service is steady
# and the load generator answers Session Manager. Costs start here (about $0.8 an hour at the minimum copies).
set -euo pipefail
source "$(dirname "$0")/common.sh"
tf init -input=false > /dev/null
case "${1:-}" in
  plan)
    tf plan -input=false -var image_tag=planned | tail -n 3 ;;
  apply)
    tf apply -input=false -auto-approve -target=aws_ecr_repository.this -target=aws_s3_bucket.work
    "$HERE/images.sh"
    tf apply -input=false -auto-approve
    cluster="$(out cluster)"
    echo "Waiting for every service to be steady (up to 10 minutes)..."
    aws ecs wait services-stable --cluster "$cluster" --services api live-hub valhalla postgis standins photon
    loadgen="$(out loadgen)"
    echo "Waiting for the load generator to answer Session Manager..."
    until [[ "$(aws ssm describe-instance-information --filters "Key=InstanceIds,Values=$loadgen" \
      --query 'InstanceInformationList[0].PingStatus' --output text)" == "Online" ]]; do sleep 10; done
    echo "ENVIRONMENT UP" ;;
  *)
    echo "usage: up.sh plan|apply"; exit 2 ;;
esac
