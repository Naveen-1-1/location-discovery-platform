/**
 * Hand-written concurrency primitives shared by the ingest, live-hub and api services.
 *
 * <p>Everything here is plain Java (no Spring) so it can be verified in isolation: linearizability with
 * Lincheck (unit tests in this module) and memory-model behaviour with JCStress
 * ({@code libs/concurrent-jcstress}).
 */
package io.github.naveen11.placemap.concurrent;
