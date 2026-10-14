import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController;
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController.*;
import dji.sampleV5.aircraft.acoustics.core.DirectionQueue;
import dji.sampleV5.aircraft.acoustics.core.SessionWatchdog;

/** Deterministic JVM scenarios. No SDK, radio or aircraft is connected. */
public final class AcousticControllerTest {
    private static int scenarios;
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    private static void near(double actual, double expected, String detail) { check(Math.abs(actual - expected) < 1e-8, detail); }
    private static void reject(Runnable task) {
        boolean rejected = false;
        try { task.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        check(rejected, "Unsafe operation accepted");
    }
    private static Telemetry t(long now) { return telemetry(now, 4, 0, 0, 0, true, true, true, true, true, 90); }
    private static Telemetry telemetry(long now, double altitude, double yaw, double horizontal, double vertical,
                                       boolean connected, boolean flying, boolean motors, boolean positioning, boolean mode, int battery) {
        return new Telemetry(altitude, yaw, horizontal, vertical, connected, flying, motors, positioning, mode, battery, now);
    }
    private static AcousticLandingController create(String queue, double speed) {
        return new AcousticLandingController(new Config(speed), DirectionQueue.parse(queue));
    }
    private static AcousticLandingController start(String queue) {
        AcousticLandingController c = create(queue, 0.1); c.start(0, t(0)); return c;
    }
    private static void tickUntil(AcousticLandingController c, long from, long through) {
        for (long now = from; now <= through; now += 100) c.tick(now, t(now));
    }
    private static void invalidTelemetry(Telemetry bad, String label) {
        AcousticLandingController c = start("180*10");
        c.tick(0, t(0));
        check(c.tick(100, bad).magnitude() == 0 && c.state() == State.ABORTED, label);
        check(c.tick(200, t(200)).magnitude() == 0 && c.state() == State.ABORTED, "Fault resumed automatically");
        scenarios++;
    }
    public static void main(String[] args) {
        DirectionQueue q = DirectionQueue.parse("180*2, -90;720，WAIT*2");
        check(q.size() == 6 && q.movingSamples() == 4 && q.next() == 180 && q.next() == 180 &&
                q.next() == 270 && q.next() == 0 && q.next() == null && q.next() == null && !q.hasNext(), "Queue parser");
        for (String bad : new String[]{"", ",,,", "NaN", "Infinity", "180*0", "180*-1", "180*121", "WAIT*120,0", "0**2", "0*", "WAI"})
            reject(() -> DirectionQueue.parse(bad));
        reject(q::next); scenarios++;
        reject(() -> new Config(Double.NaN)); reject(() -> new Config(Double.POSITIVE_INFINITY));
        reject(() -> new Config(0.01)); reject(() -> new Config(0.31));
        reject(() -> create("180*61", 0.1)); reject(() -> create("180*21", 0.3));
        create("180*20", 0.3); create("WAIT*120", 0.3); scenarios++;

        AcousticLandingController c = start("180*2");
        for (long now = 0; now < 1000; now += 100) near(c.tick(now, t(now)).north, 0.1, "Final sample duration");
        near(c.tick(1000, t(1000)).magnitude(), 0, "Last command continued after queue end");
        check(c.state() == State.HOVERING && c.consumed() == 2, "Queue end must enter hover");
        tickUntil(c, 1100, 180000); // Neither the old 120-second timeout nor hover duration may trigger landing.
        check(c.state() == State.HOVERING, "Unrequested landing after extended hover");
        near(c.commandedPath(), 0.1, "Commanded path integral"); scenarios++;

        c = start("180,0,90,270,WAIT");
        double[][] expected = {{0.1,0},{-0.1,0},{0,-0.1},{0,0.1},{0,0}};
        for (long now = 0; now < 2500; now += 100) {
            Command out = c.tick(now, t(now)); int index = (int)(now / 500);
            near(out.north, expected[index][0], "Direction reversal retained old direction");
            near(out.east, expected[index][1], "East/west bearing polarity");
        }
        near(c.tick(2500, t(2500)).magnitude(), 0, "Queue ending with WAIT"); scenarios++;

        Command north = new Command(0.1, 0);
        near(north.forward(0), 0.1, "Body forward at north heading"); near(north.right(0), 0, "Body right at north heading");
        near(north.forward(90), 0, "Body forward at east heading"); near(north.right(90), -0.1, "Body left at east heading");
        near(north.forward(-90), 0, "Body forward at west heading"); near(north.right(-90), 0.1, "Body right at west heading");
        reject(() -> new Command(Double.NaN, 0)); scenarios++;

        AcousticLandingController landing = start("WAIT");
        reject(() -> landing.requestLanding(0, t(0)));
        tickUntil(landing, 0, 2400);
        check(!landing.canRequestLanding(2400), "Landing allowed before 2 seconds of hover");
        reject(() -> landing.requestLanding(2400, t(2400)));
        landing.tick(2500, t(2500));
        check(landing.canRequestLanding(2500) && landing.state() == State.HOVERING, "Manual landing availability");
        landing.requestLanding(2500, t(2500));
        check(landing.state() == State.LANDING_REQUESTED, "Explicit landing request missing");
        landing.landingAccepted();
        landing.tick(2600, telemetry(2600, 0.1, 0, 0, 0, true, false, true, true, false, 90));
        check(landing.state() == State.LANDING, "Complete before motors stopped");
        landing.tick(2700, telemetry(2700, 0, 0, 0, 0, true, false, false, true, false, 90));
        check(landing.state() == State.COMPLETE, "Landing completion telemetry"); scenarios++;

        c = start("WAIT"); tickUntil(c, 0, 2000);
        c.tick(2100, telemetry(2100, 4, 0, 0.25, 0, true, true, true, true, true, 90));
        tickUntil(c, 2200, 4100); check(!c.canRequestLanding(4100), "Stationary dwell did not reset");
        c.tick(4200, t(4200)); check(c.canRequestLanding(4200), "Stationary dwell did not complete"); scenarios++;

        for (long abortAt : new long[]{0, 100, 500, 1000, 2500}) {
            c = start("180*2"); tickUntil(c, 0, abortAt);
            c.abort("emergency"); c.landingAccepted();
            for (long now = abortAt + 100; now < abortAt + 2000; now += 100)
                near(c.tick(now, t(now)).magnitude(), 0, "Emergency stop resumed queue");
            AcousticLandingController stopped = c;
            reject(() -> stopped.requestLanding(abortAt + 2000, t(abortAt + 2000)));
            check(c.state() == State.ABORTED, "Late accepted callback revived stopped controller");
        }
        scenarios++;
        c = start("WAIT"); tickUntil(c, 0, 2500); c.requestLanding(2500, t(2500));
        c.abort("stop during landing transfer"); c.landingAccepted();
        check(c.state() == State.ABORTED, "Stopped landing transfer revived"); scenarios++;

        invalidTelemetry(telemetry(100,4,0,0,0,false,true,true,true,true,90), "Disconnect");
        invalidTelemetry(telemetry(100,4,0,0,0,true,false,true,true,true,90), "Not flying");
        invalidTelemetry(telemetry(100,4,0,0,0,true,true,false,true,true,90), "Motors off");
        invalidTelemetry(telemetry(100,4,0,0,0,true,true,true,false,true,90), "No horizontal positioning");
        invalidTelemetry(telemetry(100,4,0,0,0,true,true,true,true,false,90), "Disallowed/ATTI mode");
        invalidTelemetry(telemetry(100,4,0,0,0,true,true,true,true,true,19), "Low battery");
        invalidTelemetry(telemetry(100,4,0,0,0,true,true,true,true,true,-1), "Unknown battery");
        invalidTelemetry(telemetry(100,4,0,0,0,true,true,true,true,true,101), "Invalid battery percentage");
        invalidTelemetry(telemetry(100,1.9,0,0,0,true,true,true,true,true,90), "Altitude below lower bound");
        invalidTelemetry(telemetry(100,8.1,0,0,0,true,true,true,true,true,90), "Altitude above upper bound");
        invalidTelemetry(telemetry(100,4.51,0,0,0,true,true,true,true,true,90), "Altitude drift");
        invalidTelemetry(telemetry(100,4,Double.NaN,0,0,true,true,true,true,true,90), "Invalid yaw");
        invalidTelemetry(telemetry(100,4,181,0,0,true,true,true,true,true,90), "Yaw out of range");
        invalidTelemetry(telemetry(100,4,0,1.01,0,true,true,true,true,true,90), "Actual horizontal overspeed");
        invalidTelemetry(telemetry(100,4,0,0,0.51,true,true,true,true,true,90), "Actual vertical overspeed");
        invalidTelemetry(telemetry(101,4,0,0,0,true,true,true,true,true,90), "Future telemetry");
        invalidTelemetry(null, "Missing telemetry");

        c = start("180*10"); c.tick(0, t(0)); c.tick(351, t(351));
        check(c.state() == State.ABORTED, "Control-loop stall"); scenarios++;
        c = start("180*10"); c.tick(100, t(100)); c.tick(99, t(99));
        check(c.state() == State.ABORTED, "Clock reversal"); scenarios++;
        c = start("180*10"); for(long now = 0; now <= 800; now += 100) c.tick(now, t(0));
        check(c.state() == State.ABORTED, "Stale telemetry"); scenarios++;
        AcousticLandingController lowBattery = create("WAIT", 0.1);
        reject(() -> lowBattery.start(0, telemetry(0,4,0,0,0,true,true,true,true,true,29)));
        AcousticLandingController moving = create("WAIT", 0.1);
        reject(() -> moving.start(0, telemetry(0,4,0,0.21,0,true,true,true,true,true,90))); scenarios++;

        SessionWatchdog gate = new SessionWatchdog(); long token = gate.arm(0);
        gate.heartbeat(token, 100); check(gate.valid(token, 450), "Watchdog boundary");
        gate.heartbeat(token, 451); check(!gate.valid(token, 451), "Late UI heartbeat revived expired session");
        gate.heartbeat(token, 500); check(!gate.valid(token, 500), "Expired latch reset"); scenarios++;
        token = gate.arm(1000); final long cancelled = token;
        gate.cancel(); check(!gate.valid(cancelled, 1000), "Emergency fence did not cancel callback permission");
        long next = gate.arm(1100); gate.heartbeat(cancelled, 1200);
        check(gate.valid(next, 1100) && !gate.valid(cancelled, 1100), "Old callback belongs to new session");
        check(!gate.valid(next, 1451), "Stale-session heartbeat refreshed current session"); scenarios++;
        token = gate.arm(2000); check(!gate.valid(token, 1999), "Watchdog backwards clock"); scenarios++;

        // Sample scheduling and world/body transforms for every whole-degree bearing.
        for (int bearing = 0; bearing < 360; bearing++) {
            c = start(bearing + ",WAIT"); Command out = c.tick(0, t(0));
            near(out.magnitude(), 0.1, "Bounded speed at every angle");
            check(out.north * Math.cos(Math.toRadians(bearing)) + out.east * Math.sin(Math.toRadians(bearing)) < 0,
                    "Correction must point opposite target-to-aircraft bearing");
            for (int heading = -180; heading <= 180; heading += 30)
                near(Math.hypot(out.forward(heading), out.right(heading)), 0.1, "Body rotation changes magnitude");
            tickUntil(c, 100, 1000); check(c.state() == State.HOVERING, "Angle-specific queue failed to stop");
        }
        scenarios++;
        System.out.println("PASS: " + scenarios + " safety/control scenarios, including all 360 bearing angles.");
    }
}
