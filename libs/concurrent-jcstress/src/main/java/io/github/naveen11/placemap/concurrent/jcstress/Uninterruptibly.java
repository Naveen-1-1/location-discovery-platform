package io.github.naveen11.placemap.concurrent.jcstress;

import io.github.naveen11.placemap.concurrent.BoundedChannel;

/** Blocking channel calls for JCStress actors, which may not throw checked exceptions. JCStress never interrupts. */
final class Uninterruptibly {

    private Uninterruptibly() {
    }

    static <T> void put(BoundedChannel<T> channel, T item) {
        try {
            channel.put(item);
        } catch (InterruptedException e) {
            throw new IllegalStateException("JCStress actors are never interrupted", e);
        }
    }

    static <T> T take(BoundedChannel<T> channel) {
        try {
            return channel.take();
        } catch (InterruptedException e) {
            throw new IllegalStateException("JCStress actors are never interrupted", e);
        }
    }
}
