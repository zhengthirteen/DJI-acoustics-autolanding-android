package dji.sampleV5.aircraft.acoustics.core;

/** Cross-thread, latched session fence. Late heartbeats/callbacks cannot revive a stopped flight. */
public final class SessionWatchdog {
    public static final long TIMEOUT_MS = 350;
    private long generation, lastHeartbeat;
    private boolean armed;
    public synchronized long arm(long now) {
        if (armed) throw new IllegalStateException("上一会话仍在运行");
        lastHeartbeat = now; armed = true;
        return ++generation;
    }
    public synchronized void heartbeat(long token, long now) {
        if (valid(token, now)) lastHeartbeat = now;
    }
    public synchronized boolean valid(long token, long now) {
        if (token != generation || !armed) return false;
        if (now < lastHeartbeat || now - lastHeartbeat > TIMEOUT_MS) armed = false;
        return armed;
    }
    public synchronized void cancel() { armed = false; generation++; }
}
