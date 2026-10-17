# One internal load balancer, a port per service: 80 for the api, 8081 for live-hub (searches' live data and the
# viewers' streams), 8002 for Valhalla. A copy gets traffic once two health checks 10 s apart pass.
locals {
  balanced = {
    api = { port = 8080, listener = 80, health = "/actuator/health", algorithm = "least_outstanding_requests" }
    # Streams last minutes, so the count of open requests says little; new streams go round the copies.
    "live-hub" = { port = 8081, listener = 8081, health = "/actuator/health", algorithm = "round_robin" }
    # A matrix takes 50 ms to a second: the copy with the fewest in flight gets the next one.
    valhalla = { port = 8002, listener = 8002, health = "/status", algorithm = "least_outstanding_requests" }
  }
}

resource "aws_lb" "main" {
  name               = var.name
  internal           = true
  load_balancer_type = "application"
  subnets            = aws_subnet.private[*].id
  security_groups    = [aws_security_group.this["alb"].id]
  idle_timeout       = 120 # live-hub writes a heartbeat every 15 s, so an open stream is never idle this long
}

resource "aws_lb_target_group" "this" {
  for_each                      = local.balanced
  name                          = "${var.name}-${each.key}"
  port                          = each.value.port
  protocol                      = "HTTP"
  target_type                   = "ip"
  vpc_id                        = aws_vpc.main.id
  deregistration_delay          = 30 # a copy being removed finishes its searches; its streams reconnect elsewhere
  load_balancing_algorithm_type = each.value.algorithm

  health_check {
    path                = each.value.health
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "this" {
  for_each          = local.balanced
  load_balancer_arn = aws_lb.main.arn
  port              = each.value.listener
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.key].arn
  }
}
