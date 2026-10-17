#!/usr/bin/env bash
# Read-only checks before the AWS test: who the CLI signs in as, the account's plan, MFA, the budget, and whether the
# account may run what the test needs (Fargate's and EC2's vCPU limits, the load generator's machine type). Prints no
# keys or tokens and masks the account number; a copy goes to target/aws-check.txt.
set -uo pipefail
source "$(dirname "$0")/common.sh"
mkdir -p "$MAIN/target"
{
  if ! arn=$(aws sts get-caller-identity --query Arn --output text 2>&1); then
    echo "Sign-in: FAILED. Run: aws login"; exit 1
  fi
  account=$(aws sts get-caller-identity --query Account --output text)
  mask() { sed "s/$account/<account>/g"; }
  echo "Signed in as: $(mask <<< "$arn")"
  echo "CLI region: $(aws configure get region || echo unset); the scripts use $AWS_REGION"
  echo "Plan (type, status, credits left, ends): $(aws freetier get-account-plan-state --query \
    '[accountPlanType, accountPlanStatus, accountPlanRemainingCredits.amount, accountPlanExpirationDate]' \
    --output text 2>&1 | mask)"
  echo "Root MFA on (1 = yes): $(aws iam get-account-summary --query SummaryMap.AccountMFAEnabled --output text 2>&1)"
  if [[ $arn == *":user/"* ]]; then
    echo "MFA devices on ${arn##*/}: $(aws iam list-mfa-devices --user-name "${arn##*/}" \
      --query 'length(MFADevices)' --output text 2>&1)"
  fi
  echo "Budgets (name, limit, period):"
  aws budgets describe-budgets --account-id "$account" --query 'Budgets[].[BudgetName, BudgetLimit.Amount, TimeUnit]' \
    --output text 2>&1 | sed 's/^/  /'
  aws budgets describe-budgets --account-id "$account" --query 'Budgets[].BudgetName' --output json 2>/dev/null |
    python3 -I -c "import json,sys; [print(n) for n in json.load(sys.stdin) or []]" |
    while IFS= read -r budget; do
      echo "  alerts on $budget: $(aws budgets describe-notifications-for-budget --account-id "$account" \
        --budget-name "$budget" --query 'Notifications[].[NotificationType, Threshold, ThresholdType]' \
        --output text 2>&1 | tr '\t\n' ' ;')"
    done
  echo "Fargate vCPUs at once (the test needs up to 41): $(aws service-quotas get-service-quota \
    --service-code fargate --quota-code L-3032A538 --query Quota.Value --output text 2>&1)"
  echo "EC2 standard vCPUs at once (the load generator needs 8): $(aws service-quotas get-service-quota \
    --service-code ec2 --quota-code L-1216C47A --query Quota.Value --output text 2>&1)"
  echo "Quota requests so far: $(aws service-quotas list-requested-service-quota-change-history --service-code fargate \
    --query 'RequestedQuotas[].[QuotaName, DesiredValue, Status]' --output text 2>&1 | tr '\t\n' ' ;')"
  ami=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
    --query Parameter.Value --output text 2>&1)
  echo "May the account start a c7g.2xlarge (dry run): $(aws ec2 run-instances --dry-run --image-id "$ami" \
    --instance-type c7g.2xlarge 2>&1 | mask | tr '\n' ' ')"
  echo "Terraform: $(terraform version 2>&1 | head -1)"
} 2>&1 | tee "$MAIN/target/aws-check.txt"
