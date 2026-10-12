"""Crash tests and the backpressure benchmark for the importer (plan: Phase 2 exit; ADR 0020, 0033).

Runs the real importer jar against a throwaway PostGIS container on this Mac, never against Neon.

  python3 -I tools/ingest-proofs.py c1 [--trials 20]   kill -9 at random moments, restart until the run completes
  python3 -I tools/ingest-proofs.py c2 [--trials 5]    cut the database connections (Toxiproxy reset_peer) mid-import
  python3 -I tools/ingest-proofs.py backpressure       the same big import twice: as is, then with 200 ms added to
                                                       every database reply; metrics go to Grafana Cloud

Every crash trial starts from the same database (migrated, with the OSM places imported) and imports the same pinned
Overture snapshot. A trial passes when its result has the same fingerprint as a clean run of that input and no place
is duplicated. The fingerprint covers every table the import writes, with place ids replaced by stable anchors (a
place's smallest "source:key"), because ids depend on commit order.

Needs Docker Desktop running and the snapshots that a real import saved in data/raw; with --fetch, a missing snapshot
is made by importing that source once into the throwaway database (how the nightly CI job gets them). c2 and
backpressure also need Toxiproxy: the Homebrew binary (brew install toxiproxy), or a server already listening on
127.0.0.1:8474, such as CI's container. backpressure reads the OTEL_EXPORTER_OTLP_* settings from .env.
"""

import argparse
import hashlib
import json
import os
import random
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "services/ingest/target/proofs"
JAR = ROOT / "services/ingest/target/ingest.jar"
MIGRATIONS = ROOT / "db/src/main/resources/db/migration"
IMAGE = "placemap/postgis-test"
CONTAINER = "placemap-proofs-db"
PASSWORD = "proofs"  # a throwaway container that only listens on 127.0.0.1, not a secret
DB_PORT = 55432
PROXY_PORT = 55433
TOXIPROXY = "http://127.0.0.1:8474"

BASE_SOURCE = "osm_geofabrik_massachusetts"  # the places the trial source matches against
TRIAL_SOURCE = "overture_places"  # 83k rows in 84 batches: links, new places, reviews and skipped rows
BENCH_SOURCE = "boston_food_inspections"  # 905k lines: long enough to watch memory while the database is slow

# As tools/dev.sh, plus the plan's 1 GB memory budget for the importer.
JAVA = ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Xmx1g"]
NO_TRACES_OR_LOGS = ["--management.tracing.export.otlp.enabled=false", "--management.logging.export.otlp.enabled=false"]
NO_TELEMETRY = NO_TRACES_OR_LOGS + ["--management.otlp.metrics.export.enabled=false"]

# Every table an import writes, as one JSON value per row, without the columns that legitimately differ between two
# runs of the same input: generated ids, run ids and timestamps. Place ids become anchors.
FINGERPRINT_PARTS = {
    "source_record": """SELECT to_jsonb(r) - 'created_run' - 'changed_run' - 'retired_at'
                               || jsonb_build_object('retired', r.retired_at IS NOT NULL)
                        FROM place.source_record r""",
    "place": """SELECT to_jsonb(p) - 'place_id' - 'created_at' - 'updated_at' - 'retired_at'
                       || jsonb_build_object('anchor', a.anchor, 'retired', p.retired_at IS NOT NULL)
                FROM place.place p LEFT JOIN anchor a USING (place_id)""",
    "place_source_link": """SELECT to_jsonb(l) - 'place_id' - 'linked_run' || jsonb_build_object('anchor', a.anchor)
                            FROM place.place_source_link l JOIN anchor a USING (place_id)""",
    "match_review": """SELECT to_jsonb(m) - 'place_id' - 'run_id' - 'created_at' || jsonb_build_object('anchor', a.anchor)
                       FROM place.match_review m LEFT JOIN anchor a USING (place_id)""",
    "inspection": "SELECT to_jsonb(i) - 'inspection_id' - 'created_run' FROM place.inspection i",
    "inspection_violation": """SELECT to_jsonb(v) - 'inspection_id'
                                      || jsonb_build_object('inspection', to_jsonb(i) - 'inspection_id' - 'created_run')
                               FROM place.inspection_violation v JOIN place.inspection i USING (inspection_id)""",
    "violation_code": "SELECT to_jsonb(c) FROM place.violation_code c",
    "inspection_summary": "SELECT to_jsonb(s) FROM place.inspection_summary s",
    "place_facts": """SELECT to_jsonb(f) - 'place_id' - 'refreshed_at' || jsonb_build_object('anchor', a.anchor)
                      FROM place.place_facts f LEFT JOIN anchor a USING (place_id)""",
    "geocode_cache": "SELECT to_jsonb(g) - 'created_at' FROM ingest.geocode_cache g",
    "reject": "SELECT to_jsonb(j) - 'run_id' FROM ingest.reject j",
    "batch_done": "SELECT to_jsonb(b) - 'run_id' - 'keys' - 'done_at' FROM ingest.batch_done b",
}
# Not compared: the static category list, and ingest.run, whose attempts and timestamps differ by design (its
# checksum and counts are compared separately).
NOT_FINGERPRINTED = {"place.category", "ingest.run"}

# Children never see settings that could point them at Neon or at the telemetry backend, except where added back.
CHILD_ENV = {k: v for k, v in os.environ.items() if not k.startswith(("PG", "OTEL_", "SPRING_", "PLACEMAP_"))}
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def say(message):
    print(message, flush=True)


def run(cmd, **kwargs):
    result = subprocess.run(cmd, capture_output=True, text=True, **kwargs)
    if result.returncode:
        sys.exit(f"failed ({result.returncode}): {' '.join(map(str, cmd))}\n{result.stdout}{result.stderr}")
    return result.stdout


class Database:
    """The throwaway PostGIS container, reached with the Mac's psql if there is one, else psql inside it."""

    def __init__(self):
        self.env = dict(CHILD_ENV, PGPASSWORD=PASSWORD)
        psql = shutil.which("psql")
        if psql:
            self.psql = [psql, "-h", "127.0.0.1", "-p", str(DB_PORT), "-U", "postgres"]
        else:
            self.psql = ["docker", "exec", "-i", "-e", f"PGPASSWORD={PASSWORD}", CONTAINER,
                         "psql", "-h", "127.0.0.1", "-U", "postgres"]

    def start(self):
        say("Starting a throwaway PostGIS container (the image builds once, then comes from the cache)...")
        run(["docker", "build", "-q", "-t", IMAGE, str(ROOT / "infra/docker/postgis")])
        self.stop()
        run(["docker", "run", "-d", "--rm", "--name", CONTAINER, "-e", f"POSTGRES_PASSWORD={PASSWORD}",
             "-p", f"127.0.0.1:{DB_PORT}:5432", "--memory", "1536m", "--shm-size", "256m", IMAGE])
        # The image's first-start setup only listens on a socket inside the container, so a TCP connection means the
        # real server is up.
        deadline = time.monotonic() + 90
        while self.query("postgres", "SELECT 1", check=False) is None:
            if time.monotonic() > deadline:
                sys.exit(f"PostGIS did not start within 90 s; see: docker logs {CONTAINER}")
            time.sleep(0.5)

    @staticmethod
    def stop():
        subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)

    def query(self, db, sql, check=True):
        """Rows as lists of strings, or None if the query failed and check is off."""
        result = subprocess.run(self.psql + ["-d", db, "-X", "-q", "-t", "-A", "-F", "\t", "-v", "ON_ERROR_STOP=1",
                                             "-c", sql], capture_output=True, text=True, env=self.env)
        if result.returncode:
            if check:
                sys.exit(f"query failed on {db}: {result.stderr.strip()}\n{sql}")
            return None
        return [line.split("\t") for line in result.stdout.splitlines() if line]

    def migrate(self, db):
        self.query("postgres", f"DROP DATABASE IF EXISTS {db} WITH (FORCE)")
        self.query("postgres", f"CREATE DATABASE {db}")
        files = sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0]))
        for file in files:
            # One transaction per file, as Flyway applies them.
            run(self.psql + ["-d", db, "-X", "-q", "-v", "ON_ERROR_STOP=1", "-1", "-f", "-"],
                input=file.read_text(encoding="utf-8"), env=self.env)
        return [f.name for f in files]

    def clone(self, db, template="base"):
        self.query("postgres", f"DROP DATABASE IF EXISTS {db} WITH (FORCE)")
        # A template must have no sessions; a finished importer's may take a moment to go.
        self.query("postgres", f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '{template}'")
        self.query("postgres", f"CREATE DATABASE {db} TEMPLATE {template}")

    def progress(self, db, source):
        """(status, total batches, committed batches) of the source's latest run, or None before it exists."""
        rows = self.query(db, f"""
            SELECT r.status, coalesce(r.total_batches, 0),
                   (SELECT count(*) FROM ingest.batch_done b WHERE b.run_id = r.run_id)
            FROM ingest.run r WHERE r.source = '{source}' ORDER BY r.run_id DESC LIMIT 1""", check=False)
        if not rows:
            return None
        status, total, done = rows[0]
        return status, int(total), int(done)

    def fingerprint(self, db, source):
        """{part: "rows:hash"}. Each hash is a sum of per-row hashes, so row order and commit order do not matter."""
        parts = [f"""SELECT '{name}', count(*),
                            coalesce(sum(('x' || substr(md5(j::text), 1, 15))::bit(60)::bigint::numeric), 0)::text
                     FROM ({sql}) AS t(j)""" for name, sql in FINGERPRINT_PARTS.items()]
        parts.append("""SELECT 'orphan_places', count(*), '0' FROM place.place p
                        WHERE NOT EXISTS (SELECT 1 FROM place.place_source_link l WHERE l.place_id = p.place_id)""")
        parts.append(f"""SELECT 'completed_runs', count(*), coalesce(max(result_checksum || '/' || total_rows || '/'
                                                                     || total_batches || '/' || rejected_rows), '')
                         FROM ingest.run WHERE source = '{source}' AND status = 'COMPLETE'""")
        rows = self.query(db, f"""
            WITH anchor AS (SELECT place_id, min((source || ':' || source_key) COLLATE "C") AS anchor
                            FROM place.place_source_link GROUP BY place_id)
            {" UNION ALL ".join(parts)}""")
        return {name: f"{n}:{digest}" for name, n, digest in rows}

    def unfingerprinted_tables(self, db):
        rows = self.query(db, "SELECT schemaname || '.' || tablename FROM pg_tables WHERE schemaname IN "
                              "('place', 'ingest')")
        covered = {f"{'ingest' if n in ('reject', 'batch_done', 'geocode_cache') else 'place'}.{n}"
                   for n in FINGERPRINT_PARTS}
        return sorted({r[0] for r in rows} - covered - NOT_FINGERPRINTED)

    def attempts(self, db, source):
        rows = self.query(db, f"SELECT max(attempts) FROM ingest.run WHERE source = '{source}' AND status = 'COMPLETE'")
        return int(rows[0][0]) if rows and rows[0][0] else None


class Ingest:
    """One importer process in once mode, reading a pinned snapshot, with its output in a log file."""

    live = set()

    def __init__(self, source, db, port, work_dir, name, extra=(), env=None, raw_dir=None):
        """Reads the pinned snapshot; with raw_dir, downloads the source into it instead."""
        work_dir.mkdir(parents=True, exist_ok=True)
        self.log_path = work_dir / f"{name}.log"
        input_args = ([f"--placemap.ingest.raw-dir={raw_dir}"] if raw_dir else
                      [f"--placemap.ingest.pinned-snapshot={pinned(source)}", f"--placemap.ingest.raw-dir={work_dir / 'raw'}"])
        cmd = JAVA + ["-jar", str(JAR), f"--placemap.ingest.once={source}", *input_args,
                      f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{port}/{db}"
                      "?ApplicationName=ingest&reWriteBatchedInserts=true",
                      "--spring.datasource.username=postgres", f"--spring.datasource.password={PASSWORD}", *extra]
        with open(self.log_path, "w", encoding="utf-8") as log:
            self.proc = subprocess.Popen(cmd, cwd=ROOT, env=env or CHILD_ENV, stdout=log, stderr=subprocess.STDOUT)
        self.started = time.monotonic()
        Ingest.live.add(self)

    def alive(self):
        if self.proc.poll() is None:
            return True
        Ingest.live.discard(self)
        return False

    def kill(self):
        """kill -9: no shutdown hook runs, nothing is flushed or rolled back by the process itself."""
        self.proc.send_signal(signal.SIGKILL)
        self.proc.wait()
        Ingest.live.discard(self)

    def wait(self):
        code = self.proc.wait()
        Ingest.live.discard(self)
        return code

    def count(self, text):
        return self.log_path.read_text(encoding="utf-8", errors="replace").count(text)

    def elapsed(self):
        return time.monotonic() - self.started


class Toxiproxy:
    """Toxiproxy in front of the container (ADR 0033): a TCP proxy that can add faults. A server already listening
    (CI's container) is used as it is; otherwise the Homebrew binary is started and stopped with the run."""

    def __init__(self):
        self.proc = None
        try:
            self.call("GET", "/version")
            say("Using the Toxiproxy server already running on " + TOXIPROXY)
        except OSError:
            self.proc = self.spawn()
        try:
            self.call("DELETE", "/proxies/postgres")  # left over from an interrupted run
        except OSError:
            pass
        self.call("POST", "/proxies", {"name": "postgres", "listen": f"127.0.0.1:{PROXY_PORT}",
                                       "upstream": f"127.0.0.1:{DB_PORT}", "enabled": True})

    def spawn(self):
        exe = shutil.which("toxiproxy-server")
        if not exe:
            sys.exit("Toxiproxy is not installed. Run: brew install toxiproxy")
        WORK.mkdir(parents=True, exist_ok=True)
        with open(WORK / "toxiproxy.log", "w", encoding="utf-8") as log:
            proc = subprocess.Popen([exe, "-host", "127.0.0.1", "-port", TOXIPROXY.rsplit(":", 1)[1]],
                                    stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 10
        while True:
            try:
                self.call("GET", "/version")
                return proc
            except OSError:
                if time.monotonic() > deadline or proc.poll() is not None:
                    sys.exit(f"Toxiproxy did not start; see {WORK / 'toxiproxy.log'}")
                time.sleep(0.2)

    @staticmethod
    def call(method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(TOXIPROXY + path, data=data, method=method,
                                         headers={"Content-Type": "application/json"})
        with NO_PROXY.open(request, timeout=5) as response:
            return response.read()

    def add(self, name, kind, attributes):
        # downstream = the database's replies to the importer
        self.call("POST", "/proxies/postgres/toxics", {"name": name, "type": kind, "stream": "downstream",
                                                       "toxicity": 1.0, "attributes": attributes})

    def remove(self, name):
        self.call("DELETE", f"/proxies/postgres/toxics/{name}")

    def stop(self):
        if self.proc is None:
            try:
                self.call("DELETE", "/proxies/postgres")
            except OSError:
                pass
            return
        self.proc.terminate()
        self.proc.wait()


DB = Database()


def pinned(source):
    return WORK / "pinned" / f"{source}.csv"


def pin(source):
    """Copies the latest snapshot a real import saved, so every run of this session reads the same bytes."""
    runs = sorted((p for p in (ROOT / "data/raw" / source).glob("*/snapshot.csv")), key=lambda p: int(p.parent.name))
    if not runs:
        sys.exit(f"No saved snapshot for {source} in data/raw. Import it once first: tools/dev.sh ingest-once {source}")
    target = pinned(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(runs[-1], target)
    with open(target, "rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    say(f"  {source}: {target.stat().st_size / 1e6:.1f} MB, sha256 {digest[:12]} (from {runs[-1].relative_to(ROOT)})")


def saved(source):
    return any((ROOT / "data/raw" / source).glob("*/snapshot.csv"))


def fetch(mode, sources):
    """Imports each source without a saved snapshot once into a scratch database, which saves one in data/raw."""
    for source in sources:
        if saved(source):
            continue
        say(f"No saved snapshot for {source}: importing it once into a scratch database to make one...")
        DB.migrate("fetch")
        ingest = Ingest(source, "fetch", DB_PORT, WORK / mode / "fetch", source, extra=NO_TELEMETRY,
                        raw_dir=ROOT / "data/raw")
        if ingest.wait() != 0 or not saved(source):
            sys.exit(f"Fetching {source} failed; see {ingest.log_path}")
        say(f"  done in {ingest.elapsed():.0f} s")


def prepare(mode, sources, build, fetch_missing=False):
    if subprocess.run(["docker", "info"], capture_output=True).returncode:
        sys.exit("Docker is not running. Start Docker Desktop and try again.")
    if not shutil.which("java"):
        sys.exit("java is not on the PATH")
    shutil.rmtree(WORK / mode, ignore_errors=True)
    if build:
        say("Building the importer jar...")
        run(["./mvnw", "-q", "-B", "-ntp", "-pl", "services/ingest", "-am", "package", "-DskipTests"], cwd=ROOT)
    DB.start()
    if fetch_missing:
        fetch(mode, sources)
    say("Pinning the input snapshots:")
    for source in sources:
        pin(source)
    migrations = DB.migrate("base")
    say(f"Base database: {len(migrations)} migrations ({migrations[-1]}); importing {BASE_SOURCE} for its places...")
    base = Ingest(BASE_SOURCE, "base", DB_PORT, WORK / mode / "base", "base", extra=NO_TELEMETRY)
    if base.wait() != 0:
        sys.exit(f"The base import failed; see {base.log_path}")
    say(f"  done in {base.elapsed():.0f} s")


def clean_run(mode, port=DB_PORT, extra=()):
    """The golden result: one uninterrupted run of the trial source on a fresh copy of the base database."""
    DB.clone("golden")
    ingest = Ingest(TRIAL_SOURCE, "golden", port, WORK / mode / "golden", "golden", extra=NO_TELEMETRY + list(extra))
    if ingest.wait() != 0:
        sys.exit(f"The clean run failed; see {ingest.log_path}")
    _, total, _ = DB.progress("golden", TRIAL_SOURCE)
    golden = DB.fingerprint("golden", TRIAL_SOURCE)
    if golden["orphan_places"] != "0:0":
        sys.exit(f"The clean run itself left places without records: {golden['orphan_places']}")
    missing = DB.unfingerprinted_tables("golden")
    if missing:
        sys.exit(f"Tables the fingerprint does not cover yet (add them to FINGERPRINT_PARTS): {', '.join(missing)}")
    say(f"Clean run of {TRIAL_SOURCE}: {total} batches in {ingest.elapsed():.0f} s, "
        f"fingerprint {short(golden)}")
    return golden, total, ingest.elapsed()


def short(fingerprint):
    return hashlib.sha256(json.dumps(fingerprint, sort_keys=True).encode()).hexdigest()[:12]


def compare(golden, db):
    result = DB.fingerprint(db, TRIAL_SOURCE)
    differs = sorted(k for k in golden if result.get(k) != golden[k])
    return result, differs


def kill_phase(progress, total):
    if progress is None or progress[0] == "FETCHING":
        return "before the import (start-up, snapshot, index)"
    status, _, done = progress
    if status == "COMPLETE":
        return "after completion"
    if done >= total:
        return "during completion"
    return f"mid-import ({done} of {total} batches committed)"


def c1_trial(number, trials, golden, total, rng):
    DB.clone("trial")
    work = WORK / "c1" / f"trial-{number:02d}"
    kills_left = rng.randint(1, 3)
    kills, starts, errors = [], 0, 0
    began = time.monotonic()
    while True:
        starts += 1
        if starts > 25:
            return {"trial": number, "pass": False, "why": "not complete after 25 starts", "kills": kills}
        before = DB.progress("trial", TRIAL_SOURCE)
        committed = before[2] if before and before[0] == "RUNNING" else 0
        deadline = target = None
        if kills_left:
            # Mostly kill mid-import, when a chosen number of batches is committed (plus a random delay, so the
            # kill lands at any point of the commits in flight); sometimes kill during start-up instead.
            if rng.random() < 0.2 or total - committed < 2:
                deadline = rng.uniform(0.5, 4.0)
            else:
                target = rng.randint(committed + 1, total - 1)
        ingest = Ingest(TRIAL_SOURCE, "trial", DB_PORT, work, f"start-{starts}", extra=NO_TELEMETRY)
        killed = False
        while ingest.alive():
            if deadline is not None and ingest.elapsed() >= deadline:
                killed = True
            elif target is not None:
                progress = DB.progress("trial", TRIAL_SOURCE)
                if progress and progress[0] == "RUNNING" and progress[2] >= target:
                    time.sleep(rng.uniform(0, 0.25))
                    killed = True
            if killed:
                at = ingest.elapsed()
                ingest.kill()
                kills_left -= 1
                progress = DB.progress("trial", TRIAL_SOURCE)
                phase = kill_phase(progress, total)
                # The database shows the run's state; the log shows how far this process got with it.
                if not (ingest.count(" started (attempt") or ingest.count(" resumed (attempt")):
                    phase = "before the import (start-up, snapshot, index)"
                elif phase.startswith("mid-import") and not ingest.count("matching against"):
                    phase = "before the import (start-up, snapshot, index)"
                kills.append({"after_s": round(at, 2), "phase": phase})
                break
            time.sleep(0.05)
        if killed:
            if kills[-1]["phase"] == "after completion":
                break
            continue
        code = ingest.wait()
        if code == 0:
            break
        if ingest.count("another process is running this source"):
            time.sleep(1)  # Postgres has not noticed the killed process's connection yet; its lock goes with it
            continue
        errors += 1
        if errors >= 3:
            return {"trial": number, "pass": False, "why": f"exit {code} without a kill; see {ingest.log_path}",
                    "kills": kills}
    result, differs = compare(golden, "trial")
    outcome = {"trial": number, "pass": not differs, "kills": kills, "starts": starts,
               "attempts": DB.attempts("trial", TRIAL_SOURCE), "seconds": round(time.monotonic() - began, 1)}
    if differs:
        outcome["differs"] = {k: {"golden": golden[k], "trial": result.get(k)} for k in differs}
    where = ", ".join(k["phase"].replace(" batches committed", "") for k in kills)
    say(f"trial {number:2d}/{trials}  {'PASS' if not differs else 'FAIL'}  {starts} starts; killed {where}  "
        f"({outcome['seconds']} s)" + (f"  differs: {', '.join(differs)}" if differs else ""))
    return outcome


def c1(args):
    rng = random.Random(args.seed)
    prepare("c1", [BASE_SOURCE, TRIAL_SOURCE], not args.no_build, args.fetch)
    golden, total, _ = clean_run("c1")
    say(f"C1: {args.trials} trials, each killed (kill -9) 1 to 3 times, seed {args.seed}")
    results = [c1_trial(n, args.trials, golden, total, rng) for n in range(1, args.trials + 1)]
    kills = [k for r in results for k in r["kills"]]
    passed = sum(r["pass"] for r in results)
    mid = sum(k["phase"].startswith("mid-import") for k in kills)
    say(f"\nC1 {'PASSED' if passed == len(results) else 'FAILED'}: {passed} of {len(results)} trials match the clean "
        f"run (fingerprint {short(golden)}) with no duplicate places. {len(kills)} kills: {mid} mid-import, "
        f"{len(kills) - mid} at other moments.")
    save("c1", {"seed": args.seed, "golden": golden, "total_batches": total, "trials": results})
    return passed == len(results)


def c2_trial(number, trials, golden, toxiproxy, cuts, rng):
    DB.clone("trial")
    work = WORK / "c2" / f"trial-{number:02d}"
    cuts_left, cut_log, starts, retries, recommits = cuts, [], 0, 0, 0
    began = time.monotonic()
    while True:
        starts += 1
        if starts > 30:
            return {"trial": number, "pass": False, "why": "not complete after 30 starts", "cuts": cut_log}
        # Errors from the cuts may end a few attempts; keep resuming the same run instead of failing it. C2 checks the
        # data, not the attempt limit.
        ingest = Ingest(TRIAL_SOURCE, "trial", PROXY_PORT, work, f"start-{starts}",
                        extra=NO_TELEMETRY + ["--placemap.ingest.max-attempts=1000"])
        next_cut = rng.uniform(0.5, 2.5)
        while ingest.alive():
            if cuts_left and ingest.elapsed() >= next_cut:
                progress = DB.progress("trial", TRIAL_SOURCE)
                if progress and progress[0] == "RUNNING":
                    toxiproxy.add("cut", "reset_peer", {"timeout": 0})
                    try:
                        time.sleep(rng.uniform(0.05, 0.3))
                    finally:
                        toxiproxy.remove("cut")
                    cuts_left -= 1
                    cut_log.append(kill_phase(progress, progress[1]))
                next_cut = ingest.elapsed() + rng.uniform(0.5, 2.5)
            time.sleep(0.02)
        code = ingest.wait()
        retries += ingest.count("retrying in")
        recommits += ingest.count("was already committed")
        if code == 0:
            break
        progress = DB.progress("trial", TRIAL_SOURCE)
        if progress and progress[0] == "COMPLETE":
            break  # the completion committed, but its acknowledgement was cut
        time.sleep(0.5)
    result, differs = compare(golden, "trial")
    outcome = {"trial": number, "pass": not differs, "cuts": cut_log, "starts": starts, "batch_retries": retries,
               "lost_acknowledgements": recommits, "attempts": DB.attempts("trial", TRIAL_SOURCE),
               "seconds": round(time.monotonic() - began, 1)}
    if differs:
        outcome["differs"] = {k: {"golden": golden[k], "trial": result.get(k)} for k in differs}
    say(f"trial {number:2d}/{trials}  {'PASS' if not differs else 'FAIL'}  {len(cut_log)} cuts, {retries} batch "
        f"retries, {recommits} lost commit acknowledgements, {starts} starts  ({outcome['seconds']} s)"
        + (f"  differs: {', '.join(differs)}" if differs else ""))
    return outcome


def c2(args):
    rng = random.Random(args.seed)
    prepare("c2", [BASE_SOURCE, TRIAL_SOURCE], not args.no_build, args.fetch)
    toxiproxy = Toxiproxy()
    try:
        golden, _, _ = clean_run("c2")
        # Slower replies widen the moment between a COMMIT reaching the database and its reply reaching the importer,
        # so some cuts land exactly there: the commit happened, the importer cannot know.
        toxiproxy.add("slow", "latency", {"latency": args.latency, "jitter": 0})
        say(f"C2: {args.trials} trials through Toxiproxy (+{args.latency} ms per reply), {args.cuts} connection cuts "
            f"(reset_peer) each, seed {args.seed}")
        results = [c2_trial(n, args.trials, golden, toxiproxy, args.cuts, rng) for n in range(1, args.trials + 1)]
    finally:
        toxiproxy.stop()
    passed = sum(r["pass"] for r in results)
    lost = sum(r.get("lost_acknowledgements", 0) for r in results)
    ok = passed == len(results) and lost > 0
    say(f"\nC2 {'PASSED' if ok else 'FAILED'}: {passed} of {len(results)} trials match the clean run (fingerprint "
        f"{short(golden)}) with no duplicate places; {lost} batches were retried after a commit whose "
        f"acknowledgement was lost.")
    if lost == 0:
        say("No cut landed between a commit and its acknowledgement, so the key case was not exercised. Run again "
            "with more --trials or --cuts.")
    save("c2", {"seed": args.seed, "latency_ms": args.latency, "golden": golden, "trials": results})
    return ok


def otel_env():
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("OTEL_") and "=" in line:
            key, value = line.split("=", 1)
            value = value.split("  #")[0].strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                value = value[1:-1]
            env[key] = value
    if not env.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        sys.exit("backpressure exports metrics to Grafana Cloud: set OTEL_EXPORTER_OTLP_* in .env")
    return env


def backpressure(args):
    env = dict(CHILD_ENV, PLACEMAP_ENV="benchmark", **otel_env())
    prepare("backpressure", [BASE_SOURCE, BENCH_SOURCE], not args.no_build)
    toxiproxy = Toxiproxy()
    runs = {}
    try:
        for label, latency in (("baseline", 0), ("slow-db", args.latency)):
            DB.clone("bench")
            if latency:
                toxiproxy.add("slow", "latency", {"latency": latency, "jitter": 0})
            say(f"{label}: importing {BENCH_SOURCE} through Toxiproxy (+{latency} ms per database reply)...")
            started = time.time()
            ingest = Ingest(BENCH_SOURCE, "bench", PROXY_PORT, WORK / "backpressure" / label, label, env=env,
                            extra=NO_TRACES_OR_LOGS + [f"--management.otlp.metrics.export.step={args.step}s"])
            code = ingest.wait()
            if latency:
                toxiproxy.remove("slow")
            _, total, done = DB.progress("bench", BENCH_SOURCE)
            runs[label] = {"latency_ms": latency, "start": int(started), "end": int(time.time()), "exit": code,
                           "seconds": round(ingest.elapsed(), 1), "batches": total, "committed": done}
            say(f"  exit {code}: {done} of {total} batches in {ingest.elapsed():.0f} s")
            time.sleep(2 * args.step)  # let the last metrics export, and leave a gap between the runs on the graphs
    finally:
        toxiproxy.stop()
    save("backpressure", {"source": BENCH_SOURCE, "step_s": args.step, "runs": runs})
    return all(r["exit"] == 0 for r in runs.values())


def save(mode, data):
    path = WORK / f"{mode}-results.json"
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    say(f"Details: {path.relative_to(ROOT)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    for name, trials in (("c1", 20), ("c2", 5)):
        p = sub.add_parser(name)
        p.add_argument("--trials", type=int, default=trials)
        p.add_argument("--seed", type=int, default=random.randrange(1_000_000))
        p.add_argument("--no-build", action="store_true", help="use the jar already in services/ingest/target")
        p.add_argument("--keep", action="store_true", help="leave the database container running afterwards")
        p.add_argument("--fetch", action="store_true", help="make missing input snapshots by importing them once")
    sub.choices["c2"].add_argument("--cuts", type=int, default=15, help="connection cuts per trial")
    sub.choices["c2"].add_argument("--latency", type=int, default=30, help="ms added to every database reply")
    p = sub.add_parser("backpressure")
    p.add_argument("--latency", type=int, default=200, help="ms added to every database reply in the slow run")
    p.add_argument("--step", type=int, default=10, help="metrics export interval in seconds")
    p.add_argument("--no-build", action="store_true")
    p.add_argument("--keep", action="store_true")
    args = parser.parse_args()
    try:
        ok = {"c1": c1, "c2": c2, "backpressure": backpressure}[args.mode](args)
    except KeyboardInterrupt:
        ok = False
        say("\nInterrupted.")
    finally:
        for ingest in list(Ingest.live):
            ingest.kill()
        if not args.keep:
            Database.stop()
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
