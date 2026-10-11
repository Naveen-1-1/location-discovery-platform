# place-discovery-map

A map app for finding places in Boston, Cambridge, Somerville and Brookline. It covers food, groceries, pharmacies,
gyms, parks, banks, transit, bike share, EV charging and more. Places can be filtered using official public data
(health inspections, licences, SNAP acceptance, live bike and train availability) and sorted by distance or real
travel time. The app also gives walk, bike and drive directions, shows the area reachable within N minutes, and
saves lists of places.

It is also a learning project. The three backend services I write contain real thread and concurrency code, and
load tests and crash tests prove that code holds up. Observability, CI/CD and infrastructure as code are part of
the project from the start.

> **Status:** Phase 1 (walking skeleton): a map of nearby places sorted by straight-line distance, with monitoring.

## How it fits together
- **Off-the-shelf parts:** PostGIS (database), Valhalla (routing), Photon (address search), MapLibre with a
  Protomaps file (map), and the OpenTelemetry → Prometheus / Loki / Tempo → Grafana stack.
- **`ingest`:** imports Overture, OpenStreetMap and the official datasets, and matches official records to places.
  Built from scratch as a pipeline of worker pools linked by bounded queues, and it resumes after `kill -9`.
- **`live-hub`:** polls Bluebikes and MBTA feeds and streams updates to browsers over Server-Sent Events. Each
  client has a small mailbox, and clients that fall too far behind are evicted.
- **`api`:** nearby search sorted by travel time. It runs its lookups in parallel with deadlines, merges identical
  in-flight requests, and enforces per-client rate limits and per-dependency bulkheads.

The reasoning behind every design choice is in [docs/adr](docs/adr/).

## Running locally
Development is hybrid: the Java services run on your machine, while the database (Neon), monitoring (Grafana
Cloud), and later routing and search use free cloud tiers (see [ADR 0033](docs/adr/0033-hybrid-development-setup.md)).

Before you start: copy `.env.example` to `.env` and fill in the Neon and Grafana Cloud values.

**One-off setup:**
```bash
tools/dev.sh migrate && tools/dev.sh seed && tools/dev.sh tiles
```
- `migrate` applies the database migrations to Neon.
- `seed` loads about 7,100 temporary Overture places.
- `tiles` builds the Boston basemap file.

**Then use two terminals:**
```bash
tools/dev.sh api    # http://localhost:8080
tools/dev.sh web    # http://localhost:5173  ← open this
```

**Importer (Phase 2):** imports official and open data into the database on each source's cadence and resumes
any import that was interrupted (see [ADR 0020](docs/adr/0020-import-crash-safety.md) and
[ADR 0021](docs/adr/0021-ingest-run-mode.md)).
```bash
tools/dev.sh ingest                                         # keeps running, imports when due
tools/dev.sh ingest-once boston_active_food_licenses        # one import now, then exit
```
Downloads are saved under `data/raw/` (git-ignored). Personal fields are never downloaded
([ADR 0031](docs/adr/0031-personal-data-dropped-at-import.md)).

## Building
Requires JDK 25 (Temurin) and Maven 3.9.6 or newer. Docker Desktop must be running for the database
integration tests; without Docker they are skipped.
```bash
./mvnw verify                                                         # unit + Lincheck tests
./mvnw -pl libs/concurrent-jcstress -am verify -Pjcstress -DskipTests # JCStress (quick mode)
java -jar libs/concurrent-jmh/target/benchmarks.jar                   # JMH benchmarks
```

## Licences
- **Code:** MIT ([LICENSE](LICENSE)).
- **Place data:** the merged place data will be published under ODbL 1.0, with attribution to OpenStreetMap
  contributors, Overture Maps Foundation and each official source (see `data/sources.yaml`).
- **Live MBTA and Bluebikes data:** displayed in the app but not republished.
