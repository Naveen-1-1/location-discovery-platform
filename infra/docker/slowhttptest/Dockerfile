# slowhttptest for the slowloris test (L10), from Debian's package, for amd64 and arm64.
FROM debian:trixie-20261027-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends slowhttptest=1.9.0-1+b2; \
    rm -rf /var/lib/apt/lists/*
USER 10001:10001
ENTRYPOINT ["slowhttptest"]
