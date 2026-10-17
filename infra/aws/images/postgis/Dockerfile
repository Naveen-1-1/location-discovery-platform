# The stack's PostGIS with the load seed restored when it first starts, for the AWS test, which has no volume to
# restore into. images.sh builds it on the stack's image (infra/docker/postgis), with data/load as the context "seed".
ARG BASE=placemap/postgis-test
FROM ${BASE}
COPY --from=seed placemap.dump /seed/placemap.dump
COPY --chmod=755 restore.sh /docker-entrypoint-initdb.d/20-restore.sh
