# slowhttptest for the slowloris test (L10), from Debian's package, for amd64 and arm64.
FROM debian:trixie-20261020-slim@sha256:62bd423aeee912639ca8ee681c2b762cb3e25137e0a92b20be455898a9f034ad

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends slowhttptest=1.9.0-1+b2; \
    rm -rf /var/lib/apt/lists/*
USER 10001:10001
ENTRYPOINT ["slowhttptest"]
