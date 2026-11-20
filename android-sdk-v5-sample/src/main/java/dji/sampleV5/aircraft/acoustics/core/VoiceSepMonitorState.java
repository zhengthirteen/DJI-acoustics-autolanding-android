package dji.sampleV5.aircraft.acoustics.core;

import java.util.HashSet;
import java.util.Set;

/** Read-only freshness/deduplication. Intentionally has no controller/LiveBearingInput/DJI reference. */
public final class VoiceSepMonitorState {
    public static final class View {
        public final boolean connected, valid, upstreamOk, demo;
        public final double angle, periodMs, resultAgeMs, inputAgeMs, backlogMs, updateHz;
        public final String reason, runId, source;
        public final long epoch, sequence, uniqueFrames, duplicateFrames, rejectedFrames;
        private View(boolean connected, boolean valid, VoiceSepFrame frame, double period, double age,
                     String reason, long unique, long duplicates, long rejected) {
            this.connected=connected; this.valid=valid; this.periodMs=period; this.resultAgeMs=age;
            this.reason=reason; this.uniqueFrames=unique; this.duplicateFrames=duplicates; this.rejectedFrames=rejected;
            upstreamOk=connected && frame != null && frame.upstreamOk;
            source=frame == null ? "voicesep" : frame.source;
            demo=!"voicesep".equals(source);
            angle=valid ? frame.angle : Double.NaN;
            inputAgeMs=frame == null ? Double.NaN : frame.inputAgeMs;
            backlogMs=frame == null ? Double.NaN : frame.backlogMs;
            updateHz=frame == null ? Double.NaN : frame.updateHz;
            runId=frame == null ? "" : frame.runId; epoch=frame == null ? 0 : frame.epoch;
            sequence=frame == null ? 0 : frame.sequence;
        }
    }
    private final double fallbackPeriodMs;
    private final Set<String> retiredRuns = new HashSet<>();
    private VoiceSepFrame frame;
    private long responseAt=-1, uniqueAt=-1, unique=0, duplicates=0, rejected=0;
    private double periodMs, ageAtResponse=Double.POSITIVE_INFINITY;
    private boolean connected;
    private String reason="尚未连接";
    public VoiceSepMonitorState(double fallbackPeriodMs) {
        if (!Double.isFinite(fallbackPeriodMs) || fallbackPeriodMs < 5 || fallbackPeriodMs > 5000)
            throw new IllegalArgumentException("备用周期须为 5–5000 ms；实际周期优先跟随服务端");
        this.fallbackPeriodMs=fallbackPeriodMs; periodMs=fallbackPeriodMs;
    }
    public synchronized void receive(VoiceSepFrame next, long requestedAt, long receivedAt) {
        if (next == null || requestedAt < 0 || receivedAt < requestedAt) {
            error("响应时间或数据无效"); return;
        }
        boolean sameRun=frame != null && next.runId != null && next.runId.equals(frame.runId);
        if ((next.runId != null && retiredRuns.contains(next.runId)) ||
                (sameRun && (next.epoch < frame.epoch || (next.epoch == frame.epoch && next.sequence < frame.sequence)))) {
            rejected++; error("收到旧会话或乱序数据，已清除角度"); return;
        }
        boolean duplicate=sameRun && next.epoch==frame.epoch && next.sequence==frame.sequence;
        if (duplicate && next.valid && (!frame.valid || Double.compare(next.angle,frame.angle)!=0)) {
            rejected++; error("同一序号的角度内容发生变化，已拒绝"); return;
        }
        if (!sameRun && frame != null && frame.runId != null && next.runId != null) {
            if (retiredRuns.size() >= 32) { error("会话切换过多，请手动重新连接"); return; }
            retiredRuns.add(frame.runId);
        }
        if (duplicate) duplicates++;
        else { unique++; uniqueAt=receivedAt; }
        // Duplicate GETs/heartbeats do not renew the unique-result clock.
        double reported=VoiceSepFrame.finiteNonnegative(next.resultAgeMs) ? next.resultAgeMs : Double.POSITIVE_INFINITY;
        double transit=receivedAt-requestedAt + (VoiceSepFrame.finiteNonnegative(next.fetchMs) ? next.fetchMs : 0);
        double newAge=reported+transit;
        if (duplicate && responseAt >= 0) newAge=Math.max(newAge, ageAtResponse + receivedAt-responseAt);
        frame=next; connected=true; responseAt=receivedAt; ageAtResponse=newAge;
        periodMs=Double.isFinite(next.configuredPeriodMs) && next.configuredPeriodMs>=5 && next.configuredPeriodMs<=5000
                ? next.configuredPeriodMs : fallbackPeriodMs;
        reason=next.reason == null ? "unknown" : next.reason;
    }
    public synchronized void error(String detail) { connected=false; reason=detail; }
    public synchronized View view(long now) {
        double age=responseAt < 0 ? Double.POSITIVE_INFINITY : ageAtResponse + Math.max(0,now-responseAt);
        age=Math.max(age, uniqueAt<0 ? Double.POSITIVE_INFINITY : now-uniqueAt);
        boolean link=connected && responseAt>=0 && now>=responseAt && now-responseAt<=Math.max(2000,5*periodMs);
        boolean current=link && frame != null && frame.valid && frame.active && frame.upstreamOk &&
                age<=Math.max(300,3*periodMs) && frame.inputAgeMs<=Math.max(1000,3*periodMs);
        String detail=reason;
        if (connected && !link) detail="接口响应已过期";
        else if (link && frame != null && frame.valid && !current) detail="角度已过期，已清除显示";
        else if (current) detail="有效声源方向（只读）";
        return new View(link,current,frame,periodMs,age,detail,unique,duplicates,rejected);
    }
    public synchronized long pollDelayMs() { return Math.max(50,Math.min(500,Math.round(periodMs/2))); }
}
