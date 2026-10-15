"""A stand-in for the Bluebikes feed, which the load-test stack must not call (standard library only). Photon's is a
WireMock stub (photon.json).

  GET /gbfs/en/station_status.json   Bluebikes GBFS station_status for the stations in the seeded database, with
                                     ttl 1. Each second, a few stations' counts change, so live-hub always has
                                     changes to fan out; the same second always gives the same counts.
  GET /health                        200 when up.

The station ids come from a text file the seed writes (one id per line), re-read whenever it changes, so the server
can start before the seed exists.
"""

import argparse
import json
import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

class Stations:
    def __init__(self, path, changes_per_second):
        self.path = Path(path)
        self.changes = changes_per_second
        self.lock = threading.Lock()
        self.mtime = None
        self.ids = []

    def current(self):
        try:
            mtime = self.path.stat().st_mtime
        except OSError:
            return []
        with self.lock:
            if mtime != self.mtime:
                self.ids = [line.strip() for line in self.path.read_text(encoding="utf-8").splitlines() if line.strip()]
                self.mtime = mtime
            return self.ids

    def status(self, now):
        """Counts as of second `now`: a station's counts are those of the last second that changed it."""
        ids = self.current()
        second = int(now)
        changed_at = {}
        # Look back far enough that every station has been changed at least once with high probability.
        horizon = max(60, 10 * len(ids) // max(1, self.changes))
        for s in range(second - horizon, second + 1):
            for i in random.Random(s).sample(range(len(ids)), min(self.changes, len(ids))):
                changed_at[i] = s
        stations = []
        for i, station_id in enumerate(ids):
            rng = random.Random(f"{station_id}:{changed_at.get(i, 0)}")
            capacity = rng.randint(11, 27)
            bikes = rng.randint(0, capacity)
            ebikes = rng.randint(0, bikes)
            stations.append({"station_id": station_id, "num_bikes_available": bikes,
                             "num_ebikes_available": ebikes, "num_docks_available": capacity - bikes,
                             "is_installed": 1, "is_renting": 1, "is_returning": 1, "last_reported": changed_at.get(i, 0)})
        return {"last_updated": second, "ttl": 1, "version": "1.1", "data": {"stations": stations}}


def handler(stations):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == "/gbfs/en/station_status.json":
                self.reply(200, json.dumps(stations.status(time.time()), separators=(",", ":")).encode())
            elif path == "/health":
                self.reply(200, b'{"status":"UP"}')
            else:
                self.reply(404, b'{"error":"not found"}')

        def reply(self, code, body):
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            pass  # one line per request would drown the container log

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--stations", default="/seed/stations.txt")
    parser.add_argument("--changes-per-second", type=int, default=5)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), handler(Stations(args.stations, args.changes_per_second)))
    server.daemon_threads = True
    print(f"Bluebikes stand-in on :{args.port}, stations from {args.stations}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
