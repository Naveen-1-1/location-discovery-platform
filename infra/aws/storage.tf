# The images (pushed by images.sh) and a bucket for the load generator's scripts and results. Both are emptied and
# deleted with everything else.
locals {
  images = ["api", "live-hub", "valhalla", "postgis", "standins", "photon", "sse-client"]
}

resource "aws_ecr_repository" "this" {
  for_each     = toset(local.images)
  name         = "${var.name}/${each.key}"
  force_delete = true
}

resource "random_id" "bucket" {
  byte_length = 4
}

resource "aws_s3_bucket" "work" {
  bucket        = "${var.name}-${random_id.bucket.hex}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "work" {
  bucket                  = aws_s3_bucket.work.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
