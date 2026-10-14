package dji.sampleV5.aircraft.acoustics.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A finite, 2 Hz source of target-to-aircraft bearings. WAIT is no measurement. */
public final class DirectionQueue {
    public static final long PERIOD_MS = 500;
    private final List<Double> bearings;
    private int index;

    private DirectionQueue(List<Double> bearings) {
        this.bearings = Collections.unmodifiableList(bearings);
    }

    /** Examples: "180, 170, WAIT, -90" or "180*40". Never loops implicitly. */
    public static DirectionQueue parse(String text) {
        if (text == null || text.trim().isEmpty() || text.length() > 4096)
            throw new IllegalArgumentException("方向队列不能为空且文本不能超过 4096 字符");
        List<Double> result = new ArrayList<>();
        for (String token : text.trim().split("[\\s,，;；]+")) {
            String[] parts = token.split("\\*", -1);
            if (parts.length > 2) throw new IllegalArgumentException("无效方向: " + token);
            int count = parts.length == 2 ? Integer.parseInt(parts[1]) : 1;
            if (count < 1 || count > 120 || result.size() + count > 120)
                throw new IllegalArgumentException("队列必须包含 1–120 个采样（最多 60 秒）");
            Double angle = null;
            if (!parts[0].toUpperCase(Locale.ROOT).equals("WAIT")) {
                double value = Double.parseDouble(parts[0]);
                if (!Double.isFinite(value)) throw new IllegalArgumentException("角度必须是有限数值");
                angle = normalize(value);
            }
            for (int i = 0; i < count; i++) result.add(angle);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("方向队列不能为空");
        return new DirectionQueue(result);
    }

    public static double normalize(double angle) { return ((angle % 360) + 360) % 360; }
    public boolean hasNext() { return index < bearings.size(); }
    public Double next() {
        if (!hasNext()) throw new IllegalStateException("方向队列已结束");
        return bearings.get(index++);
    }
    public int size() { return bearings.size(); }
    public int consumed() { return index; }
    public int movingSamples() { int count = 0; for (Double angle : bearings) if (angle != null) count++; return count; }
}
