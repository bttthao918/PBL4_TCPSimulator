package tcpsim;

import tcpsim.gui.EventBus;

/** RFC 6298 retransmission-timeout estimator. */
final class RtoEstimator {
    static final long INITIAL_RTO_MS = 1000;
    static final long MIN_RTO_MS = 200;
    static final long MAX_RTO_MS = 5000;
    private static final double ALPHA = 1.0 / 8.0;
    private static final double BETA = 1.0 / 4.0;
    private long sessionStartMs;
    private double srttMs;
    private double rttvarMs;
    private long rtoMs;
    private boolean initialized;

    RtoEstimator() { reset(System.currentTimeMillis()); }

    synchronized void reset(long startMs) {
        sessionStartMs = startMs;
        srttMs = 0;
        rttvarMs = 0;
        rtoMs = INITIAL_RTO_MS;
        initialized = false;
        publish();
    }

    /** Karn: call only when the segment was not retransmitted. */
    synchronized void onRttSample(long sampleMs) {
        double sample = Math.max(1, sampleMs);
        if (!initialized) {
            srttMs = sample;
            rttvarMs = sample / 2.0;
            initialized = true;
        } else {
            rttvarMs = (1 - BETA) * rttvarMs + BETA * Math.abs(srttMs - sample);
            srttMs = (1 - ALPHA) * srttMs + ALPHA * sample;
        }
        rtoMs = clamp(Math.round(srttMs + 4 * rttvarMs));
        publish();
    }

    synchronized void onTimeout() {
        rtoMs = clamp(rtoMs * 2);
        publish();
    }

    synchronized long getRtoMs() { return rtoMs; }
    synchronized double getSrttMs() { return initialized ? srttMs : 0; }
    synchronized double getRttvarMs() { return initialized ? rttvarMs : 0; }

    private long clamp(long value) {
        return Math.max(MIN_RTO_MS, Math.min(MAX_RTO_MS, value));
    }

    private void publish() {
        EventBus.publishRto(Math.max(0, System.currentTimeMillis() - sessionStartMs),
                getSrttMs(), rtoMs);
    }
}
