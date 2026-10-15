"""The nightly check: everything that can be proven without the cloud, in one run.

  tools/dev.sh nightly [--only STEP,...] [--skip STEP,...] [--no-history] [--fetch-snapshots]

Steps, in this order; each runs even after an earlier one failed:
  promtool   promtool checks and tests of every alert rule
  frontend   the frontend typecheck and tests
  verify     the full ./mvnw verify; Docker must be running, and no container test may be skipped
  release    the release check on the newest local release (passes when there is none)
  contracts  the source contract tests, against the publishers' real data
  jcstress   the full JCStress suite (default mode), timed
  c1         crash test C1 (kill -9) on a throwaway local database
  c2         crash test C2 (connection cuts through Toxiproxy)

Logs go to target/nightly/<start time>/<step>.log. Unless --no-history (CI), a run adds a row to the local run
history (HISTORY). The last line is NIGHTLY PASSED or NIGHTLY FAILED. Phase 7 closes on three green runs in a row of
nightly.yml on GitHub; this command checks changes before a push.
"""

import argparse
import datetime
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HISTORY = ROOT / "docs/benchmarks/nightly-runs.md"
JAR = ROOT / "services/ingest/target/ingest.jar"
JAVA = ["java", "--sun-misc-unsafe-memory-access=allow"]
ANSI = re.compile(r"\x1b\[[0-9;]*m")
# The release check reads only files: no database and no telemetry.
OFFLINE = ["--spring.datasource.url=jdbc:postgresql://localhost/unused", "--spring.datasource.username=unused",
           "--spring.datasource.password=unused", "--management.otlp.metrics.export.enabled=false",
           "--management.tracing.export.otlp.enabled=false", "--management.logging.export.otlp.enabled=false"]


class Log:
    def __init__(self, path):
        self.path = path
        self.file = open(path, "w", encoding="utf-8")

    def run(self, cmd, cwd=ROOT):
        """Runs cmd with its output in the log; returns the exit code."""
        self.file.write(f"$ {' '.join(map(str, cmd))}\n")
        self.file.flush()
        return subprocess.run(cmd, cwd=cwd, stdout=self.file, stderr=subprocess.STDOUT).returncode

    def text(self):
        """The output so far, without terminal colour codes."""
        self.file.flush()
        return ANSI.sub("", self.path.read_text(encoding="utf-8", errors="replace"))


def docker_running():
    return shutil.which("docker") and subprocess.run(["docker", "info"], capture_output=True).returncode == 0


def test_reports(since):
    """(tests, skipped, failed) over the JUnit reports written since `since`."""
    tests = skipped = failed = 0
    reports = [r for pattern in ("*/target/surefire-reports/TEST-*.xml", "*/*/target/surefire-reports/TEST-*.xml")
               for r in ROOT.glob(pattern)]
    for report in reports:
        if report.stat().st_mtime < since:
            continue
        head = report.read_text(encoding="utf-8", errors="replace")[:2000]
        counts = {k: int(v) for k, v in re.findall(r'\b(tests|skipped|failures|errors)="(\d+)"', head)[:4]}
        tests += counts.get("tests", 0)
        skipped += counts.get("skipped", 0)
        failed += counts.get("failures", 0) + counts.get("errors", 0)
    return tests, skipped, failed


def promtool(log, _):
    if not shutil.which("promtool"):
        return False, "promtool is not installed (brew install prometheus)"
    alerts = ROOT / "infra/observability/alerts"
    rules = sorted(alerts.glob("*.rules.yaml"))
    tests = sorted(alerts.glob("*.test.yaml"))
    ok = log.run(["promtool", "check", "rules", *rules]) == 0
    ok = log.run(["promtool", "test", "rules", *tests]) == 0 and ok
    return ok, f"{len(rules)} rule files, {len(tests)} test files"


def frontend(log, _):
    web = ROOT / "frontend"
    if not (web / "node_modules").is_dir() and log.run(["npm", "ci"], cwd=web) != 0:
        return False, "npm ci failed"
    ok = log.run(["npm", "run", "typecheck"], cwd=web) == 0
    ok = log.run(["npm", "test"], cwd=web) == 0 and ok
    passed = re.findall(r"Tests\s+(\d+) passed", log.text())
    return ok, f"typecheck, {passed[-1]} tests" if passed else "typecheck and tests"


def verify(log, _):
    if not docker_running():
        return False, "Docker is not running, so the container tests would be skipped"
    started = time.time()
    # Turns off Testcontainers' "disabledWithoutDocker" condition, so a container test fails rather than skips.
    ok = log.run(["./mvnw", "-B", "-ntp", "verify",
                  "-Djunit.jupiter.conditions.deactivate=org.testcontainers.junit.jupiter.TestcontainersExtension"]) == 0
    tests, skipped, failed = test_reports(started)
    return ok and failed == 0, f"{tests} tests, {skipped} skipped (opt-in live tests)"


def release(log, _):
    if not JAR.exists() and log.run(["./mvnw", "-q", "-B", "-ntp", "-pl", "services/ingest", "-am", "package",
                                     "-DskipTests"]) != 0:
        return False, "could not build the importer"
    ok = log.run(JAVA + ["-jar", str(JAR), "--placemap.ingest.tool.name=release-check", *OFFLINE]) == 0
    verdict = re.findall(r"RELEASE CHECK (PASSED|FAILED|SKIPPED): (.*)", log.text())
    if not verdict:
        return False, "no verdict"
    word, folder = verdict[-1]
    return ok and word != "FAILED", f"{word.lower()}: {Path(folder).name}" if word != "SKIPPED" else "no release yet"


def contracts(log, _):
    started = time.time()
    ok = log.run(["./mvnw", "-B", "-ntp", "-pl", "services/ingest", "-am", "test", "-Dtest=SourceContractTest",
                  "-Dsurefire.failIfNoSpecifiedTests=false", "-Dplacemap.contract=true"]) == 0
    tests, skipped, failed = test_reports(started)
    return ok and failed == 0 and tests > 0, f"{tests - skipped} sources checked, {skipped} skipped"


def jcstress(log, _):
    ok = log.run(["./mvnw", "-B", "-ntp", "-pl", "libs/concurrent-jcstress", "-am", "verify", "-Pjcstress",
                  "-Djcstress.mode=default", "-DskipTests"]) == 0
    # JCStress reports failures in its output, not always in its exit code.
    results = re.findall(r"Results: (\d+) planned; (\d+) passed, (\d+) failed, (\d+) soft errs, (\d+) hard errs",
                         log.text())
    if not results:
        return False, "no results line"
    planned, passed, failed, soft, hard = map(int, results[-1])
    return ok and failed == soft == hard == 0, f"{passed} of {planned} passed, {failed} failed, {soft + hard} errors"


def crash(mode):
    def step(log, args):
        cmd = ["python3", "-I", "tools/ingest-proofs.py", mode] + (["--no-build"] if JAR.exists() else [])
        ok = log.run(cmd + (["--fetch"] if args.fetch_snapshots else [])) == 0
        verdict = re.findall(rf"{mode.upper()} (PASSED|FAILED): (\d+) of (\d+) trials", log.text())
        return ok, (f"{verdict[-1][1]} of {verdict[-1][2]} trials" if verdict else "no verdict")
    return step


STEPS = [
    ("promtool", "alert rule checks and tests", promtool),
    ("frontend", "frontend typecheck and tests", frontend),
    ("verify", "./mvnw verify, container tests included", verify),
    ("release", "release check", release),
    ("contracts", "source contract tests", contracts),
    ("jcstress", "full JCStress suite", jcstress),
    ("c1", "crash test C1 (kill -9)", crash("c1")),
    ("c2", "crash test C2 (connection cuts)", crash("c2")),
]
NAMES = [name for name, _, _ in STEPS]


def took(seconds):
    seconds = int(round(seconds))
    if seconds < 60:
        return f"{seconds}s"
    if seconds < 3600:
        return f"{seconds // 60}m{seconds % 60:02d}s"
    return f"{seconds // 3600}h{seconds % 3600 // 60:02d}m"


def commit():
    head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"],
                           cwd=ROOT, capture_output=True, text=True).stdout.strip()
    return head + ("+" if dirty else "")


def record(started, revision, results, total, result):
    cells = [started.strftime("%Y-%m-%d"), started.strftime("%H:%M"), f"`{revision}`", result]
    for name in NAMES:
        if name in results:
            ok, seconds, _ = results[name]
            cells.append(f"{'✓' if ok else '✗'} {took(seconds)}")
        else:
            cells.append("–")
    cells.append(took(total))
    with open(HISTORY, "a", encoding="utf-8") as history:
        history.write("| " + " | ".join(cells) + " |\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--only", help="comma-separated steps to run: " + ",".join(NAMES))
    parser.add_argument("--skip", help="comma-separated steps to leave out")
    parser.add_argument("--no-history", action="store_true", help="do not add a row to " + HISTORY.name)
    parser.add_argument("--fetch-snapshots", action="store_true",
                        help="c1/c2: make missing input snapshots by importing them once (CI)")
    args = parser.parse_args()
    chosen = NAMES if not args.only else [s.strip() for s in args.only.split(",")]
    chosen = [s for s in chosen if s not in (args.skip or "").split(",")]
    unknown = [s for s in chosen if s not in NAMES]
    if unknown:
        parser.error(f"unknown steps {unknown}; choose from {NAMES}")

    started = datetime.datetime.now()
    revision = commit()  # as the run starts: what it tests
    logs = ROOT / "target/nightly" / started.strftime("%Y%m%d-%H%M%S")
    logs.mkdir(parents=True, exist_ok=True)
    print(f"Nightly check, {started:%Y-%m-%d %H:%M}, commit {revision}; logs in {logs.relative_to(ROOT)}/", flush=True)
    results = {}
    t0 = time.monotonic()
    try:
        for name, description, step in STEPS:
            if name not in chosen:
                continue
            print(f"▶ {name}: {description} ...", flush=True)
            log = Log(logs / f"{name}.log")
            t = time.monotonic()
            try:
                ok, note = step(log, args)
            except Exception as e:  # a broken step must not stop the others
                ok, note = False, f"{type(e).__name__}: {e}"
            results[name] = (ok, time.monotonic() - t, note)
            print(f"  {'PASS' if ok else 'FAIL'} {name} in {took(results[name][1])}: {note}"
                  + ("" if ok else f"  (log: {log.path.relative_to(ROOT)})"), flush=True)
    except KeyboardInterrupt:
        print("\nNIGHTLY INTERRUPTED: nothing recorded.")
        sys.exit(130)

    total = time.monotonic() - t0
    failed = [name for name, (ok, _, _) in results.items() if not ok]
    full = set(results) == set(NAMES)
    result = "FAILED" if failed else "PASSED" if full else "PARTIAL"
    if not args.no_history:
        record(started, revision, results, total, result)
    if failed:
        print(f"NIGHTLY FAILED in {took(total)}: {', '.join(failed)}")
    elif not full:
        print(f"NIGHTLY PASSED in {took(total)} for {', '.join(results)} only (a partial run)")
    else:
        print(f"NIGHTLY PASSED in {took(total)}: every step")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
