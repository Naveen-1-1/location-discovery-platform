# slowhttptest for the slowloris test (L10), from Debian's package, for amd64 and arm64.
FROM debian:trixie-20261005-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends slowhttptest=1.9.0-1+b2; \
    rm -rf /var/lib/apt/lists/*
USER 10001:10001
ENTRYPOINT ["slowhttptest"]
