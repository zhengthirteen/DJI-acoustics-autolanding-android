package dji.sampleV5.aircraft.acoustics.core;

/** Horizontal array-to-aircraft azimuth. No distance or target-offset estimator is implied. */
public final class ArrayAngleCalibration {
    public final double zeroNorthClockwiseDegrees;
    public final boolean clockwise, targetAtArray;

    public ArrayAngleCalibration(double zeroNorthClockwiseDegrees, boolean clockwise, boolean targetAtArray) {
        if (!Double.isFinite(zeroNorthClockwiseDegrees)) throw new IllegalArgumentException("阵列零点朝向必须是有限角度");
        this.zeroNorthClockwiseDegrees = DirectionQueue.normalize(zeroNorthClockwiseDegrees);
        this.clockwise = clockwise; this.targetAtArray = targetAtArray;
    }
    public double toArrayNorthBearing(double rawDegrees) {
        if (!Double.isFinite(rawDegrees)) throw new IllegalArgumentException("阵列角度无效");
        // Normalize first so very large finite input cannot overflow during rotation.
        double angle = DirectionQueue.normalize(rawDegrees);
        return DirectionQueue.normalize(zeroNorthClockwiseDegrees + (clockwise ? angle : -angle));
    }
    public void requireTargetAtArray() {
        if (!targetAtArray) throw new IllegalStateException("阵列中心与目标的水平位置不重合；仅方位角不能换算目标方向，需另接位置估计器");
    }
    public double toTargetBearing(double rawDegrees) {
        requireTargetAtArray();
        return toArrayNorthBearing(rawDegrees);
    }
    public DirectionQueue replay(String rawAngles) {
        requireTargetAtArray();
        return DirectionQueue.parse(rawAngles, DirectionQueue.ARRAY_PERIOD_MS).mapBearings(this::toTargetBearing);
    }
}
