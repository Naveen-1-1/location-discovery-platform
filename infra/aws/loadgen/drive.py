"""The AWS load test, run on the load generator as root (infra/aws/run.sh starts it through Session Manager).

  python3 -I drive.py --bundle DIR --run ID --alb HOST --cluster NAME --bucket NAME --region REGION
                      --sse-image IMAGE --alb-suffix SUFFIX --target-groups JSON [--quick] [--soak-rate N]

The phases, all against the private load balancer:
  ramp   the popular mix (L13c's) in steps of 6 minutes from 100/s up by 100/s, each judged on its last 2 minutes by
         L1's line, until a step misses it; the services have the first 4 minutes of a step to add copies. Meanwhile
         live viewers arrive 1,000 every 3 minutes up to 5,000, held 5 minutes.
  soak   L13: the popular mix at 70% of the ramp's sustained rate until a million requests (30 minutes to 2 hours),
         with 1,000 live viewers; --soak-rate skips the ramp.
  cool   25 minutes without load, to watch the services scale back in.
Every 15 s each service's desired, running and pending copies go to tasks.csv. At the end: CloudWatch's view (the load
balancer's latency and errors, the services' CPU and memory, a point a minute), Prometheus's (the api copies' shared
results and travel-time loads, live-hub's streams per copy, heaps), results.json and summary.md. The folder
/opt/placemap/results/<run> is copied to s3://<bucket>/results/<run>/ every minute; DONE marks the end.

--quick shortens every phase, to check the setup rather than the numbers. Written for the machine's Python (3.9).
"""

import argparse
import csv
import json
import math
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# As the load-test stack (infra/compose/load.compose.yaml).
K6 = "grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34"
PROMETHEUS = "prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e"
SERVICES = ["api", "valhalla", "live-hub", "postgis", "standins", "photon"]
SCALED = ["api", "valhalla", "live-hub"]
TOPICS = "alerts,bikes,vehicles"  # what the map asks for with "Show buses and trains" on
SOAK_REQUESTS = 1_000_000
PROMETHEUS_URL = "http://127.0.0.1:9090"
LOG = []


def say(message):
    line = time.strftime("%H:%M:%S", time.gmtime()) + " " + message
    print(line, flush=True)
    LOG.append(line)


def sh(cmd, check=True, **kwargs):
    result = subprocess.run(cmd, capture_output=True, text=True, **kwargs)
    if check and result.returncode:
        raise RuntimeError(f"{' '.join(cmd[:4])} ... failed ({result.returncode}): {result.stderr.strip()[-2000:]}")
    return result.stdout


def aws(*args):
    return json.loads(sh(["aws", *args, "--output", "json"]) or "null")


def get_json(url, data=None, timeout=10):
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read())


def iso(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def metric(summary, name):
    """A metric's values from a k6 summary ({} if absent), e.g. metric(s, "http_req_duration{step:300}")."""
    return (summary.get("metrics", {}).get(name) or {}).get("values", {})


def thresholds_ok(summary):
    failed = [f"{name} {expr}" for name, m in summary.get("metrics", {}).items()
              for expr, t in (m.get("thresholds") or {}).items() if not t.get("ok")]
    return not failed, failed


def ms(values, stat):
    v = values.get(stat)
    return "n/a" if v is None else f"{v:.0f} ms"


class Test:
    def __init__(self, args):
        self.args = args
        self.quick = args.quick
        self.dir = Path("/opt/placemap/results") / args.run
        self.dir.mkdir(parents=True, exist_ok=True)
        self.dir.chmod(0o777)  # k6 and the stream client write their summaries here
        shutil.copy(Path(args.bundle) / "origins.json", self.dir / "origins.json")
        self.api = f"http://{args.alb}"
        self.live = f"http://{args.alb}:8081"
        self.results = {"run": args.run, "quick": self.quick, "started": iso(time.time()), "phases": {}}
        self.tasks = []  # (epoch, service, desired, running, pending)
        self.stop = threading.Event()

    # --- Setup ----------------------------------------------------------------------------------------------------

    def setup(self):
        deadline = time.time() + 600
        while not Path("/opt/placemap/ready").exists():  # the first boot's setup.sh
            if time.time() > deadline:
                raise RuntimeError("the machine's setup never finished (/opt/placemap/ready is missing)")
            time.sleep(5)
        registry = self.args.sse_image.split("/")[0]
        password = sh(["aws", "ecr", "get-login-password", "--region", self.args.region])
        sh(["docker", "login", "--username", "AWS", "--password-stdin", registry], input=password)
        for image in (K6, PROMETHEUS, self.args.sse_image):
            sh(["docker", "pull", "-q", image])
        config = self.dir.parent / "prometheus.yml"
        config.write_text("global:\n  evaluation_interval: 15s\n"
                          "otlp:\n  promote_resource_attributes: [deployment.environment]\n", encoding="utf-8")
        data = Path("/opt/placemap/prometheus")
        data.mkdir(exist_ok=True)
        data.chmod(0o777)
        sh(["docker", "rm", "-f", "prometheus"], check=False)
        sh(["docker", "run", "-d", "--name", "prometheus", "--network", "host", "--restart", "unless-stopped",
            "-v", f"{config}:/etc/prometheus/prometheus.yml:ro", "-v", f"{data}:/prometheus", PROMETHEUS,
            "--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.retention.time=3d",
            "--web.enable-otlp-receiver", "--web.enable-remote-write-receiver"])
        say("Prometheus up; waiting for the api to answer with Valhalla's travel times...")
        deadline = time.time() + 600
        while True:
            try:
                nearby = get_json(f"{self.api}/api/places/nearby?lat=42.35500&lon=-71.06560&mode=walk")
                if nearby.get("travelTimeSource") == "valhalla" and nearby.get("places"):
                    break
                note = f"travelTimeSource={nearby.get('travelTimeSource')}"
            except Exception as e:  # not up yet
                note = str(e)
            if time.time() > deadline:
                raise RuntimeError(f"the api never answered with Valhalla's travel times: {note}")
            time.sleep(10)
        deadline = time.time() + 120
        while self.vehicles_stale():
            if time.time() > deadline:
                raise RuntimeError("live-hub's vehicles topic is still stale: is the MBTA stand-in answering?")
            time.sleep(5)
        time.sleep(15)  # a few metric pushes
        copies = self.prom_value('count(count by (instance) (jvm_threads_live{job="placemap/api"}))')
        say(f"READY: nearby from Boston Common has {len(nearby['places'])} places by walking time from Valhalla; "
            f"vehicles fresh; Prometheus sees {copies:.0f} api copies")
        self.results["api_copies_seen_at_start"] = copies

    def vehicles_stale(self):
        try:
            body = json.dumps({"keys": ["vehicles:none"]}).encode()
            return get_json(f"{self.live}/internal/live/batch", data=body)["topics"]["vehicles"]["stale"]
        except Exception:
            return True

    # --- Background: task counts and the copy to S3 ---------------------------------------------------------------

    def background(self):
        last_sync = 0.0
        with open(self.dir / "tasks.csv", "w", newline="", encoding="utf-8") as f:
            out = csv.writer(f)
            out.writerow(["time", "service", "desired", "running", "pending"])
            while not self.stop.is_set():
                now = time.time()
                try:
                    found = aws("ecs", "describe-services", "--cluster", self.args.cluster, "--services", *SERVICES,
                                "--region", self.args.region)
                    for s in found["services"]:
                        row = (now, s["serviceName"], s["desiredCount"], s["runningCount"], s["pendingCount"])
                        self.tasks.append(row)
                        out.writerow([iso(now), *row[1:]])
                    f.flush()
                except Exception as e:
                    say(f"  task counts: {e}")
                if now - last_sync >= 60:
                    self.sync()
                    last_sync = now
                self.stop.wait(15)

    def sync(self):
        (self.dir / "drive.log").write_text("\n".join(LOG) + "\n", encoding="utf-8")
        sh(["aws", "s3", "sync", str(self.dir), f"s3://{self.args.bucket}/results/{self.args.run}/",
            "--only-show-errors", "--region", self.args.region], check=False)

    def copies(self, service, start, end):
        """The most copies of a service running between two times (None before the first sample)."""
        running = [row[3] for row in self.tasks if row[1] == service and start <= row[0] <= end]
        return max(running) if running else None

    # --- Load ------------------------------------------------------------------------------------------------------

    def k6(self, name, script, env):
        """Runs one k6 script; returns (exit code, its summary)."""
        cmd = ["docker", "run", "--rm", "--network", "host", "--user", "0:0", "--ulimit", "nofile=65536:65536",
               "-v", f"{Path(self.args.bundle) / 'k6'}:/scripts:ro", "-v", f"{self.dir}:/work"]
        settings = {
            "API": self.api, "LIVE": self.live, "WORK": "/work", "K6_NO_USAGE_REPORT": "true",
            "K6_AUTO_EXTENSION_RESOLUTION": "false",
            "K6_PROMETHEUS_RW_SERVER_URL": f"{PROMETHEUS_URL}/api/v1/write",
            "K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM": "true",
            "K6_PROMETHEUS_RW_LABELS": f"scenario={name},run={self.args.run}",
            "K6_SYSTEM_TAGS": "status,method,scenario,expected_response,check,error_code",
            **{k: str(v) for k, v in env.items()},
        }
        for key, value in settings.items():
            cmd += ["-e", f"{key}={value}"]
        cmd += [K6, "run", "--quiet", "--out", "experimental-prometheus-rw", f"/scripts/{script}"]
        with open(self.dir / f"{name}.k6.log", "w", encoding="utf-8") as log:
            code = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT).returncode
        path = self.dir / f"{name}.summary.json"
        summary = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        if code not in (0, 99) or not summary:
            tail = (self.dir / f"{name}.k6.log").read_text(encoding="utf-8", errors="replace").splitlines()[-15:]
            say(f"  k6 exited with {code}; the end of {name}.k6.log:\n    " + "\n    ".join(tail))
        return code, summary

    def viewers(self, name, clients, seconds, into):
        """Runs the stream client (live viewers on alerts, bikes and vehicles); puts its summary into `into`."""
        cmd = ["docker", "run", "--rm", "--network", "host", "--user", "0:0", "--ulimit", "nofile=65536:65536",
               "-m", "1g", "-v", f"{self.dir}:/work", self.args.sse_image,
               "--url", f"{self.live}/live?topics={TOPICS}", "--clients", str(clients), "--ramp", "250",
               "--duration", str(seconds), "--slow-share", "0", "--out", f"/work/{name}.sse.json"]
        with open(self.dir / f"{name}.sse.log", "w", encoding="utf-8") as log:
            code = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT).returncode
        path = self.dir / f"{name}.sse.json"
        if code or not path.exists():
            say(f"  the stream client {name} exited with {code}; see {name}.sse.log")
            return
        into[name] = json.loads(path.read_text(encoding="utf-8"))

    # --- Prometheus and CloudWatch ------------------------------------------------------------------------------------

    def prom(self, query, at=None):
        params = {"query": query}
        if at is not None:
            params["time"] = f"{at:.3f}"
        url = f"{PROMETHEUS_URL}/api/v1/query?" + urllib.parse.urlencode(params)
        return get_json(url, timeout=30)["data"]["result"]

    def prom_value(self, query, at=None, default=0.0):
        try:
            result = self.prom(query, at)
            return float(result[0]["value"][1]) if result else default
        except Exception:
            return default

    def prom_range(self, query, start, end, step=30):
        params = {"query": query, "start": f"{start:.0f}", "end": f"{end:.0f}", "step": str(step)}
        url = f"{PROMETHEUS_URL}/api/v1/query_range?" + urllib.parse.urlencode(params)
        try:
            return [{"labels": r["metric"], "values": [[float(t), float(v)] for t, v in r["values"]]}
                    for r in get_json(url, timeout=60)["data"]["result"]]
        except Exception as e:
            return {"error": str(e)}

    def hit_rate(self, start, end):
        w = f"{max(10, int(end - start))}s"
        gets = {row["metric"].get("result"): float(row["value"][1]) for row in self.prom(
            f'sum by (result) (increase(cache_gets_total{{job="placemap/api", cache="nearby"}}[{w}]))', at=end)}
        total = gets.get("hit", 0) + gets.get("miss", 0)
        return round(gets.get("hit", 0) / total, 3) if total else None

    def cloudwatch(self, start, end):
        """The load balancer's and the services' minutes, from CloudWatch."""
        queries = []

        def add(qid, namespace, name, dims, stat):
            queries.append({"Id": qid, "ReturnData": True, "MetricStat": {
                "Metric": {"Namespace": namespace, "MetricName": name,
                           "Dimensions": [{"Name": k, "Value": v} for k, v in dims.items()]},
                "Period": 60, "Stat": stat}})

        for i, service in enumerate(SCALED + ["postgis"]):
            dims = {"ClusterName": self.args.cluster, "ServiceName": service}
            add(f"cpu_avg_{i}", "AWS/ECS", "CPUUtilization", dims, "Average")
            add(f"cpu_max_{i}", "AWS/ECS", "CPUUtilization", dims, "Maximum")
            add(f"mem_max_{i}", "AWS/ECS", "MemoryUtilization", dims, "Maximum")
            add(f"running_{i}", "ECS/ContainerInsights", "RunningTaskCount", dims, "Average")
        groups = json.loads(self.args.target_groups)
        for i, (service, suffix) in enumerate(sorted(groups.items())):
            dims = {"TargetGroup": suffix, "LoadBalancer": self.args.alb_suffix}
            add(f"requests_{i}", "AWS/ApplicationELB", "RequestCount", dims, "Sum")
            for p in ("p50", "p95", "p99"):
                add(f"latency_{p}_{i}", "AWS/ApplicationELB", "TargetResponseTime", dims, p)
            add(f"target_5xx_{i}", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", dims, "Sum")
            add(f"healthy_{i}", "AWS/ApplicationELB", "HealthyHostCount", dims, "Average")
        lb = {"LoadBalancer": self.args.alb_suffix}
        for name, stat in (("ActiveConnectionCount", "Sum"), ("NewConnectionCount", "Sum"),
                           ("ProcessedBytes", "Sum"), ("ConsumedLCUs", "Sum"), ("HTTPCode_ELB_5XX_Count", "Sum"),
                           ("RejectedConnectionCount", "Sum")):
            add(f"lb_{name}", "AWS/ApplicationELB", name, lb, stat)
        labels = {q["Id"]: q for q in queries}
        query_file = self.dir.parent / "cloudwatch-queries.json"
        query_file.write_text(json.dumps(queries), encoding="utf-8")
        series, token = {}, None
        while True:
            args = ["cloudwatch", "get-metric-data", "--metric-data-queries", f"file://{query_file}",
                    "--start-time", iso(start - 60), "--end-time", iso(end + 60), "--region", self.args.region]
            if token:
                args += ["--next-token", token]
            page = aws(*args)
            for r in page["MetricDataResults"]:
                q = labels[r["Id"]]["MetricStat"]
                key = (f"{q['Metric']['Namespace']} {q['Metric']['MetricName']} {q['Stat']} "
                       + " ".join(f"{d['Name']}={d['Value']}" for d in q["Metric"]["Dimensions"]))
                points = series.setdefault(key, {})
                for t, v in zip(r["Timestamps"], r["Values"]):
                    points[t] = v
            token = page.get("NextToken")
            if not token:
                break
        return {k: sorted(v.items()) for k, v in series.items()}

    # --- Phases ------------------------------------------------------------------------------------------------------

    def ramp(self):
        shape = (dict(WARMUP_SECONDS=30, STEP_SECONDS=90, JUDGE_SECONDS=40, SETTLE_SECONDS=20, FIRST_RATE=50,
                      STEP_BY=50, LAST_RATE=150) if self.quick else
                 dict(WARMUP_SECONDS=120, STEP_SECONDS=360, JUDGE_SECONDS=120, SETTLE_SECONDS=60, FIRST_RATE=100,
                      STEP_BY=100, LAST_RATE=1500))
        stairs, step_s, hold = (2, 30, 30) if self.quick else (5, 180, 300)
        per_stair = 100 if self.quick else 1000
        say(f"RAMP: the popular mix from {shape['FIRST_RATE']}/s by {shape['STEP_BY']}/s every "
            f"{shape['STEP_SECONDS']} s (judged on the last {shape['JUDGE_SECONDS']} s); viewers {per_stair} more "
            f"every {step_s} s up to {stairs * per_stair}")
        watching = {}
        started = time.time() + 3  # k6 takes a moment to start
        total = stairs * step_s + hold
        threads = []
        for i in range(stairs):
            t = threading.Thread(target=self.delayed, daemon=True, args=(
                started + i * step_s, self.viewers, (f"ramp-viewers-{i + 1}", per_stair, total - i * step_s, watching)))
            t.start()
            threads.append(t)
        code, s = self.k6("ramp", "L11-ceiling.js", {"MIX": "popular", "NAME": "ramp", **shape})
        for t in threads:
            t.join(timeout=total + 300)
        time.sleep(12)  # metrics arrive every 5 s
        rows, sustained, missed = [], None, None
        for i, rate in enumerate(range(shape["FIRST_RATE"], shape["LAST_RATE"] + 1, shape["STEP_BY"])):
            d = metric(s, f"http_req_duration{{step:{rate}}}")
            if not d.get("count"):
                break
            e, f = metric(s, f"errors_not_429{{step:{rate}}}"), metric(s, f"fallbacks{{step:{rate}}}")
            met = d.get("p(95)", 1e9) < 300 and e.get("rate", 1) < 0.005 and f.get("rate", 0) < 0.01
            to = started + shape["WARMUP_SECONDS"] + (i + 1) * shape["STEP_SECONDS"]
            judged_from = to - shape["JUDGE_SECONDS"]
            to = min(to, time.time() - 12)
            rows.append({
                "rate": rate, "requests": d.get("count"), "p50_ms": d.get("med"), "p95_ms": d.get("p(95)"),
                "p99_ms": d.get("p(99)"), "error_rate": e.get("rate"), "fallback_rate": f.get("rate"),
                "rejected_503": metric(s, f"rejected_503{{step:{rate}}}").get("count", 0),
                "cache_hit_rate": self.hit_rate(judged_from, to), "met": met,
                "copies": {svc: self.copies(svc, judged_from, to) for svc in SCALED},
                "judged": [iso(judged_from), iso(to)]})
            if not met:
                missed = rate
                break
            sustained = rate
        viewers = {name: {k: v for k, v in w.items() if k in ("clients", "peak_open", "open_at_end", "resyncs",
                                                                   "failures", "refused", "ends", "lag_by_phase")}
                   for name, w in sorted(watching.items())}
        top = started + total - hold / 2
        per_copy = {r["metric"].get("instance", "?")[-12:]: float(r["value"][1]) for r in self.prom(
            'sum by (instance) (placemap_live_sessions{job="placemap/live-hub"})', at=top)}
        table = ["| Rate | Requests | p50 | p95 | p99 | Errors | Fallbacks | 503s | Cache hits | api | Valhalla | "
                 "live-hub | Met |", "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
        for r in rows:
            c, hits = r["copies"], r["cache_hit_rate"]
            table.append(
                f"| {r['rate']}/s | {r['requests']:.0f} | {r['p50_ms']:.0f} ms | {r['p95_ms']:.0f} ms | "
                f"{r['p99_ms']:.0f} ms | {r['error_rate'] or 0:.2%} | {r['fallback_rate'] or 0:.2%} | "
                f"{r['rejected_503']:.0f} | {'n/a' if hits is None else f'{hits:.0%}'} | "
                f"{c['api']} | {c['valhalla']} | {c['live-hub']} | {'yes' if r['met'] else 'no'} |")
        (self.dir / "ramp.md").write_text("\n".join(table) + "\n", encoding="utf-8")
        held = sum(w.get("open_at_end", 0) for w in watching.values())
        self.results["phases"]["ramp"] = {
            "start": iso(started), "end": iso(time.time()), "k6_exit": code, "shape": shape,
            "sustained_rate": sustained, "first_missed_rate": missed, "steps": rows,
            "limited_429": metric(s, "limited_429").get("count", 0),
            "dropped_iterations": metric(s, "dropped_iterations").get("count", 0),
            "viewers": {"planned": stairs * per_stair, "held_at_end": held, "clients": viewers,
                        "streams_per_live_hub_copy_at_top": per_copy}}
        say(f"RAMP done: sustained {sustained}/s" + (f", {missed}/s missed L1's line" if missed else "")
            + f"; {held} of {stairs * per_stair} viewers held; streams per live-hub copy at the top: {per_copy}")
        return sustained

    def delayed(self, at, fn, args):
        time.sleep(max(0.0, at - time.time()))
        fn(*args)

    def soak(self, sustained):
        if self.quick:
            rate, minutes, viewers = 100, 3, 200
        elif self.args.soak_rate:
            rate = self.args.soak_rate
            minutes = min(120, max(30, -(-SOAK_REQUESTS // (rate * 60))))
            viewers = 1000
        else:
            rate = max(50, int(0.7 * sustained) // 25 * 25)
            minutes = min(120, max(30, -(-SOAK_REQUESTS // (rate * 60))))
            viewers = 1000
        seconds = minutes * 60
        say(f"SOAK: {rate}/s for {minutes} min (about {rate * seconds:,} requests) with {viewers} live viewers")
        watching = {}
        viewer_thread = threading.Thread(target=self.viewers, args=("soak-viewers", viewers, seconds, watching),
                                         daemon=True)
        viewer_thread.start()
        started = time.time() + 3
        code, s = self.k6("soak", "L13-soak.js", {"RATE": rate, "MINUTES": minutes})
        ended = time.time()
        viewer_thread.join(timeout=300)
        time.sleep(12)
        total = metric(s, "http_reqs").get("count", 0)
        d = metric(s, "http_req_duration")
        errors, fallbacks = metric(s, "errors_not_429"), metric(s, "fallbacks")
        windows = []
        for w in range(-(-seconds // 600)):
            dw, ew, fw = (metric(s, f"{m}{{w:{w}}}") for m in ("http_req_duration", "errors_not_429", "fallbacks"))
            if not dw.get("count"):
                continue
            a, b = started + 600 * w, min(started + 600 * (w + 1), ended)
            met = dw.get("p(99)", 1e9) < 600 and ew.get("rate", 1) < 0.005 and fw.get("rate", 0) < 0.01
            windows.append({"window": w, "requests": dw.get("count"), "p50_ms": dw.get("med"),
                            "p95_ms": dw.get("p(95)"), "p99_ms": dw.get("p(99)"), "error_rate": ew.get("rate"),
                            "fallback_rate": fw.get("rate"), "cache_hit_rate": self.hit_rate(a, b),
                            "copies": {svc: self.copies(svc, a, b) for svc in SCALED}, "met": met})
        span, settle = min(900, seconds / 3), min(300, seconds / 6)
        heap = {}
        for svc in ("api", "live-hub"):
            q = lambda a, b: self.prom_value(  # noqa: E731
                f'max(max_over_time(jvm_gc_live_data_size_bytes{{job="placemap/{svc}"}}[{max(10, int(b - a))}s]))',
                at=b) / 2 ** 20
            heap[svc] = {"early_mb": round(q(started + settle, started + settle + span)),
                         "late_mb": round(q(ended - span, ended))}
        flat = all(h["late_mb"] <= 1.15 * h["early_mb"] + 32 for h in heap.values())
        w = watching.get("soak-viewers", {})
        held = bool(w) and w.get("peak_open", 0) >= viewers and w.get("open_at_end", 0) >= viewers
        ok, failed = thresholds_ok(s)
        limited = metric(s, "limited_429").get("count", 0)
        passed = (code in (0, 99) and ok and limited == 0 and held and w.get("resyncs", -1) == 0 and flat
                  and bool(windows) and all(row["met"] for row in windows))
        table = ["| Minutes | Requests | p50 | p95 | p99 | Errors | Fallbacks | Cache hits | api | Valhalla | "
                 "live-hub | Met |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
        for row in windows:
            c, hits = row["copies"], row["cache_hit_rate"]
            table.append(f"| {10 * row['window']}–{10 * row['window'] + 10} | {row['requests']:.0f} | "
                         f"{row['p50_ms']:.0f} ms | {row['p95_ms']:.0f} ms | {row['p99_ms']:.0f} ms | "
                         f"{row['error_rate'] or 0:.2%} | {row['fallback_rate'] or 0:.2%} | "
                         f"{'n/a' if hits is None else f'{hits:.0%}'} | {c['api']} | {c['valhalla']} | "
                         f"{c['live-hub']} | {'yes' if row['met'] else 'no'} |")
        (self.dir / "soak.md").write_text("\n".join(table) + "\n", encoding="utf-8")
        line = (f"{total:,.0f} requests at {rate}/s for {minutes} min: p50 {ms(d, 'med')}, p95 {ms(d, 'p(95)')}, "
                f"p99 {ms(d, 'p(99)')}, errors {errors.get('passes', 0):.0f} ({errors.get('rate', 0):.3%}), "
                f"fallbacks {fallbacks.get('rate', 0):.2%}, {limited:.0f} × 429, "
                f"{metric(s, 'rejected_503').get('count', 0):.0f} × 503; "
                f"{sum(r['met'] for r in windows)} of {len(windows)} 10-minute windows met L1's line; "
                f"{w.get('open_at_end', 0)} of {viewers} viewers held, {w.get('resyncs', -1)} resyncs; live heap "
                f"api {heap['api']['early_mb']} → {heap['api']['late_mb']} MB, live-hub {heap['live-hub']['early_mb']} "
                f"→ {heap['live-hub']['late_mb']} MB" + (f"; failed: {', '.join(failed[:4])}" if failed else ""))
        self.results["phases"]["soak"] = {
            "start": iso(started), "end": iso(ended), "k6_exit": code, "passed": passed, "line": line, "rate": rate,
            "minutes": minutes, "requests": total, "p50_ms": d.get("med"), "p95_ms": d.get("p(95)"),
            "p99_ms": d.get("p(99)"), "errors": errors.get("passes", 0), "error_rate": errors.get("rate"),
            "fallback_rate": fallbacks.get("rate"), "limited_429": limited,
            "rejected_503": metric(s, "rejected_503").get("count", 0), "windows": windows, "heap": heap,
            "viewers": viewers, "client": {k: v for k, v in w.items() if k != "windows"}}
        say(f"SOAK {'PASSED' if passed else 'FAILED'}: {line}")

    def cool(self):
        minutes = 2 if self.quick else 25
        say(f"COOL: {minutes} minutes without load, watching the services scale back in")
        started = time.time()
        self.stop.wait(minutes * 60)
        self.results["phases"]["cool"] = {"start": iso(started), "end": iso(time.time())}

    # --- The report --------------------------------------------------------------------------------------------------

    def timeline(self):
        """Each scaled service's running copies, a row whenever any of them changes."""
        rows, last, current = [], None, {}
        for t, service, _desired, running, _pending in self.tasks:
            if service not in SCALED:
                continue
            current[service] = running
            if len(current) == len(SCALED):
                state = tuple(current[s] for s in SCALED)
                if state != last:
                    rows.append([iso(t), *state])
                    last = state
        table = ["| Time (UTC) | api | Valhalla | live-hub |", "|---|---:|---:|---:|"]
        table += [f"| {r[0][11:19]} | {r[1]} | {r[2]} | {r[3]} |" for r in rows]
        (self.dir / "timeline.md").write_text("\n".join(table) + "\n", encoding="utf-8")
        return rows

    def report(self, start, end):
        self.results["ended"] = iso(end)
        self.results["timeline"] = self.timeline()
        say("Collecting CloudWatch's and Prometheus's view...")
        try:
            (self.dir / "cloudwatch.json").write_text(json.dumps(self.cloudwatch(start, end)), encoding="utf-8")
        except Exception as e:
            say(f"  CloudWatch: {e}")
        queries = {
            "api_copies": 'count(count by (instance) (jvm_threads_live{job="placemap/api"}))',
            "cache_hit_rate": 'sum(rate(cache_gets_total{job="placemap/api", cache="nearby", result="hit"}[1m])) / '
                              'sum(rate(cache_gets_total{job="placemap/api", cache="nearby"}[1m]))',
            "cache_hit_rate_per_copy": 'sum by (instance) (rate(cache_gets_total{job="placemap/api", cache="nearby", '
                                       'result="hit"}[1m])) / sum by (instance) (rate(cache_gets_total{'
                                       'job="placemap/api", cache="nearby"}[1m]))',
            "loads_per_second": 'sum by (travel_time_source, fallback) '
                                '(rate(placemap_nearby_loads_total{job="placemap/api"}[1m]))',
            "live_in_nearby": 'sum by (outcome) (rate(placemap_nearby_live_total{job="placemap/api"}[1m]))',
            "streams_per_live_hub_copy": 'sum by (instance) (placemap_live_sessions{job="placemap/live-hub"})',
            "live_heap_mb": 'max by (job) (jvm_gc_live_data_size_bytes{job=~"placemap/.*"}) / 1048576',
            "k6_requests_per_second": f'sum(rate(k6_http_reqs_total{{run="{self.args.run}"}}[1m]))',
        }
        prometheus = {name: self.prom_range(q, start, end) for name, q in queries.items()}
        (self.dir / "prometheus.json").write_text(json.dumps(prometheus), encoding="utf-8")
        (self.dir / "results.json").write_text(json.dumps(self.results, indent=1), encoding="utf-8")
        ramp, soak = self.results["phases"].get("ramp", {}), self.results["phases"].get("soak", {})
        summary = [f"# AWS load test {self.args.run}" + (" (quick)" if self.quick else ""), ""]
        if ramp:
            summary += [f"Ramp: sustained {ramp.get('sustained_rate')}/s; first missed "
                        f"{ramp.get('first_missed_rate')}/s; viewers held {ramp['viewers']['held_at_end']} of "
                        f"{ramp['viewers']['planned']}.", "", (self.dir / "ramp.md").read_text(encoding="utf-8")]
        if soak:
            summary += [f"Soak: {soak['line']}", "", (self.dir / "soak.md").read_text(encoding="utf-8")]
        summary += ["Copies over time:", "", (self.dir / "timeline.md").read_text(encoding="utf-8")]
        (self.dir / "summary.md").write_text("\n".join(summary), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("--bundle", "--run", "--alb", "--cluster", "--bucket", "--region", "--sse-image", "--alb-suffix",
                 "--target-groups"):
        parser.add_argument(name, required=True)
    parser.add_argument("--quick", action="store_true")
    parser.add_argument("--soak-rate", type=int, help="skip the ramp and soak at this rate")
    args = parser.parse_args()
    test = Test(args)
    sampler = threading.Thread(target=test.background, daemon=True)
    started = time.time()
    try:
        test.setup()
        sampler.start()
        sustained = None if args.soak_rate else test.ramp()
        if sustained or args.soak_rate or args.quick:
            test.soak(sustained or 0)
        else:
            say("No ramp step met L1's line, so there is nothing to soak under.")
        test.cool()
        test.report(started, time.time())
        say("DONE")
    except Exception as e:
        say(f"STOPPED: {e}")
        test.results["error"] = str(e)
        (test.dir / "results.json").write_text(json.dumps(test.results, indent=1), encoding="utf-8")
    finally:
        test.stop.set()
        (test.dir / "DONE").write_text(iso(time.time()) + "\n", encoding="utf-8")
        test.sync()


if __name__ == "__main__":
    main()
