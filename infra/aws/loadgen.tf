# The load generator: one Graviton machine running k6, the stream client and a Prometheus that takes k6's results
# and the services' metrics. No SSH and no open ports: run.sh starts the test through Session Manager, and the
# results go to the bucket. Its role can read the test's images, services, metrics and bucket, and nothing else.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

resource "aws_iam_role" "loadgen" {
  name = "${var.name}-loadgen"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy_attachment" "loadgen_ssm" {
  role       = aws_iam_role.loadgen.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "loadgen" {
  name = "test"
  role = aws_iam_role.loadgen.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = "ecr:GetAuthorizationToken", Resource = "*" },
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
        Resource = aws_ecr_repository.this["sse-client"].arn
      },
      {
        Effect = "Allow"
        Action = ["ecs:DescribeServices", "ecs:ListTasks", "ecs:DescribeTasks", "cloudwatch:GetMetricData",
        "elasticloadbalancing:DescribeTargetHealth"]
        Resource = "*" # read-only; these take no narrower resource worth the trouble in a throwaway account
      },
      { Effect = "Allow", Action = "s3:ListBucket", Resource = aws_s3_bucket.work.arn },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = "${aws_s3_bucket.work.arn}/*" },
    ]
  })
}

resource "aws_iam_instance_profile" "loadgen" {
  name = "${var.name}-loadgen"
  role = aws_iam_role.loadgen.name
}

resource "aws_instance" "loadgen" {
  ami                         = nonsensitive(data.aws_ssm_parameter.al2023.value)
  instance_type               = var.loadgen_instance_type
  subnet_id                   = aws_subnet.public.id
  associate_public_ip_address = true
  vpc_security_group_ids      = [aws_security_group.this["loadgen"].id]
  iam_instance_profile        = aws_iam_instance_profile.loadgen.name
  user_data                   = file("${path.module}/loadgen/setup.sh")

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  tags = { Name = "${var.name}-loadgen" }

  lifecycle {
    ignore_changes = [ami] # a newer image during the test would replace the machine
  }
}
