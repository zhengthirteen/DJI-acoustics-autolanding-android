package dji.sampleV5.aircraft.acoustics

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController.*
import dji.sampleV5.aircraft.acoustics.core.SessionWatchdog
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKeyInfo
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/** All SDK mutations run on a dedicated thread. UI only supplies a heartbeat and explicit actions. */
class DjiFlightBridge {
    companion object {
        // Activity recreation must not bypass a previous adapter's unconfirmed SDK operations.
        private val aircraftOwner = AtomicReference<DjiFlightBridge?>(null)
    }
    data class Snapshot(
        val state: State = State.IDLE, val detail: String = "等待飞行器遥测",
        val busy: Boolean = false, val canStart: Boolean = false, val canLand: Boolean = false,
        val confirmationNeeded: Boolean = false, val telemetry: Telemetry? = null,
        val flightMode: String = "UNKNOWN", val consumed: Int = 0, val total: Int = 0,
        val bearing: Double? = null, val command: Command = Command.ZERO,
        val initialAltitude: Double = Double.NaN, val commandedPath: Double = 0.0,
        val capturedAt: Long = 0, val session: Long = 0,
        val aircraftConnected: Boolean? = null, val telemetryStatus: String = "尚未订阅遥测",
        val readStatus: String = "尚未发送只读请求", val controlStatus: String = "未申请控制权",
        val diagnostics: List<String> = emptyList(), val hardwareReadFresh: Boolean = false
    )
    @Volatile var snapshot = Snapshot()
        private set
    private val thread = HandlerThread("acoustic-flight-control").apply { start() }
    private val worker = Handler(thread.looper)
    private val keys by lazy { KeyManager.getInstance() }
    private val sticks by lazy { VirtualStickManager.getInstance() }
    private val claimed = AtomicBoolean(false)
    val busy get() = claimed.get() || aircraftOwner.get() != null
    private val fence = SessionWatchdog()
    private var token = 0L
    private var workerSession = false
    private var controller: AcousticLandingController? = null
    private var observing = false
    private var stickListenerAttached = false
    private var closing = false
    private var acquiring = false
    private var awaitingReady = false
    private var enabled = false
    private var ready = false
    private var releasing = false
    private var releaseBlocked = false
    private var releaseAt = 0L
    private var landingStartPending = false
    private var landingMayBeActive = false
    private var cancelPending = false
    private var cancelBlocked = false
    private var confirmPending = false
    private var landingAt = 0L
    private var stopping = false
    private var stopAt = 0L
    private var acquisitionAt = 0L
    private var stableSince = -1L
    private var detail = "等待飞行器遥测"
    private var lastCommand = Command.ZERO
    private var stickState: VirtualStickState? = null
    private var connected = false
    private var connectionKnown = false
    private var observationEpoch = 0L
    private var disconnectedAt = -1L
    private val probes = mutableListOf<ReadProbe<*>>()
    private var flying: Boolean? = null
    private var motorsOn: Boolean? = null
    private var altitude = Double.NaN
    private var yaw = Double.NaN
    private var horizontalSpeed = Double.NaN
    private var verticalSpeed = Double.NaN
    private var heightAt = -1L
    private var attitudeAt = -1L
    private var velocityAt = -1L
    private var gpsGood = false
    private var visionUsed = false
    private var mode = FlightMode.UNKNOWN
    private var battery = -1
    private var confirmationNeeded = false

    private val listener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(state: VirtualStickState) {
            worker.post {
                stickState = state
                if (ready && (!state.isVirtualStickEnable || !state.isVirtualStickAdvancedModeEnabled ||
                        state.currentFlightControlAuthorityOwner != FlightControlAuthority.MSDK)) {
                    stopInternal("控制权/高级模式已丢失，请遥控器接管")
                }
                if (stopping && !state.isVirtualStickEnable) enabled = false
            }
        }
        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) = Unit
    }

    private val ticker = object : Runnable {
        override fun run() {
            try { step() } catch (error: Exception) {
                detail = "控制线程异常：${error.message}；请遥控器接管"
                if (claimed.get()) stopInternal(detail)
            }
            if (!(closing && !claimed.get())) worker.postDelayed(this, 100)
            else {
                if (observing) {
                    runCatching { keys.cancelListen(this@DjiFlightBridge) }
                    if (stickListenerAttached) runCatching { sticks.removeVirtualStickStateListener(listener) }
                }
                thread.quitSafely()
            }
        }
    }

    init { worker.post(ticker) }
    fun observe() { worker.post { runCatching { observeInternal() }.onFailure {
        runCatching { keys.cancelListen(this) }
        observationEpoch++
        probes.clear(); heightAt = -1; attitudeAt = -1; velocityAt = -1; stableSince = -1
        observing = false
        detail = "遥测初始化失败：${it.message}"
    } } }
    fun productDisconnected() {
        emergencyStop("SDK 报告设备断开，请遥控器接管")
        worker.postAtFrontOfQueue {
            connected = false; connectionKnown = true; disconnectedAt = SystemClock.elapsedRealtime()
            heightAt = -1; attitudeAt = -1; velocityAt = -1; stableSince = -1
            flying = null; motorsOn = null
        }
    }

    /** Idle-only restart after registration/permissions/reconnection; never resumes a stopped flight. */
    fun refreshTelemetry() { worker.post {
        if (busy || closing) return@post
        observationEpoch++
        runCatching { keys.cancelListen(this) }
        observing = false; probes.clear(); stableSince = -1
        connected = false; connectionKnown = false; flying = null; motorsOn = null
        heightAt = -1; attitudeAt = -1; velocityAt = -1
        altitude = Double.NaN; yaw = Double.NaN; horizontalSpeed = Double.NaN; verticalSpeed = Double.NaN
        gpsGood = false; visionUsed = false; mode = FlightMode.UNKNOWN; battery = -1
        detail = "重新订阅并读取硬件参数中（不会起飞或移动）"
        observe()
    } }

    /** Listeners are supplemented by ASYNCHRONOUS hardware reads, never by refreshing cached values. */
    private inner class ReadProbe<T>(val name: String, val info: DJIKeyInfo<T>, val critical: Boolean,
                                     val epoch: Long, val update: (T?, Long) -> Unit) {
        var receivedAt = -1L
        var validValue = false
        var lastError = ""
        var source = ""
        var lastHardwareAt = -1L
        private var pendingAt = -1L
        private var requestedAt = -1L
        private var serial = 0L
        private fun record(value: T?, at: Long, origin: String) {
            if (epoch != observationEpoch || closing || at < disconnectedAt || at < receivedAt) return
            receivedAt = at; validValue = value != null
            source = origin; lastError = if (value == null) "返回空值" else ""
            update(value, at)
        }
        fun subscribe() {
            try {
                keys.listen(KeyTools.createKey(info), this@DjiFlightBridge, false) { _, value ->
                    val at = SystemClock.elapsedRealtime()
                    worker.post { record(value, at, "推送") }
                }
            } catch (error: Exception) { lastError = "订阅失败，尝试主动读取：${error.message}" }
        }
        fun poll(now: Long) {
            if (epoch != observationEpoch || closing) return
            if (pendingAt >= 0) {
                if (now - pendingAt < 1000) return
                serial++; pendingAt = -1; lastError = "硬件读取超过 1 秒未回包"
            }
            val period = if (critical) 300L else 1000L
            // Periodic hardware requests also provide visible read-only request/response evidence.
            if (requestedAt >= 0 && now - requestedAt < period) return
            requestedAt = now; pendingAt = now
            val request = ++serial
            try {
                keys.getValue(KeyTools.createKey(info), object : CommonCallbacks.CompletionCallbackWithParam<T> {
                    override fun onSuccess(value: T?) {
                        val arrivedAt = SystemClock.elapsedRealtime()
                        worker.post {
                            if (request != serial || epoch != observationEpoch || closing) return@post
                            pendingAt = -1
                            if (now < disconnectedAt || arrivedAt - now > AcousticLandingController.TELEMETRY_STALE_MS) {
                                lastError = "迟到的硬件回包已丢弃"; return@post
                            }
                            // Use request time conservatively; late delivery must not make old data fresh.
                            if (value != null) lastHardwareAt = now
                            record(value, now, "硬件读取")
                        }
                    }
                    override fun onFailure(error: IDJIError) { worker.post {
                        if (request == serial && epoch == observationEpoch && !closing) {
                            pendingAt = -1; lastError = error.description().ifBlank { error.toString() }
                        }
                    } }
                })
            } catch (error: Exception) { pendingAt = -1; lastError = "读取异常：${error.message}" }
        }
        fun describe(now: Long): String {
            val age = if (receivedAt < 0) "尚未收到" else "${now - receivedAt} ms 前 / $source"
            val state = if (!validValue) "缺失" else if (receivedAt < disconnectedAt) "断连前旧数据" else if (critical && now - receivedAt > AcousticLandingController.TELEMETRY_STALE_MS) "过期" else "已收到"
            return "$name：$state（$age）${if (lastError.isBlank()) "" else "；读取提示：$lastError"}"
        }
    }
    private fun <T> listen(info: DJIKeyInfo<T>, name: String, critical: Boolean = false, update: (T?, Long) -> Unit) {
        val probe = ReadProbe(name, info, critical, observationEpoch, update)
        probes.add(probe); probe.subscribe()
    }
    private fun observeInternal() {
        if (observing) return
        observing = true
        listen(FlightControllerKey.KeyConnection, "飞控连接") { value, at ->
            connectionKnown = value != null
            connected = value == true
            if (!connected) {
                disconnectedAt = maxOf(disconnectedAt, at)
                heightAt = -1; attitudeAt = -1; velocityAt = -1
                gpsGood = false; visionUsed = false; battery = -1; mode = FlightMode.UNKNOWN
                flying = null; motorsOn = null
                if (claimed.get()) stopInternal("飞行器连接中断，请立即遥控器接管")
            }
        }
        listen(FlightControllerKey.KeyAircraftAttitude, "姿态/航向", true) { value, at -> yaw = value?.yaw ?: Double.NaN; attitudeAt = at }
        listen(FlightControllerKey.KeyAltitude, "相对高度", true) { value, at -> altitude = value ?: Double.NaN; heightAt = at }
        listen(FlightControllerKey.KeyAircraftVelocity, "三轴速度", true) { value, at ->
            horizontalSpeed = hypot(value?.x ?: Double.NaN, value?.y ?: Double.NaN)
            verticalSpeed = value?.z ?: Double.NaN; velocityAt = at
        }
        listen(FlightControllerKey.KeyIsFlying, "是否飞行") { value, _ -> flying = value }
        listen(FlightControllerKey.KeyAreMotorsOn, "电机状态") { value, _ -> motorsOn = value }
        listen(FlightControllerKey.KeyGPSSignalLevel, "GPS 信号") { value, _ ->
            gpsGood = value == GPSSignalLevel.LEVEL_4 || value == GPSSignalLevel.LEVEL_5 || value == GPSSignalLevel.LEVEL_10
        }
        listen(FlightControllerKey.KeyIsVisionSensorUsed, "下视定位") { value, _ -> visionUsed = value == true }
        listen(FlightControllerKey.KeyFlightMode, "飞行模式") { value, _ -> mode = value ?: FlightMode.UNKNOWN }
        listen(BatteryKey.KeyChargeRemainingInPercent, "电池电量") { value, _ -> battery = value ?: -1 }
        listen(FlightControllerKey.KeyIsLandingConfirmationNeeded, "降落确认状态") { value, _ -> confirmationNeeded = value == true }
        if (aircraftOwner.get() == null || aircraftOwner.get() === this) {
            sticks.setVirtualStickStateListener(listener); stickListenerAttached = true
        }
    }
    private fun telemetry() = Telemetry(altitude, yaw, horizontalSpeed, verticalSpeed,
        connected, flying == true, motorsOn == true, gpsGood || visionUsed,
        mode == FlightMode.GPS_NORMAL || mode == FlightMode.GPS_TRIPOD || mode == FlightMode.VIRTUAL_STICK,
        battery, if (flying == null || motorsOn == null) -1 else minOf(heightAt, attitudeAt, velocityAt))

    /** No take-off command exists in this adapter. */
    fun start(next: AcousticLandingController): Long {
        check(claimed.compareAndSet(false, true)) { "上一会话/停止指令尚未确认，请遥控器接管" }
        if (!aircraftOwner.compareAndSet(null, this)) {
            claimed.set(false)
            error("其他页面的飞行控制或停止指令尚未确认，禁止新会话")
        }
        val requestedToken = fence.arm(SystemClock.elapsedRealtime())
        worker.post {
            workerSession = true
            token = requestedToken; controller = next; lastCommand = Command.ZERO
            try {
                check(!closing && fence.valid(token, SystemClock.elapsedRealtime())) { "启动已取消或界面心跳过期" }
                observeInternal()
                sticks.setVirtualStickStateListener(listener); stickListenerAttached = true
                val now = SystemClock.elapsedRealtime()
                val t = telemetry()
                check(stableSince >= 0 && now - stableSince >= 2000) { "需要连续稳定遥测/悬停至少 2 秒" }
                check(stickState?.isVirtualStickEnable != true) { "已有其他虚拟摇杆会话，请先结束该会话" }
                // Validate before obtaining authority. Actual start revalidates after authority confirmation.
                check(AcousticLandingController.movementIssue(now, t) == null && t.battery >= 30 &&
                    AcousticLandingController.stationary(t)) { "当前遥测不满足启动条件" }
                acquisitionAt = now; awaitingReady = true; acquiring = true
                detail = "正在申请控制权；请保持遥控器摇杆回中"
                sticks.enableVirtualStick(completion({
                    acquiring = false; enabled = true
                    if (!allowed()) stopInternal("启动已取消；释放迟到的控制权")
                    else sticks.setVirtualStickAdvancedModeEnabled(true)
                }, {
                    acquiring = false; enabled = true // A failed acknowledgement is not proof the aircraft stayed disabled.
                    stopInternal("申请控制权失败：$it")
                }))
            } catch (error: Exception) {
                // Synchronous SDK failure has no callback to wait for.
                if (acquiring) enabled = true
                acquiring = false
                stopInternal("未启动：${error.message}")
            }
        }
        return requestedToken
    }
    fun heartbeat(session: Long) { fence.heartbeat(session, SystemClock.elapsedRealtime()) }
    private fun allowed() = !stopping && !closing && fence.valid(token, SystemClock.elapsedRealtime())

    fun requestLanding(session: Long) { worker.post {
        if (session != token) return@post
        try {
            check(session == token && allowed() && ready) { "当前会话已停止或控制权无效" }
            val c = controller ?: error("没有运行中的实验")
            c.requestLanding(SystemClock.elapsedRealtime(), telemetry())
            sendRaw(Command.ZERO)
            lastCommand = Command.ZERO; ready = false; releasing = true
            releaseAt = SystemClock.elapsedRealtime(); landingAt = releaseAt
            sticks.disableVirtualStick(completion({
                releasing = false; enabled = false
                if (!allowed()) { stopInternal("降落已取消，未发出降落动作"); return@completion }
                val now = SystemClock.elapsedRealtime(); val t = telemetry()
                if (AcousticLandingController.movementIssue(now, t) != null || !AcousticLandingController.stationary(t) ||
                    kotlin.math.abs(t.altitude - c.initialAltitude()) > AcousticLandingController.MAX_ALTITUDE_DRIFT) {
                    stopInternal("降落交接时状态变化，未发出降落动作"); return@completion
                }
                landingStartPending = true; landingMayBeActive = true
                try {
                    keys.performAction(KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding), actionCallback({
                        landingStartPending = false; landingMayBeActive = true
                        if (allowed()) c.landingAccepted() else { stopInternal("迟到的降落回调，正在取消"); cancelLanding() }
                    }, {
                        landingStartPending = false; stopInternal("降落请求失败：$it")
                    }))
                } catch (error: Exception) {
                    landingStartPending = false; stopInternal("降落请求异常：${error.message}")
                }
            }, {
                releasing = false; releaseBlocked = true
                stopInternal("释放控制权失败，未发出降落动作：$it")
            }))
        } catch (error: Exception) {
            releasing = false
            stopInternal("降落未执行：${error.message}")
        }
    } }

    fun confirmLanding(session: Long) { worker.post {
        if (session != token || !allowed() || controller?.state() != State.LANDING ||
            !confirmationNeeded || confirmPending) return@post
        val t = telemetry()
        if (!AcousticLandingController.fresh(SystemClock.elapsedRealtime(), t) || !t.flying || !t.motorsOn) {
            stopInternal("最终降落确认前遥测无效，请遥控器接管"); return@post
        }
        confirmPending = true
        try {
            keys.performAction(KeyTools.createKey(FlightControllerKey.KeyConfirmLanding), actionCallback({
                confirmPending = false
                if (!allowed()) { landingMayBeActive = true; cancelLanding() }
            }, { confirmPending = false; stopInternal("DJI 最终降落确认失败：$it") }))
        } catch (error: Exception) { confirmPending = false; stopInternal("降落确认异常：${error.message}") }
    } }

    /** Immediate cross-thread invalidation; no confirmation dialog, no motor-stop command. */
    fun emergencyStop(reason: String = "人工紧急制动") {
        aircraftOwner.get()?.takeIf { it !== this }?.emergencyStop(reason)
        fence.cancel()
        worker.postAtFrontOfQueue {
            releaseBlocked = false; cancelBlocked = false
            if (claimed.get() && workerSession) stopInternal(reason)
        }
    }
    fun close() {
        emergencyStop("页面销毁，请遥控器接管")
        worker.post { closing = true }
    }

    private fun stopInternal(reason: String) {
        fence.cancel()
        if (!stopping) { stopAt = SystemClock.elapsedRealtime(); detail = reason }
        stopping = true; ready = false; awaitingReady = false
        lastCommand = Command.ZERO; controller?.abort(reason)
        // Zero is sent immediately, followed by a short zero-command interval before relinquishing.
        if (enabled) runCatching { sendRaw(Command.ZERO) }
        cancelLanding()
    }
    private fun cancelLanding() {
        if (!landingMayBeActive || cancelPending || cancelBlocked) return
        cancelPending = true
        try {
            keys.performAction(KeyTools.createKey(FlightControllerKey.KeyStopAutoLanding), actionCallback({
                cancelPending = false; landingMayBeActive = false
            }, {
                cancelPending = false; cancelBlocked = true
                detail = "取消降落失败：$it；立即遥控器接管，可再按紧急制动重试"
            }))
        } catch (error: Exception) {
            cancelPending = false; cancelBlocked = true
            detail = "取消降落异常：${error.message}；立即遥控器接管"
        }
    }
    private fun release() {
        if (!enabled || releasing || releaseBlocked) return
        releasing = true; releaseAt = SystemClock.elapsedRealtime()
        try {
            sticks.disableVirtualStick(completion({ enabled = false; releasing = false }, {
                releasing = false; releaseBlocked = true
                detail = "释放控制权失败：$it；立即遥控器接管，可再按紧急制动重试"
            }))
        } catch (error: Exception) {
            releasing = false; releaseBlocked = true
            detail = "释放控制权异常：${error.message}；立即遥控器接管"
        }
    }
    private fun sendRaw(command: Command) {
        val state = stickState ?: return
        if (!state.isVirtualStickEnable || !state.isVirtualStickAdvancedModeEnabled ||
            state.currentFlightControlAuthorityOwner != FlightControlAuthority.MSDK) return
        // Recheck immediately before dispatch: UI can cancel between the control tick and this call.
        val outgoing = if (command.magnitude() > 0 && !allowed()) Command.ZERO else command
        require(outgoing.magnitude() <= 0.300001 && (outgoing.magnitude() == 0.0 || yaw.isFinite())) { "速度/航向越界" }
        val heading = if (outgoing.magnitude() == 0.0) 0.0 else yaw
        val param = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            roll = outgoing.forward(heading); pitch = outgoing.right(heading)
            this.yaw = 0.0; verticalThrottle = 0.0
        }
        sticks.sendVirtualStickAdvancedParam(param)
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        if (observing && !closing) probes.forEach { it.poll(now) }
        val t = telemetry()
        val stable = AcousticLandingController.movementIssue(now, t) == null &&
            t.battery >= 30 && AcousticLandingController.stationary(t)
        if (stable) { if (stableSince < 0) stableSince = now } else stableSince = -1
        if (workerSession && claimed.get() && !stopping && !allowed()) stopInternal("界面心跳超过 350 ms 或会话已取消")
        val c = controller
        if (workerSession && claimed.get() && !stopping && c != null) {
            if (awaitingReady) {
                val s = stickState
                if (!acquiring && enabled && s?.isVirtualStickEnable == true && s.isVirtualStickAdvancedModeEnabled &&
                    s.currentFlightControlAuthorityOwner == FlightControlAuthority.MSDK) {
                    c.start(now, t); ready = true; awaitingReady = false
                } else if (now - acquisitionAt > 5000) stopInternal("获取控制权超过 5 秒；请遥控器接管")
            }
            if (!awaitingReady && !stopping) {
                lastCommand = c.tick(now, t)
                when (c.state()) {
                    State.RUNNING, State.HOVERING -> { check(ready) { "控制权未就绪" }; sendRaw(lastCommand) }
                    State.LANDING_REQUESTED -> if (now - landingAt > 5000) stopInternal("降落请求超过 5 秒未确认")
                    State.COMPLETE -> { landingMayBeActive = false; stopInternal(c.reason()) }
                    State.ABORTED -> stopInternal(c.reason())
                    else -> Unit
                }
                if (!stopping) detail = c.reason()
            }
        }
        if (stopping) {
            if (enabled) runCatching { sendRaw(Command.ZERO) }
            if (now - stopAt >= 300) release()
            cancelLanding()
            if (!acquiring && !enabled && !releasing && !landingStartPending && !landingMayBeActive &&
                !cancelPending && !confirmPending) {
                stopping = false; workerSession = false; fence.cancel()
                aircraftOwner.compareAndSet(this, null); claimed.set(false)
                detail += "；App 控制已结束，请确认遥控器可控"
            } else if ((releasing && now - releaseAt > 5000) || now - stopAt > 5000) {
                detail = "停止/控制权交接尚未确认；立即使用遥控器接管，禁止重新启动"
            }
        }
        val receiving = AcousticLandingController.fresh(now, t)
        val telemetryStatus = when {
            !observing -> "尚未订阅：等待 SDK 注册与手机授权"
            !connectionKnown -> "尚未收到飞控连接状态；请检查遥控器 USB 连接"
            !connected -> "飞控未连接；检查飞机开机、遥控器配对与数据线"
            receiving -> "关键遥测齐全且新鲜"
            else -> "已连接，但遥测缺失、无效或过期；查看下方逐项诊断"
        }
        val hardwareAt = probes.filter { it.critical }.maxOfOrNull { it.lastHardwareAt } ?: -1
        val readStatus = when {
            !connected -> "尚未确认飞机参数回包"
            hardwareAt < 0 -> "尚未确认硬件读取回包（推送接收与主动读取分别检查）"
            now - hardwareAt > AcousticLandingController.TELEMETRY_STALE_MS -> "硬件回包已过期：${now - hardwareAt} ms 前"
            else -> "已收到硬件参数回包：${now - hardwareAt} ms 前（只读，不起飞/不移动）"
        }
        val idleDetail = if (c == null && observing) when {
            !receiving -> telemetryStatus
            flying == false -> "通信已建立；飞机在地面，尚不满足启动条件"
            else -> AcousticLandingController.movementIssue(now, t) ?: "遥测正常，等待人工启动"
        } else detail
        val displayedDetail = if (!claimed.get() && aircraftOwner.get() != null)
            "上一页面控制/停止尚未确认；请遥控器接管，禁止重新启动" else idleDetail
        val controlStatus = when {
            stopping || releasing -> "正在停止/交还控制权"
            ready -> "已确认 MSDK 控制权与高级模式"
            awaitingReady -> "申请中，尚未发送队列移动"
            landingMayBeActive -> "已交给 DJI 处理人工降落请求"
            else -> "未申请控制权（勾选实机模式不会起飞或移动）"
        }
        snapshot = Snapshot(c?.state() ?: State.IDLE, displayedDetail, busy,
            !busy && stableSince >= 0 && now - stableSince >= 2000,
            !stopping && ready && c?.canRequestLanding(now) == true,
            !stopping && !confirmPending && confirmationNeeded && c?.state() == State.LANDING,
            t, mode.name, c?.consumed() ?: 0, c?.total() ?: 0, c?.bearing(), lastCommand,
            c?.initialAltitude() ?: Double.NaN, c?.commandedPath() ?: 0.0, now, token,
            if (connectionKnown) connected else null, telemetryStatus, readStatus, controlStatus,
            probes.map { it.describe(now) }, connected && hardwareAt >= 0 && now - hardwareAt <= AcousticLandingController.TELEMETRY_STALE_MS)
    }
    private fun completion(success: () -> Unit, failure: (String) -> Unit) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() { worker.post { runCatching(success).onFailure { stopInternal("SDK 回调异常：${it.message}") } } }
        override fun onFailure(error: IDJIError) { worker.post { failure(error.description()) } }
    }
    private fun actionCallback(success: () -> Unit, failure: (String) -> Unit) = object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
        override fun onSuccess(value: EmptyMsg?) { worker.post { runCatching(success).onFailure { stopInternal("动作回调异常：${it.message}") } } }
        override fun onFailure(error: IDJIError) { worker.post { failure(error.description()) } }
    }
}
