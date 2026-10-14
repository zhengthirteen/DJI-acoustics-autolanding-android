package dji.sampleV5.aircraft.acoustics

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import kotlin.math.max

class AcousticLandingActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val sdk: MSDKManagerVM by globalViewModels()
    private var bridge: DjiFlightBridge? = null
    private var simulation: AcousticLandingController? = null
    private var shown = DjiFlightBridge.Snapshot()
    private var active = false
    private var foreground = false
    private var liveSession = false
    private var session = 0L
    private var dialog: AlertDialog? = null
    private var lastTick = 0L
    private var simNorth = 0.0
    private var simEast = 0.0
    private var simHeight = 4.0
    private var simYaw = 0.0
    private var simCommand = Command.ZERO
    private var log: PrintWriter? = null
    private var logPath = "尚无日志"
    private var registerStatus = "初始化中"
    private var message = "先设置队列，再人工起飞和定位，最后点击启动；队列结束只悬停。"
    private val editable = mutableListOf<View>()
    private lateinit var live: CheckBox
    private lateinit var bearings: EditText
    private lateinit var speed: EditText
    private lateinit var yaw: EditText
    private lateinit var start: Button
    private lateinit var land: Button
    private lateinit var confirm: Button
    private lateinit var status: TextView

    private val ticker = object : Runnable {
        override fun run() {
            if (!foreground) return
            try {
                val now = SystemClock.elapsedRealtime()
                if (active && liveSession) {
                    bridge?.heartbeat(session)
                    val next = bridge!!.snapshot
                    if (next.session == session) {
                        shown = next
                        if (!next.busy) finishRun()
                    }
                } else if (active) stepSimulation(now)
                else if (live.isChecked || bridge?.busy == true) shown = bridge?.snapshot ?: shown
                if (active) writeLog(now)
                render()
            } catch (error: Exception) { stop("界面/日志异常：${error.message}") }
            if (foreground) handler.postDelayed(this, 100)
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
    private fun adapter(): DjiFlightBridge = bridge ?: DjiFlightBridge().also { bridge = it; it.observe() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun buildUi() {
        val prefs = getPreferences(MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        // Always visible: these controls never sit inside the scrolling settings panel.
        val emergency = Button(this).apply {
            text = "紧急制动（停止序列 / 取消本次降落）"; textSize = 18f
            setBackgroundColor(Color.rgb(180, 24, 24)); setTextColor(Color.WHITE)
            minHeight = dp(60); setOnClickListener { stop("人工紧急制动；停止队列并交还遥控器") }
        }
        root.addView(emergency, LinearLayout.LayoutParams(-1, dp(64)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(16), 0) }
        body.addView(ScrollView(this).apply { addView(settings) }, LinearLayout.LayoutParams(0, -1, 1f))
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 15f; settings.addView(this) }
        fun field(title: String, key: String, default: String, numeric: Boolean = false) = EditText(this).apply {
            label(title); setText(prefs.getString(key, default))
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            settings.addView(this); editable.add(this)
        }
        label("声学方向序列实验 · 人工决定降落").textSize = 20f
        label("偏离角为目标→无人机：0→向南，90→向西，180→向北，270→向东；WAIT→悬停。每项 0.5 秒。")
        live = CheckBox(this).apply { text = "实机模式（每次进入默认关闭）"; settings.addView(this) }
        editable.add(live)
        live.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 31)
                adapter().observe()
            }
            render()
        }
        bearings = field("方向队列（最多 120 项 / 60 秒）", "queue_manual_v2", "WAIT*20")
        speed = field("水平速度 m/s（0.05–0.3；首次用 0.1）", "speed_manual_v2", "0.1", true)
        yaw = field("本地模拟机头角度 °（实机自动读取航向）", "yaw_manual_v2", "0", true)
        Button(this).apply {
            text = "检查并保存队列"; settings.addView(this); editable.add(this)
            setOnClickListener { runCatching {
                val c = configuredController(); saveSettings()
                message = "队列已保存：${c.total()} 项，${c.total() * 0.5} 秒；结束后只悬停。"; render()
            }.onFailure { toast(it.message ?: "队列无效") } }
        }
        start = Button(this).apply { settings.addView(this); setOnClickListener { prepareStart() } }
        land = Button(this).apply {
            text = "人工申请降落…"; settings.addView(this)
            setOnClickListener { showConfirmation("确认降落", "请目视确认无人机正下方是可降落区域，且人员已离开。确定后将启动 DJI 自动降落。") {
                if (liveSession) bridge?.requestLanding(session)
                else runCatching { simulation?.requestLanding(SystemClock.elapsedRealtime(), simTelemetry(SystemClock.elapsedRealtime())) }
                    .onFailure { stop("降落未执行：${it.message}") }
            } }
        }
        confirm = Button(this).apply {
            text = "DJI 要求最终降落确认…"; settings.addView(this)
            setOnClickListener { showConfirmation("最终降落确认", "飞控要求再次确认。确认实际落区安全后才继续；不确定时取消并使用遥控器。") {
                bridge?.confirmLanding(session)
            } }
        }
        Button(this).apply {
            text = "结束控制 / 交还遥控器"; settings.addView(this)
            setOnClickListener { stop("人工结束控制，请接管遥控器") }
        }
        Button(this).apply {
            text = "DJI 注册与连接状态（只读）"; settings.addView(this); editable.add(this)
            setOnClickListener {
                adapter().observe()
                dialog = AlertDialog.Builder(this@AcousticLandingActivity).setTitle("DJI 状态（只读）")
                    .setMessage("SDK：$registerStatus\n${status.text}\n若数据尚未更新，请关闭后等几秒重试。")
                    .setPositiveButton("关闭", null).show()
            }
        }
        label("无 GPS 时必须有可用下视定位。不能遮挡下视/避障传感器。零速度不保证无漂移；遥控器须随时可接管。")
        status = TextView(this).apply { textSize = 16f; setPadding(dp(6), dp(6), 0, 0) }
        body.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
    }
    private fun configuredController() = AcousticLandingController(Config(speed.text.toString().toDouble()), DirectionQueue.parse(bearings.text.toString()))
    private fun prepareStart() {
        if (active || bridge?.busy == true) return
        runCatching {
            val c = configuredController()
            if (live.isChecked) {
                check(sdk.lvRegisterState.value?.first == true) { "DJI SDK 尚未注册成功" }
                check(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "请先授予手机精确位置权限" }
                check(adapter().snapshot.canStart) { "先手动起飞、定位并稳定悬停，等待有效遥测；请查看右侧状态" }
                showConfirmation("开始实机序列", "确认已手动飞到目标上方并稳定悬停，遥控器摇杆回中。\n${c.total()} 项 / ${c.total() * 0.5} 秒，速度 ${c.config.speed} m/s。\n程序会按预设角度移动，结束后只悬停；不判断是否到达目标。") { begin(c, true) }
            } else begin(c, false)
        }.onFailure { toast(it.message ?: "无法启动") }
    }
    private fun begin(c: AcousticLandingController, real: Boolean) {
        if (!foreground || active) return
        try {
            saveSettings(); liveSession = real
            if (!real) {
                val angle = yaw.text.toString().toDouble(); require(angle.isFinite()) { "模拟航向必须为有限数值" }
                simYaw = DirectionQueue.normalize(angle + 180) - 180
                simNorth = 0.0; simEast = 0.0; simHeight = 4.0; simCommand = Command.ZERO
                simulation = c; c.start(SystemClock.elapsedRealtime(), simTelemetry(SystemClock.elapsedRealtime()))
            }
            openLog(c); active = true; lastTick = SystemClock.elapsedRealtime()
            shown = DjiFlightBridge.Snapshot(detail = "正在启动…", busy = true, total = c.total())
            if (real) session = adapter().start(c)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            message = "正在执行实验"
            render()
        } catch (error: Exception) { stop("启动失败：${error.message}") }
    }
    private fun simTelemetry(now: Long) = Telemetry(simHeight, simYaw, simCommand.magnitude(), 0.0,
        true, simHeight > 0, simHeight > 0, true, true, 100, now)
    private fun stepSimulation(now: Long) {
        val c = simulation ?: return
        val dt = if (now - lastTick <= AcousticLandingController.MAX_TICK_GAP_MS) (now - lastTick) / 1000.0 else 0.0
        simNorth += simCommand.north * dt; simEast += simCommand.east * dt
        if (c.state() == State.LANDING) simHeight = max(0.0, simHeight - 0.5 * dt)
        val t = simTelemetry(now)
        simCommand = c.tick(now, t)
        if (c.state() == State.LANDING_REQUESTED) c.landingAccepted()
        shown = DjiFlightBridge.Snapshot(c.state(), c.reason(), c.state() != State.ABORTED && c.state() != State.COMPLETE,
            false, c.canRequestLanding(now), false, t, "LOCAL_SIMULATION", c.consumed(), c.total(), c.bearing(), simCommand,
            c.initialAltitude(), c.commandedPath(), now)
        lastTick = now
        if (!shown.busy) finishRun()
    }
    private fun showConfirmation(title: String, detail: String, action: () -> Unit) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton("取消", null)
            .setNeutralButton("紧急制动") { _, _ -> stop("人工紧急制动") }
            .setPositiveButton("确认") { _, _ -> if (foreground) action() }.show().also {
                it.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.RED)
            }
    }
    private fun stop(detail: String) {
        // Invalidate the flight session before logging, rendering or dismissing any UI.
        bridge?.emergencyStop(detail)
        dialog?.dismiss(); dialog = null
        message = detail; simCommand = Command.ZERO; simulation?.abort(detail)
        log?.println("# stop_requested: ${detail.replace('\n', ' ')}")
        log?.close(); log = null
        if (!liveSession) shown = shown.copy(state = simulation?.state() ?: State.IDLE, detail = detail, command = Command.ZERO, busy = false, canLand = false)
        if (active && liveSession && foreground && bridge?.busy == true) {
            shown = shown.copy(detail = detail, canLand = false, confirmationNeeded = false)
            render(); return
        }
        finishRun(); render()
    }
    private fun finishRun() {
        active = false; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log?.println("# logger_closed; last_observed_state=${shown.state}; ${shown.detail.replace('\n', ' ')}")
        log?.close(); log = null
    }
    private fun openLog(c: AcousticLandingController) {
        val folder = getExternalFilesDir("experiments") ?: File(filesDir, "experiments")
        check(folder.exists() || folder.mkdirs()) { "无法创建日志目录" }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
        val file = File(folder, "${if (liveSession) "live" else "simulation"}_$name.csv")
        log = PrintWriter(file); logPath = file.absolutePath
        log!!.println("# mode=${if (liveSession) "live" else "simulation"}; queue=${bearings.text.toString().replace('\n', ' ')}; speed=${c.config.speed}")
        log!!.println("monotonic_ms,state,deviation_deg,relative_altitude_m,yaw_deg,command_north_mps,command_east_mps,measured_horizontal_mps,battery_percent,positioning,samples_consumed,commanded_path_m,sim_north_m,sim_east_m")
    }
    private fun writeLog(now: Long) {
        val t = shown.telemetry ?: return
        log?.println(listOf(now, shown.state, shown.bearing ?: "", t.altitude, t.yaw, shown.command.north,
            shown.command.east, t.horizontalSpeed, t.battery, t.positioning, shown.consumed, shown.commandedPath,
            if (liveSession) "" else simNorth, if (liveSession) "" else simEast).joinToString(","))
        if (log?.checkError() == true) error("实验日志写入失败")
    }
    private fun saveSettings() { getPreferences(MODE_PRIVATE).edit()
        .putString("queue_manual_v2", bearings.text.toString()).putString("speed_manual_v2", speed.text.toString())
        .putString("yaw_manual_v2", yaw.text.toString()).apply() }
    private fun render() {
        if (!::status.isInitialized) return
        val busy = active || bridge?.busy == true
        editable.forEach { it.isEnabled = !busy }
        start.isEnabled = !busy
        start.text = if (live.isChecked) "已手动定位，启动实机序列…" else "启动本地模拟"
        land.isEnabled = active && shown.canLand
        confirm.isEnabled = active && liveSession && shown.confirmationNeeded
        val t = shown.telemetry
        val telemetry = t?.let { "相对起飞点高度：%.2f m（启动时 %.2f m）\n航向：%.1f°\n实际水平速度：%.2f m/s\n电量：%d%%\n定位可用：%s\n".format(
            it.altitude, shown.initialAltitude, it.yaw, it.horizontalSpeed, it.battery, it.positioning) } ?: ""
        val preflight = if (live.isChecked && !busy && t != null)
            "启动检查：${AcousticLandingController.movementIssue(SystemClock.elapsedRealtime(), t) ?: if (shown.canStart) "可以启动" else "等待电量≥30%且稳定悬停2秒"}\n" else ""
        val modeLabel = if (bridge?.busy == true) "实机控制或交接处理中" else if (live.isChecked) "实机模式" else "本地模拟模式"
        status.text = "$modeLabel\nSDK：$registerStatus\n状态：${shown.state}\n$message\n${shown.detail}\n" +
            preflight + telemetry + "飞行模式：${shown.flightMode}\n队列：${shown.consumed} / ${shown.total}\n" +
            "输入：${shown.bearing ?: "WAIT / 队列结束"}\n水平命令 N/E：%.2f / %.2f m/s\n".format(shown.command.north, shown.command.east) +
            "累计指令路程：%.2f m（不是实测距离）\n".format(shown.commandedPath) +
            (if (!liveSession) "模拟 N/E：%.2f / %.2f m\n".format(simNorth, simEast) else "实机无位置轨迹；移动终点须目视判断。\n") +
            "队列结束不会自动降落。紧急制动会终止本次队列并交还遥控器。\n日志：$logPath"
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onResume() { super.onResume(); foreground = true; handler.post(ticker) }
    override fun onPause() {
        foreground = false; bridge?.emergencyStop("页面离开前台，请遥控器接管")
        if (active) stop("页面离开前台，本次实验已终止")
        dialog?.dismiss(); dialog = null; handler.removeCallbacks(ticker)
        super.onPause()
    }
    override fun onDestroy() { bridge?.close(); finishRun(); super.onDestroy() }
}
