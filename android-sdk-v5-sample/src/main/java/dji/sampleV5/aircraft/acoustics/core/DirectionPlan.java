package dji.sampleV5.aircraft.acoustics.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Editable UI plan. Choices are movement directions; serialization remains target-to-aircraft bearings. */
public final class DirectionPlan {
    public enum Movement {
        HOVER("悬停", null), NORTH("向北", 180.0), NORTH_EAST("向东北", 225.0),
        EAST("向东", 270.0), SOUTH_EAST("向东南", 315.0), SOUTH("向南", 0.0),
        SOUTH_WEST("向西南", 45.0), WEST("向西", 90.0), NORTH_WEST("向西北", 135.0);
        public final String label;
        public final Double bearing;
        Movement(String label, Double bearing) { this.label = label; this.bearing = bearing; }
        @Override public String toString() { return label; }
    }
    public static final class Step {
        public final Double bearing;
        public final int samples;
        private Step(Double bearing, int samples) { this.bearing = bearing; this.samples = samples; }
        public String label() {
            for (Movement option : Movement.values()) if (Objects.equals(option.bearing, bearing)) return option.label;
            return "移动方位 " + number(DirectionQueue.normalize(bearing + 180)) + "°（导入偏离角 " + number(bearing) + "°）";
        }
        public String token() { return (bearing == null ? "WAIT" : number(bearing)) + "*" + samples; }
    }
    private final List<Step> steps = new ArrayList<>();
    public static DirectionPlan fromText(String text) {
        DirectionQueue queue = DirectionQueue.parse(text);
        DirectionPlan result = new DirectionPlan();
        while (queue.hasNext()) {
            Double bearing = queue.next();
            int last = result.steps.size() - 1;
            if (last >= 0 && Objects.equals(result.steps.get(last).bearing, bearing)) {
                Step before = result.steps.get(last);
                result.steps.set(last, new Step(bearing, before.samples + 1));
            } else result.steps.add(new Step(bearing, 1));
        }
        return result;
    }
    public List<Step> steps() { return Collections.unmodifiableList(new ArrayList<>(steps)); }
    public int totalSamples() { int total = 0; for (Step step : steps) total += step.samples; return total; }
    public void add(Movement movement, int samples) {
        if (movement == null) throw new IllegalArgumentException("请选择方向");
        validateCount(totalSamples() + samples, samples);
        steps.add(new Step(movement.bearing, samples));
    }
    public void changeDuration(int index, int samples) {
        Step old = steps.get(index);
        validateCount(totalSamples() - old.samples + samples, samples);
        steps.set(index, new Step(old.bearing, samples));
    }
    public void remove(int index) { steps.remove(index); }
    public void move(int index, int offset) {
        if (offset != -1 && offset != 1) throw new IllegalArgumentException("每次只能移动一行");
        if (index < 0 || index >= steps.size() || index + offset < 0 || index + offset >= steps.size())
            throw new IllegalArgumentException("已到队列边界");
        Collections.swap(steps, index, index + offset);
    }
    public void clear() { steps.clear(); }
    public String toText() {
        StringBuilder result = new StringBuilder();
        for (Step step : steps) { if (result.length() > 0) result.append(','); result.append(step.token()); }
        return result.toString();
    }
    private static void validateCount(int total, int samples) {
        if (samples < 1 || samples > 120 || total > 120)
            throw new IllegalArgumentException("每段至少 0.5 秒，整个队列最多 60 秒 / 120 项");
    }
    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long)value) : Double.toString(value);
    }
}
