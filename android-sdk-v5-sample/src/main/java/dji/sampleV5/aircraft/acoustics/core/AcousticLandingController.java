package dji.sampleV5.aircraft.acoustics.core;

/** Finite, open-loop direction experiment. No automatic arrival or automatic landing decision. */
public final class AcousticLandingController {
    public enum State { IDLE, RUNNING, HOVERING, LANDING_REQUESTED, LANDING, COMPLETE, ABORTED }
    public static final long MAX_TICK_GAP_MS = 350, TELEMETRY_STALE_MS = 750, HOVER_DWELL_MS = 2000;
    public static final double MAX_PATH = 3.0, MIN_ALTITUDE = 2.0, MAX_ALTITUDE = 8.0;
    public static final double MAX_ALTITUDE_DRIFT = 0.5, STATIONARY_SPEED = 0.2;

    public static final class Config {
        public final double speed;
        public Config(double speed) {
            if (!Double.isFinite(speed) || speed < 0.05 || speed > 0.3)
                throw new IllegalArgumentException("水平速度必须为 0.05–0.3 m/s；首次建议 0.1");
            this.speed = speed;
        }
    }

    public static final class Telemetry {
        public final double altitude, yaw, horizontalSpeed, verticalSpeed;
        public final boolean connected, flying, motorsOn, positioning, modeAllowed;
        public final int battery;
        public final long timestamp;
        public Telemetry(double altitude, double yaw, double horizontalSpeed, double verticalSpeed,
                         boolean connected, boolean flying, boolean motorsOn, boolean positioning,
                         boolean modeAllowed, int battery, long timestamp) {
            this.altitude = altitude; this.yaw = yaw; this.horizontalSpeed = horizontalSpeed;
            this.verticalSpeed = verticalSpeed; this.connected = connected; this.flying = flying;
            this.motorsOn = motorsOn; this.positioning = positioning; this.modeAllowed = modeAllowed;
            this.battery = battery; this.timestamp = timestamp;
        }
    }

    public static final class Command {
        public static final Command ZERO = new Command(0, 0);
        public final double north, east;
        public Command(double north, double east) {
            if (!Double.isFinite(north) || !Double.isFinite(east))
                throw new IllegalArgumentException("速度指令必须为有限数值");
            this.north = north; this.east = east;
        }
        public double magnitude() { return Math.hypot(north, east); }
        // DJI BODY / VELOCITY: roll is forward (X), pitch is right (Y).
        public double forward(double yaw) {
            double a = Math.toRadians(yaw);
            return north * Math.cos(a) + east * Math.sin(a);
        }
        public double right(double yaw) {
            double a = Math.toRadians(yaw);
            return -north * Math.sin(a) + east * Math.cos(a);
        }
    }

    public final Config config;
    private final DirectionQueue queue;
    private State state = State.IDLE;
    private String reason = "尚未控制无人机";
    private long startedAt, lastTick, stationarySince = -1;
    private double initialAltitude, commandedPath;
    private Double bearing;
    private Command lastCommand = Command.ZERO;

    public AcousticLandingController(Config config, DirectionQueue queue) {
        if (config == null || queue == null || queue.consumed() != 0)
            throw new IllegalArgumentException("每次实验必须使用新的队列");
        if (queue.movingSamples() * DirectionQueue.PERIOD_MS / 1000.0 * config.speed > MAX_PATH + 1e-9)
            throw new IllegalArgumentException("预计水平指令路程超过 3 米，请缩短队列或降低速度");
        this.config = config; this.queue = queue;
    }
    public State state() { return state; }
    public String reason() { return reason; }
    public Double bearing() { return bearing; }
    public int consumed() { return queue.consumed(); }
    public int total() { return queue.size(); }
    public double initialAltitude() { return initialAltitude; }
    public double commandedPath() { return commandedPath; }
    public boolean controlling() { return state == State.RUNNING || state == State.HOVERING; }

    public void start(long now, Telemetry t) {
        if (state != State.IDLE) throw new IllegalStateException("实验不可续播，请重新开始");
        String issue = movementIssue(now, t);
        if (issue != null) throw new IllegalStateException(issue);
        if (t.battery < 30) throw new IllegalStateException("启动要求电量至少 30%");
        if (!stationary(t)) throw new IllegalStateException("请先手动稳定悬停，水平/垂直速度须不超过 0.2 m/s");
        startedAt = lastTick = now;
        initialAltitude = t.altitude;
        state = State.RUNNING;
        reason = "正在执行有限方向序列；保持高度";
    }

    public Command tick(long now, Telemetry t) {
        if (state == State.IDLE || state == State.ABORTED || state == State.COMPLETE) return Command.ZERO;
        if (now < lastTick || now - lastTick > MAX_TICK_GAP_MS) return fail("控制循环超时或时钟异常");
        if (state == State.LANDING_REQUESTED || state == State.LANDING) {
            if (!fresh(now, t)) return fail("降落时连接或遥测异常，请遥控器接管");
            if (state == State.LANDING && !t.flying && !t.motorsOn) {
                state = State.COMPLETE; reason = "遥测确认已落地且电机停止";
            }
            lastTick = now;
            return Command.ZERO;
        }
        String issue = movementIssue(now, t);
        if (issue != null) return fail(issue);
        if (Math.abs(t.altitude - initialAltitude) > MAX_ALTITUDE_DRIFT)
            return fail("相对启动高度变化超过 0.5 米，请遥控器接管");
        commandedPath += lastCommand.magnitude() * (now - lastTick) / 1000.0;
        if (commandedPath > MAX_PATH + 0.1) return fail("累计指令路程超限");
        lastTick = now;
        long slot = (now - startedAt) / DirectionQueue.PERIOD_MS;
        if (state == State.RUNNING && slot >= queue.size()) {
            state = State.HOVERING; bearing = null;
            reason = "队列结束，持续零速度悬停；等待人工决定降落或交还遥控器";
        }
        if (state == State.HOVERING) {
            if (stationary(t)) { if (stationarySince < 0) stationarySince = now; }
            else stationarySince = -1;
            return lastCommand = Command.ZERO;
        }
        if (slot >= queue.consumed()) {
            if (slot != queue.consumed()) return fail("方向调度跳过采样，禁止追赶或续播");
            bearing = queue.next();
        }
        if (bearing == null) return lastCommand = Command.ZERO;
        // No smoothing: a reversal must never retain the previous direction.
        double angle = Math.toRadians(bearing);
        return lastCommand = new Command(-Math.cos(angle) * config.speed, -Math.sin(angle) * config.speed);
    }

    public boolean canRequestLanding(long now) {
        return state == State.HOVERING && stationarySince >= 0 && now >= lastTick &&
                now - lastTick <= MAX_TICK_GAP_MS && now - stationarySince >= HOVER_DWELL_MS;
    }
    /** Called only from the separate, explicitly confirmed landing button. */
    public void requestLanding(long now, Telemetry t) {
        if (!canRequestLanding(now)) throw new IllegalStateException("只能在队列结束并稳定悬停 2 秒后申请降落");
        String issue = movementIssue(now, t);
        if (issue != null || !stationary(t) || Math.abs(t.altitude - initialAltitude) > MAX_ALTITUDE_DRIFT)
            throw new IllegalStateException(issue == null ? "当前高度或速度不满足降落交接条件" : issue);
        state = State.LANDING_REQUESTED; lastCommand = Command.ZERO;
        reason = "操作员已确认降落，正在交接控制权";
    }
    public void landingAccepted() {
        if (state == State.LANDING_REQUESTED) { state = State.LANDING; reason = "DJI 已接受人工降落请求"; }
    }
    public void abort(String detail) {
        if (state == State.COMPLETE) return;
        state = State.ABORTED; reason = detail; bearing = null; lastCommand = Command.ZERO;
    }
    private Command fail(String detail) { abort(detail); return Command.ZERO; }
    public static boolean fresh(long now, Telemetry t) {
        return t != null && t.connected && t.timestamp >= 0 && t.timestamp <= now &&
                now - t.timestamp <= TELEMETRY_STALE_MS && Double.isFinite(t.altitude) &&
                Double.isFinite(t.yaw) && Math.abs(t.yaw) <= 180 && Double.isFinite(t.horizontalSpeed) && Double.isFinite(t.verticalSpeed);
    }
    public static boolean stationary(Telemetry t) {
        return t != null && t.horizontalSpeed >= 0 && t.horizontalSpeed <= STATIONARY_SPEED &&
                Math.abs(t.verticalSpeed) <= STATIONARY_SPEED;
    }
    public static String movementIssue(long now, Telemetry t) {
        if (!fresh(now, t)) return "连接丢失或遥测无效/超过 750 ms 未更新";
        if (!t.flying || !t.motorsOn) return "请先手动起飞；飞行状态或电机状态不满足要求";
        if (!t.positioning) return "没有可用 GPS/下视视觉定位，禁止无定位飞行实验";
        if (!t.modeAllowed) return "当前飞行模式不允许接管，请使用正常模式并退出其他自动任务";
        if (t.battery < 20 || t.battery > 100) return "电量低于 20% 或电量无效，请遥控器接管";
        if (t.altitude < MIN_ALTITUDE || t.altitude > MAX_ALTITUDE) return "相对起飞点高度必须在 2–8 米范围";
        if (t.horizontalSpeed < 0 || t.horizontalSpeed > 1.0 || Math.abs(t.verticalSpeed) > 0.5)
            return "实际飞行速度超出实验限制，请遥控器接管";
        return null;
    }
}
