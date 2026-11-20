package dji.sampleV5.aircraft.acoustics.monitor

import android.os.SystemClock
import dji.sampleV5.aircraft.acoustics.core.VoiceSepFrame
import dji.sampleV5.aircraft.acoustics.core.VoiceSepMonitorState
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Serial HTTP reads only. No DJI classes, control actions or microphone capture. */
class VoiceSepClient(origin: String, fallbackPeriodMs: Double) {
    val state = VoiceSepMonitorState(fallbackPeriodMs)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var connection: HttpURLConnection? = null
    private val endpoint = checkedEndpoint(origin)

    fun start() {
        check(running.compareAndSet(false, true)) { "已经连接" }
        executor.execute(::poll)
    }
    fun close() {
        running.set(false); connection?.disconnect(); executor.shutdownNow()
        state.error("监听已断开；电脑测角服务仍独立运行")
    }
    private fun poll() {
        if (!running.get()) return
        val began = SystemClock.elapsedRealtime()
        try {
            val request = endpoint.toURL().openConnection() as HttpURLConnection
            connection = request
            request.requestMethod = "GET"
            request.connectTimeout = 1500; request.readTimeout = 1500
            request.instanceFollowRedirects = false; request.useCaches = false
            request.setRequestProperty("Cache-Control", "no-cache")
            check(request.responseCode == 200) { "HTTP ${request.responseCode}；请确认使用只读网关端口" }
            val bytes = request.inputStream.use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(result.size() + count <= 65536) { "响应过大，已拒绝" }
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            val result = decode(bytes.toString(Charsets.UTF_8))
            if (running.get()) state.receive(result, began, SystemClock.elapsedRealtime())
        } catch (error: Exception) {
            if (running.get()) state.error("连接/解析失败：${error.message}")
        } finally {
            connection?.disconnect(); connection = null
        }
        if (running.get()) runCatching { executor.schedule(::poll, state.pollDelayMs(), TimeUnit.MILLISECONDS) }
    }
    companion object {
        fun checkedEndpoint(text: String): URI {
            val uri = URI(text.trim().trimEnd('/'))
            require(uri.scheme == "http" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/api/state") && uri.port in 1..65535) {
                "请输入局域网网关地址，例如 http://电脑IP:8014"
            }
            val parts = (uri.host ?: "").split('.')
            require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) { "请使用电脑的 IPv4 地址" }
            val ip = parts.map { it.toInt() }
            require(ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] in 16..31)) {
                "只允许局域网或本机地址，不连接公网服务"
            }
            return URI("http", null, uri.host, uri.port, "/api/state", null, null)
        }
        fun decode(text: String): VoiceSepFrame {
            val json = JSONObject(text)
            require(json.optString("protocol") == "voicesep-angle-v1" && json.opt("read_only") == true &&
                json.optString("angle_convention") == "array_x_zero_ccw") { "接口协议不匹配，请连接 VoiceSep 只读网关" }
            fun number(key: String): Double = (json.opt(key) as? Number)?.toDouble() ?: Double.NaN
            fun integer(key: String): Long {
                val value = number(key)
                require(value.isFinite() && value >= 0 && value <= 9007199254740991.0 && value == kotlin.math.floor(value)) { "$key 无效" }
                return value.toLong()
            }
            return VoiceSepFrame(json.optString("source"), json.opt("run_id") as? String,
                json.optString("reason", "unknown"), integer("epoch"), integer("sequence"),
                json.opt("upstream_ok") == true, json.opt("active") == true, json.opt("valid") == true,
                number("azimuth_deg"), number("configured_update_ms"), number("result_age_ms"),
                number("input_age_ms"), number("backlog_ms"), number("gateway_fetch_ms"), number("update_hz"))
        }
    }
}
