package dji.sampleV5.aircraft.acoustics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import dji.sampleV5.aircraft.acoustics.core.DirectionPlan
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
    private var productConnected: Boolean? = null
    private var telemetryRequested = false
    private var permissionRequestPending = false
    private var message = "先设置队列，再人工起飞和定位，最后点击启动；队列结束只悬停。"
    private val editable = mutableListOf<View>()
    private lateinit var live: CheckBox
    private var plan = DirectionPlan.fromText("WAIT*20")
    private lateinit var queueRows: LinearLayout
    private lateinit var queueSummary: TextView
    private val queueButtons = mutableListOf<Pair<View, Boolean>>()
    private lateinit var communication: TextView
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
                else if (telemetryRequested || bridge?.busy == true) shown = bridge?.snapshot ?: shown
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
            if (it.first && telemetryRequested) requestTelemetry(true)
            render()
        }
        sdk.lvProductConnectionState.observe(this) {
            productConnected = it.first
            if (!it.first) bridge?.productDisconnected()
            else if (telemetryRequested) requestTelemetry(true)
            render()
        }
        render()
    }
    private fun requestTelemetry(refresh: Boolean = false) {
        telemetryRequested = true
        if (sdk.lvRegisterState.value?.first != true) {
            message = "等待 DJI SDK 注册；手机需联网，并检查 Key/包名。注册成功后会自动读取。"
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            message = "请授予手机精确位置权限；授权后自动重新读取"
            if (!permissionRequestPending) {
                permissionRequestPending = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 31)
            }
            return
        }
        if (refresh) adapter().refreshTelemetry() else adapter().observe()
        message = "正在检查只读通信；勾选实机模式不会起飞或移动。"
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 31 && telemetryRequested) {
            permissionRequestPending = false
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                requestTelemetry(true)
            else message = "手机精确位置权限未授予，请在应用权限设置中允许后点“检查/刷新通信”"
            render()
        }
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
        label("选择实际移动方向（地理北/东，不是机头前/右）。每项 0.5 秒，软件自动转换成偏离角。")
        live = CheckBox(this).apply { text = "实机模式（每次进入默认关闭）"; settings.addView(this) }
        editable.add(live)
        live.setOnCheckedChangeListener { _, checked ->
            if (checked) requestTelemetry(true)
            render()
        }
        plan = runCatching { DirectionPlan.fromText(prefs.getString("queue_manual_v2", "WAIT*20")!!) }
            .getOrElse { DirectionPlan.fromText("WAIT*20") }
        buildQueueEditor(settings)
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
        label("无 GPS 时必须有可用下视定位。不能遮挡下视/避障传感器。零速度不保证无漂移；遥控器须随时可接管。")
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "检查/刷新通信"; editable.add(this)
            actions.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { requestTelemetry(true); render() }
        }
        Button(this).apply {
            text = "复制诊断信息"
            actions.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener {
                val report = "App 1.2 / $packageName\n${communication.text}\n${bridge?.snapshot?.diagnostics?.joinToString("\n") ?: "尚无字段诊断"}\n${status.text}"
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText("DJI 通信诊断", report.replace(Regex("[0-9a-fA-F]{24}"), "[标识已隐藏]")))
                toast("诊断信息已复制；不包含配置文件中的 API Key")
            }
        }
        results.addView(actions)
        val readout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        communication = TextView(this).apply { textSize = 15f; setPadding(dp(6), dp(8), 0, dp(8)); readout.addView(this) }
        status = TextView(this).apply { textSize = 15f; setPadding(dp(6), dp(6), 0, 0); readout.addView(this) }
        results.addView(ScrollView(this).apply { addView(readout) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(results, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
    }
    private fun buildQueueEditor(settings: LinearLayout) {
        settings.addView(TextView(this).apply { text = "选择移动方向和时长，添加到队列；可删除、改时长和排序。" })
        val choices = DirectionPlan.Movement.values()
        val direction = Spinner(this).apply {
            adapter = ArrayAdapter(this@AcousticLandingActivity, android.R.layout.simple_spinner_dropdown_item, choices.map { it.label })
            editable.add(this)
        }
        val durations = (1..120).map { "${it * 0.5} 秒（$it 项）" }
        val duration = Spinner(this).apply {
            adapter = ArrayAdapter(this@AcousticLandingActivity, android.R.layout.simple_spinner_dropdown_item, durations)
            setSelection(1); editable.add(this)
        }
        val selectors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectors.addView(direction, LinearLayout.LayoutParams(0, dp(48), 1f))
        selectors.addView(duration, LinearLayout.LayoutParams(0, dp(48), 1f))
        settings.addView(selectors)
        Button(this).apply {
            text = "＋ 添加这一段"; settings.addView(this); editable.add(this)
            setOnClickListener { editPlan { plan.add(choices[direction.selectedItemPosition], duration.selectedItemPosition + 1) } }
        }
        val presetNames = listOf("仅悬停 10 秒（首次测试）", "向北 1 秒 → 悬停 4 秒", "向东 1 秒 → 悬停 4 秒", "向北 1 秒 → 停 2 秒 → 向南 1 秒 → 停 2 秒")
        val presetText = listOf("WAIT*20", "180*2,WAIT*8", "270*2,WAIT*8", "180*2,WAIT*4,0*2,WAIT*4")
        val preset = Spinner(this).apply {
            adapter = ArrayAdapter(this@AcousticLandingActivity, android.R.layout.simple_spinner_dropdown_item, presetNames)
            settings.addView(this); editable.add(this)
        }
        Button(this).apply {
            text = "使用所选预设（替换队列）"; settings.addView(this); editable.add(this)
            setOnClickListener { editPlan { plan = DirectionPlan.fromText(presetText[preset.selectedItemPosition]) } }
        }
        queueSummary = TextView(this).apply { textSize = 15f; settings.addView(this) }
        queueRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; settings.addView(this) }
        val extra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "清空队列"; editable.add(this); extra.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { editPlan { plan.clear() } }
        }
        Button(this).apply {
            text = "导入文本…"; editable.add(this); extra.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener {
                val input = EditText(this@AcousticLandingActivity).apply {
                    setText(plan.toText()); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                val prompt = AlertDialog.Builder(this@AcousticLandingActivity).setTitle("导入偏离角文本")
                    .setMessage("此处沿用偏离角：180→向北，0→向南；WAIT→悬停。导入会替换队列。")
                    .setView(input).setNegativeButton("取消", null).setPositiveButton("导入", null).create()
                dialog = prompt; prompt.show()
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    runCatching { plan = DirectionPlan.fromText(input.text.toString()); renderQueue(); prompt.dismiss() }
                        .onFailure { toast(it.message ?: "导入失败") }
                }
            }
        }
        settings.addView(extra)
        renderQueue()
    }
    private fun editPlan(action: () -> Unit) {
        if (active || bridge?.busy == true) return
        runCatching { action(); renderQueue() }.onFailure { toast(it.message ?: "修改失败") }
    }
    private fun renderQueue() {
        queueRows.removeAllViews(); queueButtons.clear()
        val steps = plan.steps()
        queueSummary.text = if (steps.isEmpty()) "队列为空，请选择方向并添加；空队列无法启动。" else
            "共 ${steps.size} 段 / ${plan.totalSamples()} 项 / ${plan.totalSamples() * 0.5} 秒；完成后持续悬停。"
        steps.forEachIndexed { index, step ->
            queueRows.addView(TextView(this).apply { text = "${index + 1}. ${step.label()} · ${step.samples * 0.5} 秒"; textSize = 16f })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun button(title: String, available: Boolean = true, action: () -> Unit) {
                Button(this).apply {
                    text = title; textSize = 12f; minWidth = 0; setPadding(0, 0, 0, 0)
                    actions.addView(this, LinearLayout.LayoutParams(0, dp(48), 1f))
                    queueButtons.add(this to available); isEnabled = available
                    setOnClickListener { action() }
                }
            }
            button("改时长") {
                val options = (1..120).map { "${it * 0.5} 秒（$it 项）" }.toTypedArray()
                dialog = AlertDialog.Builder(this).setTitle("第 ${index + 1} 段时长")
                    .setSingleChoiceItems(options, step.samples - 1) { picker, selected ->
                        runCatching { plan.changeDuration(index, selected + 1); renderQueue(); picker.dismiss() }
                            .onFailure { toast(it.message ?: "时长无效") }
                    }.setNegativeButton("取消", null).show()
            }
            button("上移", index > 0) { editPlan { plan.move(index, -1) } }
            button("下移", index < steps.lastIndex) { editPlan { plan.move(index, 1) } }
            button("删除") { editPlan { plan.remove(index) } }
            queueRows.addView(actions)
        }
        render()
    }
    private fun configuredController() = AcousticLandingController(Config(speed.text.toString().toDouble()), DirectionQueue.parse(plan.toText()))
    private fun prepareStart() {
        if (active || bridge?.busy == true) return
        runCatching {
            val c = configuredController()
            if (live.isChecked) {
                check(sdk.lvRegisterState.value?.first == true) { "DJI SDK 尚未注册成功" }
                check(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "请先授予手机精确位置权限" }
                check(adapter().snapshot.canStart) { "暂不能启动：${adapter().snapshot.detail}；先在地面确认右侧通信检查通过，再按手册手动起飞。" }
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
        log!!.println("# mode=${if (liveSession) "live" else "simulation"}; queue=${plan.toText()}; speed=${c.config.speed}")
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
        .putString("queue_manual_v2", plan.toText()).putString("speed_manual_v2", speed.text.toString())
        .putString("yaw_manual_v2", yaw.text.toString()).apply() }
    private fun render() {
        if (!::status.isInitialized) return
        val busy = active || bridge?.busy == true
        editable.forEach { it.isEnabled = !busy }
        queueButtons.forEach { (view, allowed) -> view.isEnabled = !busy && allowed }
        start.isEnabled = !busy
        start.text = if (live.isChecked) "已手动定位，启动实机序列…" else "启动本地模拟"
        land.isEnabled = active && shown.canLand
        confirm.isEnabled = active && liveSession && shown.confirmationNeeded
        val t = shown.telemetry
        val connection = bridge?.snapshot
        val inputOk = connection?.telemetry?.let { AcousticLandingController.fresh(SystemClock.elapsedRealtime(), it) } == true
        val registered = sdk.lvRegisterState.value?.first == true
        val summary = when {
            !telemetryRequested -> "点击“检查/刷新通信”即可地面检查，无需起飞"
            !registered -> "等待 SDK 注册（不是飞机遥测）"
            connection?.aircraftConnected != true -> "飞控通信尚未建立"
            !inputOk -> "飞控已连接；遥测尚不齐全或已过期"
            connection?.hardwareReadFresh == true -> "只读通信已确认：硬件参数请求与回包正常"
            else -> "遥测接收正常；主动读取回包尚未确认"
        }
        communication.setTextColor(if (registered && inputOk) Color.rgb(0, 150, 70) else Color.rgb(210, 130, 20))
        communication.text = "$summary\n① SDK 注册：$registerStatus\n" +
            "② 设备连接回调：${when(productConnected) { true -> "已连接"; false -> "未连接"; null -> "尚无回调" }}\n" +
            "③ 飞控连接：${when(connection?.aircraftConnected) { true -> "已连接"; false -> "未连接"; null -> "尚未收到状态" }}\n" +
            "④ 遥测：${connection?.telemetryStatus ?: "尚未订阅"}\n⑤ 只读请求：${connection?.readStatus ?: "未发送"}\n" +
            "⑥ 控制权：${connection?.controlStatus ?: "未申请"}\n连接成功不等于满足起飞后程序启动条件。"
        fun number(value: Double) = if (value.isFinite()) "%.2f".format(value) else "未收到"
        val telemetry = t?.let { "相对起飞点高度：${number(it.altitude)} m（启动时 ${if (shown.initialAltitude > 0) number(shown.initialAltitude) else "未启动"}）\n" +
            "航向：${number(it.yaw)}°\n实际水平速度：${number(it.horizontalSpeed)} m/s\n" +
            "电量：${if (it.battery in 0..100) "${it.battery}%" else "未收到"}\n定位：${if (it.positioning) "可用" else "尚未确认可用"}\n" } ?: ""
        val preflight = if (live.isChecked && !busy && t != null)
            "启动检查：${AcousticLandingController.movementIssue(SystemClock.elapsedRealtime(), t) ?: if (shown.canStart) "可以启动" else "等待电量≥30%且稳定悬停2秒"}\n" else ""
        val modeLabel = if (bridge?.busy == true) "实机控制或交接处理中" else if (live.isChecked) "实机模式" else "本地模拟模式"
        status.text = "$modeLabel\nSDK：$registerStatus\n状态：${shown.state}\n$message\n${shown.detail}\n" +
            preflight + telemetry + "飞行模式：${shown.flightMode}\n队列：${shown.consumed} / ${shown.total}\n" +
            "输入：${shown.bearing ?: "WAIT / 队列结束"}\n水平命令 N/E：%.2f / %.2f m/s\n".format(shown.command.north, shown.command.east) +
            "累计指令路程：%.2f m（不是实测距离）\n".format(shown.commandedPath) +
            (if (shown.flightMode == "LOCAL_SIMULATION") "模拟 N/E：%.2f / %.2f m\n".format(simNorth, simEast) else "实机无位置轨迹；移动终点须目视判断。\n") +
            "队列结束不会自动降落。紧急制动会终止本次队列并交还遥控器。\n日志：$logPath\n\n逐项遥测诊断：\n" +
            (connection?.diagnostics?.joinToString("\n") ?: "请先检查通信")
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
