# PostGIS 3.6 + h3 on Postgres 18, for amd64 and arm64.
# The official postgis/postgis image is amd64-only. Used by integration tests now and
# self-hosted deployments later. The base image already carries the PGDG apt repository.
# Pinned by digest (the multi-arch index), so a rebuild gets the same base; Dependabot proposes updates.
FROM postgres:18.6-bookworm@sha256:afc7e2d441324c0388fa80c3d24f733b4194a4eb7f47dd8ee2b08eb1a24a647c

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        postgresql-18-postgis-3 \
        postgresql-18-postgis-3-scripts \
        postgresql-18-h3; \
    rm -rf /var/lib/apt/lists/*
