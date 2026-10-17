output "region" {
  value = var.region
}

output "cluster" {
  value = aws_ecs_cluster.main.name
}

output "alb" {
  description = "The private load balancer's name: api on 80, live-hub on 8081, Valhalla on 8002."
  value       = aws_lb.main.dns_name
}

output "alb_suffix" {
  value = aws_lb.main.arn_suffix
}

output "target_groups" {
  value = { for k, tg in aws_lb_target_group.this : k => tg.arn_suffix }
}

output "loadgen" {
  value = aws_instance.loadgen.id
}

output "bucket" {
  value = aws_s3_bucket.work.id
}

output "registry" {
  value = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

output "repositories" {
  value = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}
