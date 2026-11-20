import dji.sampleV5.aircraft.acoustics.core.VoiceSepFrame;
import dji.sampleV5.aircraft.acoustics.core.VoiceSepMonitorState;

public final class VoiceSepMonitorTest {
    private static int scenarios;
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
    private static VoiceSepFrame frame(String run,long epoch,long seq,boolean valid,double angle,double period,double age) {
        return new VoiceSepFrame("voicesep",run,valid?"tracking":"no_target",epoch,seq,true,true,valid,angle,period,age,0,0,0,1000/period);
    }
    private static void reject(Runnable action) {
        boolean failed=false; try { action.run(); } catch(IllegalArgumentException expected) { failed=true; }
        check(failed,"Invalid data accepted");
    }
    public static void main(String[] args) {
        VoiceSepMonitorState state=new VoiceSepMonitorState(128);
        check(!state.view(0).valid && !state.view(0).connected,"Unconnected display"); scenarios++;
        state.receive(frame("a",0,1,true,45,128,0),0,10);
        check(state.view(10).valid && state.view(10).angle==45 && state.pollDelayMs()==64,"128 ms receive");
        state.receive(frame("a",0,2,true,90,64,0),60,70);
        check(state.view(70).periodMs==64 && state.pollDelayMs()==50,"Period did not follow server");
        state.receive(frame("a",0,3,true,120,256,0),100,110);
        check(state.view(110).periodMs==256 && state.pollDelayMs()==128,"Configurable slow period"); scenarios++;
        state=new VoiceSepMonitorState(128);
        state.receive(frame("a",0,1,true,0,128,0),0,0);
        for(long at=100;at<=500;at+=100) state.receive(frame("a",0,1,true,0,128,0),at,at);
        check(!state.view(500).valid && state.view(500).uniqueFrames==1 && state.view(500).duplicateFrames==5,"Heartbeat renewed stale angle"); scenarios++;
        state.receive(frame("a",0,2,true,359,128,0),600,610);
        check(state.view(610).valid,"Fresh event did not recover viewer");
        state.receive(frame("a",0,2,false,Double.NaN,128,100),700,710);
        check(!state.view(710).valid,"Invalid duplicate did not clear arrow");
        state.receive(frame("a",0,2,true,359,128,0),720,730);
        check(!state.view(730).valid && state.view(730).rejectedFrames==1,"Invalid duplicate revived same angle"); scenarios++;
        state=new VoiceSepMonitorState(128);
        state.receive(frame("a",0,3,true,10,128,0),0,10);
        state.receive(frame("a",0,2,true,20,128,0),100,110);
        check(!state.view(110).valid,"Old sequence accepted");
        state.receive(frame("a",1,4,true,20,128,0),200,210);
        check(state.view(210).valid,"New epoch not accepted");
        state.receive(frame("a",0,5,true,20,128,0),300,310);
        check(!state.view(310).valid,"Old epoch accepted"); scenarios++;
        state=new VoiceSepMonitorState(128);
        state.receive(frame("a",0,1,true,1,128,0),0,10);
        state.receive(frame("b",0,0,true,2,128,0),100,110);
        check(state.view(110).valid,"New run cannot start its sequence at zero");
        state.receive(frame("a",0,2,true,3,128,0),200,210);
        check(!state.view(210).valid,"Retired run replay accepted"); scenarios++;
        state=new VoiceSepMonitorState(128);
        state.receive(frame("a",0,1,true,10,128,500),0,10);
        check(!state.view(10).valid,"Server already-stale result used");
        state.receive(frame("a",0,2,true,10,128,0),100,600);
        check(!state.view(600).valid,"Network latency omitted from age"); scenarios++;
        state=new VoiceSepMonitorState(128);
        state.receive(frame("a",0,1,true,45,128,0),0,10);
        state.error("disconnected"); check(!state.view(11).valid && !state.view(11).connected,"Disconnect retained valid angle"); scenarios++;
        state=new VoiceSepMonitorState(96);
        state.receive(frame("a",0,1,true,45,Double.NaN,0),0,10);
        check(state.view(10).periodMs==96,"User fallback ignored");
        reject(() -> new VoiceSepMonitorState(0)); scenarios++;
        reject(() -> frame("a",0,-1,true,45,128,0));
        reject(() -> frame("a",0,1,true,Double.NaN,128,0));
        reject(() -> frame("a",0,1,true,360,128,0));
        reject(() -> frame(null,0,1,true,45,128,0)); scenarios++;
        VoiceSepFrame demo=new VoiceSepFrame("demo","demo-a","synthetic",0,0,true,true,true,45,128,0,0,0,0,7.8);
        state=new VoiceSepMonitorState(128); state.receive(demo,0,10);
        check(state.view(10).demo && state.view(10).valid,"Synthetic mode not identified"); scenarios++;
        System.out.println("PASS: "+scenarios+" read-only VoiceSep monitor scenarios; no aircraft control dependencies.");
    }
}
