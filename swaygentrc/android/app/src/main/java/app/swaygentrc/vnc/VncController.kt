package app.swaygentrc.vnc

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

enum class VncConnState {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

class VncController(private val scope: CoroutineScope) {
    var state by mutableStateOf(VncConnState.IDLE)
        private set
    var frame by mutableStateOf<Bitmap?>(null)
        private set
    var errorMessage by mutableStateOf("")
        private set
    var framebufferWidth by mutableStateOf(0)
        private set
    var framebufferHeight by mutableStateOf(0)
        private set
    var connectedHost by mutableStateOf("")
        private set
    var connectedPort by mutableStateOf(5900)
        private set
    var activeOptions by mutableStateOf(VncOptions())
        private set

    private var session: VncDesktopSession? = null
    private var connectJob: Job? = null
    private val mutex = Mutex()

    /** Single thread so pointer events stay ordered and off the UI thread. */
    private val inputExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vnc-input").apply { isDaemon = true }
    }
    private val inputDispatcher = inputExecutor.asCoroutineDispatcher()
    private val liveSession = AtomicReference<VncDesktopSession?>(null)

    fun connect(host: String, port: Int, options: VncOptions = activeOptions) {
        val h = host.trim()
        if (h.isEmpty()) {
            state = VncConnState.ERROR
            errorMessage = "set VNC host in SYSTEM → API"
            return
        }
        val opts = options.normalized()
        connectJob?.cancel()
        connectJob = scope.launch {
            mutex.withLock {
                disconnectLocked()
                state = VncConnState.CONNECTING
                errorMessage = ""
                connectedHost = h
                connectedPort = port.coerceIn(1, 65535)
                activeOptions = opts
                val sess = VncDesktopSession(
                    onFrame = { bmp ->
                        scope.launch(Dispatchers.Main.immediate) {
                            framebufferWidth = bmp.width
                            framebufferHeight = bmp.height
                            frame = bmp
                            if (state == VncConnState.CONNECTING) {
                                state = VncConnState.CONNECTED
                            }
                        }
                    },
                    onError = { err ->
                        scope.launch(Dispatchers.Main.immediate) {
                            errorMessage = err.message ?: err.toString()
                            state = VncConnState.ERROR
                        }
                    },
                    onRunningChanged = { running ->
                        scope.launch(Dispatchers.Main.immediate) {
                            if (!running && state == VncConnState.CONNECTED) {
                                state = VncConnState.IDLE
                            }
                        }
                    },
                    options = opts,
                )
                session = sess
                liveSession.set(sess)
                try {
                    withContext(Dispatchers.IO) {
                        sess.connect(h, connectedPort)
                    }
                    if (state == VncConnState.CONNECTING) {
                        state = VncConnState.CONNECTED
                    }
                } catch (t: Throwable) {
                    errorMessage = t.message ?: t.toString()
                    state = VncConnState.ERROR
                    session = null
                    liveSession.set(null)
                }
            }
        }
    }

    fun reconnect(options: VncOptions = activeOptions) {
        val h = connectedHost
        val p = connectedPort
        if (h.isNotBlank()) connect(h, p, options)
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            mutex.withLock { disconnectLocked() }
        }
    }

    private fun disconnectLocked() {
        liveSession.set(null)
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        state = VncConnState.IDLE
        frame = null
    }

    private fun onInput(block: (VncDesktopSession) -> Unit) {
        val sess = liveSession.get() ?: return
        scope.launch(inputDispatcher) {
            try {
                block(sess)
            } catch (_: Throwable) {
                // Drop failed pointer events; connection errors surface via vernacular listener.
            }
        }
    }

    fun sendMove(x: Int, y: Int) {
        onInput { it.sendMouseMove(x, y) }
    }

    fun sendClick(x: Int, y: Int, button: Int = 1) {
        onInput { it.sendMouseClick(x, y, button) }
    }

    fun sendButton(button: Int, pressed: Boolean) {
        onInput { it.sendMouseButton(button, pressed) }
    }

    fun sendWheel(deltaY: Int) {
        onInput { it.sendMouseWheel(deltaY) }
    }

    fun sendKey(keySym: Int, pressed: Boolean) {
        onInput { it.sendKey(keySym, pressed) }
    }
}

/** Map view touch to framebuffer coords with letterbox ContentScale.Fit. */
fun screenToFramebuffer(
    viewX: Float,
    viewY: Float,
    viewW: Float,
    viewH: Float,
    fbW: Int,
    fbH: Int,
): Pair<Int, Int>? {
    if (viewW <= 0f || viewH <= 0f || fbW <= 0 || fbH <= 0) return null
    val scale = minOf(viewW / fbW, viewH / fbH)
    val drawW = fbW * scale
    val drawH = fbH * scale
    val offX = (viewW - drawW) / 2f
    val offY = (viewH - drawH) / 2f
    val x = ((viewX - offX) / scale).toInt()
    val y = ((viewY - offY) / scale).toInt()
    if (x < 0 || y < 0 || x >= fbW || y >= fbH) return null
    return x to y
}
