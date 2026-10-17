"""The load-test stack (plan: Phase 8): the whole app on one machine, with nothing in it calling a cloud service.

  python3 -I tools/load-tests.py seed [--fetch]       fill the stack's database with the importer, then save a dump
  python3 -I tools/load-tests.py up                   start the stack, restoring the dump if the database is older
  python3 -I tools/load-tests.py down [--volumes]     stop it (--volumes also drops its database and metrics)
  python3 -I tools/load-tests.py status               the containers and the database's seed
  python3 -I tools/load-tests.py run L1 C3 ... | all [--quick]
                                                      run scenarios against the stack, each ending PASSED or FAILED

seed imports every source with the real importer jar into the stack's PostGIS: from the snapshots a real import saved
in data/raw (no network), or with --fetch from the sources themselves (how CI makes it once a week). Addresses are
geocoded against the Photon stand-in, so records that need it stay unplaced. The result goes to data/load/ (git-ignored):
placemap.dump, seed.json (what went in) and stations.txt (the Bluebikes ids the feed stand-in serves).

up needs the dump and Valhalla's tiles (tools/build-valhalla-tiles.sh); it builds the api and live-hub jars and images.
The services get their own CPUs, the load generators and Prometheus the rest (PLACEMAP_SUT_CPUS / PLACEMAP_LOAD_CPUS
override the split), so the generators do not take time from what they measure.

run writes each scenario's k6 log and summary, and results.json, to load/results/<time>/ (git-ignored), and ends with
LOAD PASSED or LOAD FAILED. Numbers from the Mac are indicative; the authoritative ones come from the GitHub runner.
--quick shortens every scenario, to check the setup rather than the numbers.
"""

import argparse
import hashlib
import json
import os
import platform
import shutil
import re
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # python3 -I leaves this folder off the path
from harness import CHILD_ENV, JAVA, NO_PROXY, NO_TELEMETRY, Toxiproxy, migrate, migrations, run, say

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ["docker", "compose", "-f", str(ROOT / "infra/compose/load.compose.yaml")]
WORK = ROOT / "target/load"
SEED_DIR = ROOT / "data/load"
DUMP = SEED_DIR / "placemap.dump"
SEED_FILE = SEED_DIR / "seed.json"
STATIONS = SEED_DIR / "stations.txt"
TILES = ROOT / "data/valhalla/valhalla_tiles.tar"
INGEST_JAR = ROOT / "services/ingest/target/ingest.jar"

DB_URL = "jdbc:postgresql://127.0.0.1:55440/placemap?ApplicationName=ingest&reWriteBatchedInserts=true"
PASSWORD = "load"  # the stack's throwaway database, bound to 127.0.0.1 only; not a secret
API = "http://127.0.0.1:58080"
LIVE = "http://127.0.0.1:58081"
PROMETHEUS = "http://127.0.0.1:59090"
GEOCODER = "http://127.0.0.1:58088/api/"

# In the order the README gives for a first import: OSM first, so it can corroborate Overture's less certain places;
# then the official lists match both.
SOURCES = [
    "osm_geofabrik_massachusetts", "overture_places", "boston_active_food_licenses", "boston_food_inspections",
    "cambridge_food_establishment_permits", "cambridge_sanitary_inspections", "bluebikes_station_information",
    "mbta_gtfs", "massgis_hospitals", "fdic_bank_locations", "boston_open_space", "boston_bcyf_community_centers",
    "boston_public_libraries", "afdc_alt_fuel_stations", "usda_snap_retailers", "boston_licensing_board_licenses",
    "boston_park_features", "boston_wicked_free_wifi", "nppes_npi_pharmacies",
]
NEEDS_KEY = {"afdc_alt_fuel_stations": "NLR_API_KEY"}  # only to download; a saved snapshot imports without it

# As tools/dev.sh, plus the plan's 1 GB memory budget for the importer.
def cpu_split():
    """(services, load generators) as cpuset strings: about a quarter of Docker's CPUs for the generators."""
    if os.environ.get("PLACEMAP_SUT_CPUS") and os.environ.get("PLACEMAP_LOAD_CPUS"):
        return os.environ["PLACEMAP_SUT_CPUS"], os.environ["PLACEMAP_LOAD_CPUS"]
    n = int(run(["docker", "info", "--format", "{{.NCPU}}"]).strip())
    if n < 2:
        sys.exit("Docker has one CPU; give it at least 2 (the load-test stack needs 4 or more to mean anything)")
    load = max(1, n // 4)
    return f"0-{n - load - 1}", (f"{n - load}-{n - 1}" if load > 1 else str(n - 1))


def compose_env():
    sut, load = cpu_split()
    return dict(os.environ, PLACEMAP_SUT_CPUS=sut, PLACEMAP_LOAD_CPUS=load)


def compose(*args, check=True, env=None, **kwargs):
    cmd = COMPOSE + list(args)
    if check:
        return run(cmd, env=env or compose_env(), cwd=ROOT, **kwargs)
    return subprocess.run(cmd, env=env or compose_env(), cwd=ROOT, capture_output=True, text=True, **kwargs)


def psql(sql, db="placemap", check=True):
    """Rows as lists of strings, from psql inside the database container (no psql needed on the machine)."""
    result = compose("exec", "-T", "postgis", "psql", "-U", "postgres", "-d", db, "-X", "-q", "-t", "-A", "-F", "\t",
                     "-v", "ON_ERROR_STOP=1", "-c", sql, check=False)
    if result.returncode:
        if check:
            sys.exit(f"query failed on {db}: {result.stderr.strip()}\n{sql}")
        return None
    return [line.split("\t") for line in result.stdout.splitlines() if line]


def preflight(need_java=False):
    if subprocess.run(["docker", "info"], capture_output=True).returncode:
        sys.exit("Docker is not running. Start Docker Desktop and try again.")
    if need_java and not shutil.which("java"):
        sys.exit("java is not on the PATH")


def seed_id_in_database():
    rows = psql("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = 'placemap'",
                db="postgres", check=False)
    return rows[0][0] if rows and rows[0] and rows[0][0] else None


def mark(seed_id):
    psql(f"COMMENT ON DATABASE placemap IS '{seed_id}'", db="postgres")


def latest_snapshot(source):
    runs = sorted((ROOT / "data/raw" / source).glob("*/snapshot.csv"), key=lambda p: int(p.parent.name))
    return runs[-1] if runs else None


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def seed(args):
    preflight(need_java=True)
    if not args.fetch:
        missing = [s for s in SOURCES if latest_snapshot(s) is None]
        if missing:
            sys.exit("No saved snapshot for: " + ", ".join(missing) + ". Import them once (tools/dev.sh ingest-once "
                     "SOURCE) or seed with --fetch.")
    sources = [s for s in SOURCES if args.fetch is False or not NEEDS_KEY.get(s) or CHILD_ENV.get(NEEDS_KEY[s])]
    for skipped in sorted(set(SOURCES) - set(sources)):
        say(f"Leaving out {skipped}: downloading it needs {NEEDS_KEY[skipped]}")
    if not args.no_build:
        say("Building the importer jar...")
        run(["./mvnw", "-q", "-B", "-pl", "services/ingest", "-am", "package", "-DskipTests"], cwd=ROOT)

    say("Starting a fresh database and Photon's stand-in...")
    compose("down")  # a running stack would serve from the database about to be replaced
    subprocess.run(["docker", "volume", "rm", "-f", "placemap-load_db"], capture_output=True)
    SEED_DIR.mkdir(parents=True, exist_ok=True)
    STATIONS.unlink(missing_ok=True)
    compose("up", "-d", "--build", "--wait", "postgis", "photon")
    migrate(DB_URL, PASSWORD)
    files = migrations()
    say(f"Migrated ({len(files)} migrations, last {files[-1].name}); importing {len(sources)} sources:")

    work = WORK / "seed"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    inputs = {}
    started = time.monotonic()
    for source in sources:
        if args.fetch:
            input_args = [f"--placemap.ingest.raw-dir={ROOT / 'data/raw'}"]
        else:
            snapshot = latest_snapshot(source)
            pinned = work / "pinned" / f"{source}.csv"
            pinned.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(snapshot, pinned)
            input_args = [f"--placemap.ingest.pinned-snapshot={pinned}", f"--placemap.ingest.raw-dir={work / 'raw'}"]
        log_path = work / f"{source}.log"
        t0 = time.monotonic()
        with open(log_path, "w", encoding="utf-8") as log:
            code = subprocess.run(JAVA + ["-jar", str(INGEST_JAR), f"--placemap.ingest.once={source}", *input_args,
                                          f"--spring.datasource.url={DB_URL}", "--spring.datasource.username=postgres",
                                          f"--spring.datasource.password={PASSWORD}",
                                          f"--placemap.ingest.geocoder.url={GEOCODER}",
                                          "--placemap.ingest.geocoder.calls-per-second=50", *NO_TELEMETRY],
                                  cwd=ROOT, env=CHILD_ENV, stdout=log, stderr=subprocess.STDOUT).returncode
        if code != 0:
            tail = log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-25:]
            sys.exit(f"Importing {source} failed (exit {code}); see {log_path}\n" + "\n".join(tail))
        snapshot = pinned if not args.fetch else latest_snapshot(source)
        inputs[source] = sha256(snapshot) if snapshot else None
        say(f"  {source}: {time.monotonic() - t0:.0f} s")

    psql("ANALYZE")
    stations = [r[0] for r in psql("""SELECT source_key FROM place.source_record
                                      WHERE source = 'bluebikes_station_information' AND retired_at IS NULL
                                      ORDER BY 1""")]
    STATIONS.write_text("".join(s + "\n" for s in stations), encoding="utf-8")
    places = int(psql("SELECT count(*) FROM place.place WHERE retired_at IS NULL")[0][0])
    records = {s: int(n) for s, n in psql("""SELECT source, count(*) FROM place.source_record
                                              WHERE retired_at IS NULL GROUP BY source ORDER BY source""")}
    digest = hashlib.sha256()
    for file in files:
        digest.update(file.name.encode() + b"\0" + file.read_bytes())
    for source in sources:
        digest.update(f"{source}:{inputs[source]}".encode())
    seed_id = "seed-" + digest.hexdigest()[:12]
    SEED_FILE.write_text(json.dumps({
        "id": seed_id,
        "made": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "mode": "fetch" if args.fetch else "saved snapshots",
        "migrations": [f.name for f in files],
        "inputs": inputs,
        "places": places,
        "records": records,
        "stations": len(stations),
    }, indent=2) + "\n", encoding="utf-8")
    mark(seed_id)
    say(f"Imported in {(time.monotonic() - started) / 60:.1f} min: {places} places, {sum(records.values())} records, "
        f"{len(stations)} Bluebikes stations. Saving the dump...")
    with open(DUMP, "wb") as out:
        code = subprocess.run(COMPOSE + ["exec", "-T", "postgis", "pg_dump", "-U", "postgres", "-Fc", "placemap"],
                              env=compose_env(), cwd=ROOT, stdout=out).returncode
    if code:
        sys.exit("pg_dump failed")
    say(f"SEED DONE: {seed_id}, {DUMP.relative_to(ROOT)} ({DUMP.stat().st_size / 1e6:.0f} MB)")


def restore_if_needed():
    seed_info = json.loads(SEED_FILE.read_text(encoding="utf-8"))
    current = seed_id_in_database()
    if current == seed_info["id"]:
        say(f"The database already holds {current}.")
        return seed_info
    say(f"Restoring {seed_info['id']} into the database (it held {current or 'nothing'})...")
    psql("DROP DATABASE IF EXISTS placemap WITH (FORCE)", db="postgres")
    psql("CREATE DATABASE placemap", db="postgres")
    with open(DUMP, "rb") as dump:
        result = subprocess.run(COMPOSE + ["exec", "-T", "postgis", "pg_restore", "-U", "postgres", "-d", "placemap",
                                           "--no-owner", "--exit-on-error"],
                                env=compose_env(), cwd=ROOT, stdin=dump, capture_output=True, text=False)
    if result.returncode:
        sys.exit("pg_restore failed:\n" + result.stderr.decode(errors="replace")[-4000:])
    psql("ANALYZE")
    mark(seed_info["id"])
    return seed_info


def get_json(url, timeout=10):
    with NO_PROXY.open(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=timeout) as r:
        return json.loads(r.read())


def up(args):
    preflight(need_java=not args.no_build)
    if not TILES.exists():
        sys.exit(f"No Valhalla tiles at {TILES.relative_to(ROOT)}. Build them: tools/dev.sh valhalla")
    if not DUMP.exists() or not SEED_FILE.exists():
        sys.exit("No seeded database yet. Make it once: tools/dev.sh load seed")
    if not args.no_build:
        say("Building the api and live-hub jars...")
        run(["./mvnw", "-q", "-B", "-pl", "services/api,services/live-hub,load/sse-idle-client", "-am", "package",
             "-DskipTests"], cwd=ROOT)
    sut, load = cpu_split()
    say(f"CPUs: services on {sut}, load generators and Prometheus on {load}")
    compose("build", "-q", "postgis", "api", "live-hub")
    compose("--profile", "tools", "build", "-q", "sse-client", "slowhttptest")
    compose("up", "-d", "--wait", "postgis")
    seed_info = restore_if_needed()
    # Fresh JVMs and no faults left over from an earlier run; the database keeps running.
    compose("up", "-d", "--wait", "--wait-timeout", "180", "--force-recreate", "--no-deps", "standins", "photon",
            "toxiproxy", "prometheus", "valhalla", "live-hub", "api")
    nearby = get_json(f"{API}/api/places/nearby?lat=42.35500&lon=-71.06560&mode=walk")
    if nearby.get("travelTimeSource") != "valhalla" or not nearby.get("places"):
        sys.exit(f"The api answered without Valhalla's travel times: travelTimeSource={nearby.get('travelTimeSource')}, "
                 f"fallback={nearby.get('fallback')}, {len(nearby.get('places') or [])} places")
    deadline = time.time() + 45  # the first vehicle poll; the stand-in may have started a moment after live-hub
    while topic_stale("vehicles"):
        if time.time() > deadline:
            sys.exit("live-hub's vehicles topic is still stale 45 s after start: is the MBTA stand-in answering?")
        time.sleep(1)
    say(f"STACK UP: {seed_info['id']} ({seed_info['places']} places); nearby from Boston Common: "
        f"{len(nearby['places'])} places by walking time from Valhalla; vehicles fresh. api {API}, live-hub {LIVE}, "
        f"Prometheus {PROMETHEUS}")


# --- Scenarios -------------------------------------------------------------------------------------------------------

RESULTS = ROOT / "load/results"

# Centres of 150 m squares holding places, in a fixed random order. Squares that far apart never share an H3
# resolution-10 cell, so no two origins share a result.
ORIGINS_SQL = """
    WITH squares AS (
        SELECT floor(ST_X(p) / 150) * 150 + 75 AS x, floor(ST_Y(p) / 150) * 150 + 75 AS y
        FROM (SELECT ST_Transform(geom::geometry, 26986) AS p FROM place.place WHERE retired_at IS NULL) AS t
        GROUP BY 1, 2)
    SELECT round(ST_Y(c)::numeric, 5), round(ST_X(c)::numeric, 5)
    FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(x, y), 26986), 4326) AS c, x, y FROM squares) AS s
    ORDER BY md5(x::text || ',' || y::text)"""


def prom(query, at=None):
    """An instant query's result list."""
    params = {"query": query}
    if at is not None:
        params["time"] = f"{at:.3f}"
    data = get_json(f"{PROMETHEUS}/api/v1/query?" + urllib.parse.urlencode(params))
    return data["data"]["result"]


def prom_value(query, at=None, default=0.0):
    result = prom(query, at)
    return float(result[0]["value"][1]) if result else default


def metric(summary, name):
    """A metric's values from a k6 summary ({} if absent), e.g. metric(s, "http_req_duration{phase:steady}")."""
    return (summary.get("metrics", {}).get(name) or {}).get("values", {})


def thresholds_ok(summary):
    """(all passed, the names of those that failed)."""
    failed = [f"{name} {expr}" for name, m in summary.get("metrics", {}).items()
              for expr, t in (m.get("thresholds") or {}).items() if not t.get("ok")]
    return not failed, failed


def ms(values, stat):
    v = values.get(stat)
    return "n/a" if v is None else f"{v:.0f} ms"


class Run:
    def __init__(self, quick):
        self.quick = quick
        self.started = datetime.now(timezone.utc)
        self.dir = RESULTS / self.started.strftime("%Y%m%d-%H%M%S")
        self.dir.mkdir(parents=True)
        os.chmod(self.dir, 0o777)  # k6 runs as its own user and writes its summaries here
        self.results = {}
        self.env = dict(compose_env(), PLACEMAP_RUN_DIR=str(self.dir))
        origins = psql(ORIGINS_SQL)
        (self.dir / "origins.json").write_text(json.dumps([[float(a), float(b)] for a, b in origins]), encoding="utf-8")
        self.origins = len(origins)
        sut, load = cpu_split()
        self.machine = {
            "where": "GitHub runner" if os.environ.get("GITHUB_ACTIONS") else f"{platform.node()} (indicative)",
            "system": f"{platform.system()} {platform.machine()}",
            "docker_cpus": int(run(["docker", "info", "--format", "{{.NCPU}}"]).strip()),
            "docker_memory_gb": round(int(run(["docker", "info", "--format", "{{.MemTotal}}"]).strip()) / 2**30, 1),
            "service_cpus": sut, "load_cpus": load,
        }

    def k6(self, name, script, env=None):
        """Runs one k6 script; returns (exit code, its summary)."""
        args = ["run", "--rm", "--no-deps", "-T", "--user", f"{os.getuid()}:{os.getgid()}",
                "-e", f"K6_PROMETHEUS_RW_LABELS=scenario={name},run={self.dir.name}"]
        for key, value in (env or {}).items():
            args += ["-e", f"{key}={value}"]
        args += ["k6", "run", "--quiet", "--out", "experimental-prometheus-rw", f"/scripts/{script}"]
        with open(self.dir / f"{name}.k6.log", "w", encoding="utf-8") as log:
            code = subprocess.run(COMPOSE + args, env=self.env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT).returncode
        path = self.dir / f"{name}.summary.json"
        summary = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        if code not in (0, 99) or not summary:
            tail = (self.dir / f"{name}.k6.log").read_text(encoding="utf-8", errors="replace").splitlines()[-15:]
            say(f"  k6 exited with {code}; the end of {name}.k6.log:\n    " + "\n    ".join(tail))
        return code, summary

    def record(self, name, passed, line, details):
        self.results[name] = {"passed": passed, "line": line, **details}
        say(f"{name} {'PASSED' if passed else 'FAILED'}: {line}")
        if not passed:
            self.save_logs(name)
        self.save()

    def save_logs(self, name, services=("api", "live-hub", "valhalla")):
        """The services' recent logs, next to the scenario's own, before anything restarts them."""
        result = compose("logs", "--no-color", "--tail", "400", *services, check=False, env=self.env)
        (self.dir / f"{name}.services.log").write_text(result.stdout + result.stderr, encoding="utf-8")

    def save(self):
        (self.dir / "results.json").write_text(json.dumps({
            "started": self.started.isoformat(timespec="seconds"), "quick": self.quick, "machine": self.machine,
            "seed": json.loads(SEED_FILE.read_text(encoding="utf-8"))["id"], "origins": self.origins,
            "scenarios": self.results}, indent=2) + "\n", encoding="utf-8")


def container_state(service):
    cid = compose("ps", "-q", service).strip()
    if not cid:
        return {"running": False}
    info = json.loads(run(["docker", "inspect", cid]))[0]
    return {"running": info["State"]["Running"], "oom_killed": info["State"]["OOMKilled"],
            "restarts": info.get("RestartCount", 0), "started_at": info["State"]["StartedAt"]}


def scenario_l1(r):
    env = {"MINUTES": 1} if r.quick else {}
    code, s = r.k6("L1", "L1-steady.js", env)
    d, e, f = metric(s, "http_req_duration{phase:steady}"), metric(s, "errors_not_429{phase:steady}"), \
        metric(s, "fallbacks{phase:steady}")
    ok, failed = thresholds_ok(s)
    r.record("L1", code == 0 and ok, f"p95 {ms(d, 'p(95)')}, p99 {ms(d, 'p(99)')}, errors {e.get('rate', 0):.2%}, "
             f"fallbacks {f.get('rate', 0):.2%}, {d.get('count', 0):.0f} requests"
             + (f"; failed: {', '.join(failed)}" if failed else ""),
             {"p95_ms": d.get("p(95)"), "p99_ms": d.get("p(99)"), "error_rate": e.get("rate"),
              "fallback_rate": f.get("rate"), "requests": d.get("count")})


def scenario_l2(r):
    env = {"MINUTES": 1} if r.quick else {}
    code, s = r.k6("L2", "L2-misses.js", env)
    d, e, f = metric(s, "http_req_duration"), metric(s, "errors_not_429"), metric(s, "fallbacks")
    ok, failed = thresholds_ok(s)
    r.record("L2", code == 0 and ok, f"p95 {ms(d, 'p(95)')}, p99 {ms(d, 'p(99)')}, errors {e.get('rate', 0):.2%}, "
             f"fallbacks {f.get('rate', 0):.2%}, {d.get('count', 0):.0f} searches nobody made before"
             + (f"; failed: {', '.join(failed)}" if failed else ""),
             {"p95_ms": d.get("p(95)"), "p99_ms": d.get("p(99)"), "error_rate": e.get("rate"),
              "fallback_rate": f.get("rate"), "requests": d.get("count")})


def scenario_l3(r):
    before = container_state("api")
    code, s = r.k6("L3", "L3-spike.js")
    after = container_state("api")
    accepted = metric(s, "http_req_duration{status:200}")
    recovered = metric(s, "http_req_duration{phase:recovered}")
    unexpected = metric(s, "unexpected_status").get("count", 0)
    rejected = metric(s, "rejected_503").get("count", 0)
    no_oom = after.get("running") and not after.get("oom_killed") and after.get("started_at") == before.get("started_at")
    ok, failed = thresholds_ok(s)
    r.record("L3", code == 0 and ok and bool(no_oom),
             f"accepted p99 {ms(accepted, 'p(99)')}, {unexpected:.0f} answers other than 200/429/503, "
             f"{rejected:.0f} 503s, p95 from 30 s after the spike {ms(recovered, 'p(95)')}, "
             f"api {'kept running' if no_oom else 'restarted or was killed'}"
             + (f"; failed: {', '.join(failed)}" if failed else ""),
             {"accepted_p99_ms": accepted.get("p(99)"), "unexpected": unexpected, "rejected_503": rejected,
              "recovered_p95_ms": recovered.get("p(95)"), "api": after})


def latest_l1_p95(r):
    if "L1" in r.results:
        return r.results["L1"]["p95_ms"], "this run"
    for folder in sorted(RESULTS.glob("*/results.json"), reverse=True):
        data = json.loads(folder.read_text(encoding="utf-8"))
        l1 = data.get("scenarios", {}).get("L1")
        if l1 and l1.get("p95_ms") and bool(data.get("quick")) == r.quick:
            return l1["p95_ms"], folder.parent.name
    return None, None


def scenario_l4(r):
    seconds = 60 if r.quick else 180
    code, s = r.k6("L4", "L4-abuser.js", {"SECONDS": seconds})
    ordinary = metric(s, "http_req_duration{who:ordinary}")
    abuser_429 = metric(s, "limited_429{who:abuser}").get("count", 0)
    abuser_through = metric(s, "let_through{who:abuser}").get("count", 0)
    ordinary_429 = metric(s, "limited_429{who:ordinary}").get("count", 0)
    baseline, where = latest_l1_p95(r)
    p95 = ordinary.get("p(95)")
    within = baseline is not None and p95 is not None and p95 <= 1.2 * baseline
    ok, failed = thresholds_ok(s)
    r.record("L4", code == 0 and ok and within,
             f"abuser {abuser_429 / (100 * seconds):.1%} 429s ({abuser_through:.0f} let through, its limit "
             f"{10 * seconds + 20}), ordinary clients {ordinary_429:.0f} 429s and p95 "
             f"{ms(ordinary, 'p(95)')} against L1's {f'{baseline:.0f} ms ({where})' if baseline else 'unknown: run L1'}"
             + (f"; failed: {', '.join(failed)}" if failed else ""),
             {"abuser_429_share": abuser_429 / (100 * seconds), "abuser_let_through": abuser_through,
              "ordinary_429": ordinary_429, "ordinary_p95_ms": p95,
              "l1_p95_ms": baseline})


VALHALLA_CALLS = 'sum(histogram_count(http_client_requests_seconds{job="placemap/api", uri="/sources_to_targets"}))'


def scenario_l5(r):
    origins = json.loads((r.dir / "origins.json").read_text(encoding="utf-8"))
    lat, lon = origins[0]
    cell = get_json(f"{API}/api/places/nearby?lat={lat:.5f}&lon={lon:.5f}")["snappedCell"]  # straight line: no call
    time.sleep(12)  # metrics arrive every 5 s
    start = time.time()
    calls_before = prom_value(VALHALLA_CALLS)
    code, s = r.k6("L5", "L5-one-cell.js", {"EXPECTED_CELL": cell, **({"DURATION": "30s"} if r.quick else {})})
    time.sleep(12)
    calls = prom_value(VALHALLA_CALLS) - calls_before
    requests = metric(s, "http_reqs").get("count", 0)
    other = metric(s, "other_cell").get("count", 0)
    keys = 3  # one cell, three modes
    ok, failed = thresholds_ok(s)
    r.record("L5", code == 0 and ok and calls <= keys,
             f"{calls:.0f} Valhalla calls for {requests:.0f} searches by 200 users ({keys} distinct searches, "
             f"{other:.0f} answers from another cell)" + (f"; failed: {', '.join(failed)}" if failed else ""),
             {"valhalla_calls": calls, "requests": requests, "keys": keys, "other_cell": other,
              "window": [start, time.time()]})


HEAP = 'sum by (job) (jvm_memory_used_bytes{job=~"placemap/(api|live-hub)", area="heap"})'


def heap_max(start, end):
    window = max(10, int(end - start))
    return {row["metric"]["job"]: float(row["value"][1])
            for row in prom(f"max_over_time({HEAP}[{window}s:5s])", at=end)}


def scenario_l9(r):
    time.sleep(10)
    t0 = time.time()
    code, s = r.k6("L9", "L9-bodies.js", {"DURATION": "20s"} if r.quick else {})
    t1 = time.time()
    time.sleep(12)
    before, during = heap_max(t0 - 60, t0), heap_max(t0, t1)
    growth = {job: (during.get(job, 0) - before.get(job, 0)) / 2**20 for job in during}
    flat = bool(growth) and all(g < 64 for g in growth.values())
    d = metric(s, "http_req_duration")
    checks = metric(s, "checks")
    ok, failed = thresholds_ok(s)
    r.record("L9", code == 0 and ok and flat,
             f"p99 {ms(d, 'p(99)')}, {checks.get('rate', 0):.1%} answered as expected, heap growth "
             + ", ".join(f"{job.split('/')[-1]} {g:+.0f} MB" for job, g in sorted(growth.items()))
             + (f"; failed: {', '.join(failed)}" if failed else ""),
             {"p99_ms": d.get("p(99)"), "expected_rate": checks.get("rate"), "heap_growth_mb": growth})


class Sampler:
    """Samples a service's container memory and CPU every few seconds while a scenario runs.

    The headline figure is docker stats' (what the earlier runs used). Each sample also reads the container's cgroup
    (memory.current and memory.stat's anon, file, kernel and socket parts), and the end reads memory.events, so a
    peak can be explained: what the limit counts, what it does not, and whether the kernel had to reclaim.
    """

    UNITS = {"B": 1, "KiB": 2**10, "MiB": 2**20, "GiB": 2**30, "kB": 1e3, "MB": 1e6, "GB": 1e9}
    CGROUP = ("for f in memory.current memory.max memory.peak memory.stat memory.events; do echo \"## $f\"; "
              "cat /sys/fs/cgroup/$f 2>/dev/null; done")
    STAT_KEYS = ("anon", "file", "kernel", "sock", "active_file", "inactive_file", "slab", "kernel_stack", "pagetables")

    def __init__(self, service, every=5):
        self.service = service
        self.cid = compose("ps", "-q", service).strip()
        self.every = every
        self.memory = []
        self.times = []
        self.cpu = []
        self.cgroups = []  # one dict a sample, in MiB, next to self.memory's
        self.events_before = self.cgroup().get("events", {})
        self.started = time.time()
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.loop, daemon=True)

    def cgroup(self):
        out = subprocess.run(["docker", "exec", self.cid, "sh", "-c", self.CGROUP],
                             capture_output=True, text=True).stdout
        files, name = {}, None
        for line in out.splitlines():
            if line.startswith("## "):
                name = line[3:]
                files[name] = []
            elif name:
                files[name].append(line.split())
        one = lambda f: int(files[f][0][0]) if files.get(f) and files[f][0][0].isdigit() else None
        kv = lambda f: {k: int(v) for k, v in files.get(f, []) if v.isdigit()}
        stat = kv("memory.stat")
        return {"current": one("memory.current"), "max": one("memory.max"), "peak": one("memory.peak"),
                "stat": {k: stat[k] for k in self.STAT_KEYS if k in stat}, "events": kv("memory.events")}

    def loop(self):
        while not self.stop.is_set():
            out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}|{{.CPUPerc}}", self.cid],
                                 capture_output=True, text=True).stdout.strip()
            m = re.match(r"([\d.]+)\s*([A-Za-z]+)\s*/.*\|([\d.]+)%", out)
            if m:
                self.memory.append(float(m.group(1)) * self.UNITS.get(m.group(2), 1) / 2**20)
                self.times.append(time.time())
                self.cpu.append(float(m.group(3)))
                self.cgroups.append(self.cgroup())
            self.stop.wait(self.every)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.stop.set()
        self.thread.join()
        self.events_after = self.cgroup().get("events", {})
        self.ended = time.time()

    def peak_mb(self, start=None, end=None):
        return max((mb for mb, t in zip(self.memory, self.times)
                    if (start is None or t >= start) and (end is None or t <= end)), default=0)

    def mean_cpu(self):
        return sum(self.cpu) / len(self.cpu) if self.cpu else 0

    def breakdown(self):
        """At the peak sample: the cgroup's parts in MiB; over the run: the limit's events and the JVM's own view."""
        if not self.memory:
            return {}
        mib = lambda b: None if b is None else round(b / 2**20, 1)
        at = self.cgroups[self.memory.index(self.peak_mb())]
        last = self.cgroups[-1]
        job = f"placemap/{self.service}"
        w = f"{max(10, int(self.ended - self.started))}s"
        jvm = lambda q: round(prom_value(f"max_over_time(({q})[{w}:5s])", at=self.ended) / 2**20, 1)
        return {
            "docker_stats_peak_mib": round(self.peak_mb(), 1),
            "cgroup_at_peak_mib": {"current": mib(at["current"]), "max": mib(at["max"]),
                                   **{k: mib(v) for k, v in at["stat"].items()}},
            "cgroup_peak_mib": mib(last["peak"]),
            "limit_events": {k: self.events_after.get(k, 0) - self.events_before.get(k, 0)
                             for k in ("high", "max", "oom", "oom_kill")},
            "jvm_max_mib": {
                "heap_used": jvm(f'sum(jvm_memory_used_bytes{{job="{job}", area="heap"}})'),
                "heap_committed": jvm(f'sum(jvm_memory_committed_bytes{{job="{job}", area="heap"}})'),
                "nonheap_committed": jvm(f'sum(jvm_memory_committed_bytes{{job="{job}", area="nonheap"}})'),
                "direct_buffers": jvm(f'sum(jvm_buffer_memory_used_bytes{{job="{job}", id="direct"}})'),
            },
        }


def live_sessions_settled(limit=120):
    """Waits until live-hub has no open streams left from an earlier scenario; returns the seconds it took, or None."""
    start = time.time()
    while time.time() - start < limit:
        if prom_value('sum(placemap_live_sessions{job="placemap/live-hub"})', default=0) == 0:
            return time.time() - start
        time.sleep(5)  # metrics arrive every 5 s
    return None


def sse(r, name, clients, seconds, slow_share=0.0, ramp=250, topics="bikes", extra=()):
    """Runs the idle-stream client; returns its summary (or {} if it failed)."""
    settled = live_sessions_settled()
    if settled is None:
        say(f"  live-hub still holds streams from before {name} after 120 s; starting anyway")
    elif settled > 10:
        say(f"  live-hub took {settled:.0f} s to let go of the streams from before {name}")
    args = ["--profile", "tools", "run", "--rm", "--no-deps", "-T", "--user", f"{os.getuid()}:{os.getgid()}",
            "sse-client", "--url", f"http://live-hub:8081/live?topics={topics}", "--clients", str(clients),
            "--ramp", str(ramp), "--duration", str(seconds), "--slow-share", str(slow_share),
            "--out", f"/work/{name}.sse.json", *extra]
    with open(r.dir / f"{name}.sse.log", "w", encoding="utf-8") as log:
        code = subprocess.run(COMPOSE + args, env=r.env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT).returncode
    path = r.dir / f"{name}.sse.json"
    if code or not path.exists():
        tail = (r.dir / f"{name}.sse.log").read_text(encoding="utf-8", errors="replace").splitlines()[-15:]
        say(f"  the stream client exited with {code}; the end of {name}.sse.log:\n    " + "\n    ".join(tail))
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def live_server_stats(start, end):
    """live-hub's own view over a window: fan-out lag p99, evictions and how far behind, peak threads."""
    w = f"{max(10, int(end - start))}s"
    return {
        "fanout_p99_ms": 1000 * prom_value(
            f"histogram_quantile(0.99, sum(increase(placemap_live_fanout_lag_seconds[{w}])))", at=end, default=-0.001),
        "evictions": prom_value(f"sum(histogram_count(increase(placemap_live_evictions_behind_seconds[{w}])))", at=end),
        "evicted_behind_max_s": prom_value(
            f'max(max_over_time(placemap_live_evictions_behind_max_seconds{{job="placemap/live-hub"}}[{w}]))', at=end),
        "threads_max": prom_value(f'max(max_over_time(jvm_threads_live{{job="placemap/live-hub"}}[{w}]))', at=end),
    }


def scenario_l6(r):
    clients, seconds = (1000, 60) if r.quick else (5000, 600)
    start = time.time()
    with Sampler("live-hub") as sampler:
        s = sse(r, "L6", clients, seconds)
    time.sleep(12)
    server = live_server_stats(start + 30, time.time() - 12)
    lag = s.get("lag_ms", {})
    held = s.get("peak_open", 0) >= clients and s.get("open_at_end", 0) >= clients
    passed = bool(s) and held and 0 <= lag.get("p99", -1) < 1000 and sampler.peak_mb() < 1024
    r.record("L6", passed,
             f"{s.get('open_at_end', 0)} of {clients} streams held for {seconds // 60 or seconds} "
             f"{'min' if seconds >= 60 else 's'}, lag p99 {lag.get('p99', -1)} ms (live-hub's own {server['fanout_p99_ms']:.0f} ms), "
             f"live-hub peak {sampler.peak_mb():.0f} MB, {s.get('events', 0)} changes delivered",
             {"clients": clients, "seconds": seconds, "client": {k: v for k, v in s.items() if k != "windows"},
              "server": server, "live_hub_peak_mb": sampler.peak_mb(), "live_hub_mean_cpu": sampler.mean_cpu(),
              "memory": sampler.breakdown()})


def scenario_l7(r):
    clients, seconds = (1000, 90) if r.quick else (5000, 180)
    start = time.time()
    s = sse(r, "L7", clients, seconds, slow_share=0.01)
    time.sleep(12)
    server = live_server_stats(start, time.time() - 12)
    slow = s.get("slow", 0)
    evicted = s.get("slow_clients", {}).get("evicted", 0)
    p99 = s.get("lag_ms", {}).get("p99", -1)
    baseline = r.results.get("L6", {}).get("client", {}).get("lag_ms", {}).get("p99")
    unchanged = baseline is not None and 0 <= p99 <= 1.2 * baseline + 20
    passed = bool(s) and slow > 0 and evicted == slow and server["evicted_behind_max_s"] <= 12 and unchanged
    r.record("L7", passed,
             f"{evicted} of {slow} slow readers evicted, at most {server['evicted_behind_max_s']:.1f} s behind; "
             f"the other streams' lag p99 {p99} ms against L6's "
             f"{f'{baseline} ms' if baseline is not None else 'unknown: run L6 first'}",
             {"slow": slow, "evicted": evicted, "server": server, "lag_p99_ms": p99, "l6_lag_p99_ms": baseline,
              "client": {k: v for k, v in s.items() if k != "windows"}})


def restart_live_hub(r, threads):
    r.save_logs(f"live-hub-before-{threads}", services=("live-hub",))
    r.env["PLACEMAP_LIVE_THREADS"] = threads
    compose("up", "-d", "--wait", "--force-recreate", "--no-deps", "live-hub", env=r.env)
    time.sleep(10)  # the first feed poll and snapshot


def scenario_l8(r):
    levels, seconds = ([500, 1000], 30) if r.quick else ([500, 1000, 2000, 5000], 120)
    rows = []
    try:
        for threads in ("virtual", "pool"):
            restart_live_hub(r, threads)
            for n in levels:
                start = time.time()
                with Sampler("live-hub", every=3) as sampler:
                    s = sse(r, f"L8-{threads}-{n}", n, seconds)
                time.sleep(12)
                server = live_server_stats(start + 15, time.time() - 12)
                lag = s.get("lag_ms", {})
                rows.append({"threads": threads, "streams": n, "held": s.get("open_at_end", 0),
                             "lag_p50_ms": lag.get("p50"), "lag_p99_ms": lag.get("p99"), "lag_max_ms": lag.get("max"),
                             "fanout_p99_ms": round(server["fanout_p99_ms"]), "jvm_threads": server["threads_max"],
                             "peak_mb": round(sampler.peak_mb()), "mean_cpu_pct": round(sampler.mean_cpu(), 1)})
                say(f"  {threads} threads, {n} streams: held {rows[-1]['held']}, lag p99 {lag.get('p99')} ms, "
                    f"{server['threads_max']:.0f} platform threads, "
                    f"{sampler.peak_mb():.0f} MB, {sampler.mean_cpu():.0f}% CPU")
    finally:
        restart_live_hub(r, "virtual")
    # Platform threads only: the JVM counts virtual threads only while they run, not while they wait on a stream.
    table = ["| Threads | Streams | Held | Lag p50 | Lag p99 | Lag max | live-hub p99 | Platform threads | Peak MB | "
             "CPU % |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for row in rows:
        table.append(f"| {row['threads']} | {row['streams']} | {row['held']} | {row['lag_p50_ms']} ms | "
                     f"{row['lag_p99_ms']} ms | {row['lag_max_ms']} ms | {row['fanout_p99_ms']} ms | "
                     f"{row['jvm_threads']:.0f} | {row['peak_mb']} | {row['mean_cpu_pct']} |")
    (r.dir / "L8.md").write_text("\n".join(table) + "\n", encoding="utf-8")
    complete = len(rows) == 2 * len(levels) and all(row["held"] >= row["streams"] for row in rows)
    r.record("L8", complete, f"{len(rows)} measurements written to L8.md"
             + ("" if complete else "; some levels did not hold every stream"), {"rows": rows})



NEARBY_HITS = 'sum by (result) (increase(cache_gets_total{{job="placemap/api", cache="nearby"}}[{w}]))'


def scenario_l11(r):
    """The traffic ceiling: L1's mix at rising rates; the last step before the first miss is the sustained rate."""
    ceiling(r, "L11", {}, dict(WARMUP_SECONDS=20, STEP_SECONDS=40, SETTLE_SECONDS=20, FIRST_RATE=50, STEP_BY=25,
                               LAST_RATE=100) if r.quick else
            dict(WARMUP_SECONDS=60, STEP_SECONDS=120, SETTLE_SECONDS=60, FIRST_RATE=50, STEP_BY=25, LAST_RATE=500))


def scenario_l13c(r):
    """The popular mix's ceiling, in quicker and bigger steps, for the soak (L13) to run under."""
    ceiling(r, "L13c", {"MIX": "popular", "NAME": "L13c"},
            dict(WARMUP_SECONDS=20, STEP_SECONDS=30, SETTLE_SECONDS=15, FIRST_RATE=100, STEP_BY=100, LAST_RATE=300)
            if r.quick else
            dict(WARMUP_SECONDS=60, STEP_SECONDS=60, SETTLE_SECONDS=30, FIRST_RATE=100, STEP_BY=100, LAST_RATE=1500))


def ceiling(r, name, env, shape):
    """Rising steps until one misses L1's line; the last step before it is the sustained rate."""
    started = time.time() + 3  # k6 takes a moment to start
    code, s = r.k6(name, "L11-ceiling.js", {**env, **shape})
    time.sleep(12)  # metrics arrive every 5 s
    rows, sustained, missed, sustained_end = [], None, None, None
    for i, rate in enumerate(range(shape["FIRST_RATE"], shape["LAST_RATE"] + 1, shape["STEP_BY"])):
        d = metric(s, f"http_req_duration{{step:{rate}}}")
        if not d.get("count"):
            break
        e, f = metric(s, f"errors_not_429{{step:{rate}}}"), metric(s, f"fallbacks{{step:{rate}}}")
        rejected = metric(s, f"rejected_503{{step:{rate}}}").get("count", 0)
        met = d.get("p(95)", 1e9) < 300 and e.get("rate", 1) < 0.005 and f.get("rate", 0) < 0.01
        to = started + shape["WARMUP_SECONDS"] + (i + 1) * shape["STEP_SECONDS"]
        window = max(10, int(min(to, time.time() - 12) - (to - shape["STEP_SECONDS"] + 5)))
        gets = {row["metric"].get("result"): float(row["value"][1])
                for row in prom(NEARBY_HITS.format(w=f"{window}s"), at=min(to, time.time() - 12))}
        hits = gets.get("hit", 0) / max(1, gets.get("hit", 0) + gets.get("miss", 0))
        rows.append({"rate": rate, "requests": d.get("count"), "p95_ms": d.get("p(95)"), "p99_ms": d.get("p(99)"),
                     "error_rate": e.get("rate"), "fallback_rate": f.get("rate"), "rejected_503": rejected,
                     "cache_hit_rate": round(hits, 3), "met": met})
        if not met:
            missed = rate
            break
        sustained, sustained_end = rate, to
    limited = metric(s, "limited_429").get("count", 0)
    dropped = metric(s, "dropped_iterations").get("count", 0)
    # Iterations k6 could not start (no free VU) before the sustained step ended: k6, not the api, would have capped
    # the rate there. Drops in the step that missed come from its slow answers and do not change the ceiling. k6
    # writes the counter only once it is non-zero, so its largest value by then is the count.
    dropped_by_ceiling = 0 if sustained_end is None else prom_value(
        f'max(max_over_time(k6_dropped_iterations_total{{run="{r.dir.name}", scenario="ceiling"}}'
        f'[{int(sustained_end - started) + 60}s]))', at=sustained_end, default=0)
    table = ["| Rate | Requests | p95 | p99 | Fallbacks | Errors | 503s | Cache hits | Met |",
             "|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    for row in rows:
        table.append(f"| {row['rate']}/s | {row['requests']:.0f} | {row['p95_ms']:.0f} ms | {row['p99_ms']:.0f} ms | "
                     f"{row['fallback_rate'] or 0:.2%} | {row['error_rate'] or 0:.2%} | {row['rejected_503']:.0f} | "
                     f"{row['cache_hit_rate']:.0%} | {'yes' if row['met'] else 'no'} |")
    (r.dir / f"{name}.md").write_text("\n".join(table) + "\n", encoding="utf-8")
    passed = code in (0, 99) and sustained is not None and limited == 0 and dropped_by_ceiling == 0
    r.record(name, passed,
             f"sustained {f'{sustained}/s' if sustained else 'none'} (the last step meeting L1's line"
             + (f"; {missed}/s missed it" if missed else "; every step met it, up to the cap") + "), "
             f"{limited:.0f} answers 429, {dropped_by_ceiling:.0f} dropped iterations up to it ({dropped:.0f} in all); "
             f"steps in {name}.md",
             {"sustained_rate": sustained, "first_missed_rate": missed, "limited_429": limited,
              "dropped_iterations": dropped, "dropped_by_ceiling": dropped_by_ceiling, "steps": rows,
              "shape": shape})



SOAK_REQUESTS = 1_000_000  # how many requests the soak aims for: as long as that takes, at most 2 hours


def latest_value(r, name, key):
    """A scenario's figure from this run, else from the newest run that has it (quick with quick)."""
    if name in r.results:
        return r.results[name].get(key), "this run"
    for path in sorted(RESULTS.glob("*/results.json"), reverse=True):
        data = json.loads(path.read_text(encoding="utf-8"))
        value = data.get("scenarios", {}).get(name, {}).get(key)
        if value and bool(data.get("quick")) == r.quick:
            return value, path.parent.name
    return None, None


def live_heap_mb(service, start, end):
    """The service's largest live heap (after a collection) between two times, in MiB."""
    window = max(10, int(end - start))
    return prom_value(f'max(max_over_time(jvm_gc_live_data_size_bytes{{job="placemap/{service}"}}[{window}s]))',
                      at=end) / 2**20


def scenario_l13(r):
    """A soak: the popular mix at 70% of its ceiling until about a million requests, with live viewers watching."""
    found, where = latest_value(r, "L13c", "sustained_rate")
    if r.quick:
        rate, minutes, viewers = 100, 3, 200
    elif not found:
        sys.exit("L13 runs under its mix's ceiling: run L13c first")
    else:
        rate = max(50, int(0.7 * found) // 25 * 25)
        minutes = min(120, max(30, -(-SOAK_REQUESTS // (rate * 60))))
        viewers = 1000
    seconds = minutes * 60
    watching = {}
    viewers_thread = threading.Thread(
        target=lambda: watching.update(sse(r, "L13", viewers, seconds, topics=VEHICLES_TOPICS)), daemon=True)
    with Sampler("api") as api_memory, Sampler("live-hub") as hub_memory:
        viewers_thread.start()
        started = time.time() + 3
        code, s = r.k6("L13", "L13-soak.js", {"RATE": rate, "MINUTES": minutes})
        ended = time.time()
        viewers_thread.join(timeout=180)
    time.sleep(12)  # metrics arrive every 5 s
    total = metric(s, "http_reqs").get("count", 0)
    d = metric(s, "http_req_duration")
    errors, fallbacks = metric(s, "errors_not_429"), metric(s, "fallbacks")
    limited = metric(s, "limited_429").get("count", 0)
    rejected = metric(s, "rejected_503").get("count", 0)
    windows = []
    for w in range(-(-seconds // 600)):
        dw, ew, fw = (metric(s, f"{m}{{w:{w}}}") for m in ("http_req_duration", "errors_not_429", "fallbacks"))
        if dw.get("count"):
            met = dw.get("p(99)", 1e9) < 600 and ew.get("rate", 1) < 0.005 and fw.get("rate", 0) < 0.01
            windows.append({"window": w, "requests": dw.get("count"), "p50_ms": dw.get("med"),
                            "p95_ms": dw.get("p(95)"), "p99_ms": dw.get("p(99)"), "error_rate": ew.get("rate"),
                            "fallback_rate": fw.get("rate"), "met": met})
    # Memory over the run, after the cache has filled: the largest live heap early on against the same near the end.
    span = min(900, seconds / 3)
    settle = min(300, seconds / 6)  # the shared results fill in the first minutes
    heap = {svc: {"early_mb": round(live_heap_mb(svc, started + settle, started + settle + span)),
                  "late_mb": round(live_heap_mb(svc, ended - span, ended))} for svc in ("api", "live-hub")}
    flat = all(h["late_mb"] <= 1.15 * h["early_mb"] + 32 for h in heap.values())
    vehicles = {row["phase"]: row for row in watching.get("lag_by_phase", {}).get("vehicles", [])}
    held = bool(watching) and watching.get("peak_open", 0) >= viewers and watching.get("open_at_end", 0) >= viewers
    ok, failed = thresholds_ok(s)
    table = ["| Minutes | Requests | p50 | p95 | p99 | Errors | Fallbacks | Met |",
             "|---|---:|---:|---:|---:|---:|---:|---|"]
    for row in windows:
        table.append(f"| {10 * row['window']}–{10 * row['window'] + 10} | {row['requests']:.0f} | "
                     f"{row['p50_ms']:.0f} ms | {row['p95_ms']:.0f} ms | {row['p99_ms']:.0f} ms | "
                     f"{row['error_rate'] or 0:.2%} | {row['fallback_rate'] or 0:.2%} | "
                     f"{'yes' if row['met'] else 'no'} |")
    (r.dir / "L13.md").write_text("\n".join(table) + "\n", encoding="utf-8")
    passed = (code in (0, 99) and ok and limited == 0 and held and watching.get("resyncs", -1) == 0 and flat
              and bool(windows) and all(row["met"] for row in windows))
    r.record("L13", passed,
             f"{total:,.0f} requests at {rate}/s for {minutes} min (70% of the popular mix's ceiling "
             f"{found or '?'}/s, {where or 'quick'}): p50 {ms(d, 'med')}, p95 {ms(d, 'p(95)')}, p99 {ms(d, 'p(99)')}, "
             f"errors {errors.get('passes', 0):.0f} ({errors.get('rate', 0):.3%}), "
             f"fallbacks {fallbacks.get('rate', 0):.2%}, "
             f"{limited:.0f} × 429, {rejected:.0f} × 503; {sum(row['met'] for row in windows)} of {len(windows)} "
             f"10-minute windows met L1's line; {watching.get('open_at_end', 0)} of {viewers} live viewers held, "
             f"{watching.get('resyncs', -1)} resyncs; live heap api {heap['api']['early_mb']} → "
             f"{heap['api']['late_mb']} MB, "
             f"live-hub {heap['live-hub']['early_mb']} → {heap['live-hub']['late_mb']} MB; windows in L13.md"
             + (f"; failed: {', '.join(failed[:4])}" if failed else ""),
             {"rate": rate, "minutes": minutes, "ceiling": found, "requests": total, "p50_ms": d.get("med"),
              "p95_ms": d.get("p(95)"), "p99_ms": d.get("p(99)"), "errors": errors.get("passes", 0),
              "error_rate": errors.get("rate"), "fallback_rate": fallbacks.get("rate"), "limited_429": limited,
              "rejected_503": rejected, "windows": windows, "heap": heap, "viewers": viewers,
              "vehicles_p99_ms": vehicles.get(0, {}).get("p99"),
              "client": {k: v for k, v in watching.items() if k != "windows"},
              "memory": {"api_peak_mb": round(api_memory.peak_mb()), "live_hub_peak_mb": round(hub_memory.peak_mb())}})


STANDINS = "http://127.0.0.1:58089"
VEHICLES_TOPICS = "alerts,bikes,vehicles"  # what the map asks for with "Show buses and trains" on


def vehicles_multiple(k):
    request = urllib.request.Request(f"{STANDINS}/control/vehicles?multiple={k}", data=b"", method="POST")
    with NO_PROXY.open(request, timeout=5) as response:
        return json.loads(response.read())


def scenario_l12(r):
    """Live trains and buses: 5,000 viewers with vehicles on, at MBTA's measured rate, then at 2x, 4x and 8x."""
    clients, real, step = (1000, 60, 30) if r.quick else (5000, 600, 120)
    multiples = [1, 2, 4, 8]
    seconds = real + step * (len(multiples) - 1)
    ramp = 250
    if live_sessions_settled() is None:
        say("  live-hub still holds streams from before L12 after 120 s; starting anyway")
    setting = vehicles_multiple(1)
    steady = time.time() + 3 + clients / ramp + 10  # compose run, the ramp, the client's own 10 s
    phases = []  # (multiple, start, end), wall clock
    t = steady
    for k in multiples:
        length = real if k == 1 else step
        phases.append((k, t, t + length))
        if k > 1:
            at(t, lambda k=k: vehicles_multiple(k))
        t += length
    try:
        with Sampler("live-hub") as sampler:
            boundaries = ",".join(str(round(start - steady)) for _, start, _ in phases[1:])
            s = sse(r, "L12", clients, seconds, topics=VEHICLES_TOPICS, ramp=ramp, extra=("--phases", boundaries))
    finally:
        vehicles_multiple(1)
    time.sleep(12)  # metrics arrive every 5 s
    windows = s.get("windows", [])
    by_phase = {row["phase"]: row for row in s.get("lag_by_phase", {}).get("vehicles", [])}
    rows = []
    for i, (k, start, end) in enumerate(phases):
        settle = 20  # a switch's first windows still carry the step before
        mine = [w for w in windows if start - steady + (settle if k > 1 else 0) < w["t"] <= end - steady]
        w = f"{max(10, int(end - start))}s"
        overflows = prom_value(f"sum(increase(placemap_live_mailbox_overflows_total[{w}]))", at=end)
        delay = 1000 * prom_value(f'histogram_quantile(0.99, sum(increase(placemap_live_publish_delay_seconds'
                                  f'{{topic="vehicles"}}[{w}])))', at=end, default=-0.001)
        worst = max((x.get("vehicles_p99", -1) for x in mine), default=-1)  # the slowest 10 s window's p99
        client = by_phase.get(i, {}).get("p99", -1)  # the p99 over the whole phase
        seconds_in = 10 * len(mine)
        rows.append({
            "multiple": k, "vehicles": setting["vehicles"] * k, "windows": len(mine),
            "min_open": min((x["open"] for x in mine), default=0),
            "resyncs": sum(x.get("resyncs", 0) for x in mine), "server_overflows": round(overflows),
            "vehicles_client_p99_ms": client, "vehicles_worst_window_p99_ms": worst,
            "publish_delay_p99_ms": round(delay),
            "delivery_p99_ms": client + max(0, round(delay)) if client >= 0 else -1,
            "bytes_per_viewer_per_s": round(sum(x.get("bytes", 0) for x in mine) / max(1, clients * seconds_in)),
            "peak_mb": round(sampler.peak_mb(start, end))})
        row = rows[-1]
        row["kept_up"] = (row["resyncs"] == 0 and row["server_overflows"] == 0
                          and 0 <= row["delivery_p99_ms"] < 1000 and row["min_open"] >= clients)
    table = ["| Rate | Vehicles | Resyncs (client) | Overflows (live-hub) | Vehicles lag p99 | Worst 10 s window | "
             "Publish delay p99 | Delivery p99 | Bytes/viewer/s | Peak MB | Kept up |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    for row in rows:
        table.append(f"| {row['multiple']}x | {row['vehicles']} | {row['resyncs']} | {row['server_overflows']} | "
                     f"{row['vehicles_client_p99_ms']} ms | {row['vehicles_worst_window_p99_ms']} ms | "
                     f"{row['publish_delay_p99_ms']} ms | "
                     f"{row['delivery_p99_ms']} ms | {row['bytes_per_viewer_per_s']} | {row['peak_mb']} | "
                     f"{'yes' if row['kept_up'] else 'no'} |")
    (r.dir / "L12.md").write_text("\n".join(table) + "\n", encoding="utf-8")
    first = rows[0] if rows else {}
    highest = None
    for row in rows:
        if not row["kept_up"]:
            break
        highest = row["multiple"]
    passed = bool(s) and bool(first) and first["kept_up"] and first["peak_mb"] < 1024
    r.record("L12", passed,
             f"{first.get('min_open', 0)} of {clients} viewers held on {VEHICLES_TOPICS} at MBTA's rate "
             f"({setting['vehicles']} vehicles, each reporting every {setting['per_vehicle_period_s']} s): "
             f"{first.get('resyncs', -1)} resyncs, {first.get('server_overflows', -1)} mailbox overflows, vehicles "
             f"delivered p99 {first.get('delivery_p99_ms', -1)} ms from live-hub's receipt, "
             f"{first.get('bytes_per_viewer_per_s', 0)} bytes a viewer a second, live-hub peak "
             f"{first.get('peak_mb', 0)} MB; keeps up to {f'{highest}x' if highest else 'none'}; rates in L12.md",
             {"clients": clients, "setting": setting, "phases": rows, "highest_multiple": highest,
              "client": {k: v for k, v in s.items() if k != "windows"}, "memory": sampler.breakdown()})


TOXIPROXY = Toxiproxy("http://127.0.0.1:58474")


def at(epoch, action):
    """Runs action at the given time on a timer thread; returns the timer."""
    timer = threading.Timer(max(0.0, epoch - time.time()), action)
    timer.daemon = True
    timer.start()
    return timer


def everyday(r, name, rate, seconds, fault_from=None, fault_to=None):
    env = {"NAME": name, "RATE": rate, "DURATION": f"{seconds}s"}
    if fault_from:
        env["FAULT_FROM"] = int(fault_from * 1000)
    if fault_to:
        env["FAULT_TO"] = int(fault_to * 1000)
    return r.k6(name, "everyday.js", env)


def scenario_l10(r):
    seconds = 60 if r.quick else 120
    code, base = everyday(r, "L10-baseline", 20, seconds)
    baseline = metric(base, "http_req_duration{phase:before}").get("p(95)")
    # 2,000 connections opened at 200 a second, each sending one more header line every 10 s, for the whole run.
    attack_seconds = seconds + 40
    args = ["--profile", "tools", "run", "--rm", "--no-deps", "-T", "slowhttptest", "-H", "-c", "2000", "-r", "200",
            "-i", "10", "-x", "24", "-p", "3", "-l", str(attack_seconds), "-t", "GET",
            "-u", "http://api:8080/api/places/nearby?lat=42.355&lon=-71.0656", "-g", "-o", "/work/L10-slowloris"]
    log = open(r.dir / "L10.slowhttptest.log", "w", encoding="utf-8")
    attack = subprocess.Popen(COMPOSE + args, env=r.env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    time.sleep(20)  # every slow connection is open
    code, during = everyday(r, "L10-during", 20, seconds)
    attack.wait()
    log.close()
    p95 = metric(during, "http_req_duration{phase:before}").get("p(95)")
    errors = metric(during, "errors_not_429{phase:fault}").get("rate", 0) or \
        metric(during, "errors_not_429").get("rate", 0)
    # slowhttptest's CSV: Seconds, Closed, Pending, Connected, Service Available, one row a second.
    csv_path = r.dir / "L10-slowloris.csv"
    rows = [line.split(",") for line in csv_path.read_text(encoding="utf-8").splitlines()[1:]] if csv_path.exists() else []
    connected = max((int(row[3]) for row in rows if len(row) >= 4 and row[3].isdigit()), default=0)
    passed = baseline is not None and p95 is not None and p95 < 2 * baseline and code in (0, 99) and connected >= 1500
    r.record("L10", passed,
             f"ordinary p95 {p95 or 0:.0f} ms with up to {connected} slow connections held, against "
             f"{baseline or 0:.0f} ms without (limit 2x); errors {errors:.2%}",
             {"baseline_p95_ms": baseline, "during_p95_ms": p95, "slow_connections_peak": connected,
              "error_rate": errors})


def scenario_c3(r):
    seconds, kill_after = (90, 30) if r.quick else (180, 60)
    start = time.time() + 5  # k6 takes a moment to start
    fault_from = start + kill_after
    killed = {}

    def kill():
        compose("kill", "-s", "SIGKILL", "valhalla")
        killed["at"] = time.time()
    at(fault_from, kill)
    code, s = everyday(r, "C3", 50, seconds, fault_from=fault_from)
    compose("up", "-d", "--wait", "--no-deps", "valhalla")
    errors = metric(s, "errors_not_429").get("rate", 0)
    unflagged = metric(s, "unflagged_straight_line").get("count", 0)
    first_open = metric(s, "breaker_open_after_ms").get("min") if metric(s, "breaker_opened").get("count") else None
    fault = metric(s, "http_req_duration{phase:fault}")
    held, failed = thresholds_ok(s)  # everyday.js's C3 thresholds: k6 judges
    passed = "at" in killed and bool(s) and held
    r.record("C3", passed,
             f"Valhalla killed {kill_after} s in: errors {errors:.2%}, {unflagged:.0f} unflagged straight-line "
             f"answers, breaker open {f'{first_open / 1000:.1f} s' if first_open is not None else 'never'} after the "
             f"kill, p95 during the outage {ms(fault, 'p(95)')}" + (f"; failed: {', '.join(failed)}" if failed else ""),
             {"error_rate": errors, "unflagged": unflagged, "breaker_open_after_ms": first_open,
              "outage_p95_ms": fault.get("p(95)")})


def scenario_c4(r):
    seconds, slow_from, slow_for = (120, 30, 60) if r.quick else (240, 60, 120)
    start = time.time() + 5
    fault_from, fault_to = start + slow_from, start + slow_from + slow_for
    at(fault_from, lambda: TOXIPROXY.add_toxic("postgres", "slow", "latency", {"latency": 500, "jitter": 0}))
    at(fault_to, lambda: TOXIPROXY.remove_toxic("postgres", "slow"))
    try:
        code, s = everyday(r, "C4", 50, seconds, fault_from=fault_from, fault_to=fault_to)
    finally:
        TOXIPROXY.remove_toxic("postgres", "slow")
    accepted = metric(s, "http_req_duration{phase:fault,status:200}")
    refused = metric(s, "refused_503{phase:fault}").get("count", 0)
    missing = metric(s, "refused_without_retry_after").get("count", 0)
    errors = metric(s, "errors_not_429{phase:fault}").get("rate", 0)
    p99 = accepted.get("p(99)")
    held, failed = thresholds_ok(s)  # everyday.js's C4 thresholds: k6 judges
    passed = bool(s) and held
    r.record("C4", passed,
             f"500 ms added to every database reply for {slow_for} s: {refused:.0f} answers 503 "
             f"({missing:.0f} without Retry-After), accepted p99 {ms(accepted, 'p(99)')} (limit: the 1.5 s deadline + "
             f"100 ms), other errors {errors:.2%}" + (f"; failed: {', '.join(failed)}" if failed else ""),
             {"refused_503": refused, "without_retry_after": missing, "accepted_p99_ms": p99, "error_rate": errors})


def scenario_c5(r):
    clients, seconds = (1000, 90) if r.quick else (5000, 180)
    kill_after = 40 if r.quick else 60  # after the ramp and the steady start

    def kill():
        r.save_logs("C5-before-kill", services=("live-hub",))
        run(["docker", "kill", "-s", "KILL", compose("ps", "-q", "live-hub").strip()])
        compose("up", "-d", "--no-deps", "live-hub")  # a supervisor would start it again at once
    at(time.time() + clients / 250 + 10 + kill_after, kill)
    s = sse(r, "C5", clients, seconds)
    rec = s.get("recoveries", {})
    share = rec.get("within_30s", 0) / clients
    passed = bool(s) and share >= 0.99
    r.record("C5", passed,
             f"live-hub killed with -9 under {clients} streams: {rec.get('within_30s', 0)} of {clients} "
             f"({share:.1%}) back with a fresh snapshot within 30 s, p50 {rec.get('p50_ms', -1) / 1000:.1f} s, "
             f"p99 {rec.get('p99_ms', -1) / 1000:.1f} s, {rec.get('unrecovered', 0)} never",
             {"recovered_within_30s": rec.get("within_30s", 0), "share": share, "recoveries": rec})


def topic_stale(topic):
    body = json.dumps({"keys": [f"{topic}:none"]}).encode()
    request = urllib.request.Request(f"{LIVE}/internal/live/batch", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
    with NO_PROXY.open(request, timeout=2) as response:
        return json.loads(response.read())["topics"][topic]["stale"]


def feed_goes_silent(r, name, label, proxy, topic, ttl, fresh_within):
    """A feed's replies stop arriving for a while: its topic must turn stale within 3x its ttl (+1 s for a tick and a
    poll), turn fresh again once the feed is back, and live-hub must not pile up threads waiting on it."""
    hold = 30 if r.quick and ttl < 10 else 60
    threads = 'max(max_over_time(jvm_threads_live{{job="placemap/live-hub"}}[{w}]))'
    time.sleep(10)
    before = prom_value(threads.format(w="60s"))
    if topic_stale(topic):
        sys.exit(f"{name}: the {topic} feed is already stale before the fault")
    # The feed's replies stop arriving: connections stay open, nothing comes back.
    fault_from = time.time()
    TOXIPROXY.add_toxic(proxy, "silent", "timeout", {"timeout": 0})
    stale_after = None
    try:
        while time.time() < fault_from + hold:
            if stale_after is None and topic_stale(topic):
                stale_after = time.time() - fault_from
            time.sleep(0.25)
    finally:
        TOXIPROXY.remove_toxic(proxy, "silent")
    fault_to = time.time()
    fresh_after = None
    while time.time() < fault_to + fresh_within:
        if not topic_stale(topic):
            fresh_after = time.time() - fault_to
            break
        time.sleep(0.25)
    time.sleep(12)
    during = prom_value(threads.format(w=f"{int(hold)}s"), at=fault_to)
    passed = stale_after is not None and stale_after <= 3 * ttl + 1 and fresh_after is not None and during <= before + 2
    r.record(name, passed,
             f"the {label} feed went silent for {hold} s: stale after "
             f"{f'{stale_after:.1f} s' if stale_after is not None else 'never'} (ttl {ttl} s, limit 3x ttl + 1 s), "
             f"fresh again {f'{fresh_after:.1f} s' if fresh_after is not None else f'not within {fresh_within} s'} "
             f"after it came back, live-hub threads {before:.0f} before, at most {during:.0f} during",
             {"stale_after_s": stale_after, "fresh_after_s": fresh_after, "threads_before": before,
              "threads_during": during, "ttl_s": ttl})


def scenario_c6(r):
    feed_goes_silent(r, "C6", "Bluebikes", "bluebikes", "bikes", ttl=1, fresh_within=30)  # the stand-in's ttl


def scenario_c7(r):
    # Vehicles: ttl = the 15 s poll. A poll caught by the fault waits out its 20 s read timeout before the next one.
    feed_goes_silent(r, "C7", "vehicles", "mbta", "vehicles", ttl=15, fresh_within=60)


SCENARIOS = {"L1": scenario_l1, "L2": scenario_l2, "L3": scenario_l3, "L4": scenario_l4, "L5": scenario_l5,
             "L6": scenario_l6, "L7": scenario_l7, "L8": scenario_l8, "L9": scenario_l9, "L10": scenario_l10,
             "L11": scenario_l11, "L12": scenario_l12, "C3": scenario_c3, "C4": scenario_c4, "C5": scenario_c5,
             "C6": scenario_c6, "C7": scenario_c7}
OPT_IN = {"L13c": scenario_l13c, "L13": scenario_l13}  # run only when named: the soak alone takes up to 2 hours


def run_scenarios(args):
    preflight()
    names = list(SCENARIOS) if args.scenarios == ["all"] else args.scenarios
    known = {**SCENARIOS, **OPT_IN}
    unknown = [n for n in names if n not in known]
    if unknown:
        sys.exit(f"Unknown scenarios {unknown}; there are {', '.join(known)}")
    try:
        get_json(f"{API}/actuator/health", timeout=3)
    except OSError:
        sys.exit("The stack is not up. Start it: tools/dev.sh load up")
    r = Run(args.quick)
    header = (f"Run {r.dir.name} on {r.machine['where']}: {r.machine['docker_cpus']} CPUs and "
              f"{r.machine['docker_memory_gb']} GB for Docker; services on {r.machine['service_cpus']}, load on "
              f"{r.machine['load_cpus']}; {r.origins} origins" + (" (quick: setup check only)" if args.quick else ""))
    say(header)
    for i, name in enumerate(names):
        if i:
            time.sleep(15)  # let queues, caches' loads and metrics settle between scenarios
        known[name](r)
    failed = [n for n, v in r.results.items() if not v["passed"]]
    verdict = (f"LOAD {'FAILED' if failed else 'PASSED'}: {len(names) - len(failed)} of {len(names)} scenarios"
               + (f" (failed: {', '.join(failed)})" if failed else ""))
    say(f"{verdict}; results in {r.dir.relative_to(ROOT)}")
    if os.environ.get("GITHUB_STEP_SUMMARY"):  # the run's page on GitHub
        lines = [f"{n} {'PASSED' if v['passed'] else 'FAILED'}: {v['line']}" for n, v in r.results.items()]
        tables = [f"\n#### {path.stem}\n\n" + path.read_text(encoding="utf-8")
                  for path in (r.dir / f"{n}.md" for n in known) if path.exists()]
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as page:
            page.write("\n".join(["### Load and crash tests", "```", header, *lines, verdict, "```", ""])
                       + "".join(tables))
    sys.exit(1 if failed else 0)


def down(args):
    preflight()
    compose("down", *(["--volumes"] if args.volumes else []))
    say("Stack stopped" + (", database and metrics dropped" if args.volumes else ""))


def status(_args):
    preflight()
    say(compose("ps", "--all").rstrip() or "No containers.")
    if compose("ps", "-q", "postgis").strip():
        say(f"Database seed: {seed_id_in_database() or 'none'}")
    if SEED_FILE.exists():
        info = json.loads(SEED_FILE.read_text(encoding="utf-8"))
        say(f"Saved dump: {info['id']}, made {info['made']} from {info['mode']}, {info['places']} places")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("seed", help="fill the database with the importer and save a dump")
    p.add_argument("--fetch", action="store_true", help="download each source instead of reading data/raw")
    p.add_argument("--no-build", action="store_true", help="use the importer jar already built")
    p.set_defaults(func=seed)
    p = commands.add_parser("up", help="start the stack")
    p.add_argument("--no-build", action="store_true", help="use the jars already built")
    p.set_defaults(func=up)
    p = commands.add_parser("down", help="stop the stack")
    p.add_argument("--volumes", action="store_true", help="also drop the database and metrics")
    p.set_defaults(func=down)
    commands.add_parser("status", help="show the containers and the database's seed").set_defaults(func=status)
    p = commands.add_parser("run", help="run scenarios against the stack")
    p.add_argument("scenarios", nargs="+", help="L1 to L10, C3 to C6, or all")
    p.add_argument("--quick", action="store_true", help="shorter scenarios, to check the setup")
    p.set_defaults(func=run_scenarios)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
