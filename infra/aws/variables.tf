variable "region" {
  description = "Where the test runs."
  type        = string
  default     = "us-east-2"
}

variable "name" {
  description = "Prefix for every resource's name."
  type        = string
  default     = "placemap-load"
}

variable "image_tag" {
  description = "The tag images.sh pushed (it writes images.auto.tfvars)."
  type        = string
  default     = "none"
}

# The Free Plan allows only small EC2 types; the largest is this Intel one (2 vCPUs, 8 GB). The driver records its CPU,
# so a run shows whether it, rather than the services, set a limit.
variable "loadgen_instance_type" {
  description = "The load generator: k6, the stream client and Prometheus on one machine."
  type        = string
  default     = "m7i-flex.large"
}

variable "loadgen_architecture" {
  description = "The load generator's architecture as Amazon Linux names it (x86_64 or arm64); images.sh builds the stream client to match."
  type        = string
  default     = "x86_64"
}

# How many copies each scaled service may run; the average CPU it scales out above; and, behind the load balancer, the
# requests a second each copy should take on average before another is added (0: CPU only). Valhalla answers a few
# matrices at a time per copy, so its requests pile up and time out while its average CPU still looks moderate:
# requests per copy add a copy before that happens.
variable "scaling" {
  description = "Minimum and maximum copies, the CPU target in percent, and requests a second per copy (0: none)."
  type        = map(object({ min = number, max = number, cpu = number, requests = number }))
  default = {
    api        = { min = 2, max = 10, cpu = 60, requests = 50 }
    valhalla   = { min = 2, max = 6, cpu = 50, requests = 20 }
    "live-hub" = { min = 1, max = 4, cpu = 40, requests = 0 }
  }
}

variable "postgis_size" {
  description = "PostGIS's vCPUs (as Fargate units), memory in MiB, shared buffers and the planner's cache size."
  type        = object({ cpu = number, memory = number, shared_buffers = string, effective_cache_size = string })
  default     = { cpu = 2048, memory = 4096, shared_buffers = "1GB", effective_cache_size = "3GB" }
}

variable "valhalla_size" {
  description = "Each Valhalla copy's vCPUs (as Fargate units, 1024 a vCPU), memory in MiB, and worker threads."
  type        = object({ cpu = number, memory = number, threads = number })
  default     = { cpu = 4096, memory = 8192, threads = 4 }
}
