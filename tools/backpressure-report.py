"""Turns the backpressure benchmark's metrics into a CSV (backpressure.csv in OUT) and a summary.

Usage:  tools/dev.sh backpressure-report [RESULTS_JSON]

Reads the run windows that `tools/dev.sh proofs backpressure` saved (services/ingest/target/proofs/
backpressure-results.json) and queries those windows from Grafana Cloud (GRAFANA_URL and GRAFANA_SA_TOKEN, from .env).
Writes every measure of both runs as CSV and prints the summary: with the database slowed down, each partition reads
its next batch only after committing the last one, so the import slows to the commit rate and the heap stays flat.
"""

import csv
import json
import os
import statistics
import subprocess
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/benchmarks"
SEL = 'job="placemap/ingest", deployment_environment="benchmark"'

# Measures: (key, [(series name, PromQL)]).
MEASURES = [
    ("rates", [
        ("read", f'sum(rate(spring_batch_item_read_seconds_count{{{SEL}}}[30s]))'),
        ("committed", f'sum(rate(placemap_ingest_batches_total{{{SEL}}}[30s]))')]),
    ("write", [
        ("write", f'sum(rate(spring_batch_chunk_write_seconds_sum{{{SEL}}}[30s])) / '
                  f'sum(rate(spring_batch_chunk_write_seconds_count{{{SEL}}}[30s]))')]),
    ("heap", [
        ("heap", f'sum(jvm_memory_used_bytes{{{SEL}, area="heap"}}) / 1e6')]),
]
HEAP_LIMIT_MB = 1024 ** 3 / 1e6  # -Xmx1g, the plan's budget


def load_env():
    """The Grafana settings, which tools/dev.sh loads from .env."""
    missing = [key for key in ('GRAFANA_URL', 'GRAFANA_SA_TOKEN') if not os.environ.get(key)]
    if missing:
        sys.exit(f"{', '.join(missing)} not set: run this as tools/dev.sh backpressure-report, with them in .env")
    return os.environ


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
                       for key, series in MEASURES}
    return data


def summary(results, data):
    lines = []
    for label, run in results["runs"].items():
        d = data[label]
        heap = [v for _, v in d["heap"][0][1]]
        write = [v for _, v in d["write"][0][1] if v == v]  # NaN while nothing was written
        lines.append(f"{label}: +{run['latency_ms']} ms, {run['batches']} batches in {run['seconds']:.0f} s "
                     f"({run['batches'] / run['seconds']:.2f} batches/s, exit {run['exit']}); write and commit "
                     f"{statistics.median(write) if write else 0:.2f} s per batch (median); heap "
                     f"min {min(heap, default=0):.0f} / median {statistics.median(heap) if heap else 0:.0f} / max "
                     f"{max(heap, default=0):.0f} MB of {HEAP_LIMIT_MB:.0f}")
    return "\n".join(lines)


def main():
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "services/ingest/target/proofs/backpressure-results.json"
    results = json.loads(path.read_text(encoding="utf-8"))
    data = collect(results)
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / "backpressure.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["run", "latency_ms", "seconds_since_start", "measure", "series", "value"])
        for label, run in results["runs"].items():
            for key, measured in data[label].items():
                for name, pts in measured:
                    for t, v in pts:
                        writer.writerow([label, run["latency_ms"], t, key, name, f"{v:.4g}"])
    print(summary(results, data))
    print(f"wrote {(OUT / 'backpressure.csv').relative_to(ROOT)}")


if __name__ == "__main__":
    main()
