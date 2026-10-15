/**
 * The live hub's fan-out, the hand-written concurrency code that nothing existing provides:
 *
 * <ul>
 *   <li>{@link io.github.naveen11.placemap.concurrent.SnapshotPublisher}: the newest snapshot per topic, handed to
 *       subscribers without a buffer in between, so "subscribe, then read" never misses an update;
 *   <li>{@link io.github.naveen11.placemap.concurrent.CoalescingMailbox}: a bounded per-client mailbox that keeps only
 *       the newest value per key and turns overflow into "reload everything";
 *   <li>{@link io.github.naveen11.placemap.concurrent.SerialDrain}: runs one connection's writes at most once at a
 *       time on a shared pool without losing a wake-up (pool mode).
 * </ul>
 *
 * <p>Everything here is plain Java (no Spring) so it can be verified in isolation: linearizability with
 * Lincheck (unit tests in this module) and memory-model behaviour with JCStress
 * ({@code libs/concurrent-jcstress}).
 */
package io.github.naveen11.placemap.concurrent;
