"""The load-test stack (plan: Phase 8): the whole app on one machine, with nothing in it calling a cloud service.

  python3 -I tools/load-tests.py seed [--fetch]       fill the stack's database with the importer, then save a dump
  python3 -I tools/load-tests.py up                   start the stack, restoring the dump if the database is older
  python3 -I tools/load-tests.py down [--volumes]     stop it (--volumes also drops its database and metrics)
  python3 -I tools/load-tests.py status               the containers and the database's seed

seed imports every source with the real importer jar into the stack's PostGIS: from the snapshots a real import saved
in data/raw (no network), or with --fetch from the sources themselves (how CI makes it once a week). Addresses are
geocoded against the Photon stand-in, so records that need it stay unplaced. The result goes to data/load/ (git-ignored):
placemap.dump, seed.json (what went in) and stations.txt (the Bluebikes ids the feed stand-in serves).

up needs the dump and Valhalla's tiles (tools/build-valhalla-tiles.sh); it builds the api and live-hub jars and images.
The services get their own CPUs, the load generators and Prometheus the rest (PLACEMAP_SUT_CPUS / PLACEMAP_LOAD_CPUS
override the split), so the generators do not take time from what they measure.
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ["docker", "compose", "-f", str(ROOT / "infra/compose/load.yaml")]
WORK = ROOT / "target/load"
SEED_DIR = ROOT / "data/load"
DUMP = SEED_DIR / "placemap.dump"
SEED_FILE = SEED_DIR / "seed.json"
STATIONS = SEED_DIR / "stations.txt"
TILES = ROOT / "data/valhalla/valhalla_tiles.tar"
MIGRATIONS = ROOT / "db/src/main/resources/db/migration"
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
JAVA = ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Xmx1g"]
NO_TELEMETRY = ["--management.otlp.metrics.export.enabled=false", "--management.tracing.export.otlp.enabled=false",
                "--management.logging.export.otlp.enabled=false"]
# The importer never sees settings that could point it at Neon or at Grafana Cloud.
CHILD_ENV = {k: v for k, v in os.environ.items() if not k.startswith(("PG", "OTEL_", "SPRING_", "PLACEMAP_"))}
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def say(message):
    print(message, flush=True)


def run(cmd, **kwargs):
    result = subprocess.run(cmd, capture_output=True, text=True, **kwargs)
    if result.returncode:
        sys.exit(f"failed ({result.returncode}): {' '.join(map(str, cmd))}\n{result.stdout[-4000:]}{result.stderr[-4000:]}")
    return result.stdout


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


def compose(*args, check=True, **kwargs):
    cmd = COMPOSE + list(args)
    if check:
        return run(cmd, env=compose_env(), cwd=ROOT, **kwargs)
    return subprocess.run(cmd, env=compose_env(), cwd=ROOT, capture_output=True, text=True, **kwargs)


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


def wait_for_database():
    deadline = time.monotonic() + 120
    while psql("SELECT 1", db="postgres", check=False) is None:
        if time.monotonic() > deadline:
            sys.exit("PostGIS did not start within 2 minutes; see: docker compose -f infra/compose/load.yaml logs postgis")
        time.sleep(1)


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
        run(["./mvnw", "-q", "-B", "-ntp", "-pl", "services/ingest", "-am", "package", "-DskipTests"], cwd=ROOT)

    say("Starting a fresh database and the Photon stand-in...")
    compose("rm", "-sf", "postgis")
    subprocess.run(["docker", "volume", "rm", "-f", "placemap-load_db"], capture_output=True)
    SEED_DIR.mkdir(parents=True, exist_ok=True)
    STATIONS.unlink(missing_ok=True)
    compose("up", "-d", "--build", "postgis", "standins")
    wait_for_database()
    migrations = sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0]))
    for file in migrations:
        # One transaction per file, as Flyway applies them.
        compose("exec", "-T", "postgis", "psql", "-U", "postgres", "-d", "placemap", "-X", "-q", "-v",
                "ON_ERROR_STOP=1", "-1", "-f", "-", input=file.read_text(encoding="utf-8"))
    say(f"Migrated ({len(migrations)} migrations, last {migrations[-1].name}); importing {len(sources)} sources:")

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
    for file in migrations:
        digest.update(file.name.encode() + b"\0" + file.read_bytes())
    for source in sources:
        digest.update(f"{source}:{inputs[source]}".encode())
    seed_id = "seed-" + digest.hexdigest()[:12]
    SEED_FILE.write_text(json.dumps({
        "id": seed_id,
        "made": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "mode": "fetch" if args.fetch else "saved snapshots",
        "migrations": [f.name for f in migrations],
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


def wait_healthy(services, limit=180):
    deadline = time.monotonic() + limit
    while True:
        out = compose("ps", "--format", "json", *services).strip()
        # One object per line in current Compose, one array in older versions.
        rows = json.loads(out) if out.startswith("[") else [json.loads(l) for l in out.splitlines() if l.strip()]
        health = {r["Service"]: (r.get("Health") or r.get("State")) for r in rows}
        if all(health.get(s) in ("healthy", "running") for s in services) and \
                all(health.get(s) == "healthy" for s in services if s not in ("toxiproxy", "prometheus")):
            return
        if time.monotonic() > deadline:
            sys.exit(f"Not healthy within {limit} s: {health}; see: docker compose -f infra/compose/load.yaml logs")
        time.sleep(2)


def up(args):
    preflight(need_java=not args.no_build)
    if not TILES.exists():
        sys.exit(f"No Valhalla tiles at {TILES.relative_to(ROOT)}. Build them: tools/dev.sh valhalla")
    if not DUMP.exists() or not SEED_FILE.exists():
        sys.exit("No seeded database yet. Make it once: tools/dev.sh load seed")
    if not args.no_build:
        say("Building the api and live-hub jars...")
        run(["./mvnw", "-q", "-B", "-ntp", "-pl", "services/api,services/live-hub", "-am", "package", "-DskipTests"],
            cwd=ROOT)
    sut, load = cpu_split()
    say(f"CPUs: services on {sut}, load generators and Prometheus on {load}")
    compose("build", "-q", "postgis", "api", "live-hub")
    compose("up", "-d", "postgis")
    wait_for_database()
    seed_info = restore_if_needed()
    # Fresh JVMs and no faults left over from an earlier run; the database keeps running.
    compose("up", "-d", "--force-recreate", "--no-deps", "standins", "toxiproxy", "prometheus", "valhalla",
            "live-hub", "api")
    wait_healthy(["postgis", "valhalla", "standins", "toxiproxy", "prometheus", "live-hub", "api"])
    nearby = get_json(f"{API}/api/places/nearby?lat=42.35500&lon=-71.06560&mode=walk")
    if nearby.get("travelTimeSource") != "valhalla" or not nearby.get("places"):
        sys.exit(f"The api answered without Valhalla's travel times: travelTimeSource={nearby.get('travelTimeSource')}, "
                 f"fallback={nearby.get('fallback')}, {len(nearby.get('places') or [])} places")
    say(f"STACK UP: {seed_info['id']} ({seed_info['places']} places); nearby from Boston Common: "
        f"{len(nearby['places'])} places by walking time from Valhalla. api {API}, live-hub {LIVE}, "
        f"Prometheus {PROMETHEUS}")


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
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
