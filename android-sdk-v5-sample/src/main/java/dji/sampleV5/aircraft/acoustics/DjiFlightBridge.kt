package dji.sampleV5.aircraft.acoustics

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.*
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener

/** Only this adapter sends flight commands. All mutations and callbacks run on the main thread. */
class DjiFlightBridge(private val onFault: (String) -> Unit, private val onChanged: () -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private val keys = KeyManager.getInstance()
    private val sticks = VirtualStickManager.getInstance()
    private var listening = false
    private var generation = 0
    private var acquiring = false
    private var releasing = false
    private var enabled = false
    private var ready = false
    private var landing = false
    private var landingPending = false
    private var onReady: (() -> Unit)? = null
    private var lastStickState: VirtualStickState? = null
    private var locationAt = -1L
    private var attitudeAt = -1L
    private var heightAt = -1L
    private var flyingAt = -1L
    private var gpsAt = -1L
    private var connectionAt = -1L
    var confirmationNeeded = false
        private set
    val busy get() = acquiring || releasing || enabled || landing || landingPending

    private val listener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(state: VirtualStickState) {
            handler.post {
                lastStickState = state
                if (ready && (!state.isVirtualStickEnable ||
                            state.currentFlightControlAuthorityOwner != FlightControlAuthority.MSDK)) {
                    ready = false
                    onFault("虚拟摇杆控制权丢失，请使用遥控器接管")
                } else completeAcquisitionIfReady()
                onChanged()
            }
        }
        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            // The state callback supplies the actual current owner.
        }
    }

    fun observe() {
        if (listening) return
        listening = true
        keys.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D), this, true) { _, _ ->
            handler.post { locationAt = SystemClock.elapsedRealtime() }
        }
        keys.listen(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude), this, true) { _, _ ->
            handler.post { attitudeAt = SystemClock.elapsedRealtime() }
        }
        keys.listen(KeyTools.createKey(FlightControllerKey.KeyAltitude), this, true) { _, _ ->
            handler.post { heightAt = SystemClock.elapsedRealtime() }
        }
        keys.listen(KeyTools.createKey(FlightControllerKey.KeyIsFlying), this, true) { _, _ ->
            handler.post { flyingAt = SystemClock.elapsedRealtime() }
        }
        keys.listen(KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel), this, true) { _, _ ->
            handler.post { gpsAt = SystemClock.elapsedRealtime() }
        }
        keys.listen(KeyTools.createKey(FlightControllerKey.KeyConnection), this, true) { _, _ ->
            handler.post { connectionAt = SystemClock.elapsedRealtime() }
        }
        keys.listen(KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded), this, true) { _, value ->
            handler.post { confirmationNeeded = value == true }
        }
        sticks.setVirtualStickStateListener(listener)
    }

    fun telemetry(targetLatitude: Double?, targetLongitude: Double?): AcousticLandingController.Telemetry {
        val location = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D))
        val yaw = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude))?.yaw ?: Double.NaN
        val altitude = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyAltitude)) ?: Double.NaN
        val connected = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false)
        val flying = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyIsFlying), false)
        val gps = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel))
        val latitude = location?.latitude ?: Double.NaN
        val longitude = location?.longitude ?: Double.NaN
        val goodGps = gps == GPSSignalLevel.LEVEL_4 || gps == GPSSignalLevel.LEVEL_5 || gps == GPSSignalLevel.LEVEL_10
        val valid = goodGps && AcousticLandingController.validCoordinate(latitude, longitude) &&
                !(latitude == 0.0 && longitude == 0.0)
        val hasTarget = targetLatitude != null && targetLongitude != null
        val offset = if (valid && hasTarget)
            AcousticLandingController.relativePosition(latitude, longitude, targetLatitude!!, targetLongitude!!)
            else doubleArrayOf(Double.NaN, Double.NaN)
        // Event keys (connection, flying, GPS) need not push when unchanged. Attitude/height/location are periodic.
        val stamp = if (hasTarget) minOf(locationAt, attitudeAt, heightAt) else minOf(attitudeAt, heightAt)
        return AcousticLandingController.Telemetry(offset[0], offset[1], altitude, yaw,
            connected && connectionAt >= 0 && flyingAt >= 0, flying,
            valid && gpsAt >= 0 && hasTarget, stamp)
    }

    fun acquire(done: () -> Unit) {
        check(!busy) { "上一次飞行指令仍在处理，请等待" }
        observe()
        acquiring = true
        lastStickState = null
        onReady = done
        val request = ++generation
        sticks.enableVirtualStick(completion({
            acquiring = false
            enabled = true
            if (request != generation) { release(); return@completion }
            sticks.setVirtualStickAdvancedModeEnabled(true)
            completeAcquisitionIfReady()
            handler.postDelayed({
                if (request == generation && !ready && enabled) onFault("未确认 MSDK 控制权，停止实验")
            }, 2000)
        }, { detail ->
            acquiring = false
            onReady = null
            if (request == generation) onFault("获取控制权失败：$detail")
        }))
    }

    private fun completeAcquisitionIfReady() {
        val state = lastStickState ?: return
        if (enabled && !ready && !acquiring && !releasing && !landingPending && onReady != null &&
            state.isVirtualStickEnable && state.isVirtualStickAdvancedModeEnabled &&
            state.currentFlightControlAuthorityOwner == FlightControlAuthority.MSDK) {
            ready = true
            onReady?.also { onReady = null; it() }
        }
    }

    fun send(command: AcousticLandingController.Command, yaw: Double) {
        check(ready) { "未持有飞行控制权" }
        require(yaw.isFinite()) { "航向无效" }
        val param = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            roll = command.forward(yaw)
            pitch = command.right(yaw)
            this.yaw = 0.0
            verticalThrottle = 0.0
        }
        sticks.sendVirtualStickAdvancedParam(param)
    }

    fun startLanding(onAccepted: () -> Unit) {
        check(ready && !landingPending) { "降落请求状态无效" }
        send(AcousticLandingController.Command.ZERO, 0.0)
        ready = false
        releasing = true
        landingPending = true
        val request = generation
        sticks.disableVirtualStick(completion({
            releasing = false
            enabled = false
            if (request != generation) { landingPending = false; return@completion }
            val connected = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection), false)
            val flying = keys.getValue(KeyTools.createKey(FlightControllerKey.KeyIsFlying), false)
            if (!connected || !flying) {
                landingPending = false
                onFault("降落前连接或飞行状态发生变化")
                return@completion
            }
            // Mark before dispatch so Stop also cancels a not-yet-acknowledged landing action.
            landing = true
            keys.performAction(KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding), actionCallback({
                landingPending = false
                if (request == generation) onAccepted() else cancelLanding()
            }, { detail ->
                landingPending = false
                landing = false
                if (request == generation) onFault("自动降落失败：$detail")
            }))
        }, { detail ->
            releasing = false
            landingPending = false
            if (request == generation) onFault("释放摇杆失败，未发降落指令：$detail")
        }))
    }

    fun confirmLanding() {
        check(landing && confirmationNeeded) { "当前未要求降落确认" }
        keys.performAction(KeyTools.createKey(FlightControllerKey.KeyConfirmLanding), actionCallback({}, {
            onFault("降落确认失败：$it")
        }))
    }

    fun stop() {
        generation++
        onReady = null
        if (ready) runCatching { send(AcousticLandingController.Command.ZERO, 0.0) }
        ready = false
        if (landing) cancelLanding()
        if (enabled && !releasing) release()
        // An in-flight acquire callback sees the changed generation and releases immediately.
    }

    private fun release() {
        releasing = true
        sticks.disableVirtualStick(completion({
            enabled = false
            releasing = false
        }, {
            releasing = false
            onFault("释放控制权失败：$it；请用遥控器接管")
        }))
    }

    private fun cancelLanding() {
        keys.performAction(KeyTools.createKey(FlightControllerKey.KeyStopAutoLanding), actionCallback({
            landing = false
        }, { onFault("停止自动降落失败：$it；请用遥控器接管") }))
    }

    fun markLanded() { landing = false }
    fun close() {
        stop()
        if (listening) {
            keys.cancelListen(this)
            sticks.removeVirtualStickStateListener(listener)
            listening = false
        }
    }

    private fun completion(success: () -> Unit, failure: (String) -> Unit) =
        object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { handler.post {
                runCatching { success() }.onFailure { onFault("DJI 回调处理失败：${it.message}") }
                onChanged()
            } }
            override fun onFailure(error: IDJIError) { handler.post { failure(error.description()); onChanged() } }
        }
    private fun actionCallback(success: () -> Unit, failure: (String) -> Unit) =
        object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(value: EmptyMsg?) { handler.post {
                runCatching { success() }.onFailure { onFault("DJI 动作回调处理失败：${it.message}") }
                onChanged()
            } }
            override fun onFailure(error: IDJIError) { handler.post { failure(error.description()); onChanged() } }
        }
}
