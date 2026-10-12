# place-discovery-map

A map app for finding places in Boston, Cambridge, Somerville and Brookline. It covers food, groceries, pharmacies,
gyms, parks, banks, transit, bike share, EV charging and more. Places can be filtered using official public data
(health inspections, licences, SNAP acceptance, live bike and train availability) and sorted by distance or real
travel time. The app also gives walk, bike and drive directions and shows the area reachable within N minutes.

It is also a learning project. The three backend services I write contain real thread and concurrency code, and
load tests and crash tests prove that code holds up. Observability, CI/CD and infrastructure as code are part of
the project from the start.

> **Status:** Phase 7, hardening and the open data release ([ADR 0044](docs/adr/0044-hardening-and-data-release.md)):
> request-body limits, burn-rate alerts on the api's SLOs, the ODbL release built locally (not yet published), and a
> nightly check that must pass on three different days. Sign-in and saved lists were dropped
> ([ADR 0043](docs/adr/0043-sign-in-and-lists-dropped.md)).

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
tools/dev.sh migrate && tools/dev.sh tiles
```
- `migrate` applies the database migrations to Neon.
- `tiles` builds the Boston basemap file.

**First import** (OSM first, so it can corroborate Overture's less certain places; then the official lists match
both):
```bash
tools/dev.sh ingest-once osm_geofabrik_massachusetts overture_places boston_active_food_licenses boston_food_inspections cambridge_food_establishment_permits cambridge_sanitary_inspections bluebikes_station_information mbta_gtfs massgis_hospitals fdic_bank_locations boston_open_space boston_bcyf_community_centers boston_public_libraries afdc_alt_fuel_stations usda_snap_retailers boston_licensing_board_licenses boston_park_features boston_wicked_free_wifi nppes_npi_pharmacies
```
Records without coordinates (pharmacies, a few licences) are placed with Photon at one call a second, so the first
import of those sources takes several minutes; every answer is cached in the database and never asked again.
EV chargers (`afdc_alt_fuel_stations`) need a free key from [developer.nlr.gov](https://developer.nlr.gov/signup/)
in `.env` as `NLR_API_KEY`; without it the importer skips that source and says so once.

**Then use three terminals:**
```bash
tools/dev.sh api    # http://localhost:8080
tools/dev.sh live   # http://localhost:8081: live bikes, MBTA alerts, vehicles and departures
tools/dev.sh web    # http://localhost:5173  ← open this
```
Travel times, routes and reachable areas come from openrouteservice and need `ORS_API_KEY` in `.env` (a free
account). Without it the map still works and sorts by straight-line distance
([ADR 0040](docs/adr/0040-travel-time-search-in-development.md)).

Live data works without a key: Bluebikes and MBTA's realtime files are open. A free MBTA V3 key in `.env` as
`MBTA_API_KEY` ([api-v3.mbta.com](https://api-v3.mbta.com)) streams MBTA alerts and vehicles as they change. Without
`live` running, the map still works and simply shows no live counts
([ADR 0041](docs/adr/0041-live-hub-in-development.md)).

**Importer (Phase 2):** imports official and open data into the database on each source's cadence and resumes
any import that was interrupted (see [ADR 0020](docs/adr/0020-import-crash-safety.md) and
[ADR 0021](docs/adr/0021-ingest-run-mode.md)).
```bash
tools/dev.sh ingest                                         # keeps running, imports when due
tools/dev.sh ingest-once boston_active_food_licenses        # one import now, then exit (add --force to redo)
```
Downloads are saved under `data/raw/` (git-ignored). Personal fields are never downloaded
([ADR 0031](docs/adr/0031-personal-data-dropped-at-import.md)).

**Filters and search (Phase 5):** "open now", "accepts SNAP/EBT", "licensed" and "passed inspection" work from facts
each import stores per place; the search box finds places by name in the database and addresses through Photon, by way
of the api so typed addresses never leave our servers ([ADR 0042](docs/adr/0042-sources-filters-search.md)).

**Checking the importers and the matcher:**
```bash
./mvnw -pl services/ingest -am test -Dtest=SourceContractTest -Dsurefire.failIfNoSpecifiedTests=false -Dplacemap.contract=true
tools/dev.sh match-sample     # data/eval/match-sample.csv: 400 scored cases to label, 100 marked for a blind check
tools/dev.sh match-evaluate   # precision and recall per threshold from the labels (and the blind check)
tools/dev.sh coverage         # docs/data/coverage.md from the database
```
The contract test fetches each source's real data and fails when a publisher changes what the importer reads; it also
runs nightly in CI. The blind check is `tools/label/index.html`, opened in a browser.

## Building
Requires JDK 25 (Temurin) and Maven 3.9.6 or newer. Docker Desktop must be running for the database
integration tests; without Docker they are skipped.
```bash
./mvnw verify                                                         # unit + Lincheck tests
./mvnw -pl libs/concurrent-jcstress -am verify -Pjcstress -DskipTests # JCStress (quick mode)
java -jar libs/concurrent-jmh/target/benchmarks.jar                   # JMH benchmarks
```
The importer's crash tests and backpressure benchmark run the real jar against a throwaway local database and need
Docker Desktop, Toxiproxy (`brew install toxiproxy`) and the snapshots in `data/raw`
([ADR 0039](docs/adr/0039-crash-and-backpressure-proofs.md), results in [docs/benchmarks](docs/benchmarks)):
```bash
tools/dev.sh proofs c1             # kill -9 at random moments, 20 trials
tools/dev.sh proofs c2             # connection cuts during commits, 5 trials
tools/dev.sh proofs backpressure   # the same import with and without 200 ms of database latency
```

**Nightly check** ([ADR 0044](docs/adr/0044-hardening-and-data-release.md)): every check that needs no cloud, in one
command that ends with `NIGHTLY PASSED` or `NIGHTLY FAILED`: promtool tests of the alerts, the frontend, the full
`./mvnw verify` (Docker must be running), the release check, the contract tests, the full JCStress suite, and C1 and
C2. Each run adds a row to [docs/benchmarks/nightly-runs.md](docs/benchmarks/nightly-runs.md); `nightly.yml` runs the
same on GitHub. Needs promtool (`brew install prometheus`) besides the tools above.
```bash
tools/dev.sh nightly                       # everything, about 65 minutes (JCStress takes 45)
tools/dev.sh nightly --only verify,c1      # some steps (a partial run is not counted)
```

**Alerts:** burn-rate alerts on the api's availability and nearby latency ([docs/slo.md](docs/slo.md)), plus 5xx,
pinning, import and stale-feed alerts, all by email; runbooks in [docs/runbooks](docs/runbooks). Apply them to Grafana
Cloud with `python3 -I tools/grafana-apply.py`.

## Licences
- **Code:** MIT ([LICENSE](LICENSE)).
- **Place data:** the merged place data is released under ODbL 1.0, with attribution to OpenStreetMap contributors,
  Overture Maps Foundation and each official source (see `data/sources.yaml`). `tools/dev.sh release` builds it into
  `data/release/<date>/` (places GeoParquet, their source records, `LICENSE`, `ATTRIBUTION.md`, `METHOD.md`), and
  `tools/dev.sh release-check` checks a built folder. It is published as a GitHub Release once the owner decides to.
- **Live MBTA and Bluebikes data:** displayed in the app but not republished; transit stops and bike docks are not in
  the release.
