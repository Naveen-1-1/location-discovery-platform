/**
 * Hand-written concurrency primitives shared by the ingest, live-hub and api services.
 *
 * <ul>
 *   <li>Ingest pipeline: {@link io.github.naveen11.placemap.concurrent.BoundedChannel},
 *       {@link io.github.naveen11.placemap.concurrent.ContiguousWatermark}.
 *   <li>Live-hub fan-out: {@link io.github.naveen11.placemap.concurrent.SnapshotPublisher},
 *       {@link io.github.naveen11.placemap.concurrent.CoalescingMailbox},
 *       {@link io.github.naveen11.placemap.concurrent.SerialDrain}.
 * </ul>
 *
 * <p>Everything here is plain Java (no Spring) so it can be verified in isolation: linearizability with
 * Lincheck (unit tests in this module) and memory-model behaviour with JCStress
 * ({@code libs/concurrent-jcstress}).
 */
package io.github.naveen11.placemap.concurrent;
