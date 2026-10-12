package dji.sampleV5.aircraft.acoustics

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import dji.sampleV5.aircraft.DJIAircraftMainActivity
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController
import dji.sampleV5.aircraft.acoustics.core.AcousticLandingController.*
import dji.sampleV5.aircraft.acoustics.core.DirectionQueue
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

class AcousticLandingActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val sdk: MSDKManagerVM by globalViewModels()
    private var bridge: DjiFlightBridge? = null
    private var controller: AcousticLandingController? = null
    private var queue: DirectionQueue? = null
    private var active = false
    private var acquiring = false
    private var pendingAt = 0L
    private var nextDirectionAt = 0L
    private var landingTransfer = false
    private var landingRequestedAt = 0L
    private var liveSession = false
    private var targetLatitude: Double? = null
    private var targetLongitude: Double? = null
    private var simNorth = -4.0
    private var simEast = 0.0
    private var simHeight = 4.0
    private var simYaw = 0.0
    private var lastTick = 0L
    private var lastBearing: Double? = null
    private var lastCommand = Command.ZERO
    private var log: PrintWriter? = null
    private var logPath = ""
    private var registerStatus = "初始化中"
    private var message = "默认模拟：无人机位于目标南侧 4 m、高 4 m；目标位于原点。"
    private val editable = mutableListOf<View>()
    private lateinit var live: CheckBox
    private lateinit var arrival: Spinner
    private lateinit var bearings: EditText
    private lateinit var speed: EditText
    private lateinit var radius: EditText
    private lateinit var latitude: EditText
    private lateinit var longitude: EditText
    private lateinit var yaw: EditText
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var arrived: Button
    private lateinit var confirm: Button
    private lateinit var status: TextView
    private lateinit var plot: PositionPlot

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            try { step(SystemClock.elapsedRealtime()) }
            catch (error: Exception) { fault("控制停止：${error.message}") }
            if (active) handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        sdk.lvRegisterState.observe(this) {
            registerStatus = if (it.first) "已注册" else "注册失败：${it.second?.description()}"
            render()
        }
        render()
    }

    private fun buildUi() {
        val prefs = getPreferences(MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(18, 12, 18, 12)
        }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 20, 0) }
        val scroll = ScrollView(this).apply { addView(settings) }
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 15f; settings.addView(this) }
        fun field(title: String, key: String, default: String, numeric: Boolean = false): EditText {
            label(title)
            return EditText(this).apply {
                setText(prefs.getString(key, default))
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                settings.addView(this)
                editable.add(this)
            }
        }
        label("声学自主降落 · 第一阶段").textSize = 22f
        label("偏离角：目标 → 无人机，北 0° / 东 90°；控制器沿反方向修正。每 500 ms 一个采样。")
        live = CheckBox(this).apply { text = "实机发送（默认关闭；需先人工起飞）"; settings.addView(this) }
        editable.add(live)
        live.setOnCheckedChangeListener { _, checked ->
            if (checked && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 31)
            }
            if (checked) runCatching {
                val adapter = bridge ?: DjiFlightBridge(::fault) { render() }.also { bridge = it }
                adapter.observe()
            }.onFailure { message = "等待 DJI 初始化：${it.message}" }
            render()
        }
        label("到达判据")
        arrival = Spinner(this).apply {
            adapter = ArrayAdapter(this@AcousticLandingActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("位置判据：模拟位置 / 实机 GPS", "人工确认：已到目标正上方"))
            settings.addView(this)
        }
        editable.add(arrival)
        bearings = field("方向队列（角度、WAIT；180*40 表示重复 40 次）", "queue", "180*40")
        speed = field("水平速度 m/s（0.05–0.5）", "speed", "0.3", true)
        radius = field("到达半径 m（0.5–3；GPS 不保证此精度）", "radius", "0.8", true)
        latitude = field("实机目标纬度 WGS84（位置判据必填）", "latitude", "", true)
        longitude = field("实机目标经度 WGS84（位置判据必填）", "longitude", "", true)
        yaw = field("模拟机头方向 °（实机使用航向遥测）", "yaw", "0", true)
        start = Button(this).apply { settings.addView(this); setOnClickListener { begin() } }
        arrived = Button(this).apply {
            text = "确认已到目标正上方"
            settings.addView(this)
            setOnClickListener {
                runCatching { controller?.confirmOperatorArrival() }.onFailure { toast(it.message ?: "确认失败") }
            }
        }
        confirm = Button(this).apply {
            text = "确认 DJI 最终降落"
            settings.addView(this)
            setOnClickListener { runCatching { bridge?.confirmLanding() }.onFailure { fault(it.message ?: "确认失败") } }
        }
        stop = Button(this).apply {
            text = "停止 / 取消降落 / 交还遥控器"
            setTextColor(Color.RED)
            settings.addView(this)
            setOnClickListener {
                if (!active && bridge?.busy == true) runCatching { bridge?.stop() }
                fault("操作员停止实验")
            }
        }
        Button(this).apply {
            text = "DJI 注册与连接诊断"
            settings.addView(this)
            editable.add(this)
            setOnClickListener { startActivity(Intent(this@AcousticLandingActivity, DJIAircraftMainActivity::class.java)) }
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results, LinearLayout.LayoutParams(0, -1, 1f))
        status = TextView(this).apply { textSize = 16f }
        val statusScroll = ScrollView(this).apply { addView(status) }
        results.addView(statusScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        plot = PositionPlot().apply { results.addView(this, LinearLayout.LayoutParams(-1, 0, 1f)) }
        setContentView(root)
    }

    private fun begin() {
        if (active) return
        try {
            check(bridge?.busy != true) { "上一条 DJI 指令仍在处理，请等待；必要时遥控器接管" }
            val config = Config(if (arrival.selectedItemPosition == 0) ArrivalMode.POSITION else ArrivalMode.OPERATOR,
                speed.text.toString().toDouble(), radius.text.toString().toDouble())
            val source = DirectionQueue.parse(bearings.text.toString())
            liveSession = live.isChecked
            targetLatitude = null
            targetLongitude = null
            if (liveSession) {
                check(sdk.lvRegisterState.value?.first == true) { "DJI SDK 尚未注册成功，请配置 API Key 并连接遥控器" }
                check(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED) { "请先授予精确位置权限" }
                if (config.arrivalMode == ArrivalMode.POSITION) {
                    targetLatitude = latitude.text.toString().toDouble()
                    targetLongitude = longitude.text.toString().toDouble()
                    require(AcousticLandingController.validCoordinate(targetLatitude!!, targetLongitude!!) &&
                        !(targetLatitude == 0.0 && targetLongitude == 0.0)) { "请填写实际目标经纬度" }
                }
            }
            simYaw = yaw.text.toString().toDouble()
            require(simYaw.isFinite()) { "模拟航向必须为有限角度" }
            queue = source
            controller = AcousticLandingController(config)
            simNorth = -4.0; simEast = 0.0; simHeight = 4.0
            lastBearing = null; lastCommand = Command.ZERO; landingTransfer = false
            plot.reset()
            val now = SystemClock.elapsedRealtime()
            if (liveSession) {
                val adapter = bridge ?: DjiFlightBridge(::fault) { render() }.also { bridge = it }
                adapter.observe()
                // Validate using the same guards as the runtime loop before acquiring authority.
                val preflight = AcousticLandingController(config)
                preflight.start(now)
                preflight.tick(now, adapter.telemetry(targetLatitude, targetLongitude))
                check(preflight.state() != State.ABORTED) { preflight.reason() }
            }
            saveSettings()
            openLog()
            active = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (liveSession) {
                acquiring = true
                pendingAt = now
                message = "申请虚拟摇杆，等待确认控制权"
                bridge!!.acquire { if (active && acquiring) startController() }
            } else startController()
            render()
            handler.post(ticker)
        } catch (error: Exception) {
            if (active) fault(error.message ?: "启动失败") else {
                controller = null
                message = "未启动：${error.message}"
                toast(message)
                render()
            }
        }
    }

    private fun startController() {
        acquiring = false
        val now = SystemClock.elapsedRealtime()
        controller!!.start(now)
        nextDirectionAt = now
        lastTick = now
    }

    private fun step(now: Long) {
        val c = controller ?: return
        if (acquiring) {
            if (now - pendingAt > 5000) fault("获取控制权超过 5 秒；请检查连接并遥控器接管")
            render()
            return
        }
        val telemetry = if (liveSession) bridge!!.telemetry(targetLatitude, targetLongitude) else
            Telemetry(simNorth, simEast, simHeight, simYaw, true, simHeight > 0, true, now)
        if (c.isNavigating && now >= nextDirectionAt) {
            if (now - nextDirectionAt >= DirectionQueue.PERIOD_MS) {
                fault("方向调度延迟超过 500 ms，停止实验")
                return
            }
            val source = queue!!
            if (!source.hasNext()) { fault("方向队列结束，停止修正；队列结束不表示到达"); return }
            lastBearing = source.next()
            c.acceptBearing(lastBearing, now, now)
            nextDirectionAt += DirectionQueue.PERIOD_MS
        }
        val command = c.tick(now, telemetry)
        lastCommand = command
        when (c.state()) {
            State.ABORTED -> { fault(c.reason()); return }
            State.LANDING_REQUESTED -> if (!landingTransfer) {
                landingTransfer = true
                landingRequestedAt = now
                if (liveSession) bridge!!.startLanding { if (active) controller?.landingAccepted() }
                else c.landingAccepted()
            } else if (now - landingRequestedAt > 5000) {
                fault("自动降落请求超过 5 秒未确认，请遥控器接管"); return
            }
            State.LANDING -> {
                if (liveSession) {
                    if (!telemetry.connected || now - telemetry.timestamp > c.config.staleMs) {
                        fault("降落时连接或遥测丢失，请遥控器接管"); return
                    }
                    if (!telemetry.flying) { c.landed(); bridge!!.markLanded() }
                } else {
                    simHeight = max(0.0, simHeight - 0.5 * (now - lastTick) / 1000.0)
                    if (simHeight == 0.0) c.landed()
                }
            }
            State.ALIGNING, State.HOLDING -> {
                if (liveSession) bridge!!.send(command, telemetry.yaw)
                else {
                    simNorth += command.north * (now - lastTick) / 1000.0
                    simEast += command.east * (now - lastTick) / 1000.0
                }
            }
            else -> Unit
        }
        log?.println(listOf(now, c.state(), lastBearing ?: "", telemetry.north, telemetry.east,
            telemetry.altitude, telemetry.yaw, command.north, command.east, queue!!.consumed()).joinToString(","))
        log?.flush()
        plot.add(telemetry.north, telemetry.east, c.config.arrivalRadius)
        lastTick = now
        message = c.reason()
        if (c.state() == State.COMPLETE) finishRun()
        render(telemetry)
    }

    private fun fault(detail: String) {
        val wasActive = active
        message = detail
        controller?.abort(detail)
        // Do not recursively retry failed release/cancel actions through onFault.
        if (wasActive) {
            active = false
            acquiring = false
            if (liveSession) runCatching { bridge?.stop() }
            log?.println("# stopped: ${detail.replace('\n', ' ')}")
            finishRun()
        }
        if (::status.isInitialized) render()
    }

    private fun finishRun() {
        active = false
        acquiring = false
        handler.removeCallbacks(ticker)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log?.close()
        log = null
    }

    private fun openLog() {
        val folder = getExternalFilesDir("experiments") ?: File(filesDir, "experiments")
        check(folder.exists() || folder.mkdirs()) { "无法创建实验日志目录" }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
        val file = File(folder, "${if (liveSession) "live" else "simulation"}_$name.csv")
        log = PrintWriter(file)
        logPath = file.absolutePath
        log!!.println("# mode=${if (liveSession) "live" else "simulation"}; arrival=${controller!!.config.arrivalMode}")
        log!!.println("# target_wgs84=$targetLatitude,$targetLongitude; queue=${bearings.text}")
        log!!.println("monotonic_ms,state,deviation_deg,north_m,east_m,relative_altitude_m,yaw_deg,command_north_mps,command_east_mps,samples_consumed")
    }

    private fun saveSettings() {
        getPreferences(MODE_PRIVATE).edit().apply {
            putString("queue", bearings.text.toString()); putString("speed", speed.text.toString())
            putString("radius", radius.text.toString()); putString("latitude", latitude.text.toString())
            putString("longitude", longitude.text.toString()); putString("yaw", yaw.text.toString())
        }.apply()
    }

    private fun render(telemetry: Telemetry? = null) {
        start.text = if (live.isChecked) "启动实机控制（当前模拟方向队列）" else "启动模拟实验"
        start.isEnabled = !active
        editable.forEach { it.isEnabled = !active }
        stop.isEnabled = active || bridge?.busy == true
        arrived.isEnabled = active && !acquiring && controller?.isNavigating == true &&
                controller?.config?.arrivalMode == ArrivalMode.OPERATOR
        confirm.isEnabled = active && liveSession && controller?.state() == State.LANDING && bridge?.confirmationNeeded == true
        val position = telemetry?.let { "位置 N/E：%.2f / %.2f m\n相对高度：%.2f m\n距离判据：%.2f m\n".format(
            it.north, it.east, it.altitude, hypot(it.north, it.east)) } ?: ""
        status.text = "${if (active && liveSession) "实机控制流程中" else if (active) "模拟中" else if (bridge?.busy == true)
            "已停止发送；DJI 指令仍在处理，请留意遥控器接管" else "未控制无人机"}\n" +
                "SDK：$registerStatus\n状态：${controller?.state() ?: State.IDLE}\n$message\n" + position +
                "方向：${lastBearing?.let { "%.1f°".format(it) } ?: "WAIT / 未输入"}\n" +
                "速度 N/E：%.2f / %.2f m/s\n".format(lastCommand.north, lastCommand.east) +
                "队列：${queue?.consumed() ?: 0} / ${queue?.size() ?: 0}\n" +
                "日志：$logPath\n目标点为图中绿圈。实机定位轨迹仅在 GPS 判据下显示。"
    }

    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onPause() {
        if (active) fault("页面离开前台，停止实验并交还遥控器")
        super.onPause()
    }
    override fun onDestroy() {
        finishRun()
        runCatching { bridge?.close() }
        super.onDestroy()
    }

    private inner class PositionPlot : View(this) {
        private val points = mutableListOf<Pair<Float, Float>>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var arrivalRadius = 0.8
        fun reset() { points.clear(); invalidate() }
        fun add(north: Double, east: Double, radius: Double) {
            if (!north.isFinite() || !east.isFinite()) return
            if (points.size >= 1500) points.removeAt(0)
            points.add(north.toFloat() to east.toFloat())
            arrivalRadius = radius
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(16, 25, 36))
            val cx = width / 2f; val cy = height / 2f
            val range = max(6f, points.maxOfOrNull { max(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) + 1 } ?: 6f)
            val scale = minOf(width, height) / (2f * range)
            paint.color = Color.DKGRAY; paint.strokeWidth = 2f
            canvas.drawLine(cx, 0f, cx, height.toFloat(), paint)
            canvas.drawLine(0f, cy, width.toFloat(), cy, paint)
            paint.color = Color.GREEN; paint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, (arrivalRadius * scale).toFloat(), paint)
            paint.color = Color.CYAN
            points.zipWithNext().forEach { (a, b) ->
                canvas.drawLine(cx + a.second * scale, cy - a.first * scale,
                    cx + b.second * scale, cy - b.first * scale, paint)
            }
            paint.style = Paint.Style.FILL
            points.lastOrNull()?.let { canvas.drawCircle(cx + it.second * scale, cy - it.first * scale, 7f, paint) }
            paint.color = Color.WHITE; paint.textSize = 24f
            canvas.drawText("N ↑   E →   ±${range.toInt()} m", 12f, 30f, paint)
        }
    }
}
