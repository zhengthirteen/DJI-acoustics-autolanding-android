package dji.sampleV5.aircraft.acoustics.monitor

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import dji.sampleV5.aircraft.acoustics.core.VoiceSepMonitorState
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Standalone read-only viewer: no drone registration or control authorization is required. */
class VoiceSepMonitorActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var client: VoiceSepClient? = null
    private var foreground = false
    private lateinit var server: EditText
    private lateinit var fallback: EditText
    private lateinit var connect: Button
    private lateinit var angle: TextView
    private lateinit var details: TextView
    private lateinit var compass: Compass
    private var lastReport = "尚未连接"
    private val ticker = object : Runnable {
        override fun run() {
            if (!foreground) return
            render(); handler.postDelayed(this, 100)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getPreferences(MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 18, 24, 18) }
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; root.addView(this) }
        label("VoiceSep 实时角度 · 只读", 23f)
        label("无需连接无人机。仅显示电脑测角结果，不申请飞行控制权。")
        server = EditText(this).apply {
            hint = "http://电脑局域网IP:8014"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("server", "")); root.addView(this)
        }
        label("备用周期 ms（服务端提供周期时自动跟随）")
        fallback = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getString("period", "128")); root.addView(this)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; root.addView(this) }
        connect = Button(this).apply {
            text = "连接只读角度"
            buttons.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener {
                runCatching {
                    val next = VoiceSepClient(server.text.toString(), fallback.text.toString().toDouble())
                    client?.close(); client = next; next.start()
                    prefs.edit().putString("server", server.text.toString()).putString("period", fallback.text.toString()).apply()
                    server.isEnabled = false; fallback.isEnabled = false; isEnabled = false
                    render()
                }.onFailure { Toast.makeText(this@VoiceSepMonitorActivity, it.message, Toast.LENGTH_LONG).show() }
            }
        }
        Button(this).apply {
            text = "断开监听"; buttons.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { disconnect() }
        }
        angle = label("—", 52f).apply { gravity = android.view.Gravity.CENTER }
        compass = Compass().apply { root.addView(this, LinearLayout.LayoutParams(-1, (260 * resources.displayMetrics.density).toInt())) }
        details = label("在 Mac 启动只读网关后，填入电脑局域网地址。")
        Button(this).apply {
            text = "复制角度链路诊断"; root.addView(this)
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VoiceSep 诊断", lastReport))
                Toast.makeText(this@VoiceSepMonitorActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        label("原始坐标：+x 为 0°，+y 为 90°，逆时针增加。角度是声源所在方位。\n暂无采集端同步时间戳，本页不用于启用实机飞控；真实采集或合成音轨来源需在 Mac 确认。")
        Button(this).apply { text = "返回控制页"; root.addView(this); setOnClickListener { finish() } }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun disconnect() {
        client?.close(); server.isEnabled = true; fallback.isEnabled = true; connect.isEnabled = true; render()
    }
    private fun render() {
        if (!::angle.isInitialized) return
        val view = client?.state?.view(SystemClock.elapsedRealtime()) ?: return
        fun number(value: Double) = if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value) else "—"
        angle.text = if (view.valid) "${number(view.angle)}°" else "—"
        angle.setTextColor(if (view.demo) Color.rgb(215, 135, 20) else if (view.valid) Color.rgb(0, 155, 90) else Color.GRAY)
        compass.current = view; compass.invalidate()
        val sourceTitle = when(view.source) {
            "demo" -> "合成角度演示（不是声音测角）"
            "synthetic_audio" -> "合成13路音频经 VoiceSep 测角（无真实阵列）"
            else -> "VoiceSep 算法输出（音轨来源由电脑确认）"
        }
        val reasonText = when(view.reason) {
            "idle" -> "电脑测角尚未启动"
            "waiting_input" -> "等待13路采集文件"
            "warming" -> "算法预热中"
            "waiting_growth" -> "等待音轨继续增长"
            "no_target" -> "没有有效声源"
            "stalled" -> "音频或处理结果已停滞"
            "resyncing" -> "正在丢弃积压并重新预热"
            "stopped" -> "电脑测角已停止"
            "upstream_unavailable_or_invalid" -> "网关在线，测角服务不可达或返回异常"
            else -> view.reason
        }
        lastReport = "$sourceTitle\n" +
            "网关 HTTP：${if (view.connected) "已连接" else "未连接/已断开"}\nVoiceSep 上游：${if (view.upstreamOk) "可访问" else "未连接"}\n" +
            "$reasonText\n配置周期：${number(view.periodMs)} ms\n上游实际更新：${number(view.updateHz)} Hz\n" +
            "结果年龄上界：${number(view.resultAgeMs)} ms（不是采集时刻）\n文件积压：${number(view.backlogMs)} ms\n" +
            "输入更新年龄：${number(view.inputAgeMs)} ms\n新事件 ${view.uniqueFrames} / 重复 ${view.duplicateFrames} / 拒绝 ${view.rejectedFrames}\n" +
            "run=${view.runId ?: "—"}\nepoch=${view.epoch} / seq=${view.sequence}\n仅观察：没有发送飞行指令。"
        details.text = lastReport
    }
    override fun onResume() { super.onResume(); foreground = true; handler.post(ticker) }
    override fun onPause() { foreground = false; handler.removeCallbacks(ticker); disconnect(); super.onPause() }
    override fun onDestroy() { client?.close(); super.onDestroy() }
    private inner class Compass : View(this) {
        var current: VoiceSepMonitorState.View? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val radius = minOf(width, height) * .34f
            paint.style = Paint.Style.STROKE; paint.color = Color.GRAY; paint.strokeWidth = 2f
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint); canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 15 * resources.displayMetrics.scaledDensity; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("+y 90°", cx, cy - radius - 12, paint)
            canvas.drawText("270°", cx, cy + radius + paint.textSize + 8, paint)
            canvas.drawText("0° +x", cx + radius + 28, cy - 10, paint)
            canvas.drawText("180°", cx - radius - 28, cy - 10, paint)
            val value = current ?: return
            if (!value.valid) return
            val rad = Math.toRadians(value.angle)
            val x = cx + (cos(rad) * radius * .85).toFloat(); val y = cy - (sin(rad) * radius * .85).toFloat()
            paint.color = if (value.demo) Color.rgb(215,135,20) else Color.rgb(0,155,90); paint.strokeWidth = 8f
            canvas.drawLine(cx, cy, x, y, paint); canvas.drawCircle(x, y, 10f, paint)
        }
    }
}
