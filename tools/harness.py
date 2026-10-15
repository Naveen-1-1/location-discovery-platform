"""What the load tests (tools/load-tests.py) and the importer's crash tests (tools/ingest-proofs.py) share: running
commands, the importer's JVM settings, migrating a database with Flyway, and Toxiproxy's API.

Both run with python3 -I, which leaves this folder off the import path, so each adds it before importing this file.
"""

import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# As tools/dev.sh, plus the plan's 1 GB memory budget for the importer.
JAVA = ["java", "--sun-misc-unsafe-memory-access=allow", "-Xmx1g"]
NO_TRACES_OR_LOGS = ["--management.tracing.export.otlp.enabled=false", "--management.logging.export.otlp.enabled=false"]
NO_TELEMETRY = NO_TRACES_OR_LOGS + ["--management.otlp.metrics.export.enabled=false"]

# Children never see settings that could point them at Neon or at the telemetry backend, except where added back.
CHILD_ENV = {k: v for k, v in os.environ.items() if not k.startswith(("PG", "OTEL_", "SPRING_", "PLACEMAP_"))}
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))

# The pinned image the load-test stack runs too.
TOXIPROXY_IMAGE = ("ghcr.io/shopify/toxiproxy:2.12.0"
                   "@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e")


def say(message):
    print(message, flush=True)


def run(cmd, **kwargs):
    result = subprocess.run(cmd, capture_output=True, text=True, **kwargs)
    if result.returncode:
        sys.exit(f"failed ({result.returncode}): {' '.join(map(str, cmd))}\n{result.stdout[-4000:]}{result.stderr[-4000:]}")
    return result.stdout


def migrate(jdbc_url, password):
    """Applies every migration with Flyway through the db module, as tools/dev.sh migrate does for Neon."""
    run([str(ROOT / "mvnw"), "-q", "-B", "-ntp", "-pl", "db", "flyway:migrate", f"-Dflyway.url={jdbc_url}",
         "-Dflyway.user=postgres", f"-Dflyway.password={password}"], cwd=ROOT, env=CHILD_ENV)


def migrations():
    """The migration files, oldest first: what a migrated database holds."""
    folder = ROOT / "db/src/main/resources/db/migration"
    return sorted(folder.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0]))


class Toxiproxy:
    """A Toxiproxy server's HTTP API: proxies, and faults (toxics) on them."""

    def __init__(self, url):
        self.url = url

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.url + path, data=data, method=method,
                                         headers={"Content-Type": "application/json"})
        with NO_PROXY.open(request, timeout=5) as response:
            return response.read()

    def add_toxic(self, proxy, name, kind, attributes, stream="downstream"):
        """downstream: the replies to the client (for a database, its answers to the service)."""
        self.call("POST", f"/proxies/{proxy}/toxics", {"name": name, "type": kind, "stream": stream, "toxicity": 1.0,
                                                       "attributes": attributes})

    def remove_toxic(self, proxy, name):
        try:
            self.call("DELETE", f"/proxies/{proxy}/toxics/{name}")
        except OSError:
            pass  # already gone
