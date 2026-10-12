"""Turns the backpressure benchmark's metrics into a chart and a CSV (backpressure.svg and .csv in OUT).

Usage:  python3 -I tools/backpressure-report.py [RESULTS_JSON]

Reads the run windows that `tools/dev.sh proofs backpressure` saved (services/ingest/target/proofs/
backpressure-results.json) and queries those windows from Grafana Cloud (GRAFANA_URL and GRAFANA_SA_TOKEN in .env).
Writes the slow run as a chart (one row per measure, one y-axis per row; the baseline is too short for a time series
at the export interval, so it is compared in the summary instead), every measure of both runs as CSV, and prints the
summary.
"""

import csv
import json
import math
import statistics
import subprocess
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/benchmarks"
SEL = 'job="placemap/ingest", deployment_environment="benchmark"'

# Charted rows: (key, title, unit, [(series name, PromQL)]).
MEASURES = [
    ("waiting", "Batches waiting in the three queues", "", [
        ("waiting", f'sum(placemap_ingest_queue_size{{{SEL}}})')]),
    ("rates", "Batches per second", "", [
        ("read", f'sum(rate(placemap_ingest_stage_process_seconds_count{{{SEL}, stage="read"}}[30s]))'),
        ("committed", f'sum(rate(placemap_ingest_stage_process_seconds_count{{{SEL}, stage="upsert"}}[30s]))')]),
    ("blocked", "Share of time the reader waits for room in the queue", "%", [
        ("reader", f'sum(rate(placemap_ingest_stage_blocked_seconds_sum{{{SEL}, stage="read"}}[30s])) * 100')]),
    ("heap", "JVM heap used (MB)", "", [
        ("heap", f'sum(jvm_memory_used_bytes{{{SEL}, area="heap"}}) / 1e6')]),
]
# Only in the CSV and the summary.
EXTRA = {
    "capacity": f'sum(placemap_ingest_queue_capacity{{{SEL}}})',
    **{f"fill_{q}": f'sum(placemap_ingest_queue_size{{{SEL}, queue="{q}"}}) / '
                    f'sum(placemap_ingest_queue_capacity{{{SEL}, queue="{q}"}}) * 100'
       for q in ("parse", "match", "upsert")},
}
HEAP_LIMIT_MB = 1024 ** 3 / 1e6  # -Xmx1g, the plan's budget


def load_env():
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            value = value.split("  #")[0].strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
                value = value[1:-1]
            env[key] = value
    for key in ("GRAFANA_URL", "GRAFANA_SA_TOKEN"):
        if not env.get(key):
            sys.exit(f"{key} is empty in .env")
    return env


ENV = load_env()


def query_range(expr, start, end, step):
    path = ("/api/datasources/proxy/uid/grafanacloud-prom/api/v1/query_range?"
            + urllib.parse.urlencode({"query": expr, "start": start, "end": end, "step": step}))
    out = subprocess.run(["curl", "-sS", "-f", ENV["GRAFANA_URL"].rstrip("/") + path,
                          "-H", f"Authorization: Bearer {ENV['GRAFANA_SA_TOKEN']}"],
                         capture_output=True, text=True)
    if out.returncode:
        sys.exit(f"query failed ({out.stderr.strip()}): {expr}")
    result = json.loads(out.stdout)["data"]["result"]
    return [(int(t) - start, float(v)) for t, v in result[0]["values"]] if result else []


def collect(results):
    step = results["step_s"]
    data = {}
    for label, run in results["runs"].items():
        start, end = run["start"], run["end"] + step
        data[label] = {key: [(name, query_range(expr, start, end, step)) for name, expr in series]
                       for key, _, _, series in MEASURES}
        data[label].update({key: [(key, query_range(expr, start, end, step))] for key, expr in EXTRA.items()})
    return data


# ---- SVG ------------------------------------------------------------------------------------------------------------

STYLE = """
  .surface { fill: #fcfcfb; } .ink { fill: #0b0b0b; } .ink2 { fill: #52514e; } .muted { fill: #898781; }
  .grid { stroke: #e1e0d9; } .axis { stroke: #c3c2b7; } .limit { stroke: #898781; }
  .s1 { stroke: #2a78d6; } .s2 { stroke: #eb6834; } .s3 { stroke: #1baf7a; }
  .f1 { fill: #2a78d6; } .f2 { fill: #eb6834; } .f3 { fill: #1baf7a; }
  .ring { stroke: #fcfcfb; }
  @media (prefers-color-scheme: dark) {
    .surface { fill: #1a1a19; } .ink { fill: #ffffff; } .ink2 { fill: #c3c2b7; }
    .grid { stroke: #2c2c2a; } .axis { stroke: #383835; }
    .s1 { stroke: #3987e5; } .s2 { stroke: #d95926; } .s3 { stroke: #199e70; }
    .f1 { fill: #3987e5; } .f2 { fill: #d95926; } .f3 { fill: #199e70; }
    .ring { stroke: #1a1a19; }
  }
  text { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; }
  .line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
"""


def axis(value):
    """A clean y-axis for values up to `value`: (top, ticks), stepping by 1, 2, 2.5 or 5 times a power of ten."""
    if value <= 0:
        return 1, [0, 1]
    raw = value / 4
    magnitude = 10 ** math.floor(math.log10(raw))
    step = next(base * magnitude for base in (1, 2, 2.5, 5, 10) if base * magnitude >= raw)
    count = math.ceil(value / step - 1e-9)
    return count * step, [i * step for i in range(count + 1)]


def fmt(value, unit):
    if unit == "%":
        return f"{value:.0f}%"
    if value >= 100:
        return f"{value:,.0f}"
    return f"{value:g}" if value == int(value) else f"{value:.1f}"


def svg(results, data):
    label, run = next((k, r) for k, r in results["runs"].items() if r["latency_ms"] > 0)
    d = data[label]
    seconds = run["seconds"]
    capacity = max((v for _, v in d["capacity"][0][1]), default=0)
    width, left, right = 960, 64, 96
    panel_w = width - left - right
    panel_h, row_gap, top = 110, 74, 84
    height = top + len(MEASURES) * (panel_h + row_gap) - 20
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" '
           f'height="{height}" role="img" aria-label="Backpressure benchmark: with {run["latency_ms"]} ms added to '
           f'every database reply, the queues stay full, reading slows to the commit rate and the heap stays flat">',
           f"<style>{STYLE}</style>", f'<rect class="surface" width="{width}" height="{height}" rx="8"/>',
           f'<text class="ink" x="{left}" y="32" font-size="16" font-weight="600">Importing {results["source"]} with '
           f'+{run["latency_ms"]} ms on every database reply</text>',
           f'<text class="ink2" x="{left}" y="54" font-size="12">{run["batches"]} batches of 1,000 lines in '
           f'{seconds / 60:.1f} min. Each point is one {results["step_s"]} s metrics export from the importer.</text>']

    def px(t):
        return left + min(max(t, 0), seconds) / seconds * panel_w

    for row, (key, title, unit, series) in enumerate(MEASURES):
        y0 = top + row * (panel_h + row_gap) + 26
        values = [v for _, pts in d[key] for _, v in pts]
        reference = {"waiting": (capacity, f"capacity ({capacity:.0f})"),
                     "heap": (HEAP_LIMIT_MB, "heap limit (1 GB)")}.get(key)
        y_top, y_ticks = axis(100 if unit == "%" else max(values + [reference[0] if reference else 0]) * 1.05)

        def py(v, y_top=y_top, y0=y0):
            return y0 + panel_h - min(max(v, 0), y_top) / y_top * panel_h

        out.append(f'<text class="ink" x="{left}" y="{y0 - 14}" font-size="13" font-weight="600">{title}</text>')
        if len(series) > 1:
            lx = left + 10 + 7.3 * len(title)
            for i, (name, _) in enumerate(series):
                out.append(f'<rect class="f{i + 1}" x="{lx:.0f}" y="{y0 - 23}" width="10" height="10" rx="2"/>')
                out.append(f'<text class="ink2" x="{lx + 14:.0f}" y="{y0 - 14}" font-size="12">{name}</text>')
                lx += 28 + 7 * len(name)
        for tick in y_ticks:
            y = py(tick)
            out.append(f'<line class="{"axis" if tick == 0 else "grid"}" x1="{left}" x2="{left + panel_w}" '
                       f'y1="{y:.1f}" y2="{y:.1f}" stroke-width="1"/>')
            out.append(f'<text class="muted" x="{left - 8}" y="{y + 4:.1f}" font-size="11" '
                       f'text-anchor="end">{fmt(tick, unit)}</text>')
        for m in range(0, int(seconds // 60) + 1, 2):
            out.append(f'<text class="muted" x="{px(m * 60):.1f}" y="{y0 + panel_h + 16}" font-size="11" '
                       f'text-anchor="middle">{m} min</text>')
        if reference:
            y = py(reference[0])
            out.append(f'<line class="limit" x1="{left}" x2="{left + panel_w}" y1="{y:.1f}" y2="{y:.1f}" '
                       f'stroke-width="1"/>')
            out.append(f'<text class="muted" x="{left + panel_w}" y="{y - 5:.1f}" font-size="11" '
                       f'text-anchor="end">{reference[1]}</text>')
        ends = []
        for i, (name, pts) in enumerate(d[key]):
            pts = [(t, v) for t, v in pts if t <= seconds + results["step_s"]]
            if not pts:
                continue
            path = " ".join(f"{'M' if j == 0 else 'L'}{px(t):.1f},{py(v):.1f}" for j, (t, v) in enumerate(pts))
            out.append(f'<path class="line s{i + 1}" d="{path}"/>')
            ex, ey = px(pts[-1][0]), py(pts[-1][1])
            out.append(f'<circle class="f{i + 1} ring" cx="{ex:.1f}" cy="{ey:.1f}" r="4" stroke-width="2"/>')
            ends.append([ey, name, pts[-1][1]])
        # Direct labels at the line ends, nudged apart when they would overlap.
        ends.sort()
        for j in range(1, len(ends)):
            ends[j][0] = max(ends[j][0], ends[j - 1][0] + 13)
        for ey, name, value in ends:
            text = name if len(ends) > 1 else fmt(value, unit)
            out.append(f'<text class="ink2" x="{left + panel_w + 8}" y="{ey + 4:.1f}" font-size="11">{text}</text>')
    out.append("</svg>")
    return "\n".join(out) + "\n"


def summary(results, data):
    lines = []
    for label, run in results["runs"].items():
        d = data[label]
        heap = [v for _, v in d["heap"][0][1]]
        fills = {q: max((v for _, v in d[f"fill_{q}"][0][1]), default=0) for q in ("parse", "match", "upsert")}
        # The share while running: leave out the first and last 30 s (filling, draining) when the run is long enough.
        blocked = d["blocked"][0][1]
        busy = [v for t, v in blocked if 30 <= t <= run["seconds"] - 30] or [v for _, v in blocked]
        lines.append(f"{label}: +{run['latency_ms']} ms, {run['batches']} batches in {run['seconds']:.0f} s "
                     f"({run['batches'] / run['seconds']:.2f} batches/s, exit {run['exit']}); max queue fill "
                     + ", ".join(f"{k} {v:.0f}%" for k, v in fills.items())
                     + f"; reader waiting {statistics.mean(busy) if busy else 0:.0f}% of the time; heap "
                     f"min {min(heap, default=0):.0f} / median {statistics.median(heap) if heap else 0:.0f} / max "
                     f"{max(heap, default=0):.0f} MB")
    return "\n".join(lines)


def main():
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "services/ingest/target/proofs/backpressure-results.json"
    results = json.loads(path.read_text(encoding="utf-8"))
    data = collect(results)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "backpressure.svg").write_text(svg(results, data), encoding="utf-8")
    with open(OUT / "backpressure.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["run", "latency_ms", "seconds_since_start", "measure", "series", "value"])
        for label, run in results["runs"].items():
            for key, measured in data[label].items():
                for name, pts in measured:
                    for t, v in pts:
                        writer.writerow([label, run["latency_ms"], t, key, name, f"{v:.4g}"])
    print(summary(results, data))
    print(f"wrote {(OUT / 'backpressure.svg').relative_to(ROOT)} and {(OUT / 'backpressure.csv').relative_to(ROOT)}")


if __name__ == "__main__":
    main()
