# A temporary AWS environment for the autoscaled load test: the api, live-hub and Valhalla on ECS Fargate, adding and
# removing copies as traffic changes, behind a private load balancer that only the load generator can reach. It is
# made for one test and destroyed after it (infra/aws/down.sh); the state stays on this machine, git-ignored.
terraform {
  required_version = "~> 1.16.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.67.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "3.9.1"
    }
  }
}

provider "aws" {
  region = var.region

  # Every resource carries these, so down.sh can check that nothing was left behind.
  default_tags {
    tags = {
      project   = "placemap"
      purpose   = "load-test"
      temporary = "true"
    }
  }
}
