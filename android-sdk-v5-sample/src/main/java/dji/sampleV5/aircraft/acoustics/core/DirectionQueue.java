package dji.sampleV5.aircraft.acoustics.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/** Finite bearings. The legacy 500 ms default is preserved; array replay explicitly selects 100 ms. */
public final class DirectionQueue {
    public static final long PERIOD_MS = 500;
    public static final long ARRAY_PERIOD_MS = 100;
    private final List<Double> bearings;
    private final long periodMs;
    private int index;
    private int skipped;

    private DirectionQueue(List<Double> bearings, long periodMs) {
        this.bearings = Collections.unmodifiableList(bearings);
        this.periodMs = periodMs;
    }

    /** Examples: "180, 170, WAIT, -90" or "180*40". Never loops implicitly. */
    public static DirectionQueue parse(String text) {
        return parse(text, PERIOD_MS);
    }

    public static DirectionQueue parse(String text, long periodMs) {
        if (periodMs != PERIOD_MS && periodMs != ARRAY_PERIOD_MS)
            throw new IllegalArgumentException("采样周期必须是 100 ms 或 500 ms");
        int maxSamples = (int) (60000 / periodMs);
        if (text == null || text.trim().isEmpty() || text.length() > 4096)
            throw new IllegalArgumentException("方向队列不能为空且文本不能超过 4096 字符");
        List<Double> result = new ArrayList<>();
        for (String token : text.trim().split("[\\s,，;；]+")) {
            String[] parts = token.split("\\*", -1);
            if (parts.length > 2) throw new IllegalArgumentException("无效方向: " + token);
            int count = parts.length == 2 ? Integer.parseInt(parts[1]) : 1;
            if (count < 1 || count > maxSamples || result.size() + count > maxSamples)
                throw new IllegalArgumentException("此周期最多 " + maxSamples + " 个采样（最多 60 秒）");
            Double angle = null;
            if (!parts[0].toUpperCase(Locale.ROOT).equals("WAIT")) {
                double value = Double.parseDouble(parts[0]);
                if (!Double.isFinite(value)) throw new IllegalArgumentException("角度必须是有限数值");
                angle = normalize(value);
            }
            for (int i = 0; i < count; i++) result.add(angle);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("方向队列不能为空");
        return new DirectionQueue(result, periodMs);
    }

    public DirectionQueue mapBearings(DoubleUnaryOperator converter) {
        if (index != 0) throw new IllegalStateException("已开始的队列不能重新标定");
        List<Double> result = new ArrayList<>();
        for (Double value : bearings) {
            Double converted = value == null ? null : converter.applyAsDouble(value);
            if (converted != null && !Double.isFinite(converted)) throw new IllegalArgumentException("角度换算无效");
            result.add(converted == null ? null : normalize(converted));
        }
        return new DirectionQueue(result, periodMs);
    }

    /** Drop missed replay samples; never emit a burst of historical velocity commands. */
    public void discardBefore(int sample) {
        if (sample < index || sample > bearings.size()) throw new IllegalArgumentException("无效的回放位置");
        skipped += sample - index; index = sample;
    }

    public static double normalize(double angle) { return ((angle % 360) + 360) % 360; }
    public boolean hasNext() { return index < bearings.size(); }
    public Double next() {
        if (!hasNext()) throw new IllegalStateException("方向队列已结束");
        return bearings.get(index++);
    }
    public int size() { return bearings.size(); }
    public int consumed() { return index; }
    public int skipped() { return skipped; }
    public long periodMs() { return periodMs; }
    public long durationMs() { return periodMs * size(); }
    public int movingSamples() { int count = 0; for (Double angle : bearings) if (angle != null) count++; return count; }
}
