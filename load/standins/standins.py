"""Stand-ins for the live feeds, which the load-test stack must not call (standard library only). Photon's is a
WireMock stub (photon.json).

  GET /gbfs/en/station_status.json   Bluebikes GBFS station_status for the stations in the seeded database, with
                                     ttl 1. Each second, a few stations' counts change, so live-hub always has
                                     changes to fan out; the same second always gives the same counts.
  GET /realtime/VehiclePositions_enhanced.json
                                     MBTA's vehicle positions in its open JSON form, for --vehicles vehicles. Each
                                     reports every --vehicle-period seconds, at its own fixed phase, as MBTA's do: a
                                     report moves it a little and stamps its time, so a 15 s poll finds about
                                     15 / period of them changed. The same second always gives the same feed.
  GET /realtime/Alerts_enhanced.json, /realtime/TripUpdates_enhanced.json
                                     empty feeds
  GET /MBTA_GTFS.zip                 a minimal static GTFS zip: the routes the vehicles run on
  GET|POST /control/vehicles[?multiple=k]
                                     the vehicle count's multiple (k times as many vehicles, each reporting as
                                     often), for scenarios that step the rate up; GET shows it
  GET /health                        200 when up.

The station ids come from a text file the seed writes (one id per line), re-read whenever it changes, so the server
can start before the seed exists.
"""

import argparse
import gzip
import io
import json
import random
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

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


# Where the vehicles are: most inside the four cities' box, the rest (commuter rail, outer buses) around it.
INNER = (42.23, 42.41, -71.19, -70.99)
OUTER = (41.80, 42.75, -71.70, -70.60)
# A rough mix of MBTA's fleet (most are buses), with the routes the GTFS zip names.
KINDS = [("bus", 0.66, ["1", "39", "66", "111", "SL1"]),
         ("rapid", 0.28, ["Red", "Orange", "Blue", "Green-B", "Green-E"]),
         ("rail", 0.06, ["CR-Franklin", "CR-Worcester", "CR-Providence"])]
STATUSES = ["IN_TRANSIT_TO", "STOPPED_AT", "INCOMING_AT"]


class Vehicles:
    def __init__(self, count, period, inner_share):
        self.count = count
        self.period = period
        self.inner_share = inner_share
        self.multiple = 1
        self.lock = threading.Lock()

    def set_multiple(self, k):
        with self.lock:
            self.multiple = k

    def settings(self):
        with self.lock:
            return {"vehicles": self.count * self.multiple, "per_vehicle_period_s": self.period,
                    "multiple": self.multiple}

    def vehicle(self, i, now):
        """Vehicle i as of second `now`: its fixed traits, then its state at its last report."""
        traits = random.Random(f"vehicle:{i}")
        r = traits.random()
        for kind, share, routes in KINDS:
            if r < share:
                break
            r -= share
        box = INNER if traits.random() < self.inner_share else OUTER
        home = (traits.uniform(box[0], box[1]), traits.uniform(box[2], box[3]))
        vehicle_id = {"bus": f"y{1000 + i}", "rapid": f"R-{545000 + i}", "rail": f"{1500 + i}"}[kind]
        route = traits.choice(routes)
        phase = traits.randrange(self.period)
        reported = now - (now + phase) % self.period
        at = random.Random(f"{vehicle_id}:{reported}")
        return {"id": vehicle_id, "vehicle": {
            "current_status": at.choice(STATUSES), "current_stop_sequence": at.randint(1, 40),
            "occupancy_status": "MANY_SEATS_AVAILABLE",
            "position": {"bearing": at.randrange(360), "latitude": round(home[0] + at.uniform(-0.01, 0.01), 5),
                         "longitude": round(home[1] + at.uniform(-0.01, 0.01), 5),
                         "speed": round(at.uniform(0, 15), 1)},
            "stop_id": str(at.randint(70000, 72000)), "timestamp": reported,
            "trip": {"direction_id": i % 2, "route_id": route, "schedule_relationship": "SCHEDULED",
                     "start_date": time.strftime("%Y%m%d", time.gmtime(now)), "trip_id": f"7774{i:04d}",
                     "start_time": "08:00:00", "revenue": True, "last_trip": False},
            "vehicle": {"id": vehicle_id, "label": str(3000 + i)}}}

    def feed(self, now):
        second = int(now)
        with self.lock:
            n = self.count * self.multiple
        return {"header": header(second), "entity": [self.vehicle(i, second) for i in range(n)]}


def header(second):
    return {"timestamp": second, "gtfs_realtime_version": "2.0", "incrementality": "FULL_DATASET"}


def gtfs_zip():
    routes = ["route_id,route_short_name,route_long_name,route_type"]
    for kind, _, ids in KINDS:
        for route in ids:
            routes.append({"bus": f"{route},{route},,3", "rapid": f"{route},,{route} Line,1",
                           "rail": f"{route},,{route[3:]} Line,2"}[kind])
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("routes.txt", "\n".join(routes) + "\n")
    return out.getvalue()


def handler(stations, vehicles):
    gtfs = gtfs_zip()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            url = urlsplit(self.path)
            if url.path == "/gbfs/en/station_status.json":
                self.reply(200, json.dumps(stations.status(time.time()), separators=(",", ":")).encode())
            elif url.path == "/realtime/VehiclePositions_enhanced.json":
                self.reply(200, json.dumps(vehicles.feed(time.time()), separators=(",", ":")).encode(), gzipped=True)
            elif url.path in ("/realtime/Alerts_enhanced.json", "/realtime/TripUpdates_enhanced.json"):
                self.reply(200, json.dumps({"header": header(int(time.time())), "entity": []}).encode(), gzipped=True)
            elif url.path == "/MBTA_GTFS.zip":
                self.reply(200, gtfs, "application/zip")
            elif url.path == "/control/vehicles":
                self.control(url)
            elif url.path == "/health":
                self.reply(200, b'{"status":"UP"}')
            else:
                self.reply(404, b'{"error":"not found"}')

        def do_POST(self):
            url = urlsplit(self.path)
            if url.path == "/control/vehicles":
                self.control(url)
            else:
                self.reply(404, b'{"error":"not found"}')

        def control(self, url):
            multiple = parse_qs(url.query).get("multiple")
            if multiple:
                vehicles.set_multiple(max(1, int(multiple[0])))
            self.reply(200, json.dumps(vehicles.settings()).encode())

        def reply(self, code, body, content_type="application/json", gzipped=False):
            encoding = None
            if gzipped and "gzip" in self.headers.get("Accept-Encoding", ""):
                body = gzip.compress(body, compresslevel=6)  # as MBTA's CDN does
                encoding = "gzip"
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            if encoding:
                self.send_header("Content-Encoding", encoding)
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
    parser.add_argument("--vehicles", type=int, default=1000)
    parser.add_argument("--vehicle-period", type=int, default=20, help="seconds between one vehicle's reports")
    parser.add_argument("--vehicle-inner-share", type=float, default=0.88,
                        help="share of vehicles inside the four cities")
    args = parser.parse_args()
    vehicles = Vehicles(args.vehicles, args.vehicle_period, args.vehicle_inner_share)
    server = ThreadingHTTPServer(("0.0.0.0", args.port),
                                 handler(Stations(args.stations, args.changes_per_second), vehicles))
    server.daemon_threads = True
    print(f"Feed stand-ins on :{args.port}, stations from {args.stations}, {args.vehicles} vehicles reporting every "
          f"{args.vehicle_period} s", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
