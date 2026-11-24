# slowhttptest for the slowloris test (L10), from Debian's package, for amd64 and arm64.
FROM debian:trixie-20261104-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends slowhttptest=1.9.0-1+b2; \
    rm -rf /var/lib/apt/lists/*
USER 10001:10001
ENTRYPOINT ["slowhttptest"]
