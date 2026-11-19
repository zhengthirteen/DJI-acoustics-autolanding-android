import dji.sampleV5.aircraft.acoustics.core.*;
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController.*;

/** New input behavior; legacy regression scenarios remain unchanged in AcousticControllerTest. */
public final class ArrayInputTest {
    private static int scenarios;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-8, message); }
    private static void reject(Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        check(rejected, "Unsafe configuration accepted");
    }
    private static Telemetry t(long now) { return new Telemetry(4,0,0,0,true,true,true,true,true,90,now); }
    private static ArrayAngleCalibration calibration() { return new ArrayAngleCalibration(0,true,true); }
    private static AcousticLandingController replay(String text, double speed) {
        AcousticLandingController c = new AcousticLandingController(new Config(speed), calibration().replay(text));
        c.start(0,t(0)); return c;
    }
    private static LiveBearingInput startedInput() {
        LiveBearingInput input = new LiveBearingInput(calibration()); input.start(0); return input;
    }
    private static AcousticLandingController streaming(LiveBearingInput input, double speed) {
        check(input.offer(0,0.0,0,0), "Preflight packet rejected");
        AcousticLandingController c = new AcousticLandingController(new Config(speed), input);
        c.start(0,t(0)); near(c.tick(0,t(0)).magnitude(),0,"Pre-start packet moved aircraft"); return c;
    }
    public static void main(String[] args) {
        check(DirectionQueue.parse("WAIT*20").durationMs() == 10000, "Legacy duration changed");
        check(DirectionQueue.parse("WAIT*100",100).durationMs() == 10000, "10 Hz duration wrong");
        check(DirectionQueue.parse("WAIT*600",100).durationMs() == 60000, "60-second array limit");
        reject(() -> DirectionQueue.parse("WAIT*601",100)); reject(() -> DirectionQueue.parse("WAIT*121"));
        reject(() -> DirectionQueue.parse("0",200)); scenarios++;

        ArrayAngleCalibration rotated = new ArrayAngleCalibration(90,true,true);
        near(rotated.toTargetBearing(0),90,"Zero-axis rotation"); near(rotated.toTargetBearing(270),0,"Wrap after rotation");
        ArrayAngleCalibration reverse = new ArrayAngleCalibration(90,false,true);
        near(reverse.toTargetBearing(90),0,"Counterclockwise rotation"); near(reverse.toTargetBearing(-90),180,"Signed angle");
        near(calibration().toTargetBearing(22.5),22.5,"Fractional angle quantized");
        near(calibration().toTargetBearing(360),0,"360 normalization");
        reject(() -> calibration().toTargetBearing(Double.NaN));
        reject(() -> new ArrayAngleCalibration(Double.POSITIVE_INFINITY,true,true)); scenarios++;

        ArrayAngleCalibration differentOrigin = new ArrayAngleCalibration(90,true,false);
        near(differentOrigin.toArrayNorthBearing(0),90,"Read-only preview should not require target origin");
        reject(() -> differentOrigin.toTargetBearing(0)); reject(() -> differentOrigin.replay("0"));
        reject(() -> new LiveBearingInput(differentOrigin)); scenarios++;

        AcousticLandingController c = replay("0,90,180,270",0.1);
        double[][] expected = {{-0.1,0},{0,-0.1},{0.1,0},{0,0.1}};
        for (int i=0;i<4;i++) {
            Command out = c.tick(i*100,t(i*100));
            near(out.north,expected[i][0],"100 ms north component"); near(out.east,expected[i][1],"100 ms east component");
        }
        near(c.tick(400,t(400)).magnitude(),0,"Final 100 ms sample did not end");
        check(c.state()==State.HOVERING && c.consumed()==4,"Replay must end in hover");
        for(long now=500;now<=5000;now+=100) near(c.tick(now,t(now)).magnitude(),0,"Post-replay motion");
        check(c.state()==State.HOVERING,"Replay inferred landing"); scenarios++;

        new AcousticLandingController(new Config(0.3),calibration().replay("180*100"));
        reject(() -> new AcousticLandingController(new Config(0.3),calibration().replay("180*101")));
        reject(() -> new AcousticLandingController(new Config(0.3),DirectionQueue.parse("180*21"))); scenarios++;

        c=replay("0,90,180,270,0,90",0.1); c.tick(0,t(0));
        Command latest=c.tick(250,t(250));
        near(latest.north,0.1,"Jitter replayed a missed direction instead of latest");
        check(c.skippedSamples()==1 && c.consumed()==3,"Missed sample accounting");
        c.tick(350,t(350)); c.tick(450,t(450)); c.tick(550,t(550)); c.tick(650,t(650));
        check(c.state()==State.HOVERING,"Jittered queue failed to end"); scenarios++;

        c=replay("180*30",0.1);
        for(long now=0;now<=3080;now+=110) c.tick(now,t(now));
        check(c.state()==State.HOVERING && c.skippedSamples()>0,"Cumulative scheduler drift aborted 10 Hz replay");
        c=replay("180*30",0.1); c.tick(0,t(0)); c.tick(351,t(351));
        check(c.state()==State.ABORTED,"10 Hz mode weakened stalled-loop guard"); scenarios++;

        for(int zero=0;zero<360;zero+=45) for(boolean clockwise:new boolean[]{true,false}) for(int raw=0;raw<360;raw++) {
            ArrayAngleCalibration frame=new ArrayAngleCalibration(zero,clockwise,true);
            double bearing=frame.toTargetBearing(raw);
            double expectedBearing=DirectionQueue.normalize(zero+(clockwise?raw:-raw));
            near(bearing,expectedBearing,"Every-degree frame conversion");
            Command cmd=new Command(-Math.cos(Math.toRadians(bearing))*0.1,-Math.sin(Math.toRadians(bearing))*0.1);
            check(cmd.north*Math.cos(Math.toRadians(bearing))+cmd.east*Math.sin(Math.toRadians(bearing))<-.099,
                    "Correction pointed away from source");
        }
        scenarios++;

        LiveBearingInput input=startedInput(); input.offer(1,180.0,0,0);
        check(input.poll(0).status==LiveBearingInput.Status.VALUE,"Initial live bearing");
        check(input.poll(199).status==LiveBearingInput.Status.VALUE,"Premature angle expiry");
        check(input.poll(200).status==LiveBearingInput.Status.ZERO,"200 ms did not zero");
        check(input.poll(500).status==LiveBearingInput.Status.FAULT,"500 ms did not latch stop");
        check(!input.offer(2,90.0,501,501),"Late packet revived timed-out stream");
        input.finishForOperator(600); check(input.poll(600).status==LiveBearingInput.Status.FAULT,"Finish cleared fault"); scenarios++;

        input=startedInput(); input.offer(1,180.0,0,0); input.poll(0);
        check(!input.offer(1,0.0,100,100),"Duplicate accepted");
        check(!input.offer(0,0.0,100,100),"Old sequence accepted");
        check(!input.offer(2,0.0,200,100),"Future sample accepted");
        check(!input.offer(2,0.0,1,300),"Stale sample accepted");
        check(input.accepted()==1 && input.rejected()==4,"Rejected packets refreshed statistics");
        check(input.poll(500).status==LiveBearingInput.Status.FAULT,"Invalid packets kept source alive"); scenarios++;

        input=startedInput(); input.offer(1,180.0,0,0);
        for(long now=100;now<500;now+=100) {
            input.offer(now,null,now,now); check(input.poll(now).status==LiveBearingInput.Status.ZERO,"Invalid frame did not immediately zero");
        }
        check(!input.offer(500,null,500,500) && input.poll(500).status==LiveBearingInput.Status.FAULT,"Invalid packets indefinitely renewed validity"); scenarios++;

        input=startedInput(); input.offer(1,180.0,0,0); input.offer(2,null,100,100); input.poll(100);
        input.offer(3,270.0,200,200);
        check(input.poll(200).status==LiveBearingInput.Status.VALUE,"Fresh recovery before fault latch");
        LiveBearingInput invalid=startedInput(); invalid.offer(1,Double.NaN,0,0);
        check(invalid.poll(0).status==LiveBearingInput.Status.FAULT,"NaN did not fail closed"); scenarios++;

        input=startedInput();
        for(int i=0;i<10;i++) check(input.offer(i,(double)i,i*10,90),"Burst sample rejected");
        near(input.poll(100).targetBearing,9,"Buffered historical samples instead of latest");
        check(input.accepted()==10,"Burst count");
        input=startedInput(); input.offer(1,90.0,101,101);
        check(input.poll(100).status==LiveBearingInput.Status.ZERO,"Concurrent arrival used future data");
        check(input.poll(110).status==LiveBearingInput.Status.VALUE,"Concurrent packet not usable on next tick"); scenarios++;

        LiveBearingInput empty=new LiveBearingInput(calibration());
        AcousticLandingController noData=new AcousticLandingController(new Config(0.1),empty);
        reject(() -> noData.start(0,t(0))); check(noData.state()==State.IDLE,"No-data start changed state");
        input=new LiveBearingInput(calibration()); c=streaming(input,0.1);
        check(!input.offer(0,90.0,0,0),"Pre-start replay accepted");
        input.offer(1,180.0,100,100); near(c.tick(100,t(100)).north,0.1,"Fresh post-start frame not applied");
        c.abort("emergency"); check(!input.offer(2,0.0,200,200),"Stopped controller accepted new motion");
        check(c.tick(200,t(200)).magnitude()==0 && c.state()==State.ABORTED,"Emergency stream resumed"); scenarios++;

        input=new LiveBearingInput(calibration()); c=streaming(input,0.1);
        for(long now=100;now<=1000;now+=100) { input.offer(now,180.0,now,now); c.tick(now,t(now)); }
        input.finishForOperator(1100); c.tick(1100,t(1100));
        check(c.state()==State.HOVERING,"Explicit stream completion did not hover");
        check(!input.offer(2000,0.0,1200,1200),"Finished input accepted more frames");
        for(long now=1200;now<=3300;now+=100) c.tick(now,t(now));
        check(c.state()==State.HOVERING,"Finished stream auto-landed");
        c.requestLanding(3300,t(3300)); check(c.state()==State.LANDING_REQUESTED,"Explicit manual landing unavailable"); scenarios++;

        input=new LiveBearingInput(calibration()); c=streaming(input,0.05);
        for(long now=100;now<=60000 && c.state()!=State.ABORTED;now+=100) { input.offer(now,180.0,now,now); c.tick(now,t(now)); }
        check(c.state()==State.ABORTED && c.reason().contains("60"),"Live session time budget"); scenarios++;
        input=new LiveBearingInput(calibration()); c=streaming(input,0.3);
        for(long now=100;now<=12000 && c.state()!=State.ABORTED;now+=100) { input.offer(now,180.0,now,now); c.tick(now,t(now)); }
        check(c.state()==State.ABORTED && c.reason().contains("路程"),"Live commanded-path budget"); scenarios++;
        input=new LiveBearingInput(calibration()); c=streaming(input,0.1);
        input.fail("transport disconnected"); c.tick(100,t(100));
        check(c.state()==State.ABORTED,"Transport disconnect was treated as normal completion"); scenarios++;
        System.out.println("PASS: "+scenarios+" array input scenarios; legacy timing preserved, 100 ms replay and latest-only stream verified.");
    }
}
