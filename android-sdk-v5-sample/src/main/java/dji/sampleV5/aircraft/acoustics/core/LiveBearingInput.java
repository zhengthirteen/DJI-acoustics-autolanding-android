package dji.sampleV5.aircraft.acoustics.core;

/** Protocol-neutral latest-sample mailbox. No socket, Android or DJI dependency. */
public final class LiveBearingInput {
    public static final long PERIOD_MS = 100, ZERO_AFTER_MS = 200, ABORT_AFTER_MS = 500, MAX_SESSION_MS = 60000;
    public enum Status { VALUE, ZERO, FINISHED, FAULT }
    public static final class Reading {
        public final Status status;
        public final Double targetBearing;
        public final String reason;
        private Reading(Status status, Double targetBearing, String reason) {
            this.status = status; this.targetBearing = targetBearing; this.reason = reason;
        }
    }
    private final ArrayAngleCalibration calibration;
    private boolean started, finished;
    private String fault;
    private long startedAt, lastPoll = -1, lastSequence = -1, lastCaptured = -1, lastReceived = -1, lastValid = -1;
    private Double latestRaw;
    private int accepted, rejected;

    public LiveBearingInput(ArrayAngleCalibration calibration) {
        if (calibration == null) throw new IllegalArgumentException("必须提供阵列标定");
        calibration.requireTargetAtArray();
        this.calibration = calibration;
    }
    /** The controller starts one fresh session after control authority is confirmed. */
    public synchronized void start(long now) {
        if (started || finished || fault != null || now < 0) throw new IllegalStateException("输入已终止或开始过，请新建输入会话");
        started = true; startedAt = now; lastPoll = now;
        latestRaw = null; lastValid = -1; accepted = 0;
        // Retain sequence/capture high-water marks, but pre-start values cannot move the aircraft.
    }
    /**
     * Called by a future transport adapter. Capture time MUST be mapped to phone monotonic time;
     * receivedAt MUST come from the phone's clock, never a packet-provided wall clock.
     * Null angle means an explicitly invalid/low-confidence measurement, never arrival.
     */
    public synchronized boolean offer(long sequence, Double rawDegrees, long capturedAt, long receivedAt) {
        if (finished || fault != null) return false;
        if (started && receivedAt >= startedAt && expired(receivedAt)) {
            fail("阵列有效角度超过 500 ms 未更新，禁止迟到数据恢复控制"); return false;
        }
        if (sequence < 0 || sequence <= lastSequence || capturedAt < 0 || receivedAt < 0 ||
                capturedAt > receivedAt || capturedAt <= lastCaptured || receivedAt < lastReceived ||
                receivedAt - capturedAt > ZERO_AFTER_MS || (started && capturedAt < startedAt)) {
            rejected++; return false;
        }
        if (rawDegrees != null && !Double.isFinite(rawDegrees)) {
            rejected++; fail("阵列角度不是有限数值"); return false;
        }
        lastSequence = sequence; lastCaptured = capturedAt; lastReceived = receivedAt;
        latestRaw = rawDegrees;
        if (rawDegrees != null) lastValid = capturedAt;
        accepted++;
        return true;
    }
    public synchronized Reading poll(long now) {
        if (fault != null) return new Reading(Status.FAULT, null, fault);
        if (finished) return new Reading(Status.FINISHED, null, "操作员结束阵列输入，悬停等待人工决定");
        if (!started) return new Reading(Status.ZERO, null, "尚未启动阵列输入会话");
        if (now < startedAt || now < lastPoll) { fail("阵列输入时钟异常"); return poll(now); }
        lastPoll = now;
        if (now - startedAt >= MAX_SESSION_MS) { fail("阵列会话达到 60 秒上限"); return poll(now); }
        if (expired(now)) { fail("阵列有效角度超过 500 ms 未更新"); return poll(now); }
        if (latestRaw == null || lastCaptured < startedAt || lastReceived > now || lastCaptured > now || now - lastCaptured >= ZERO_AFTER_MS)
            return new Reading(Status.ZERO, null, "没有新鲜有效角度，零速度等待");
        return new Reading(Status.VALUE, calibration.toTargetBearing(latestRaw), "使用最新阵列角度，保持高度");
    }
    private boolean expired(long now) { return now - (lastValid >= startedAt ? lastValid : startedAt) >= ABORT_AFTER_MS; }
    /** Explicit human action only. Socket EOF/disconnect must call fail(), not this method. */
    public synchronized void finishForOperator(long now) {
        if (!started || poll(now).status == Status.FAULT) return;
        finished = true; latestRaw = null;
    }
    public synchronized void fail(String reason) {
        if (fault == null) fault = reason == null ? "阵列输入已终止" : reason;
        latestRaw = null;
    }
    public synchronized int accepted() { return accepted; }
    public synchronized boolean readyForStart(long now) {
        return !started && !finished && fault == null && latestRaw != null && lastCaptured >= 0 &&
                now >= lastReceived && now - lastCaptured < ZERO_AFTER_MS;
    }
    public synchronized int rejected() { return rejected; }
    public synchronized long lastSequence() { return lastSequence; }
    public synchronized Double latestRaw() { return latestRaw; }
}
