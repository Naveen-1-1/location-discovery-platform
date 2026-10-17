#!/usr/bin/env bash
# Deletes what the test made, then lists anything still tagged as the test's (AWS's tag index can lag a few minutes).
#   down.sh              everything, the images and the bucket's results too (copy them first with watch.sh)
#   down.sh keep-images  everything but the registry and the bucket (cents a month), so a later up.sh apply needs no
#                        new push
set -euo pipefail
source "$(dirname "$0")/common.sh"
cluster="$(out cluster 2>/dev/null || echo placemap-load)"
if [[ "${1:-}" == "keep-images" ]]; then
  targets=()
  while IFS= read -r address; do
    case "$address" in
      aws_ecr_repository.*|aws_s3_bucket.work|aws_s3_bucket_public_access_block.work|random_id.bucket|data.*) ;;
      *) targets+=("-target=$address") ;;
    esac
  done < <(tf state list)
  [[ ${#targets[@]} -eq 0 ]] || tf destroy -input=false -auto-approve "${targets[@]}"
else
  tf destroy -input=false -auto-approve
fi
# Container Insights makes this log group itself, so Terraform does not know it.
aws logs delete-log-group --log-group-name "/aws/ecs/containerinsights/$cluster/performance" 2>/dev/null || true
echo "Still tagged as the test's (empty means all gone):"
aws resourcegroupstaggingapi get-resources --tag-filters Key=purpose,Values=load-test \
  --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n' | sed 's/[0-9]\{12\}/<account>/'
echo "Running instances: $(aws ec2 describe-instances --filters Name=tag:purpose,Values=load-test \
  Name=instance-state-name,Values=pending,running,stopping,stopped --query 'length(Reservations)' --output text)"
echo "DOWN"
