package app.swaygentrc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

data class ChatReply(
    val text: String,
    val thoughts: String,
    val httpCode: Int,
    val error: String? = null,
    val cancelled: Boolean = false,
)

data class SwaygentrcStatus(
    val running: Boolean,
    val pid: Int?,
    val port: Int,
    val bind: String,
    val listening: Boolean,
    val httpCode: Int,
    val error: String? = null,
    val scheduled: Boolean = false,
    val paused: Boolean = false,
    val jailed: Boolean = false,
    val display: String = "",
    val activity: List<String> = emptyList(),
    val frameMtime: Double = 0.0,
)

sealed class ChatEvent {
    data class Thought(val text: String) : ChatEvent()
    data class Text(val text: String) : ChatEvent()
    data class Tool(val name: String, val detail: String, val status: String) : ChatEvent()
    data class Frame(val bitmap: Bitmap) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
    data class Done(val text: String, val thoughts: String) : ChatEvent()
}

class SwaygentrcClient(context: Context) {
    private val app = context.applicationContext
    @Volatile
    private var chatConn: HttpURLConnection? = null
    @Volatile
    var chatCancelReason: String? = null
        private set

    fun cancelChat(reason: String = "stopped") {
        chatCancelReason = reason
        try {
            chatConn?.disconnect()
        } catch (_: Exception) {
        }
    }

    fun root(baseUrl: String): String {
        var s = baseUrl.trim().trimEnd('/')
        if (s.endsWith("/chat", ignoreCase = true)) {
            s = s.dropLast(5).trimEnd('/')
        }
        if (s.isEmpty()) return ""
        if (!s.contains("://")) {
            s = "http://$s"
        }
        return s
    }

    private fun open(target: String): HttpURLConnection {
        val url = URI.create(target).toURL()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val host = url.host ?: ""
        // Bind to the Tailscale VPN network for overlay destinations.
        // Binding to Wi‑Fi (activeNetwork) bypasses the VPN and breaks
        // MagicDNS plus Tailscale CGNAT. Unbound sockets follow system routes.
        val net = if (isOverlayHost(host)) vpnNetwork(cm) else null
        val conn = if (net != null) net.openConnection(url) else url.openConnection()
        val http = conn as HttpURLConnection
        http.useCaches = false
        http.instanceFollowRedirects = false
        return http
    }

    private fun vpnNetwork(cm: ConnectivityManager): Network? {
        var fallback: Network? = null
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (fallback == null) fallback = net
            val addrs = cm.getLinkProperties(net)?.linkAddresses ?: continue
            val tailscale = addrs.any { la ->
                val addr = la.address.hostAddress ?: return@any false
                isOverlayHost(addr)
            }
            if (tailscale) return net
        }
        return fallback
    }

    companion object {
        fun hostOf(baseUrl: String): String {
            val raw = baseUrl.trim()
            if (raw.isEmpty()) return ""
            return try {
                val withScheme = if (raw.contains("://")) raw else "http://$raw"
                URI.create(withScheme).host ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        fun isOverlayHost(host: String): Boolean {
            val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
            if (h.isEmpty()) return false
            if (h.endsWith(".ts.net") || h.endsWith(".ts.net.")) return true
            if (h == "fd7a:115c:a1e0" || h.startsWith("fd7a:115c:a1e0:")) return true
            val parts = h.split('.')
            if (parts.size == 4) {
                val nums = parts.map { it.toIntOrNull() }
                if (nums.any { it == null }) return false
                val a = nums[0]!!
                val b = nums[1]!!
                if (nums.all { it in 0..255 } && a == 100 && b in 64..127) return true
            }
            return false
        }

        fun needsLocalNetwork(host: String): Boolean {
            if (isOverlayHost(host)) return true
            val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
            if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h.endsWith(".local")) {
                return true
            }
            val parts = h.split('.')
            if (parts.size == 4) {
                val nums = parts.map { it.toIntOrNull() }
                if (nums.any { it == null }) return false
                if (nums.any { it !in 0..255 }) return false
                val a = nums[0]!!
                val b = nums[1]!!
                if (a == 10) return true
                if (a == 192 && b == 168) return true
                if (a == 172 && b in 16..31) return true
                if (a == 169 && b == 254) return true
            }
            if (h.startsWith("fe80:") || h.startsWith("fd") || h.startsWith("fc")) return true
            return false
        }
    }

    private fun auth(conn: HttpURLConnection, token: String) {
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
    }

    fun status(baseUrl: String, token: String): SwaygentrcStatus {
        return request(baseUrl, token, "/grok/status", "GET")
    }

    fun start(baseUrl: String, token: String): SwaygentrcStatus {
        return request(baseUrl, token, "/grok/start", "POST")
    }

    fun pause(baseUrl: String, token: String): SwaygentrcStatus {
        return request(baseUrl, token, "/grok/pause", "POST")
    }

    fun stop(baseUrl: String, token: String): SwaygentrcStatus {
        return request(baseUrl, token, "/grok/stop", "POST")
    }

    fun poweroff(baseUrl: String, token: String): SwaygentrcStatus {
        return request(
            baseUrl,
            token,
            "/host/poweroff",
            "POST",
            JSONObject().put("confirm", "POWEROFF").toString().toByteArray(Charsets.UTF_8),
        )
    }

    fun frame(baseUrl: String, token: String): Bitmap? {
        val root = root(baseUrl)
        if (root.isEmpty()) return null
        val target = "$root/grok/frame"
        return try {
            val conn = open(target)
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.requestMethod = "GET"
            auth(conn, token)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun chat(
        baseUrl: String,
        token: String,
        message: String,
        onEvent: (ChatEvent) -> Unit,
    ): ChatReply {
        val root = root(baseUrl)
        val target = "$root/chat"
        chatCancelReason = null
        try {
            val conn = open(target)
            chatConn = conn
            val payload = JSONObject().put("message", message).toString().toByteArray(Charsets.UTF_8)
            conn.connectTimeout = 8000
            conn.readTimeout = 0
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            auth(conn, token)
            conn.doOutput = true
            conn.outputStream.use { it.write(payload) }
            val cancelled = chatCancelReason
            if (cancelled != null) {
                return ChatReply("", "", -1, cancelled, cancelled = true)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code == 401) {
                return ChatReply("", "", code, "Unauthorized. Check the API token.")
            }
            val ctype = conn.contentType ?: ""
            if ("event-stream" in ctype && stream != null && code in 200..299) {
                val reply = readSse(stream, onEvent)
                val stop = chatCancelReason
                if (stop != null) {
                    return ChatReply(reply.text, reply.thoughts, code, stop, cancelled = true)
                }
                return reply.copy(httpCode = code)
            }
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
            val obj = if (text.isBlank()) JSONObject() else JSONObject(text)
            val error = if (obj.has("error") && !obj.isNull("error")) obj.getString("error") else null
            val stop = chatCancelReason
            if (stop != null) {
                return ChatReply("", "", code, stop, cancelled = true)
            }
            return ChatReply(
                text = obj.optString("text", ""),
                thoughts = obj.optString("thoughts", ""),
                httpCode = code,
                error = error,
            )
        } catch (exc: Exception) {
            val stop = chatCancelReason
            if (stop != null) {
                return ChatReply("", "", -1, stop, cancelled = true)
            }
            return ChatReply(
                text = "",
                thoughts = "",
                httpCode = -1,
                error = "POST $target: ${exc.message ?: exc.javaClass.simpleName}",
            )
        } finally {
            try {
                chatConn?.disconnect()
            } catch (_: Exception) {
            }
            chatConn = null
        }
    }

    private fun readSse(
        stream: java.io.InputStream,
        onEvent: (ChatEvent) -> Unit,
    ): ChatReply {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var event = "message"
        val data = StringBuilder()
        var text = ""
        var thoughts = ""
        var error: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("event:")) {
                event = line.substring(6).trim()
                continue
            }
            if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart())
                continue
            }
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val parsed = applySse(event, data.toString(), onEvent)
                    if (parsed.first != null) text = parsed.first ?: text
                    if (parsed.second != null) thoughts = parsed.second ?: thoughts
                    if (parsed.third != null) error = parsed.third
                }
                event = "message"
                data.clear()
            }
        }
        if (data.isNotEmpty()) {
            val parsed = applySse(event, data.toString(), onEvent)
            if (parsed.first != null) text = parsed.first ?: text
            if (parsed.second != null) thoughts = parsed.second ?: thoughts
            if (parsed.third != null) error = parsed.third
        }
        return ChatReply(text = text, thoughts = thoughts, httpCode = 200, error = error)
    }

    private fun applySse(
        event: String,
        raw: String,
        onEvent: (ChatEvent) -> Unit,
    ): Triple<String?, String?, String?> {
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return Triple(null, null, null)
        }
        when (event) {
            "thought" -> {
                val t = obj.optString("text", "")
                if (t.isNotEmpty()) onEvent(ChatEvent.Thought(t))
            }
            "text" -> {
                val t = obj.optString("text", "")
                if (t.isNotEmpty()) onEvent(ChatEvent.Text(t))
            }
            "tool" -> {
                val name = obj.optString("name", obj.optString("title", "tool"))
                val detail = obj.optString("detail", "")
                val status = obj.optString("status", "")
                onEvent(ChatEvent.Tool(name, detail, status))
            }
            "frame" -> {
                val b64 = obj.optString("b64", "")
                if (b64.isNotEmpty()) {
                    try {
                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) onEvent(ChatEvent.Frame(bmp))
                    } catch (_: Exception) {
                    }
                }
            }
            "error" -> {
                val err = obj.optString("error", "error")
                onEvent(ChatEvent.Error(err))
                return Triple(null, null, err)
            }
            "done" -> {
                val text = obj.optString("text", "")
                val thoughts = obj.optString("thoughts", "")
                onEvent(ChatEvent.Done(text, thoughts))
                return Triple(text, thoughts, null)
            }
        }
        return Triple(null, null, null)
    }

    private fun request(
        baseUrl: String,
        token: String,
        path: String,
        method: String,
        body: ByteArray? = null,
    ): SwaygentrcStatus {
        val root = root(baseUrl)
        if (root.isEmpty()) {
            return SwaygentrcStatus(
                running = false, pid = null, port = 2419, bind = "",
                listening = false, httpCode = -1, error = "Set the API URL first",
            )
        }
        val target = "$root$path"
        try {
            val conn = open(target)
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            auth(conn, token)
            if (method == "POST") {
                conn.doOutput = true
                val payload = body ?: ByteArray(0)
                if (body != null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                } else {
                    conn.setRequestProperty("Content-Length", "0")
                }
                conn.outputStream.use { it.write(payload) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
            conn.disconnect()
            if (code == 401) {
                return SwaygentrcStatus(
                    running = false, pid = null, port = 2419, bind = "",
                    listening = false, httpCode = code, error = "Unauthorized. Check the API token.",
                )
            }
            return parse(text, code)
        } catch (exc: Exception) {
            return SwaygentrcStatus(
                running = false,
                pid = null,
                port = 2419,
                bind = "",
                listening = false,
                httpCode = -1,
                error = "$method $target: ${exc.message ?: exc.javaClass.simpleName}",
            )
        }
    }

    private fun parse(text: String, code: Int): SwaygentrcStatus {
        val obj = if (text.isBlank()) JSONObject() else JSONObject(text)
        val statusObj = if (obj.has("status") && obj.get("status") is JSONObject) {
            obj.getJSONObject("status")
        } else {
            obj
        }
        val pid = if (statusObj.has("pid") && !statusObj.isNull("pid")) {
            statusObj.getInt("pid")
        } else {
            null
        }
        val error = if (obj.has("error") && !obj.isNull("error")) {
            obj.getString("error")
        } else {
            null
        }
        val activity = mutableListOf<String>()
        val arr = obj.optJSONArray("activity")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val kind = item.optString("type", "")
                if (kind == "text" || kind == "thought") continue
                val name = item.optString("name", if (kind == "tool") "tool" else kind)
                val detail = item.optString("detail", "")
                val line = if (detail.isNotBlank()) "$name $detail" else name
                if (line.isNotBlank()) activity.add(line.take(80))
            }
        }
        return SwaygentrcStatus(
            running = statusObj.optBoolean("running", false),
            pid = pid,
            port = statusObj.optInt("port", 2419),
            bind = statusObj.optString("bind", "127.0.0.1:2419"),
            listening = statusObj.optBoolean("listening", false),
            httpCode = code,
            error = error,
            scheduled = obj.optBoolean("scheduled", false),
            paused = statusObj.optBoolean("paused", obj.optBoolean("paused", false)),
            jailed = statusObj.optBoolean("jailed", false),
            display = statusObj.optString("display", ""),
            activity = activity,
            frameMtime = statusObj.optDouble("frame_mtime", 0.0),
        )
    }
}
