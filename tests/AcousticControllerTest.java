import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController;
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController.*;
import dji.sampleV5.aircraft.acoustics.core.DirectionQueue;

/** Runnable JVM regression scenarios; no DJI SDK or aircraft required. */
public final class AcousticControllerTest {
    private static int scenarios;
    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
    private static Telemetry position(double north, double east, long now) {
        return new Telemetry(north, east, 4, 0, true, true, true, now);
    }
    private static AcousticLandingController controller(ArrivalMode mode) {
        AcousticLandingController c = new AcousticLandingController(new Config(mode, 0.3, 0.8));
        c.start(0);
        return c;
    }
    private static void reject(Runnable operation) {
        try { operation.run(); throw new AssertionError("Invalid configuration accepted"); }
        catch (IllegalArgumentException expected) { /* expected */ }
    }

    public static void main(String[] args) {
        DirectionQueue q = DirectionQueue.parse("180*2, -90, 720, WAIT*2");
        check(q.size() == 6 && q.next() == 180 && q.next() == 180 && q.next() == 270 &&
            q.next() == 0 && q.next() == null && q.next() == null && !q.hasNext(), "Queue parsing");
        reject(() -> DirectionQueue.parse("NaN"));
        reject(() -> DirectionQueue.parse("180*0"));
        reject(() -> DirectionQueue.parse("180*1201"));
        reject(() -> new Config(ArrivalMode.POSITION, Double.NaN, 0.8));
        reject(() -> new Config(ArrivalMode.POSITION, 1, 0.8));
        scenarios++;

        // A complete simulated approach with the same 2 Hz input and 10 Hz control loop as Android.
        AcousticLandingController c = controller(ArrivalMode.POSITION);
        q = DirectionQueue.parse("180*40");
        double north = -4;
        long landedAt = -1;
        for (long now = 0; now <= 20000; now += 100) {
            if (now % 500 == 0 && c.isNavigating()) {
                check(q.hasNext(), "Default demonstration ran out of samples");
                c.acceptBearing(q.next(), now, now);
            }
            Command command = c.tick(now, position(north, 0, now));
            check(command.magnitude() <= 0.300001, "Speed exceeds configured limit");
            if (c.state() == State.HOLDING || c.state() == State.LANDING_REQUESTED)
                check(command.magnitude() == 0, "Movement during arrival confirmation");
            north += command.north * 0.1;
            if (c.state() == State.LANDING_REQUESTED) {
                check(Math.abs(north) <= 0.8, "Landing requested outside target radius");
                c.landingAccepted(); c.landed(); landedAt = now; break;
            }
        }
        check(c.state() == State.COMPLETE && landedAt >= 12000, "End-to-end demonstration failed");
        scenarios++;

        // Eastward deviation means westward correction; arbitrary aircraft heading must not change it.
        c = controller(ArrivalMode.POSITION);
        c.acceptBearing(90.0, 0, 0);
        Command command = c.tick(100, position(0, 4, 100));
        check(command.east < -0.29 && Math.abs(command.north) < 0.0001, "Bearing polarity reversed");
        Command northCommand = new Command(0.3, 0);
        check(Math.abs(northCommand.forward(90)) < 0.0001 && northCommand.right(90) < -0.29,
            "North command at eastward heading must be body-left");
        scenarios++;

        c = controller(ArrivalMode.POSITION);
        c.acceptBearing(359.0, 0, 0); c.tick(100, position(4, 0, 100));
        c.acceptBearing(1.0, 500, 500);
        command = c.tick(500, position(4, 0, 500));
        check(command.north < -0.29 && Math.abs(command.east) < 0.01, "Circular wrap smoothing failed");
        scenarios++;

        c.acceptBearing(null, 600, 600);
        check(c.tick(600, position(4, 0, 600)).magnitude() == 0, "WAIT should hover immediately");
        check(c.state() != State.LANDING_REQUESTED, "Missing angle is not arrival");
        scenarios++;

        // A direction or a queue ending is never evidence of arrival.
        c = controller(ArrivalMode.POSITION);
        q = DirectionQueue.parse("180");
        c.acceptBearing(q.next(), 0, 0);
        c.tick(100, position(-4, 0, 100));
        check(!q.hasNext() && c.state() == State.ALIGNING, "Queue completion inferred arrival");
        c.abort("finite source exhausted");
        check(c.tick(200, position(0, 0, 200)).magnitude() == 0 && c.state() == State.ABORTED,
            "Aborted source later triggered landing");
        scenarios++;

        // Continuous control polling with a silent direction source must abort.
        c = controller(ArrivalMode.POSITION);
        c.acceptBearing(180.0, 0, 0);
        for (long now = 0; now <= 1600; now += 100) c.tick(now, position(-4, 0, now));
        check(c.state() == State.ABORTED && c.reason().contains("方向输入"), "Input watchdog failed");
        scenarios++;

        c = controller(ArrivalMode.POSITION);
        c.acceptBearing(180.0, 0, 0);
        c.tick(100, new Telemetry(-4, 0, 4, 0, false, true, true, 100));
        check(c.state() == State.ABORTED, "Disconnect did not abort");
        c = controller(ArrivalMode.POSITION);
        c.tick(100, new Telemetry(-4, 0, 4, 0, true, true, false, 100));
        check(c.state() == State.ABORTED, "Invalid GPS did not abort");
        scenarios++;

        // A long scheduling gap cannot skip the two-second arrival dwell.
        c = controller(ArrivalMode.POSITION);
        c.acceptBearing(180.0, 0, 0); c.tick(0, position(0, 0, 0));
        c.acceptBearing(180.0, 2000, 2000); c.tick(2000, position(0, 0, 2000));
        check(c.state() == State.ABORTED, "Stalled loop triggered landing");
        scenarios++;

        // Replayed or future-dated inputs cannot prolong the input watchdog.
        c = controller(ArrivalMode.POSITION);
        c.acceptBearing(180.0, 0, 0);
        for (long now = 0; now <= 1600; now += 100) {
            c.acceptBearing(90.0, 0, now);
            c.acceptBearing(90.0, now + 5000, now);
            c.tick(now, position(-4, 0, now));
        }
        check(c.state() == State.ABORTED, "Invalid timestamps refreshed input watchdog");
        scenarios++;

        // Position radius must hold continuously; moving outside resets dwell.
        c = controller(ArrivalMode.POSITION);
        for (long now = 0; now <= 2500; now += 100) {
            if (now % 500 == 0) c.acceptBearing(180.0, now, now);
            c.tick(now, position(now == 1500 ? -2 : 0, 0, now));
        }
        check(c.state() == State.HOLDING, "Interrupted arrival dwell was not reset");
        scenarios++;

        // No-GPS experiments require explicit operator arrival and still hover before landing.
        c = controller(ArrivalMode.OPERATOR);
        for (long now = 0; now <= 1000; now += 100) {
            if (now % 500 == 0) c.acceptBearing(180.0, now, now);
            c.tick(now, new Telemetry(Double.NaN, Double.NaN, 4, 0, true, true, false, now));
        }
        check(c.state() == State.ALIGNING, "No-GPS mode inferred arrival");
        c.confirmOperatorArrival();
        for (long now = 1100; now <= 3100; now += 100) {
            if (now % 500 == 0) c.acceptBearing(180.0, now, now);
            check(c.tick(now, position(-4, 0, now)).magnitude() == 0, "Manual arrival failed to hover");
        }
        check(c.state() == State.LANDING_REQUESTED, "Manual confirmation did not lead to landing request");
        scenarios++;

        // Default no-GPS workflow: WAIT holds position until the operator explicitly confirms arrival.
        c = controller(ArrivalMode.OPERATOR);
        q = DirectionQueue.parse("WAIT*120");
        for (long now = 0; now <= 8000; now += 100) {
            if (now % 500 == 0) c.acceptBearing(q.next(), now, now);
            if (now == 6000) c.confirmOperatorArrival();
            command = c.tick(now, new Telemetry(Double.NaN, Double.NaN, 4, 0, true, true, false, now));
            check(command.magnitude() == 0, "Default no-GPS workflow issued horizontal movement");
            if (now < 6000) check(c.state() == State.ALIGNING, "Default no-GPS workflow inferred arrival");
            if (now >= 6000 && now < 8000) check(c.state() == State.HOLDING, "Manual hover dwell failed");
        }
        check(c.state() == State.LANDING_REQUESTED, "No-GPS WAIT workflow failed to request landing after confirmation");
        scenarios++;

        double[] offset = AcousticLandingController.relativePosition(31.00001, 121, 31, 121);
        check(offset[0] > 1.11 && offset[0] < 1.12 && offset[1] == 0, "WGS84 offset conversion");
        scenarios++;

        // Wrong-direction input remains bounded even with valid GPS and fresh packets.
        c = controller(ArrivalMode.POSITION);
        for (long now = 0; now <= 60000 && c.isNavigating(); now += 100) {
            if (now % 500 == 0) c.acceptBearing(0.0, now, now);
            c.tick(now, position(-4, 0, now));
        }
        check(c.state() == State.ABORTED && c.reason().contains("15 米"), "Commanded travel budget failed");
        scenarios++;

        System.out.println("PASS: " + scenarios + " acoustic control scenarios; position demo requested landing at " +
            landedAt + " ms.");
    }
}
