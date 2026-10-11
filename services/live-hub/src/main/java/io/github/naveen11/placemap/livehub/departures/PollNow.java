package io.github.naveen11.placemap.livehub.departures;

/** Asks the departures feed for an extra poll. Connected once the feeds start; does nothing before or without them. */
public final class PollNow implements Runnable {

    private volatile Runnable target = () -> { };

    public void connect(Runnable pollNow) {
        this.target = pollNow;
    }

    @Override
    public void run() {
        target.run();
    }
}
