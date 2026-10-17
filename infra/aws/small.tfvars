# A small environment that fits a new account's first Fargate limit (12 vCPUs at once: here at most 11), to check the
# wiring with "run.sh quick" before the limit is raised; the numbers it gives mean nothing.
scaling = {
  api        = { min = 2, max = 3, cpu = 60 }
  valhalla   = { min = 2, max = 2, cpu = 60 }
  "live-hub" = { min = 1, max = 1, cpu = 40 }
}
valhalla_size = { cpu = 2048, memory = 4096, threads = 2 }
