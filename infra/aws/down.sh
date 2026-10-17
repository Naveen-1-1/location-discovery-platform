#!/usr/bin/env bash
# Deletes everything the test made (images and results in the bucket too: copy them first with watch.sh), then lists
# anything still tagged as the test's. AWS's tag index can lag a few minutes behind a deletion.
set -euo pipefail
source "$(dirname "$0")/common.sh"
cluster="$(out cluster 2>/dev/null || echo placemap-load)"
tf destroy -input=false -auto-approve
# Container Insights makes this log group itself, so Terraform does not know it.
aws logs delete-log-group --log-group-name "/aws/ecs/containerinsights/$cluster/performance" 2>/dev/null || true
echo "Still tagged as the test's (empty means all gone):"
aws resourcegroupstaggingapi get-resources --tag-filters Key=purpose,Values=load-test \
  --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n' | sed 's/[0-9]\{12\}/<account>/'
echo "Running instances: $(aws ec2 describe-instances --filters Name=tag:purpose,Values=load-test \
  Name=instance-state-name,Values=pending,running,stopping,stopped --query 'length(Reservations)' --output text)"
echo "DOWN"
