import android.os.TestLoop
import dji.sampleV5.aircraft.acoustics.DjiFlightBridge
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController.State
import dji.sampleV5.aircraft.acoustics.core.DirectionQueue
import dji.sdk.keyvalue.key.*
import dji.sdk.keyvalue.value.common.*
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.*

private class Fixture {
    val keys: KeyManager
    val sticks: VirtualStickManager
    val bridge: DjiFlightBridge
    var session = 0L
    init {
        TestLoop.reset(); KeyManager.reset(); VirtualStickManager.reset()
        keys = KeyManager.getInstance(); sticks = VirtualStickManager.getInstance()
        keys.values.putAll(mapOf("connected" to true, "flying" to true, "motors" to true,
            "altitude" to 4.0, "attitude" to Attitude(0.0), "velocity" to Velocity3D(0.0,0.0,0.0),
            "gps" to GPSSignalLevel.NONE, "vision" to true, "mode" to FlightMode.GPS_NORMAL,
            "battery" to 90, "confirmNeeded" to false))
        bridge = DjiFlightBridge(); bridge.observe(); advance(2500)
        check(bridge.snapshot.canStart) { "Fixture not ready: ${bridge.snapshot}" }
    }
    fun advance(ms: Long, heartbeat: Boolean = true, telemetry: Boolean = true) {
        val end = TestLoop.now + ms
        while (TestLoop.now < end) {
            TestLoop.advanceTo(minOf(end, TestLoop.now + 50))
            if (heartbeat && session != 0L) bridge.heartbeat(session)
            if (telemetry) {
                keys.emit(FlightControllerKey.KeyAircraftAttitude, keys.values["attitude"] as Attitude)
                keys.emit(FlightControllerKey.KeyAltitude, keys.values["altitude"] as Double)
                keys.emit(FlightControllerKey.KeyAircraftVelocity, keys.values["velocity"] as Velocity3D)
            }
            TestLoop.advanceTo(TestLoop.now)
        }
    }
    fun start(queue: String = "180*6") {
        session = bridge.start(AcousticLandingController(AcousticLandingController.Config(0.1), DirectionQueue.parse(queue)))
        advance(200)
    }
    fun hover() { start("WAIT"); advance(3000); check(bridge.snapshot.canLand) { "Not in landing hover: ${bridge.snapshot}" } }
    fun noMotionAfter(time: Long) {
        check(sticks.commands.filter { it.at >= time }.all { it.forward == 0.0 && it.right == 0.0 }) { "Motion after stop at $time" }
    }
    fun stopped() { check(!bridge.busy && bridge.snapshot.state == State.ABORTED) { "Stop not completed: ${bridge.snapshot}" } }
}
private var scenarios = 0
private fun scenario(name: String, block: () -> Unit) {
    try { block(); scenarios++ } catch (error: Throwable) { throw AssertionError("Bridge scenario failed: $name", error) }
}
private fun rejected(block: () -> Unit) {
    var failed = false
    try { block() } catch (expected: IllegalStateException) { failed = true }
    check(failed) { "Unconfirmed session allowed a new flight" }
}
fun main() {
    scenario("normal queue hovers until separate landing action") {
        val f = Fixture(); f.start("180*2"); f.advance(3000)
        check(f.bridge.snapshot.state == State.HOVERING && f.bridge.busy)
        check(f.sticks.commands.any { it.forward > 0 })
        val hoverAt = TestLoop.now; f.advance(10000)
        f.noMotionAfter(hoverAt); check("startLanding" !in f.keys.actions)
        f.bridge.requestLanding(f.session); f.advance(200)
        check(f.keys.actions.count { it == "startLanding" } == 1 && f.bridge.snapshot.state == State.LANDING)
        f.keys.emit(FlightControllerKey.KeyIsFlying, false); f.keys.emit(FlightControllerKey.KeyAreMotorsOn, false)
        f.advance(200); check(f.bridge.snapshot.state == State.COMPLETE && !f.bridge.busy)
    }
    scenario("UI heartbeat stalls independently of flight worker") {
        val f = Fixture(); f.start(); val lastUi = TestLoop.now
        f.advance(900, heartbeat = false); f.noMotionAfter(lastUi + 400); f.stopped()
        f.advance(1000); f.noMotionAfter(lastUi + 400)
    }
    scenario("emergency before enable callback") {
        val f = Fixture(); f.sticks.holdEnable = true; f.start()
        val stoppedAt = TestLoop.now; f.bridge.emergencyStop(); f.advance(500)
        check(f.bridge.busy); rejected { f.start() }
        f.sticks.completeEnable(); f.advance(500)
        f.noMotionAfter(stoppedAt); f.stopped(); check(f.sticks.disableCalls > 0)
    }
    scenario("emergency while landing waits for stick release") {
        val f = Fixture(); f.hover(); f.sticks.holdDisable = true
        f.bridge.requestLanding(f.session); f.advance(100)
        f.bridge.emergencyStop(); f.advance(100); check(f.bridge.busy)
        f.sticks.completeDisable(); f.advance(500)
        check("startLanding" !in f.keys.actions); f.stopped()
    }
    scenario("late landing success requires second cancellation") {
        val f = Fixture(); f.hover(); f.keys.held.add("startLanding")
        f.bridge.requestLanding(f.session); f.advance(100)
        f.bridge.emergencyStop(); f.advance(500)
        check(f.bridge.busy && f.keys.actions.count { it == "stopLanding" } == 1)
        rejected { f.start() }; f.keys.complete("startLanding"); f.advance(500)
        check(f.keys.actions.count { it == "stopLanding" } == 2); f.stopped()
    }
    scenario("release failure blocks landing and restart") {
        val f = Fixture(); f.hover(); f.sticks.failDisable = true
        f.bridge.requestLanding(f.session); f.advance(500)
        check("startLanding" !in f.keys.actions && f.bridge.busy)
        rejected { f.start() }; check(f.sticks.disableCalls == 1)
        f.sticks.failDisable = false; f.bridge.emergencyStop(); f.advance(500); f.stopped()
    }
    scenario("unknown flight/motor values never mean landed") {
        val f = Fixture(); f.hover(); f.bridge.requestLanding(f.session); f.advance(200)
        f.keys.emit(FlightControllerKey.KeyIsFlying, null); f.keys.emit(FlightControllerKey.KeyAreMotorsOn, null)
        f.advance(500); check(f.bridge.snapshot.state != State.COMPLETE); f.stopped()
    }
    scenario("advanced mode loss stops sequence") {
        val f = Fixture(); f.start(); val stoppedAt = TestLoop.now
        f.sticks.update(VirtualStickState(true, false, FlightControlAuthority.MSDK)); f.advance(600)
        f.noMotionAfter(stoppedAt + 1); f.stopped()
    }
    scenario("RC authority never automatically reacquired") {
        val f = Fixture(); f.start(); val stoppedAt = TestLoop.now
        f.sticks.update(VirtualStickState(true, true, FlightControlAuthority.RC)); f.advance(600)
        f.noMotionAfter(stoppedAt + 1); f.stopped(); check(f.sticks.enableCalls == 1)
    }
    scenario("loss of visual positioning without GPS") {
        val f = Fixture(); f.start(); f.keys.emit(FlightControllerKey.KeyIsVisionSensorUsed, false)
        f.advance(600); f.stopped()
    }
    scenario("ATTI mode during movement") {
        val f = Fixture(); f.start(); f.keys.emit(FlightControllerKey.KeyFlightMode, FlightMode.ATTI)
        f.advance(600); f.stopped()
    }
    scenario("stale periodic telemetry") {
        val f = Fixture(); f.start(); f.advance(1300, telemetry = false); f.stopped()
    }
    scenario("late final landing confirmation after cancellation") {
        val f = Fixture(); f.hover(); f.bridge.requestLanding(f.session); f.advance(200)
        f.keys.emit(FlightControllerKey.KeyIsLandingConfirmationNeeded, true); f.advance(100)
        f.keys.held.add("confirmLanding"); f.bridge.confirmLanding(f.session); f.advance(100)
        f.bridge.emergencyStop(); f.advance(500); check(f.bridge.busy)
        f.keys.complete("confirmLanding"); f.advance(500)
        check(f.keys.actions.count { it == "stopLanding" } == 2); f.stopped()
    }
    scenario("acquire timeout blocks until late enable is released") {
        val f = Fixture(); f.sticks.holdEnable = true; f.start(); f.advance(6000)
        check(f.bridge.busy && f.bridge.snapshot.state == State.ABORTED)
        f.sticks.completeEnable(); f.advance(500); f.stopped()
        check(f.sticks.commands.all { it.forward == 0.0 && it.right == 0.0 })
    }
    scenario("close during acquire still releases late control") {
        val f = Fixture(); f.sticks.holdEnable = true; f.start(); f.bridge.close(); f.advance(500)
        f.sticks.completeEnable(); f.advance(500); check(!f.bridge.busy)
        check(f.sticks.commands.all { it.forward == 0.0 && it.right == 0.0 })
    }
    scenario("cancel failure needs explicit retry") {
        val f = Fixture(); f.hover(); f.bridge.requestLanding(f.session); f.advance(200)
        f.keys.failed.add("stopLanding"); f.bridge.emergencyStop(); f.advance(1000)
        check(f.bridge.busy && f.keys.actions.count { it == "stopLanding" } == 1)
        f.keys.failed.clear(); f.bridge.emergencyStop(); f.advance(500); f.stopped()
    }
    scenario("worker scheduling gap never resumes old velocity") {
        val f = Fixture(); f.start(); val stoppedAt = TestLoop.now
        TestLoop.now += 800; TestLoop.advanceTo(TestLoop.now)
        f.advance(500); f.noMotionAfter(stoppedAt + 1); f.stopped()
    }
    scenario("emergency before queued start never acquires authority") {
        val f = Fixture()
        f.session = f.bridge.start(AcousticLandingController(AcousticLandingController.Config(0.1), DirectionQueue.parse("180")))
        f.bridge.emergencyStop(); f.advance(500)
        check(f.sticks.enableCalls == 0); f.stopped()
    }
    scenario("failed enable acknowledgement still requests disable") {
        val f = Fixture(); f.sticks.failEnable = true; f.start(); f.advance(500)
        check(f.sticks.disableCalls == 1 && f.sticks.commands.isEmpty()); f.stopped()
    }
    scenario("a recreated activity cannot bypass an unresolved old session") {
        val f = Fixture(); f.sticks.holdEnable = true; f.start(); f.bridge.close(); f.advance(200)
        val other = DjiFlightBridge()
        check(other.busy)
        rejected { other.start(AcousticLandingController(AcousticLandingController.Config(0.1), DirectionQueue.parse("180"))) }
        other.emergencyStop(); f.sticks.completeEnable(); f.advance(600)
        check(!other.busy && !f.bridge.busy)
        check(f.sticks.commands.all { it.forward == 0.0 && it.right == 0.0 })
        other.close(); f.advance(100)
    }
    println("PASS: $scenarios production-adapter scenarios using deterministic Android/DJI fakes (no aircraft I/O).")
}
