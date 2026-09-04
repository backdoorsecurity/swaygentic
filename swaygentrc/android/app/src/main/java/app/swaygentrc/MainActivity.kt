package app.swaygentrc

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.swaygentrc.vnc.VncConnState
import app.swaygentrc.vnc.VncController
import app.swaygentrc.vnc.VncDragMode
import app.swaygentrc.vnc.VncKeyboardHost
import app.swaygentrc.vnc.VncOptions
import app.swaygentrc.vnc.VncSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HAIR = 1.dp
private val R_PANEL = 16.dp
private val R_BTN = 12.dp
private val R_INNER = 10.dp

enum class MainTab { CHAT, VIEW, SYSTEM }

enum class LineKind { USER, THOUGHT, REPLY, WAIT, ERROR, TOOL }

data class ChatLine(val kind: LineKind, val text: String)

class SwaygentrcSession(app: Application) : AndroidViewModel(app) {
    val client = SwaygentrcClient(app)
    val chatLines = mutableStateListOf<ChatLine>()
    var draft by mutableStateOf("")
    var tab by mutableStateOf(MainTab.CHAT)
    var chatting by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var running by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var jailed by mutableStateOf(false)
    var display by mutableStateOf("")
    var statusText by mutableStateOf("STOPPED")
    var killArmed by mutableStateOf(false)
    var cmdOpen by mutableStateOf(false)
    var liveFrame by mutableStateOf<Bitmap?>(null)
    var activityLine by mutableStateOf("")
    val vnc = VncController(viewModelScope)

    override fun onCleared() {
        vnc.disconnect()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent {
            val prefs = remember { UiPrefs(this) }
            var palette by remember { mutableStateOf(prefs.load()) }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = palette.c(1),
                    surface = palette.c(1),
                    onBackground = palette.c(4),
                    onSurface = palette.c(4),
                    primary = palette.c(3),
                ),
            ) {
                ControlScreen(
                    prefs = prefs,
                    palette = palette,
                    onPalette = {
                        palette = it
                        prefs.save(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun ControlScreen(
    prefs: UiPrefs,
    palette: UiPalette,
    onPalette: (UiPalette) -> Unit,
) {
    val session: SwaygentrcSession = viewModel()
    val scope = session.viewModelScope
    val context = LocalContext.current
    val client = session.client
    var serverUrl by remember { mutableStateOf(prefs.loadUrl()) }
    var apiToken by remember { mutableStateOf(prefs.loadToken()) }
    var vncHost by remember { mutableStateOf(prefs.loadVncHost()) }
    var vncPort by remember { mutableStateOf(prefs.loadVncPort()) }
    var statusText by session::statusText
    val localNetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= 37) {
            session.statusText = "ERROR: allow local network. Android 17 blocks Tailscale without it"
        }
    }

    fun requestLocalNetwork(url: String) {
        if (Build.VERSION.SDK_INT < 37) return
        val host = SwaygentrcClient.hostOf(url)
        if (host.isNotEmpty() && !SwaygentrcClient.needsLocalNetwork(host)) return
        val perm = Manifest.permission.ACCESS_LOCAL_NETWORK
        if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            localNetLauncher.launch(perm)
        }
    }

    LaunchedEffect(serverUrl) {
        requestLocalNetwork(serverUrl)
    }
    var chatting by session::chatting
    var busy by session::busy
    var running by session::running
    var paused by session::paused
    var jailed by session::jailed
    var display by session::display
    var tab by session::tab
    var draft by session::draft
    var killArmed by session::killArmed
    var cmdOpen by session::cmdOpen
    var liveFrame by session::liveFrame
    var activityLine by session::activityLine
    val cmds = remember { mutableStateListOf<SavedCmd>().also { it.addAll(prefs.loadCmds()) } }
    val userSchemes = remember { mutableStateListOf<UiScheme>().also { it.addAll(prefs.loadUserSchemes()) } }
    val chatLines = session.chatLines

    fun persistCmds() {
        prefs.saveCmds(cmds.toList())
    }

    fun persistUserSchemes() {
        prefs.saveUserSchemes(userSchemes.toList())
    }

    fun applyScheme(scheme: UiScheme) {
        onPalette(palette.withScheme(scheme))
    }

    val pickToken = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readUriText(context, uri, 2048)
        if (text == null) {
            toast(context, "token file too large")
            return@rememberLauncherForActivityResult
        }
        val creds = parsePhoneCreds(text)
        if (creds == null) {
            toast(context, "no token in file")
            return@rememberLauncherForActivityResult
        }
        if (creds.url.isNotBlank()) {
            serverUrl = creds.url
            prefs.saveUrl(creds.url)
        }
        apiToken = creds.token
        prefs.saveToken(creds.token)
        val resolvedVnc = effectiveVncHost(creds.vncHost, creds.url.ifBlank { serverUrl })
        if (resolvedVnc.isNotBlank()) {
            vncHost = resolvedVnc
            prefs.saveVncHost(resolvedVnc)
            vncPort = creds.vncPort
            prefs.saveVncPort(creds.vncPort)
        }
        toast(
            context,
            when {
                resolvedVnc.isNotBlank() && creds.url.isNotBlank() ->
                    "url, token, vnc $resolvedVnc:${creds.vncPort} loaded"
                creds.url.isNotBlank() -> "url and token loaded"
                else -> "token loaded"
            },
        )
    }
    val pickCmds = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readUriText(context, uri)
        if (text == null) {
            toast(context, "template file too large")
            return@rememberLauncherForActivityResult
        }
        val incoming = parseCmdsJson(text)
        if (incoming.isNullOrEmpty()) {
            toast(context, "no templates in file")
            return@rememberLauncherForActivityResult
        }
        val merged = mergeCmds(cmds.toList(), incoming)
        cmds.clear()
        cmds.addAll(merged)
        persistCmds()
        toast(context, "imported ${incoming.size} template(s)")
    }
    val exportCmds = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (writeUriText(context, uri, cmdsToJson(cmds))) {
            toast(context, "templates exported")
        } else {
            toast(context, "export failed")
        }
    }

    fun copyText(text: String) {
        if (text.isEmpty()) return
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("swaygentrc", text))
    }

    fun statusPaint(): Pair<Color, Color> {
        val t = statusText.lowercase()
        return when {
            t.startsWith("error") || t.contains("unauth") -> palette.c(8) to palette.c(8)
            t.startsWith("running") || t.startsWith("already") -> palette.c(9) to palette.c(9)
            t.contains("power") || t.startsWith("kill") || t.contains("paused") -> palette.c(10) to palette.c(10)
            else -> palette.c(12) to palette.c(12)
        }
    }

    fun applyStatus(result: SwaygentrcStatus) {
        statusText = formatStatus(result)
        running = result.running && result.error == null
        paused = result.paused
        jailed = result.jailed
        display = result.display
        if (result.activity.isNotEmpty()) {
            activityLine = result.activity.last()
        }
    }

    fun runAction(label: String, call: () -> SwaygentrcStatus) {
        killArmed = false
        statusText = label
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    call()
                } catch (exc: Exception) {
                    SwaygentrcStatus(
                        running = false,
                        pid = null,
                        port = 2419,
                        bind = "",
                        listening = false,
                        httpCode = -1,
                        error = exc.message ?: exc.javaClass.simpleName,
                    )
                }
            }
            applyStatus(result)
            busy = false
        }
    }

    fun sendDraft() {
        val text = draft.trim()
        if (text.isEmpty() || chatting || busy) return
        if (serverUrl.isBlank() || apiToken.isBlank()) {
            chatLines.add(ChatLine(LineKind.ERROR, "set URL and token in SYSTEM → API"))
            tab = MainTab.SYSTEM
            return
        }
        chatLines.add(ChatLine(LineKind.USER, "> $text"))
        draft = ""
        chatting = true
        tab = MainTab.CHAT
        var thoughtBuf = ""
        var replyBuf = ""
        var thoughtIdx = -1
        var replyIdx = -1
        chatLines.add(ChatLine(LineKind.WAIT, "…"))
        fun dropWait() {
            val i = chatLines.indexOfFirst { it.kind == LineKind.WAIT }
            if (i >= 0) {
                chatLines.removeAt(i)
                if (thoughtIdx > i) thoughtIdx -= 1
                if (replyIdx > i) replyIdx -= 1
            }
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                client.chat(serverUrl, apiToken, text) { event ->
                    scope.launch(Dispatchers.Main) {
                        when (event) {
                            is ChatEvent.Thought -> {
                                dropWait()
                                thoughtBuf += event.text
                                if (thoughtIdx < 0) {
                                    chatLines.add(ChatLine(LineKind.THOUGHT, thoughtBuf))
                                    thoughtIdx = chatLines.lastIndex
                                } else if (thoughtIdx < chatLines.size) {
                                    chatLines[thoughtIdx] = ChatLine(LineKind.THOUGHT, thoughtBuf)
                                }
                            }
                            is ChatEvent.Text -> {
                                dropWait()
                                replyBuf += event.text
                                if (replyIdx < 0) {
                                    chatLines.add(ChatLine(LineKind.REPLY, replyBuf))
                                    replyIdx = chatLines.lastIndex
                                } else if (replyIdx < chatLines.size) {
                                    chatLines[replyIdx] = ChatLine(LineKind.REPLY, replyBuf)
                                }
                            }
                            is ChatEvent.Tool -> {
                                dropWait()
                                val line = buildString {
                                    append(event.name)
                                    if (event.status.isNotBlank()) {
                                        append(" [")
                                        append(event.status)
                                        append("]")
                                    }
                                    if (event.detail.isNotBlank()) {
                                        append("  ")
                                        append(event.detail)
                                    }
                                }
                                activityLine = line
                                chatLines.add(ChatLine(LineKind.TOOL, line))
                            }
                            is ChatEvent.Frame -> liveFrame = event.bitmap
                            is ChatEvent.Error -> {
                                dropWait()
                                chatLines.add(ChatLine(LineKind.ERROR, event.message))
                            }
                            is ChatEvent.Done -> {
                                dropWait()
                                if (event.thoughts.isNotBlank() && thoughtIdx < 0) {
                                    chatLines.add(ChatLine(LineKind.THOUGHT, event.thoughts))
                                }
                                if (event.text.isNotBlank() && replyIdx < 0) {
                                    chatLines.add(ChatLine(LineKind.REPLY, event.text))
                                }
                            }
                        }
                    }
                }
            }
            dropWait()
            if (result.cancelled) {
                chatLines.add(ChatLine(LineKind.ERROR, result.error ?: "stopped"))
            } else if (result.error != null && result.text.isBlank() && replyBuf.isBlank()) {
                chatLines.add(ChatLine(LineKind.ERROR, result.error))
            } else if (result.text.isBlank() && replyBuf.isBlank() && result.error == null) {
                chatLines.add(ChatLine(LineKind.ERROR, "(empty reply)"))
            }
            chatting = false
        }
    }

    LaunchedEffect(serverUrl, apiToken) {
        if (serverUrl.isBlank() || apiToken.isBlank()) return@LaunchedEffect
        while (true) {
            val result = withContext(Dispatchers.IO) {
                try {
                    client.status(serverUrl, apiToken)
                } catch (exc: Exception) {
                    SwaygentrcStatus(
                        running = false, pid = null, port = 2419, bind = "",
                        listening = false, httpCode = -1,
                        error = exc.message ?: exc.javaClass.simpleName,
                    )
                }
            }
            if (!busy && !chatting) {
                applyStatus(result)
            } else {
                running = result.running && result.error == null
                paused = result.paused
                jailed = result.jailed
                display = result.display
            }
            delay(2000)
        }
    }

    // JPEG /grok/frame is secondary; skip while native VNC is connected.
    LaunchedEffect(serverUrl, apiToken, running, chatting, session.vnc.state) {
        if (serverUrl.isBlank() || apiToken.isBlank()) return@LaunchedEffect
        if (session.vnc.state == VncConnState.CONNECTED || session.vnc.state == VncConnState.CONNECTING) {
            return@LaunchedEffect
        }
        while (running || chatting) {
            if (session.vnc.state == VncConnState.CONNECTED || session.vnc.state == VncConnState.CONNECTING) break
            val bmp = withContext(Dispatchers.IO) {
                try {
                    client.frame(serverUrl, apiToken)
                } catch (_: Exception) {
                    null
                }
            }
            if (bmp != null) liveFrame = bmp
            delay(if (chatting) 800 else 2000)
        }
    }

    val vncEndpointHost = effectiveVncHost(vncHost, serverUrl)
    var vncOptions by remember { mutableStateOf(prefs.loadVncOptions()) }
    var viewFullscreen by remember { mutableStateOf(false) }
    val view = LocalView.current
    val activity = context as? ComponentActivity

    LaunchedEffect(tab, vncEndpointHost, vncPort, vncOptions) {
        if (tab == MainTab.VIEW && vncEndpointHost.isNotBlank()) {
            val need =
                session.vnc.state == VncConnState.IDLE ||
                    session.vnc.state == VncConnState.ERROR ||
                    session.vnc.connectedHost != vncEndpointHost ||
                    session.vnc.connectedPort != vncPort.coerceIn(1, 65535) ||
                    session.vnc.activeOptions != vncOptions.normalized()
            if (need) {
                session.vnc.connect(vncEndpointHost, vncPort, vncOptions)
            }
        } else if (tab != MainTab.VIEW) {
            viewFullscreen = false
            session.vnc.disconnect()
        }
    }

    DisposableEffect(viewFullscreen, tab) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (viewFullscreen && tab == MainTab.VIEW && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = viewFullscreen) {
        viewFullscreen = false
    }

    val (statusBorder, statusInk) = statusPaint()
    val bottomInsets = WindowInsets.ime.union(WindowInsets.navigationBars)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.c(1))
            .then(
                if (viewFullscreen && tab == MainTab.VIEW) Modifier
                else Modifier.statusBarsPadding().windowInsetsPadding(bottomInsets),
            ),
    ) {
        if (!(viewFullscreen && tab == MainTab.VIEW)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabBox("CHAT", tab == MainTab.CHAT, palette, { tab = MainTab.CHAT }, Modifier.weight(1f))
                TabBox("VIEW", tab == MainTab.VIEW, palette, { tab = MainTab.VIEW }, Modifier.weight(1f))
                TabBox("SYSTEM", tab == MainTab.SYSTEM, palette, { tab = MainTab.SYSTEM }, Modifier.weight(1f))
            }
        }
        when (tab) {
            MainTab.CHAT -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CmdPickDrop(
                        palette = palette,
                        open = cmdOpen,
                        onToggle = { cmdOpen = !cmdOpen },
                        cmds = cmds,
                        onPick = { cmd ->
                            draft = cmd.content
                            cmdOpen = false
                        },
                    )
                    ChatPane(
                        palette = palette,
                        lines = chatLines,
                        draft = draft,
                        onDraft = { draft = it },
                        onSend = { sendDraft() },
                        sendEnabled = !chatting && !busy,
                        onCopyLine = { copyText(it) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    )
                }
            }
            MainTab.VIEW -> {
                var dragScroll by remember { mutableStateOf(prefs.loadVncDragScroll()) }
                LiveFrame(
                    palette = palette,
                    jpegFrame = liveFrame,
                    vnc = session.vnc,
                    activity = activityLine,
                    running = running,
                    paused = paused,
                    jailed = jailed,
                    display = display,
                    vncHost = vncEndpointHost,
                    vncPort = vncPort,
                    dragScroll = dragScroll,
                    fullscreen = viewFullscreen,
                    optionsLabel = vncOptions.label(),
                    onToggleDragMode = {
                        dragScroll = !dragScroll
                        prefs.saveVncDragScroll(dragScroll)
                    },
                    onToggleFullscreen = { viewFullscreen = !viewFullscreen },
                    onReconnect = {
                        session.vnc.connect(vncEndpointHost, vncPort, vncOptions)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            MainTab.SYSTEM -> SysPane(
                palette = palette,
                onPalette = onPalette,
                statusText = statusText,
                statusBorder = statusBorder,
                statusInk = statusInk,
                busy = busy,
                chatting = chatting,
                killArmed = killArmed,
                vncOptions = vncOptions,
                onVncOptions = { next ->
                    vncOptions = next.normalized()
                    prefs.saveVncOptions(vncOptions)
                },
                serverUrl = serverUrl,
                onUrl = {
                    serverUrl = it
                    prefs.saveUrl(it.trim())
                    if (vncHost.isBlank()) {
                        val derived = hostFromServerUrl(it)
                        if (derived.isNotBlank()) {
                            vncHost = derived
                            prefs.saveVncHost(derived)
                        }
                    }
                },
                apiToken = apiToken,
                onToken = {
                    apiToken = it
                    prefs.saveToken(it.trim())
                },
                vncHost = vncHost,
                onVncHost = {
                    vncHost = it
                    prefs.saveVncHost(it.trim())
                },
                vncPort = vncPort,
                onVncPort = { port ->
                    vncPort = port
                    prefs.saveVncPort(port)
                },
                cmds = cmds,
                userSchemes = userSchemes,
                onAdd = { cmd ->
                    val i = cmds.indexOfFirst { it.name.equals(cmd.name, ignoreCase = true) }
                    if (i >= 0) cmds[i] = cmd else cmds.add(cmd)
                    persistCmds()
                },
                onDelete = { cmd ->
                    val i = cmds.indexOfFirst {
                        it.name.equals(cmd.name, ignoreCase = true) && it.content == cmd.content
                    }
                    if (i < 0) {
                        val j = cmds.indexOfFirst { it.name.equals(cmd.name, ignoreCase = true) }
                        if (j >= 0) cmds.removeAt(j)
                    } else {
                        cmds.removeAt(i)
                    }
                    persistCmds()
                },
                onPickToken = { pickToken.launch("*/*") },
                onImportCmds = { pickCmds.launch("*/*") },
                onExportCmds = { exportCmds.launch("swaygentrc-templates.json") },
                onSaveUserScheme = { scheme ->
                    val i = userSchemes.indexOfFirst { it.id == scheme.id }
                    if (i >= 0) userSchemes[i] = scheme else userSchemes.add(scheme)
                    persistUserSchemes()
                    applyScheme(scheme)
                },
                onDeleteUserScheme = { scheme ->
                    userSchemes.removeAll { it.id == scheme.id }
                    persistUserSchemes()
                    if (palette.schemeId == scheme.id) {
                        applyScheme(SCHEME_BY_ID.getValue("phosphor"))
                    }
                },
                onStart = { runAction("STARTING…") { client.start(serverUrl, apiToken) } },
                onPause = {
                    client.cancelChat("paused")
                    runAction("PAUSING…") { client.pause(serverUrl, apiToken) }
                },
                onStop = {
                    client.cancelChat("stopped")
                    runAction("STOPPING…") { client.stop(serverUrl, apiToken) }
                },
                onKill = {
                    if (!killArmed) {
                        killArmed = true
                        statusText = "POWER: TAP AGAIN TO SHUT DOWN THE HOST"
                    } else {
                        client.cancelChat("stopped")
                        runAction("POWEROFF…") { client.poweroff(serverUrl, apiToken) }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LiveFrame(
    palette: UiPalette,
    jpegFrame: Bitmap?,
    vnc: VncController,
    activity: String,
    running: Boolean,
    paused: Boolean,
    jailed: Boolean,
    display: String = "",
    vncHost: String = "",
    vncPort: Int = 5900,
    dragScroll: Boolean = true,
    fullscreen: Boolean = false,
    optionsLabel: String = "",
    onToggleDragMode: () -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onReconnect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var keyboardOn by remember { mutableStateOf(false) }
    val dragMode = if (dragScroll) VncDragMode.SCROLL else VncDragMode.SELECT
    val endpoint = if (vncHost.isNotBlank()) "$vncHost:$vncPort" else "(no vnc host)"
    val connLabel = when (vnc.state) {
        VncConnState.IDLE -> "IDLE"
        VncConnState.CONNECTING -> "CONNECTING"
        VncConnState.CONNECTED -> "CONNECTED"
        VncConnState.ERROR -> "ERROR"
    }
    val showVnc = vnc.frame != null || vnc.state == VncConnState.CONNECTING || vnc.state == VncConnState.CONNECTED
    val placeholder = when {
        vnc.state == VncConnState.CONNECTING -> "CONNECTING\n$endpoint"
        vnc.state == VncConnState.ERROR -> "VNC ERROR\n${vnc.errorMessage.ifBlank { endpoint }}"
        vncHost.isBlank() -> "SET VNC HOST IN SYSTEM → API"
        jpegFrame != null -> ""
        running -> "WAITING FOR VNC\n$endpoint"
        else -> "NO VNC FRAME\n$endpoint"
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (fullscreen) Modifier else Modifier.padding(horizontal = 12.dp, vertical = 8.dp)),
        verticalArrangement = Arrangement.spacedBy(if (fullscreen) 0.dp else 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (fullscreen) Modifier else Modifier.clip(RoundedCornerShape(R_PANEL))),
        ) {
            if (showVnc || jpegFrame == null) {
                VncSurface(
                    palette = palette,
                    frame = vnc.frame ?: jpegFrame,
                    fbW = vnc.framebufferWidth.let { if (it > 0) it else (vnc.frame?.width ?: 0) },
                    fbH = vnc.framebufferHeight.let { if (it > 0) it else (vnc.frame?.height ?: 0) },
                    placeholder = placeholder,
                    controller = vnc,
                    dragMode = dragMode,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.c(1))
                        .border(HAIR, palette.c(11), RoundedCornerShape(if (fullscreen) 0.dp else R_PANEL)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = jpegFrame.asImageBitmap(),
                        contentDescription = "desktop preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Capture Ctrl/Alt/Shift/etc from the system IME (Hacker's Keyboard if installed).
            VncKeyboardHost(active = keyboardOn, controller = vnc)
            if (fullscreen) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .systemBarsPadding()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnsiButton(
                        if (keyboardOn) "KEYB*" else "KEYB",
                        if (keyboardOn) palette.c(9) else palette.c(3),
                        if (keyboardOn) palette.c(9) else palette.c(3),
                        true,
                        { keyboardOn = !keyboardOn },
                        Modifier,
                        palette,
                    )
                    AnsiButton(
                        "EXIT FS",
                        palette.c(3),
                        palette.c(3),
                        true,
                        onToggleFullscreen,
                        Modifier,
                        palette,
                    )
                }
            }
        }
        if (!fullscreen) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnsiButton(
                    "FULL",
                    palette.c(3),
                    palette.c(3),
                    true,
                    onToggleFullscreen,
                    Modifier.weight(1f),
                    palette,
                )
                AnsiButton(
                    if (keyboardOn) "KEYB*" else "KEYB",
                    if (keyboardOn) palette.c(9) else palette.c(3),
                    if (keyboardOn) palette.c(9) else palette.c(3),
                    true,
                    { keyboardOn = !keyboardOn },
                    Modifier.weight(1f),
                    palette,
                )
                AnsiButton(
                    "RECONNECT",
                    palette.c(3),
                    palette.c(3),
                    vncHost.isNotBlank(),
                    onReconnect,
                    Modifier.weight(1f),
                    palette,
                )
                AnsiButton(
                    if (dragScroll) "SCROLL" else "SELECT",
                    if (dragScroll) palette.c(9) else palette.c(10),
                    if (dragScroll) palette.c(9) else palette.c(10),
                    true,
                    onToggleDragMode,
                    Modifier.weight(1f),
                    palette,
                )
            }
            val tag = buildString {
                append("VNC $connLabel  $endpoint")
                if (optionsLabel.isNotBlank()) append("  $optionsLabel")
                append(if (dragScroll) "  SCROLL" else "  SELECT")
                if (keyboardOn) append("  KEYB")
                if (paused) append("  AGENT PAUSED")
                else if (running) append("  AGENT LIVE")
                if (jailed) append("  JAILED")
                val backend = display.trim().uppercase()
                if (backend.isNotEmpty()) {
                    append("  ")
                    append(backend)
                }
                val tool = activity.trim()
                if (tool.isNotBlank() && !tool.startsWith("text", ignoreCase = true)) {
                    append("  ")
                    append(tool)
                }
            }
            Text(
                text = tag,
                color = when {
                    vnc.state == VncConnState.ERROR -> palette.c(8)
                    vnc.state == VncConnState.CONNECTED -> palette.c(9)
                    paused -> palette.c(10)
                    else -> palette.c(2)
                },
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun TabBox(
    label: String,
    selected: Boolean,
    palette: UiPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) palette.c(3) else palette.c(2)
    val ink = if (selected) palette.c(3) else palette.c(2)
    val shape = RoundedCornerShape(R_BTN)
    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.c(1))
            .border(HAIR, border, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = ink,
            fontFamily = FontFamily.Monospace,
            fontSize = palette.fontSp.sp,
        )
    }
}

@Composable
private fun ChatPane(
    palette: UiPalette,
    lines: List<ChatLine>,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    onCopyLine: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LineBox(palette.c(4), Modifier.weight(1f).fillMaxWidth(), palette.c(1)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(lines) { line ->
                    val color = when (line.kind) {
                        LineKind.USER -> palette.c(4)
                        LineKind.THOUGHT -> palette.c(5)
                        LineKind.REPLY -> palette.c(6)
                        LineKind.WAIT -> palette.c(7)
                        LineKind.ERROR -> palette.c(8)
                        LineKind.TOOL -> palette.c(12)
                    }
                    WrapText(
                        text = line.text,
                        color = color,
                        palette = palette,
                        onLongCopy = { onCopyLine(line.text) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(R_PANEL))
                .background(palette.c(1))
                .border(HAIR, palette.c(3), RoundedCornerShape(R_PANEL))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = ">",
                color = palette.c(4),
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                singleLine = false,
                minLines = 1,
                maxLines = 12,
                cursorBrush = SolidColor(palette.c(4)),
                textStyle = TextStyle(
                    color = palette.c(4),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 24.dp, max = 220.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SEND",
                color = if (sendEnabled) palette.c(3) else palette.c(2),
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
                modifier = Modifier.clickable(enabled = sendEnabled, onClick = onSend),
            )
        }
    }
}

private const val MAX_PACK_BYTES = 64 * 1024

private fun readUriText(context: Context, uri: Uri, maxBytes: Int = MAX_PACK_BYTES): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(maxBytes + 1)
            val n = input.read(buf)
            if (n < 0) return ""
            if (n > maxBytes) return null
            String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }
}

private fun writeUriText(context: Context, uri: Uri, text: String): Boolean {
    return try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        true
    } catch (_: Exception) {
        false
    }
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

@Composable
private fun SysPane(
    palette: UiPalette,
    onPalette: (UiPalette) -> Unit,
    statusText: String,
    statusBorder: Color,
    statusInk: Color,
    busy: Boolean,
    chatting: Boolean,
    killArmed: Boolean,
    vncOptions: VncOptions,
    onVncOptions: (VncOptions) -> Unit,
    serverUrl: String,
    onUrl: (String) -> Unit,
    apiToken: String,
    onToken: (String) -> Unit,
    vncHost: String,
    onVncHost: (String) -> Unit,
    vncPort: Int,
    onVncPort: (Int) -> Unit,
    cmds: List<SavedCmd>,
    userSchemes: List<UiScheme>,
    onAdd: (SavedCmd) -> Unit,
    onDelete: (SavedCmd) -> Unit,
    onPickToken: () -> Unit,
    onImportCmds: () -> Unit,
    onExportCmds: () -> Unit,
    onSaveUserScheme: (UiScheme) -> Unit,
    onDeleteUserScheme: (UiScheme) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onKill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var openDrop by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var cloneName by remember { mutableStateOf("") }
    var hexDraft by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val shownScheme = resolveScheme(palette.schemeId, userSchemes)
        ?: SCHEME_BY_ID.getValue("phosphor")
    val allSchemes = UI_SCHEMES + userSchemes

    val pickScheme = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readUriText(context, uri)
        if (text == null) {
            toast(context, "scheme file too large")
            return@rememberLauncherForActivityResult
        }
        val incoming = parseSchemesJson(text)
        if (incoming.isEmpty()) {
            toast(context, "no scheme in file")
            return@rememberLauncherForActivityResult
        }
        var last: UiScheme? = null
        for (raw in incoming) {
            val existingIds = userSchemes.map { it.id }.toMutableSet()
            last?.let { existingIds.add(it.id) }
            val id = if (SCHEME_BY_ID.containsKey(raw.id) || userSchemes.any { it.id == raw.id }) {
                uniqueUserSchemeId(raw.name, existingIds)
            } else {
                raw.id
            }
            val saved = raw.copy(id = id, builtin = false)
            onSaveUserScheme(saved)
            last = saved
        }
        toast(context, "imported ${incoming.size} scheme(s)")
    }
    val exportScheme = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = schemeToJson(shownScheme).toString(2)
        if (writeUriText(context, uri, json)) {
            toast(context, "scheme exported")
        } else {
            toast(context, "export failed")
        }
    }

    fun startHexEdit(scheme: UiScheme) {
        hexDraft = SLOT_IDS.associateWith { scheme.slot(it).toHexRgb() }
        cloneName = if (scheme.builtin) "${scheme.name} copy" else scheme.name
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.c(1))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnsiButton("START", palette.c(9), palette.c(9), !busy && !chatting, onStart, Modifier.weight(1f), palette)
            AnsiButton("PAUSE", palette.c(7), palette.c(7), !busy, onPause, Modifier.weight(1f), palette)
            AnsiButton("STOP", palette.c(3), palette.c(3), !busy, onStop, Modifier.weight(1f), palette)
            AnsiButton(
                if (killArmed) "CONFIRM" else "POWER",
                palette.c(10),
                palette.c(10),
                !busy,
                onKill,
                Modifier.weight(1f),
                palette,
            )
        }
        LineBox(statusBorder, Modifier.fillMaxWidth(), palette.c(1)) {
            Text(
                text = statusText,
                color = statusInk,
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
            )
        }
        DropHead(
            title = "API",
            open = openDrop == "api",
            border = palette.c(5),
            ink = palette.c(5),
            palette = palette,
            onToggle = { openDrop = if (openDrop == "api") "" else "api" },
        )
        if (openDrop == "api") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(R_INNER))
                    .background(palette.c(1))
                    .border(HAIR, palette.c(5), RoundedCornerShape(R_INNER))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FieldRow("URL", serverUrl, !busy, palette) { onUrl(it) }
                FieldRow(
                    "TOKEN",
                    apiToken,
                    !busy,
                    palette,
                    secret = !showToken,
                    onChange = onToken,
                )
                FieldRow("VNC HOST", vncHost, !busy, palette) { onVncHost(it) }
                FieldRow(
                    "VNC PORT",
                    vncPort.toString(),
                    !busy,
                    palette,
                ) { text ->
                    text.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let(onVncPort)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnsiButton(
                        if (showToken) "HIDE" else "SHOW",
                        palette.c(5),
                        palette.c(5),
                        true,
                        { showToken = !showToken },
                        Modifier.weight(1f),
                        palette,
                    )
                    AnsiButton(
                        "UPLOAD",
                        palette.c(3),
                        palette.c(3),
                        !busy,
                        onPickToken,
                        Modifier.weight(1f),
                        palette,
                    )
                }
                Text(
                    text = "Upload run/credentials.swaygentrc (url/token/vnc_*), or paste URL. VNC host defaults to the URL host for VIEW.",
                    color = palette.c(2),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                )
            }
        }
        DropHead(
            title = "PERFORMANCE",
            open = openDrop == "perf",
            border = palette.c(7),
            ink = palette.c(7),
            palette = palette,
            onToggle = { openDrop = if (openDrop == "perf") "" else "perf" },
        )
        if (openDrop == "perf") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(R_INNER))
                    .background(palette.c(1))
                    .border(HAIR, palette.c(7), RoundedCornerShape(R_INNER))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "VIEW VNC client (reconnects on change). C16+ZLIB is usually snappier on Tailscale. Haven’s ZRLE is not in vernacular yet.",
                    color = palette.c(2),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnsiButton(
                        "C${vncOptions.bits}",
                        palette.c(7),
                        palette.c(7),
                        true,
                        {
                            val nextBits = when (vncOptions.bits) {
                                16 -> 24
                                24 -> 8
                                else -> 16
                            }
                            onVncOptions(vncOptions.copy(bits = nextBits))
                        },
                        Modifier.weight(1f),
                        palette,
                    )
                    AnsiButton(
                        if (vncOptions.zlib) "ZLIB" else "NOZ",
                        if (vncOptions.zlib) palette.c(9) else palette.c(2),
                        if (vncOptions.zlib) palette.c(9) else palette.c(2),
                        true,
                        { onVncOptions(vncOptions.copy(zlib = !vncOptions.zlib)) },
                        Modifier.weight(1f),
                        palette,
                    )
                    AnsiButton(
                        "${vncOptions.targetFps}fps",
                        palette.c(7),
                        palette.c(7),
                        true,
                        {
                            val next = when (vncOptions.targetFps) {
                                10 -> 15
                                15 -> 30
                                else -> 10
                            }
                            onVncOptions(vncOptions.copy(targetFps = next))
                        },
                        Modifier.weight(1f),
                        palette,
                    )
                }
                Text(
                    text = "Active: ${vncOptions.label()}",
                    color = palette.c(4),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                )
            }
        }
        DropHead(
            title = "EDIT TEMPLATES",
            open = openDrop == "cmds",
            border = palette.c(5),
            ink = palette.c(5),
            palette = palette,
            onToggle = { openDrop = if (openDrop == "cmds") "" else "cmds" },
        )
        if (openDrop == "cmds") {
            CmdEditBody(
                palette = palette,
                cmds = cmds,
                onAdd = onAdd,
                onDelete = onDelete,
                onImport = onImportCmds,
                onExport = onExportCmds,
            )
        }
        DropHead(
            title = "USER INTERFACE",
            open = openDrop == "ui",
            border = palette.c(3),
            ink = palette.c(3),
            palette = palette,
            onToggle = { openDrop = if (openDrop == "ui") "" else "ui" },
        )
        if (openDrop == "ui") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(R_INNER))
                    .background(palette.c(1))
                    .border(HAIR, palette.c(3), RoundedCornerShape(R_INNER))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "SCHEME",
                    color = palette.c(3),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                )
                allSchemes.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { scheme ->
                            val on = palette.schemeId == scheme.id
                            val shape = RoundedCornerShape(R_INNER)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(shape)
                                    .background(palette.c(1))
                                    .border(
                                        HAIR,
                                        if (on) scheme.accent else palette.c(2),
                                        shape,
                                    )
                                    .clickable {
                                        onPalette(palette.withScheme(scheme))
                                        if (!scheme.builtin) startHexEdit(scheme)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(scheme.ink),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(scheme.accent),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(scheme.thought),
                                    )
                                }
                                Text(
                                    text = scheme.name.uppercase(),
                                    color = if (on) scheme.accent else palette.c(2),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = palette.fontSp.sp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        if (row.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "CLONE",
                        color = palette.c(3),
                        fontFamily = FontFamily.Monospace,
                        fontSize = palette.fontSp.sp,
                        modifier = Modifier.clickable {
                            val name = cloneName.trim().ifEmpty { "${shownScheme.name} copy" }
                            val id = uniqueUserSchemeId(name, userSchemes.map { it.id })
                            val clone = shownScheme.copy(id = id, name = name, builtin = false)
                            onSaveUserScheme(clone)
                            startHexEdit(clone)
                        },
                    )
                    Text(
                        text = "IMPORT",
                        color = palette.c(3),
                        fontFamily = FontFamily.Monospace,
                        fontSize = palette.fontSp.sp,
                        modifier = Modifier.clickable { pickScheme.launch("*/*") },
                    )
                    Text(
                        text = "EXPORT",
                        color = palette.c(3),
                        fontFamily = FontFamily.Monospace,
                        fontSize = palette.fontSp.sp,
                        modifier = Modifier.clickable {
                            exportScheme.launch("${schemeSlug(shownScheme.name)}.json")
                        },
                    )
                    if (!shownScheme.builtin) {
                        Text(
                            text = "DELETE",
                            color = palette.c(8),
                            fontFamily = FontFamily.Monospace,
                            fontSize = palette.fontSp.sp,
                            modifier = Modifier.clickable { onDeleteUserScheme(shownScheme) },
                        )
                    }
                }
                if (!shownScheme.builtin) {
                    LaunchedEffect(shownScheme.id) {
                        startHexEdit(shownScheme)
                    }
                    FieldRow("NAME", cloneName, true, palette) { cloneName = it }
                    SLOT_IDS.forEach { slotId ->
                        FieldRow(
                            SLOT_LABELS.getValue(slotId),
                            hexDraft[slotId] ?: shownScheme.slot(slotId).toHexRgb(),
                            true,
                            palette,
                        ) { value ->
                            hexDraft = hexDraft + (slotId to value)
                        }
                    }
                    Text(
                        text = "SAVE HEX",
                        color = palette.c(3),
                        fontFamily = FontFamily.Monospace,
                        fontSize = palette.fontSp.sp,
                        modifier = Modifier.clickable {
                            val slots = mutableMapOf<Int, Color>()
                            for (slotId in SLOT_IDS) {
                                val parsed = parseHexColor(hexDraft[slotId] ?: "")
                                if (parsed == null) {
                                    toast(context, "bad hex on ${SLOT_LABELS[slotId]}")
                                    return@clickable
                                }
                                slots[slotId] = parsed
                            }
                            val name = cloneName.trim().ifEmpty { shownScheme.name }
                            onSaveUserScheme(shownScheme.copy(name = name, slots = slots))
                            toast(context, "scheme saved")
                        },
                    )
                }
                Text(
                    text = "Built-in schemes stay as-is. CLONE to edit hex. IMPORT a JSON file of 12 slots.",
                    color = palette.c(2),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                )
                Text(
                    text = "FONT",
                    color = palette.c(3),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnsiButton(
                        "-",
                        palette.c(7),
                        palette.c(7),
                        true,
                        { onPalette(palette.copy(fontSp = (palette.fontSp - 1).coerceAtLeast(12))) },
                        Modifier.weight(1f),
                        palette,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(R_BTN))
                            .background(palette.c(1))
                            .border(HAIR, palette.c(4), RoundedCornerShape(R_BTN))
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = palette.fontSp.toString(),
                            color = palette.c(4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = palette.fontSp.sp,
                        )
                    }
                    AnsiButton(
                        "+",
                        palette.c(7),
                        palette.c(7),
                        true,
                        { onPalette(palette.copy(fontSp = (palette.fontSp + 1).coerceAtMost(22))) },
                        Modifier.weight(1f),
                        palette,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    enabled: Boolean,
    palette: UiPalette,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = palette.c(5),
            fontFamily = FontFamily.Monospace,
            fontSize = palette.fontSp.sp,
            modifier = Modifier.width(80.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(palette.c(5)),
            textStyle = TextStyle(
                color = palette.c(5),
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DropHead(
    title: String,
    open: Boolean,
    border: Color,
    ink: Color,
    palette: UiPalette,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(R_BTN))
            .background(palette.c(1))
            .border(HAIR, border, RoundedCornerShape(R_BTN))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (open) "$title ^" else "$title v",
            color = ink,
            fontFamily = FontFamily.Monospace,
            fontSize = palette.fontSp.sp,
        )
    }
}

@Composable
private fun CmdPickDrop(
    palette: UiPalette,
    open: Boolean,
    onToggle: () -> Unit,
    cmds: List<SavedCmd>,
    onPick: (SavedCmd) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DropHead(
            title = "TEMPLATES",
            open = open,
            border = palette.c(5),
            ink = palette.c(5),
            palette = palette,
            onToggle = onToggle,
        )
        if (open) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(R_INNER))
                    .background(palette.c(1))
                    .border(HAIR, palette.c(5), RoundedCornerShape(R_INNER))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (cmds.isEmpty()) {
                    Text(
                        text = "NONE",
                        color = palette.c(2),
                        fontFamily = FontFamily.Monospace,
                        fontSize = palette.fontSp.sp,
                    )
                }
                cmds.forEach { cmd ->
                    Text(
                        text = cmd.name.uppercase(),
                        color = palette.c(5),
                        fontFamily = FontFamily.Monospace,
                        fontSize = palette.fontSp.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(cmd) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CmdEditBody(
    palette: UiPalette,
    cmds: List<SavedCmd>,
    onAdd: (SavedCmd) -> Unit,
    onDelete: (SavedCmd) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(R_INNER))
            .background(palette.c(1))
            .border(HAIR, palette.c(5), RoundedCornerShape(R_INNER))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (cmds.isEmpty()) {
            Text(
                text = "NONE",
                color = palette.c(2),
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
            )
        }
        cmds.forEach { cmd ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = cmd.name.uppercase(),
                    color = palette.c(5),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            name = cmd.name
                            body = cmd.content
                        }
                        .padding(vertical = 6.dp),
                )
                Text(
                    text = "DELETE",
                    color = palette.c(8),
                    fontFamily = FontFamily.Monospace,
                    fontSize = palette.fontSp.sp,
                    modifier = Modifier
                        .clickable { onDelete(cmd) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            cursorBrush = SolidColor(palette.c(5)),
            textStyle = TextStyle(
                color = palette.c(5),
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
            ),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(R_INNER))
                        .border(HAIR, palette.c(5), RoundedCornerShape(R_INNER))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    if (name.isEmpty()) {
                        Text(
                            text = "NAME",
                            color = palette.c(2),
                            fontFamily = FontFamily.Monospace,
                            fontSize = palette.fontSp.sp,
                        )
                    }
                    inner()
                }
            },
        )
        BasicTextField(
            value = body,
            onValueChange = { body = it },
            singleLine = false,
            minLines = 2,
            maxLines = 6,
            cursorBrush = SolidColor(palette.c(4)),
            textStyle = TextStyle(
                color = palette.c(4),
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
            ),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(R_INNER))
                        .border(HAIR, palette.c(5), RoundedCornerShape(R_INNER))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    if (body.isEmpty()) {
                        Text(
                            text = "CONTENT",
                            color = palette.c(2),
                            fontFamily = FontFamily.Monospace,
                            fontSize = palette.fontSp.sp,
                        )
                    }
                    inner()
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnsiButton(
                "SAVE",
                palette.c(3),
                palette.c(3),
                true,
                {
                    val n = name.trim()
                    val c = body.trim()
                    if (n.isEmpty() || c.isEmpty()) return@AnsiButton
                    onAdd(SavedCmd(n, c))
                    name = ""
                    body = ""
                },
                Modifier.weight(1f),
                palette,
            )
            AnsiButton(
                "IMPORT",
                palette.c(3),
                palette.c(3),
                true,
                onImport,
                Modifier.weight(1f),
                palette,
            )
            AnsiButton(
                "EXPORT",
                palette.c(3),
                palette.c(3),
                true,
                onExport,
                Modifier.weight(1f),
                palette,
            )
        }
        Text(
            text = "DELETE removes a saved template. Select a name to edit it. IMPORT merges by name.",
            color = palette.c(2),
            fontFamily = FontFamily.Monospace,
            fontSize = palette.fontSp.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WrapText(
    text: String,
    color: Color,
    palette: UiPalette,
    onLongCopy: () -> Unit,
) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = palette.fontSp.sp,
        softWrap = true,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongCopy),
    )
}

@Composable
private fun LineBox(
    color: Color,
    modifier: Modifier = Modifier,
    bg: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(R_PANEL))
            .background(bg)
            .border(HAIR, color, RoundedCornerShape(R_PANEL))
            .padding(12.dp),
    ) {
        content()
    }
}

@Composable
private fun AnsiButton(
    label: String,
    border: Color,
    ink: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    palette: UiPalette,
) {
    val drawBorder = if (enabled) border else border.copy(alpha = 0.35f)
    val drawInk = if (enabled) ink else ink.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(R_BTN))
            .background(palette.c(1))
            .border(HAIR, drawBorder, RoundedCornerShape(R_BTN))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = drawInk,
            fontFamily = FontFamily.Monospace,
            fontSize = palette.fontSp.sp,
        )
    }
}

private fun formatStatus(status: SwaygentrcStatus): String {
    if (status.error != null) {
        return "ERROR: ${status.error}"
    }
    if (status.httpCode == 409 && !status.paused) {
        return "ALREADY RUNNING (PID ${status.pid})"
    }
    if (status.scheduled) {
        return "POWEROFF SCHEDULED"
    }
    if (status.paused) {
        return "SESSION PAUSED"
    }
    val jail = if (status.jailed) "  JAILED" else ""
    val backend = status.display.trim().uppercase().let { if (it.isNotEmpty()) "  $it" else "" }
    return if (status.running) {
        "RUNNING (PID ${status.pid})$jail$backend"
    } else {
        "STOPPED$jail$backend"
    }
}
