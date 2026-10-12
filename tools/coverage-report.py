"""Writes the coverage report (Phase 5 exit: "the coverage report is regenerated") from the database.

Usage: tools/dev.sh coverage   (runs: python3 -I tools/coverage-report.py OUTPUT.md)

Reads with psql, using the PG* variables tools/dev.sh loads from .env, and prints nothing secret. Counts only:
places per category and the sources behind them, each source's records and how they matched, what the filters
know, and the geocoder's answers.
"""
import csv
import datetime
import io
import subprocess
import sys

QUERIES = {
    "places": """
        SELECT c.label AS category, count(p.place_id) AS places
        FROM place.category c LEFT JOIN place.place p ON p.category = c.code AND p.retired_at IS NULL
        GROUP BY c.code, c.label, c.sort_order ORDER BY c.sort_order""",
    "backing": """
        SELECT c.label AS category, l.source, count(DISTINCT p.place_id) AS places
        FROM place.place p
        JOIN place.category c ON c.code = p.category
        JOIN place.place_source_link l ON l.place_id = p.place_id
        JOIN place.source_record r ON r.source = l.source AND r.source_key = l.source_key AND r.retired_at IS NULL
        WHERE p.retired_at IS NULL
        GROUP BY c.label, c.sort_order, l.source ORDER BY c.sort_order, places DESC""",
    "records": """
        SELECT r.source,
               count(*) AS live_records,
               count(l.place_id) AS linked,
               count(*) FILTER (WHERE l.method = 'created') AS created_places,
               count(*) FILTER (WHERE l.method IN ('score', 'corroborated', 'id')) AS matched,
               count(*) FILTER (WHERE l.place_id IS NULL AND r.geom IS NULL) AS unlinked_without_location,
               (SELECT count(*) FROM place.match_review m WHERE m.source = r.source) AS in_review
        FROM place.source_record r
        LEFT JOIN place.place_source_link l ON l.source = r.source AND l.source_key = r.source_key
        WHERE r.retired_at IS NULL
        GROUP BY r.source ORDER BY r.source""",
    "facts": """
        SELECT count(*) AS live_places,
               count(f.place_id) AS with_facts,
               count(*) FILTER (WHERE f.open_week IS NOT NULL) AS hours_known,
               count(*) FILTER (WHERE f.hours_source = 'osm') AS hours_from_osm,
               count(*) FILTER (WHERE f.hours_source = 'cambridge_permit') AS hours_from_cambridge_permits,
               count(*) FILTER (WHERE f.hours_source = 'boston_licence') AS hours_from_boston_licences,
               count(*) FILTER (WHERE f.accepts_snap) AS accept_snap,
               count(*) FILTER (WHERE f.active_licence) AS licensed,
               count(*) FILTER (WHERE f.inspection = 'passed') AS passed_inspection,
               count(*) FILTER (WHERE f.inspection = 'failed') AS failed_inspection
        FROM place.place p LEFT JOIN place.place_facts f ON f.place_id = p.place_id
        WHERE p.retired_at IS NULL""",
    "geocodes": """
        SELECT quality, count(*) AS addresses FROM ingest.geocode_cache GROUP BY quality ORDER BY quality""",
}

TITLES = {
    "places": "Live places per category",
    "backing": "Sources behind each category's places (a place counts once per source that describes it)",
    "records": "Records per source and how they matched",
    "facts": "What the filters know",
    "geocodes": "Geocoder answers, one per address ever asked (Photon)",
}


def query(sql):
    out = subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", "--csv", "-c", sql],
                         capture_output=True, text=True, check=True)
    return list(csv.reader(io.StringIO(out.stdout)))


def table(rows):
    header, body = rows[0], rows[1:]
    numeric = [bool(body) and all(r[i].lstrip("-").isdigit() for r in body if r[i]) for i in range(len(header))]
    lines = ["| " + " | ".join(h.replace("_", " ") for h in header) + " |",
             "|" + "|".join("---:" if numeric[i] else "---" for i in range(len(header))) + "|"]
    lines += ["| " + " | ".join(f"{int(v):,}" if numeric[i] and v else v for i, v in enumerate(r)) + " |" for r in body]
    return "\n".join(lines)


def main(target):
    parts = ["# Coverage report", "",
             f"Generated {datetime.date.today().isoformat()} by `tools/dev.sh coverage` from the database. "
             "It replaces the Phase 0 count in [phase0-coverage.md](phase0-coverage.md), which compared the sources "
             "before any were merged.", ""]
    for name, sql in QUERIES.items():
        parts += [f"## {TITLES[name]}", "", table(query(sql)), ""]
    with open(target, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))
    print(f"wrote {target}")


if __name__ == "__main__":
    main(sys.argv[1])
