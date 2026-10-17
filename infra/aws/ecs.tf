# The app as the load-test stack runs it on one machine, but on Fargate (Graviton, as the Mac's arm64 images): the
# api, live-hub and Valhalla behind the load balancer and scaled (autoscaling.tf); PostGIS, restored from the load
# seed, and the stand-ins for Photon and the live feeds as single tasks, found by name in placemap.internal.
data "aws_caller_identity" "current" {}

resource "random_password" "database" {
  length  = 32
  special = false
}

locals {
  internal = "placemap.internal"
  alb      = "http://${aws_lb.main.dns_name}"

  # Metrics go to the load generator's Prometheus every 5 s, as in the stack; traces and logs stay out.
  telemetry = {
    PLACEMAP_ENV                           = "load"
    MANAGEMENT_OTLP_METRICS_EXPORT_URL     = "http://${aws_instance.loadgen.private_ip}:9090/api/v1/otlp/v1/metrics"
    MANAGEMENT_OTLP_METRICS_EXPORT_STEP    = "5s"
    MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED = "false"
    MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED = "false"
    # Each copy its own series: the task's metadata address is unique to it (Spring resolves the placeholder).
    MANAGEMENT_OPENTELEMETRY_RESOURCEATTRIBUTES_SERVICE_INSTANCE_ID = "$${ECS_CONTAINER_METADATA_URI_V4}"
  }

  # Each service's size, port, environment, command (null: the image's) and container health check (null: none).
  services = {
    api = {
      cpu     = 1024
      memory  = 2048
      port    = 8080
      command = null
      health  = null
      env = merge(local.telemetry, {
        SPRING_DATASOURCE_URL                = "jdbc:postgresql://postgis.${local.internal}:5432/placemap?sslmode=disable&ApplicationName=api"
        SPRING_DATASOURCE_USERNAME           = "postgres"
        SPRING_DATASOURCE_PASSWORD           = random_password.database.result
        SPRING_DATASOURCE_HIKARI_MINIMUMIDLE = "10" # the test's database never scales to zero
        PLACEMAP_ROUTING_ENGINE              = "valhalla"
        PLACEMAP_VALHALLA_URL                = "${local.alb}:8002"
        PLACEMAP_NEARBY_TRAVELTIMETIMEOUT    = "800ms" # the plan's value for an engine on the same network
        PLACEMAP_LIVE_URL                    = "${local.alb}:8081"
        PLACEMAP_SEARCH_PHOTONURL            = "http://photon.${local.internal}:8080/api/"
      })
    }
    "live-hub" = {
      cpu     = 1024
      memory  = 2048
      port    = 8081
      command = null
      health  = null
      env = merge(local.telemetry, {
        PLACEMAP_LIVE_THREADS               = "virtual"
        PLACEMAP_LIVE_BLUEBIKES_URL         = "http://standins.${local.internal}:8080/gbfs/en/station_status.json"
        PLACEMAP_LIVE_BLUEBIKES_MININTERVAL = "1s" # the stand-in's ttl is 1 s
        PLACEMAP_LIVE_MBTA_REALTIMEURL      = "http://standins.${local.internal}:8080/realtime/"
        PLACEMAP_LIVE_MBTA_GTFSURL          = "http://standins.${local.internal}:8080/MBTA_GTFS.zip"
        HEAP_PERCENT                        = "60" # as in the stack: the heap's share, not the streams, sets the size
        MALLOC_ARENA_MAX                    = "2"
      })
    }
    valhalla = {
      cpu     = var.valhalla_size.cpu
      memory  = var.valhalla_size.memory
      port    = 8002
      env     = {}
      command = ["bash", "/serve.sh", tostring(var.valhalla_size.threads)]
      health  = null
    }
    postgis = {
      cpu    = 2048
      memory = 4096
      port   = 5432
      env = {
        POSTGRES_PASSWORD = random_password.database.result
        POSTGRES_DB       = "placemap"
      }
      # Room for ten api copies' pools of ten. Fargate has no shm_size, so parallel queries share memory through files.
      command = [
        "postgres", "-c", "shared_buffers=1GB", "-c", "effective_cache_size=3GB", "-c", "work_mem=16MB",
        "-c", "max_connections=200", "-c", "jit=off", "-c", "random_page_cost=1.1",
        "-c", "dynamic_shared_memory_type=mmap",
      ]
      # Healthy once the seed is restored: until then the server listens only on its socket.
      health = {
        command     = ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U postgres -d placemap"]
        interval    = 5
        timeout     = 3
        retries     = 5
        startPeriod = 300
      }
    }
    standins = {
      cpu    = 512
      memory = 1024
      port   = 8080
      env    = {}
      # As the stack: 818 vehicles at MBTA's weekday morning peak, each reporting every 18 s, 80% inside the area.
      command = [
        "python3", "-I", "/standins/standins.py", "--stations", "/seed/stations.txt", "--changes-per-second", "5",
        "--vehicles", "818", "--vehicle-period", "18", "--vehicle-inner-share", "0.8",
      ]
      health = null
    }
    photon = {
      cpu     = 512
      memory  = 1024
      port    = 8080
      env     = {}
      command = ["--disable-banner", "--no-request-journal", "--disable-request-logging"]
      health  = null
    }
  }

  discovered = ["postgis", "standins", "photon"]
}

resource "aws_ecs_cluster" "main" {
  name = var.name
  setting {
    name  = "containerInsights" # task counts and each service's CPU and memory, a point a minute
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "this" {
  for_each          = toset(keys(local.services))
  name              = "/${var.name}/${each.key}"
  retention_in_days = 3
}

resource "aws_iam_role" "execution" {
  name = "${var.name}-execution"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ecs-tasks.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_service_discovery_private_dns_namespace" "main" {
  name = local.internal
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "this" {
  for_each = toset(local.discovered)
  name     = each.key
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
  force_destroy = true
}

resource "aws_ecs_task_definition" "this" {
  for_each                 = toset(keys(local.services))
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.services[each.key].cpu
  memory                   = local.services[each.key].memory
  execution_role_arn       = aws_iam_role.execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([merge(
    {
      name         = each.key
      image        = "${aws_ecr_repository.this[each.key].repository_url}:${var.image_tag}"
      essential    = true
      portMappings = [{ containerPort = local.services[each.key].port, protocol = "tcp" }]
      environment  = [for k, v in local.services[each.key].env : { name = k, value = v }]
      ulimits      = [{ name = "nofile", softLimit = 65536, hardLimit = 65536 }] # every stream is a socket
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this[each.key].name
          awslogs-region        = var.region
          awslogs-stream-prefix = each.key
        }
      }
    },
    local.services[each.key].command == null ? {} : { command = local.services[each.key].command },
    local.services[each.key].health == null ? {} : { healthCheck = local.services[each.key].health },
  )])
}

resource "aws_ecs_service" "this" {
  for_each                = toset(keys(local.services))
  name                    = each.key
  cluster                 = aws_ecs_cluster.main.id
  task_definition         = aws_ecs_task_definition.this[each.key].arn
  desired_count           = try(var.scaling[each.key].min, 1)
  launch_type             = "FARGATE"
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.this[each.key].id]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = contains(keys(local.balanced), each.key) ? [each.key] : []
    content {
      target_group_arn = aws_lb_target_group.this[each.key].arn
      container_name   = each.key
      container_port   = local.services[each.key].port
    }
  }

  dynamic "service_registries" {
    for_each = contains(local.discovered, each.key) ? [each.key] : []
    content {
      registry_arn = aws_service_discovery_service.this[each.key].arn
    }
  }

  # A JVM and its first health checks take about a minute; Valhalla loads its tiles.
  health_check_grace_period_seconds = contains(keys(local.balanced), each.key) ? 120 : null

  lifecycle {
    ignore_changes = [desired_count] # autoscaling owns it
  }

  depends_on = [aws_lb_listener.this, aws_iam_role_policy_attachment.execution]
}
