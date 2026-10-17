# A smaller environment for a new account's first Fargate limit (12 vCPUs at once): at most api 3 + Valhalla 5 +
# live-hub 1 + PostGIS 2 + the stand-ins 1 = 12. Valhalla, the limit, gets the room in 1-vCPU copies and starts with
# four: with fewer, a cold start's searches outran it before a new copy could start (about 4-5 minutes). live-hub
# stays one copy, as on the Mac (5,000 viewers fit in one).
scaling = {
  api        = { min = 2, max = 3, cpu = 60, requests = 50 }
  valhalla   = { min = 4, max = 5, cpu = 50, requests = 5 }
  "live-hub" = { min = 1, max = 1, cpu = 40, requests = 0 }
}
valhalla_size = { cpu = 1024, memory = 2048, threads = 2 }
