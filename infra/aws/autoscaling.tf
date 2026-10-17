# Target tracking: each scaled service keeps its average CPU near a target, and the api also its requests per copy.
# AWS adds copies after about three minutes above a target and removes them after about fifteen well below it; a
# service runs more copies while any of its policies asks for them.
resource "aws_appautoscaling_target" "this" {
  for_each           = var.scaling
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.this[each.key].name}"
  min_capacity       = each.value.min
  max_capacity       = each.value.max
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each           = var.scaling
  name               = "${var.name}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[each.key].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[each.key].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[each.key].resource_id

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = each.value.cpu
    scale_out_cooldown = 60
    scale_in_cooldown  = 120
  }
}

resource "aws_appautoscaling_policy" "api_requests" {
  name               = "${var.name}-api-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this["api"].service_namespace
  scalable_dimension = aws_appautoscaling_target.this["api"].scalable_dimension
  resource_id        = aws_appautoscaling_target.this["api"].resource_id

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget" # requests a minute
      resource_label         = "${aws_lb.main.arn_suffix}/${aws_lb_target_group.this["api"].arn_suffix}"
    }
    target_value       = var.api_requests_per_copy * 60
    scale_out_cooldown = 60
    scale_in_cooldown  = 120
  }
}
