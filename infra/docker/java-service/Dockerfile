# The api or live-hub for the load-test stack: the service's own jar on Temurin 25's JRE, for amd64 and arm64.
# Built with the jar's folder as the context, e.g. services/api/target with JAR=api.jar.
FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab

ARG JAR
COPY ${JAR} /app/service.jar
# Not root; the image has no other use for a user account.
USER 10001:10001
# As tools/dev.sh: H3 and DuckDB load native code and protobuf uses sun.misc.Unsafe, both expected.
# Under 1.8 GB the JVM picks the Serial collector, which stops the service for every collection; G1 is its choice on
# any larger machine, so the tests measure the collector a real deployment runs. The heap gets 70% of the container's
# memory limit; the rest is for the JIT, metaspace, platform thread stacks and network buffers.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", \
            "-XX:+UseG1GC", "-XX:MaxRAMPercentage=70", "-jar", "/app/service.jar"]
