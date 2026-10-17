variable "region" {
  description = "Where the test runs."
  type        = string
  default     = "us-east-1"
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

variable "loadgen_instance_type" {
  description = "The load generator: k6, the stream client and Prometheus on one machine (Graviton, as the tasks)."
  type        = string
  default     = "c7g.2xlarge"
}

# How many copies each scaled service may run, and the average CPU it scales out above. The api also scales on
# requests per copy (api_requests_per_copy).
variable "scaling" {
  description = "Minimum and maximum copies, and the CPU target in percent, per scaled service."
  type        = map(object({ min = number, max = number, cpu = number }))
  default = {
    api        = { min = 2, max = 10, cpu = 60 }
    valhalla   = { min = 2, max = 6, cpu = 60 }
    "live-hub" = { min = 1, max = 4, cpu = 40 }
  }
}

variable "api_requests_per_copy" {
  description = "Requests a second each api copy should take on average before another is added."
  type        = number
  default     = 50
}

variable "valhalla_size" {
  description = "Each Valhalla copy's vCPUs (as Fargate units, 1024 a vCPU), memory in MiB, and worker threads."
  type        = object({ cpu = number, memory = number, threads = number })
  default     = { cpu = 4096, memory = 8192, threads = 4 }
}
