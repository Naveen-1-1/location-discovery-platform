#!/bin/bash
# On the load generator, started by run.sh (or park.sh on the Mac) beside a run: once the run writes DONE, parks the
# environment so it costs little while nobody is watching. Every service goes to zero copies, with autoscaling held
# at zero, and this machine stops itself; the load balancer and the endpoints stay (about $0.08 an hour) until
# down.sh. The results are already in the bucket. up.sh apply undoes the parking.
# Usage: park.sh RUN CLUSTER REGION
set -uo pipefail
run=$1 cluster=$2 region=$3
until [ -f "/opt/placemap/results/$run/DONE" ]; do sleep 60; done
sleep 90  # the driver's last copy to the bucket
for service in api valhalla live-hub; do
  aws application-autoscaling register-scalable-target --region "$region" --service-namespace ecs \
    --scalable-dimension ecs:service:DesiredCount --resource-id "service/$cluster/$service" \
    --min-capacity 0 --max-capacity 0
done
for service in api valhalla live-hub postgis standins photon; do
  aws ecs update-service --region "$region" --cluster "$cluster" --service "$service" --desired-count 0 > /dev/null
done
echo "parked $(date -u +%FT%TZ)"
shutdown -h +1
