package dji.sampleV5.aircraft.acoustics.core;

/** A read-only VoiceSep result. Publication ages are NOT microphone capture timestamps. */
public final class VoiceSepFrame {
    public final String source, runId, reason;
    public final long epoch, sequence;
    public final boolean upstreamOk, active, valid;
    public final double angle, configuredPeriodMs, resultAgeMs, inputAgeMs, backlogMs, fetchMs, updateHz;
    public VoiceSepFrame(String source, String runId, String reason, long epoch, long sequence,
                         boolean upstreamOk, boolean active, boolean valid, double angle,
                         double configuredPeriodMs, double resultAgeMs, double inputAgeMs,
                         double backlogMs, double fetchMs, double updateHz) {
        if (!("voicesep".equals(source) || "demo".equals(source) || "synthetic_audio".equals(source)) || epoch < 0 || sequence < 0)
            throw new IllegalArgumentException("未知数据来源或非法序号");
        if (valid && (!upstreamOk || !active || runId == null || runId.isEmpty() ||
                !Double.isFinite(angle) || angle < 0 || angle >= 360 ||
                !finiteNonnegative(resultAgeMs) || !finiteNonnegative(inputAgeMs)))
            throw new IllegalArgumentException("有效角度缺少必需字段");
        this.source=source; this.runId=runId; this.reason=reason; this.epoch=epoch; this.sequence=sequence;
        this.upstreamOk=upstreamOk; this.active=active; this.valid=valid; this.angle=angle;
        this.configuredPeriodMs=configuredPeriodMs; this.resultAgeMs=resultAgeMs;
        this.inputAgeMs=inputAgeMs; this.backlogMs=backlogMs; this.fetchMs=fetchMs; this.updateHz=updateHz;
    }
    public static boolean finiteNonnegative(double value) { return Double.isFinite(value) && value >= 0; }
}
