# The api or live-hub for the load-test stack: the service's own jar on Temurin 25's JRE, for amd64 and arm64.
# Built with the jar's folder as the context, e.g. services/api/target with JAR=api.jar.
FROM eclipse-temurin:26.0.2_10-jre-noble@sha256:0140636ef128fa014041fa12dae3a9de53b6a6c6f055271f4e6a7417ea34012c

ARG JAR
COPY ${JAR} /app/service.jar
# Not root; the image has no other use for a user account.
USER 10001:10001
# As tools/dev.sh: protobuf uses sun.misc.Unsafe, which is expected (the jar's manifest allows its native code).
# Under 1.8 GB the JVM picks the Serial collector, which stops the service for every collection; G1 is its choice on
# any larger machine, so the tests measure the collector a real deployment runs. The heap gets HEAP_PERCENT of the
# container's memory limit (70% unless the service sets less); the rest is for the JIT, metaspace, platform thread
# stacks and network buffers. G1 grows the heap to its maximum under load, so the share, not the service's need, sets
# how close the container comes to its limit. A service whose heap runs out exits, as a supervisor would want, instead
# of limping on with failing threads. The shell only reads the share, then becomes java with the container's
# arguments (the stream client takes its options that way).
ENV HEAP_PERCENT=70
ENTRYPOINT ["/bin/sh", "-c", "exec java --sun-misc-unsafe-memory-access=allow -XX:+UseG1GC \
-XX:MaxRAMPercentage=\"$HEAP_PERCENT\" -XX:+ExitOnOutOfMemoryError -jar /app/service.jar \"$@\"", "service"]
