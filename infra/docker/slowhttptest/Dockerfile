# slowhttptest for the slowloris test (L10), from Debian's package, for amd64 and arm64.
FROM debian:trixie-20260918-slim@sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends slowhttptest=1.9.0-1+b2; \
    rm -rf /var/lib/apt/lists/*
USER 10001:10001
ENTRYPOINT ["slowhttptest"]
