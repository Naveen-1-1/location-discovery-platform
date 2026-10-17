# A smaller environment for a new account's first Fargate limit (12 vCPUs at once): at most api 4 + Valhalla 4 +
# live-hub 2 + PostGIS 1 + the stand-ins 1 = 12, and each scaled service can still add copies. The first runs showed
# the shared results answer about 80% of the popular mix, so PostGIS has little to do.
scaling = {
  api        = { min = 2, max = 4, cpu = 60 }
  valhalla   = { min = 1, max = 2, cpu = 60 }
  "live-hub" = { min = 1, max = 2, cpu = 40 }
}
valhalla_size = { cpu = 2048, memory = 4096, threads = 2 }
postgis_size  = { cpu = 1024, memory = 2048, shared_buffers = "512MB", effective_cache_size = "1536MB" }
