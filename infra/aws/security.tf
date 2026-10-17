# One security group per part, and only the connections the test needs between them. Nothing on the internet can
# connect to anything here: the load balancer is internal and takes requests only from the load generator (and
# Valhalla and live-hub calls from the api); the load generator accepts only the services' metrics.
locals {
  tasks = ["api", "live-hub", "valhalla", "postgis", "standins", "photon"]

  ingress = [
    { to = "alb", port = 80, from = "loadgen" },   # searches
    { to = "alb", port = 8081, from = "loadgen" }, # live streams
    { to = "alb", port = 8081, from = "api" },     # live data in nearby results
    { to = "alb", port = 8002, from = "api" },     # travel times
    { to = "api", port = 8080, from = "alb" },
    { to = "live-hub", port = 8081, from = "alb" },
    { to = "valhalla", port = 8002, from = "alb" },
    { to = "postgis", port = 5432, from = "api" },
    { to = "photon", port = 8080, from = "api" },
    { to = "standins", port = 8080, from = "live-hub" },
    { to = "loadgen", port = 9090, from = "api" }, # metrics, pushed to the load generator's Prometheus
    { to = "loadgen", port = 9090, from = "live-hub" },
  ]
}

resource "aws_security_group" "this" {
  for_each    = toset(concat(local.tasks, ["alb", "loadgen"]))
  name        = "${var.name}-${each.key}"
  description = "${var.name}: ${each.key}"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-${each.key}" }
}

resource "aws_vpc_security_group_ingress_rule" "this" {
  for_each                     = { for r in local.ingress : "${r.to}-${r.port}-from-${r.from}" => r }
  security_group_id            = aws_security_group.this[each.value.to].id
  referenced_security_group_id = aws_security_group.this[each.value.from].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}

# The tasks and the load balancer reach only this network and S3 (ECR's image layers); there is no route out anyway.
resource "aws_vpc_security_group_egress_rule" "inside" {
  for_each          = toset(concat(local.tasks, ["alb"]))
  security_group_id = aws_security_group.this[each.key].id
  cidr_ipv4         = local.cidr
  ip_protocol       = "-1"
}

resource "aws_vpc_security_group_egress_rule" "s3" {
  for_each          = toset(local.tasks)
  security_group_id = aws_security_group.this[each.key].id
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# The load generator downloads its tools and talks to Session Manager over the internet.
resource "aws_vpc_security_group_egress_rule" "loadgen" {
  security_group_id = aws_security_group.this["loadgen"].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "endpoints" {
  name        = "${var.name}-endpoints"
  description = "${var.name}: ECR and CloudWatch Logs endpoints"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-endpoints" }
}

resource "aws_vpc_security_group_ingress_rule" "endpoints" {
  security_group_id = aws_security_group.endpoints.id
  cidr_ipv4         = local.cidr
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
