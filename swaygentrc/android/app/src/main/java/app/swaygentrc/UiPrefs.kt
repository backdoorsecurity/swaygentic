package app.swaygentrc

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class SavedCmd(
    val name: String,
    val content: String,
)

data class PhoneCreds(
    val url: String,
    val token: String,
    val vncHost: String = "",
    val vncPort: Int = 5900,
)

class UiPrefs(context: Context) {
    private val store = context.getSharedPreferences("swaygentrc_ui", Context.MODE_PRIVATE)

    fun load(userSchemes: List<UiScheme> = loadUserSchemes()): UiPalette {
        val schemeId = store.getString("scheme", "phosphor") ?: "phosphor"
        val scheme = resolveScheme(schemeId, userSchemes) ?: SCHEME_BY_ID.getValue("phosphor")
        return UiPalette(
            schemeId = scheme.id,
            fontSp = store.getInt("fontSp", 16).coerceIn(12, 22),
            slots = scheme.slots,
        )
    }

    fun save(palette: UiPalette) {
        store.edit()
            .putString("scheme", palette.schemeId)
            .putInt("fontSp", palette.fontSp)
            .apply()
    }

    fun loadUrl(): String {
        return store.getString("serverUrl", "") ?: ""
    }

    fun saveUrl(url: String) {
        store.edit().putString("serverUrl", url).apply()
    }

    fun loadToken(): String {
        return store.getString("apiToken", "") ?: ""
    }

    fun saveToken(token: String) {
        store.edit().putString("apiToken", token).apply()
    }

    fun loadVncHost(): String {
        return store.getString("vncHost", "") ?: ""
    }

    fun saveVncHost(host: String) {
        store.edit().putString("vncHost", host).apply()
    }

    fun loadVncPort(): Int {
        return store.getInt("vncPort", 5900).coerceIn(1, 65535)
    }

    fun saveVncPort(port: Int) {
        store.edit().putInt("vncPort", port.coerceIn(1, 65535)).apply()
    }

    /** VIEW 1-finger drag: scroll (default) vs select (mouse button 1 drag). */
    fun loadVncDragScroll(): Boolean {
        return store.getBoolean("vncDragScroll", true)
    }

    fun saveVncDragScroll(scroll: Boolean) {
        store.edit().putBoolean("vncDragScroll", scroll).apply()
    }

    fun loadVncOptions(): app.swaygentrc.vnc.VncOptions {
        return app.swaygentrc.vnc.VncOptions(
            bits = store.getInt("vncBits", 16),
            zlib = store.getBoolean("vncZlib", true),
            targetFps = store.getInt("vncFps", 15),
        ).normalized()
    }

    fun saveVncOptions(options: app.swaygentrc.vnc.VncOptions) {
        val o = options.normalized()
        store.edit()
            .putInt("vncBits", o.bits)
            .putBoolean("vncZlib", o.zlib)
            .putInt("vncFps", o.targetFps)
            .apply()
    }

    fun loadCmds(): List<SavedCmd> {
        if (!store.contains("cmds")) {
            val seeded = defaultCmds()
            saveCmds(seeded)
            store.edit().putBoolean("seeded_qbo_v4", true).apply()
            return seeded
        }
        val out = (parseCmdsJson(store.getString("cmds", "[]") ?: "[]") ?: defaultCmds()).toMutableList()
        if (!store.getBoolean("seeded_qbo_v4", false)) {
            val qbo = qboInvoiceCmd()
            out.removeAll {
                it.name.equals("Draft invoice", ignoreCase = true)
            }
            val i = out.indexOfFirst {
                it.name.equals(qbo.name, ignoreCase = true) ||
                    it.name.equals("QBO invoice", ignoreCase = true)
            }
            if (i >= 0) out[i] = qbo else out.add(qbo)
            saveCmds(out)
            store.edit().putBoolean("seeded_qbo_v4", true).apply()
        }
        return out
    }

    fun defaultCmds(): List<SavedCmd> {
        return listOf(qboInvoiceCmd())
    }

    fun qboInvoiceCmd(): SavedCmd {
        return SavedCmd(
            "qbo-invoice",
            QBO_INVOICE_TEMPLATE,
        )
    }

    fun saveCmds(cmds: List<SavedCmd>) {
        store.edit().putString("cmds", cmdsToJson(cmds)).apply()
    }

    fun loadUserSchemes(): List<UiScheme> {
        val raw = store.getString("userSchemes", "[]") ?: "[]"
        return parseSchemesJson(raw).filter { !it.builtin }
    }

    fun saveUserSchemes(schemes: List<UiScheme>) {
        store.edit().putString("userSchemes", schemesToJson(schemes.filter { !it.builtin })).apply()
    }
}

fun resolveScheme(id: String, userSchemes: List<UiScheme>): UiScheme? {
    return userSchemes.firstOrNull { it.id == id } ?: SCHEME_BY_ID[id]
}

fun cmdsToJson(cmds: List<SavedCmd>): String {
    val arr = JSONArray()
    for (cmd in cmds) {
        val obj = JSONObject()
        obj.put("name", cmd.name)
        obj.put("content", cmd.content)
        arr.put(obj)
    }
    return arr.toString(2)
}

fun parseCmdsJson(raw: String): List<SavedCmd>? {
    return try {
        val arr = JSONArray(raw)
        val out = mutableListOf<SavedCmd>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val content = obj.optString("content").trim()
            if (name.isNotEmpty() && content.isNotEmpty()) {
                out.add(SavedCmd(name, content))
            }
        }
        out
    } catch (_: Exception) {
        null
    }
}

/** Prefer explicit VNC host; otherwise use the HTTP credentials URL host. */
fun effectiveVncHost(vncHost: String, serverUrl: String): String {
    val explicit = vncHost.trim()
    if (explicit.isNotEmpty()) return explicit
    return hostFromServerUrl(serverUrl)
}

fun hostFromServerUrl(serverUrl: String): String {
    val raw = serverUrl.trim()
    if (raw.isEmpty()) return ""
    return try {
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        android.net.Uri.parse(withScheme).host?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }
}

fun parseVncEndpoint(raw: String): Pair<String, Int>? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val stripped = value
        .removePrefix("vnc://")
        .removePrefix("VNC://")
        .removePrefix("rfb://")
        .removePrefix("RFB://")
    val hostPort = stripped.substringBefore('/').substringBefore('?')
    if (hostPort.isEmpty()) return null
    val colon = hostPort.lastIndexOf(':')
    if (colon > 0 && !hostPort.contains(']')) {
        val host = hostPort.substring(0, colon).trim()
        val port = hostPort.substring(colon + 1).trim().toIntOrNull()
        if (host.isNotEmpty() && port != null && port in 1..65535) {
            return host to port
        }
    }
    if (hostPort.startsWith('[') && hostPort.contains(']')) {
        val end = hostPort.indexOf(']')
        val host = hostPort.substring(1, end).trim()
        val portPart = hostPort.substring(end + 1).removePrefix(":").trim()
        val port = portPart.toIntOrNull() ?: 5900
        if (host.isNotEmpty() && port in 1..65535) {
            return host to port
        }
    }
    return hostPort to 5900
}

fun parsePhoneCreds(raw: String): PhoneCreds? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    var url = ""
    var token = ""
    var vncHost = ""
    var vncPort = 5900
    if (trimmed.startsWith("{")) {
        try {
            val obj = JSONObject(trimmed)
            url = obj.optString("url").trim()
            token = obj.optString("token").trim()
            vncHost = obj.optString("vnc_host").ifBlank { obj.optString("vncHost") }.trim()
            val portRaw = obj.optString("vnc_port").ifBlank { obj.optString("vncPort") }.trim()
            portRaw.toIntOrNull()?.takeIf { it in 1..65535 }?.let { vncPort = it }
            val vncUrl = obj.optString("vnc_url").ifBlank { obj.optString("vncUrl") }.trim()
            if (vncUrl.isNotEmpty()) {
                parseVncEndpoint(vncUrl)?.let { (h, p) ->
                    if (vncHost.isEmpty()) vncHost = h
                    vncPort = p
                }
            }
        } catch (_: Exception) {
        }
    }
    val lines = trimmed.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()
    for (line in lines) {
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim().lowercase()
        val value = line.substring(eq + 1).trim()
        when (key) {
            "url", "listen", "base", "host" -> url = value
            "token", "api", "api-token", "apitoken" -> token = value
            "vnc_host", "vnchost" -> vncHost = value
            "vnc_port", "vncport" -> value.toIntOrNull()?.takeIf { it in 1..65535 }?.let { vncPort = it }
            "vnc_url", "vncurl" -> parseVncEndpoint(value)?.let { (h, p) ->
                vncHost = h
                vncPort = p
            }
        }
    }
    if (token.isEmpty() && url.isEmpty() && lines.size >= 2 && lines[0].startsWith("http")) {
        url = lines[0]
        token = lines[1]
    }
    if (token.isEmpty() && lines.size == 1 && !lines[0].startsWith("http") && !lines[0].contains("=")) {
        token = lines[0]
    }
    if (token.isEmpty()) return null
    // Credentials often only have url= — reuse that host for Haven VNC.
    if (vncHost.isEmpty()) {
        vncHost = hostFromServerUrl(url)
    }
    return PhoneCreds(url = url, token = token, vncHost = vncHost, vncPort = vncPort)
}

fun mergeCmds(existing: List<SavedCmd>, incoming: List<SavedCmd>): List<SavedCmd> {
    val out = existing.toMutableList()
    for (cmd in incoming) {
        val i = out.indexOfFirst { it.name.equals(cmd.name, ignoreCase = true) }
        if (i >= 0) out[i] = cmd else out.add(cmd)
    }
    return out
}

fun schemesToJson(schemes: List<UiScheme>): String {
    val arr = JSONArray()
    for (scheme in schemes) {
        arr.put(schemeToJson(scheme))
    }
    return arr.toString(2)
}

fun schemeToJson(scheme: UiScheme): JSONObject {
    val obj = JSONObject()
    obj.put("id", scheme.id)
    obj.put("name", scheme.name)
    val colors = JSONObject()
    for (id in SLOT_IDS) {
        colors.put(id.toString(), scheme.slot(id).toHexRgb())
    }
    obj.put("colors", colors)
    return obj
}

fun parseSchemesJson(raw: String): List<UiScheme> {
    val t = raw.trim()
    if (t.isEmpty()) return emptyList()
    return try {
        if (t.startsWith("[")) {
            val arr = JSONArray(t)
            val out = mutableListOf<UiScheme>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseSchemeObj(obj)?.let { out.add(it) }
            }
            out
        } else {
            val one = parseSchemeObj(JSONObject(t))
            if (one != null) listOf(one) else emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseSchemeObj(obj: JSONObject): UiScheme? {
    val name = obj.optString("name").trim().ifEmpty { obj.optString("id").trim() }
    if (name.isEmpty()) return null
    val id = obj.optString("id").trim().ifEmpty { schemeSlug(name) }
    if (id.isEmpty()) return null
    val colors = obj.optJSONObject("colors") ?: JSONObject()
    val base = SCHEME_BY_ID.getValue("phosphor").slots
    val slots = mutableMapOf<Int, Color>()
    for (slotId in SLOT_IDS) {
        val hex = colors.optString(slotId.toString()).ifEmpty { colors.optString(SLOT_LABELS.getValue(slotId)) }
        slots[slotId] = parseHexColor(hex) ?: base.getValue(slotId)
    }
    return UiScheme(id = id, name = name, slots = slots, builtin = false)
}

const val QBO_INVOICE_TEMPLATE = """1. Launch chromium to https://accounts.intuit.com/
2. Wait for me to sign in.
3. Create new invoice.
4. Fill in boxes with this template:
5. Once invoice is complete, do NOT send invoice without explicit instructions to do so.
6. Default final action is save invoice.

[General info]
Customer:
Invoice date:
Ship to:

[product/service]
product/service type:
Qty:
Rate:
Description:

[Sales Tax]
Select Sales Tax Rate: automatic
"""
