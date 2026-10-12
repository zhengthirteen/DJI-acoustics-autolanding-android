package dji.sampleV5.aircraft.acoustics.core;

/** Pure control logic. Time is monotonic milliseconds; positions are local metres north/east. */
public final class AcousticLandingController {
    public enum State { IDLE, ALIGNING, HOLDING, LANDING_REQUESTED, LANDING, COMPLETE, ABORTED }
    public enum ArrivalMode { POSITION, OPERATOR }

    public static final class Config {
        public final ArrivalMode arrivalMode;
        public final double speed, arrivalRadius;
        public final long arrivalHoldMs, timeoutMs, staleMs;
        public final double maxRadius, maxCommandedPath, minAltitude, maxAltitude;

        public Config(ArrivalMode mode, double speed, double arrivalRadius) {
            if (mode == null || !Double.isFinite(speed) || speed < 0.05 || speed > 0.5 ||
                    !Double.isFinite(arrivalRadius) || arrivalRadius < 0.5 || arrivalRadius > 3)
                throw new IllegalArgumentException("速度范围 0.05–0.5 m/s；到达半径范围 0.5–3 m");
            this.arrivalMode = mode;
            this.speed = speed;
            this.arrivalRadius = arrivalRadius;
            arrivalHoldMs = 2000;
            timeoutMs = 120000;
            staleMs = 1500;
            maxRadius = 30;
            maxCommandedPath = 15;
            minAltitude = 2;
            maxAltitude = 15;
        }
    }

    public static final class Telemetry {
        public final double north, east, altitude, yaw;
        public final boolean connected, flying, positionValid;
        public final long timestamp;
        public Telemetry(double north, double east, double altitude, double yaw,
                         boolean connected, boolean flying, boolean positionValid, long timestamp) {
            this.north = north; this.east = east; this.altitude = altitude; this.yaw = yaw;
            this.connected = connected; this.flying = flying; this.positionValid = positionValid;
            this.timestamp = timestamp;
        }
    }

    public static final class Command {
        public static final Command ZERO = new Command(0, 0);
        public final double north, east;
        public Command(double north, double east) { this.north = north; this.east = east; }
        public double magnitude() { return Math.hypot(north, east); }
        // DJI BODY +roll is forward, +pitch is right in VELOCITY mode.
        public double forward(double yaw) {
            double radians = Math.toRadians(yaw);
            return north * Math.cos(radians) + east * Math.sin(radians);
        }
        public double right(double yaw) {
            double radians = Math.toRadians(yaw);
            return -north * Math.sin(radians) + east * Math.cos(radians);
        }
    }

    public final Config config;
    private State state = State.IDLE;
    private String reason = "尚未开始";
    private long startedAt, lastTick, lastSignal = -1, insideSince = -1;
    private long lastMeasurement = -1;
    private double filteredNorth, filteredEast, commandedPath;
    private boolean hasDirection, filterInitialized, operatorArrival;

    public AcousticLandingController(Config config) { this.config = config; }
    public State state() { return state; }
    public String reason() { return reason; }
    public double commandedPath() { return commandedPath; }
    public boolean isNavigating() { return state == State.ALIGNING || state == State.HOLDING; }

    public void start(long now) {
        if (state != State.IDLE) throw new IllegalStateException("请为每次实验新建控制器");
        startedAt = lastTick = now;
        state = State.ALIGNING;
        reason = "接收方向，保持高度";
    }

    /** Null is an explicit missing measurement; it commands hover immediately. */
    public void acceptBearing(Double targetToAircraftDegrees, long capturedAt, long now) {
        if (!isNavigating()) return;
        if (capturedAt > now || now - capturedAt > config.staleMs || capturedAt <= lastMeasurement) return;
        lastMeasurement = capturedAt;
        lastSignal = capturedAt;
        if (targetToAircraftDegrees == null) {
            hasDirection = false;
            filterInitialized = false;
            return;
        }
        if (!Double.isFinite(targetToAircraftDegrees)) { abort("方向数据无效"); return; }
        double radians = Math.toRadians(DirectionQueue.normalize(targetToAircraftDegrees));
        // Move towards the target: opposite to the measured deviation.
        double north = -Math.cos(radians), east = -Math.sin(radians);
        if (!filterInitialized) { filteredNorth = north; filteredEast = east; }
        else {
            filteredNorth = 0.65 * filteredNorth + 0.35 * north;
            filteredEast = 0.65 * filteredEast + 0.35 * east;
        }
        filterInitialized = hasDirection = true;
    }

    public void confirmOperatorArrival() {
        if (config.arrivalMode != ArrivalMode.OPERATOR || !isNavigating())
            throw new IllegalStateException("仅人工到达模式允许确认");
        operatorArrival = true;
    }

    public Command tick(long now, Telemetry telemetry) {
        if (!isNavigating()) return Command.ZERO;
        if (now < lastTick) { abort("控制时钟异常"); return Command.ZERO; }
        if (now - lastTick > 500) { abort("控制循环超过 500 ms 未执行"); return Command.ZERO; }
        if (now - startedAt > config.timeoutMs) { abort("实验超过 120 秒"); return Command.ZERO; }
        if (!validTelemetry(now, telemetry)) return Command.ZERO;
        if ((lastSignal < 0 && now - startedAt > config.staleMs) ||
                (lastSignal >= 0 && now - lastSignal > config.staleMs)) {
            abort("方向输入超过 1.5 秒未更新"); return Command.ZERO;
        }
        double distance = Math.hypot(telemetry.north, telemetry.east);
        if (config.arrivalMode == ArrivalMode.POSITION && distance > config.maxRadius) {
            abort("超出目标 30 米范围"); return Command.ZERO;
        }
        boolean arrived = config.arrivalMode == ArrivalMode.POSITION
                ? distance <= config.arrivalRadius : operatorArrival;
        if (arrived) {
            if (insideSince < 0) insideSince = now;
            state = State.HOLDING;
            reason = "已满足到达判据，悬停确认 2 秒";
            if (now - insideSince >= config.arrivalHoldMs) {
                state = State.LANDING_REQUESTED;
                reason = "到达确认完成，申请自动降落";
            }
            lastTick = now;
            return Command.ZERO;
        }
        insideSince = -1;
        state = State.ALIGNING;
        reason = hasDirection ? "按偏离方向反向修正，保持高度" : "没有方向，悬停";
        double norm = Math.hypot(filteredNorth, filteredEast);
        Command command = !hasDirection || norm < 0.05 ? Command.ZERO :
                new Command(config.speed * filteredNorth / norm, config.speed * filteredEast / norm);
        // A stalled control loop must not resume movement after a long scheduling gap.
        commandedPath += command.magnitude() * (now - lastTick) / 1000.0;
        lastTick = now;
        if (commandedPath > config.maxCommandedPath) {
            abort("累计指令路程超过 15 米"); return Command.ZERO;
        }
        return command;
    }

    private boolean validTelemetry(long now, Telemetry t) {
        if (t == null || t.timestamp > now || now - t.timestamp > config.staleMs || !t.connected) {
            abort("连接丢失或遥测超过 1.5 秒未更新"); return false;
        }
        if (!t.flying) { abort("无人机未处于飞行状态"); return false; }
        if (!Double.isFinite(t.yaw) || !Double.isFinite(t.altitude) ||
                t.altitude < config.minAltitude || t.altitude > config.maxAltitude) {
            abort("航向无效或相对起飞点高度不在 2–15 米范围"); return false;
        }
        if (config.arrivalMode == ArrivalMode.POSITION &&
                (!t.positionValid || !Double.isFinite(t.north) || !Double.isFinite(t.east))) {
            abort("定位无效或 GPS 信号不足"); return false;
        }
        return true;
    }

    public void landingAccepted() {
        if (state == State.LANDING_REQUESTED) { state = State.LANDING; reason = "DJI 已接受降落请求"; }
    }
    public void landed() {
        if (state == State.LANDING) { state = State.COMPLETE; reason = "飞行状态确认已着陆"; }
    }
    public void abort(String detail) { state = State.ABORTED; reason = detail; }

    /** Small-area WGS84 approximation, adequate for the 30 m experiment boundary. */
    public static double[] relativePosition(double latitude, double longitude,
                                            double targetLatitude, double targetLongitude) {
        if (!validCoordinate(latitude, longitude) || !validCoordinate(targetLatitude, targetLongitude))
            throw new IllegalArgumentException("经纬度无效");
        return new double[] {Math.toRadians(latitude - targetLatitude) * 6378137,
                Math.toRadians(longitude - targetLongitude) * 6378137 * Math.cos(Math.toRadians(targetLatitude))};
    }
    public static boolean validCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude) &&
                latitude >= -85 && latitude <= 85 && longitude >= -180 && longitude <= 180;
    }
}
