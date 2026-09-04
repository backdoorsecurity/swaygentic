package app.swaygentrc.vnc

import android.graphics.Bitmap
import com.shinyhut.vernacular.client.VernacularClient
import com.shinyhut.vernacular.client.VernacularConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin adapter over MIT vernacular-vnc (Android Bitmap port).
 * Scroll uses VNC buttons 4/5.
 */
class VncDesktopSession(
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onRunningChanged: (Boolean) -> Unit,
    options: VncOptions = VncOptions(),
) : RemoteDesktopSession {

    private val client: VernacularClient
    private val started = AtomicBoolean(false)
    val options: VncOptions = options.normalized()

    init {
        val opts = this.options
        val config = VernacularConfig().apply {
            setColorDepth(opts.colorDepth())
            setTargetFramesPerSecond(opts.targetFps)
            setEnableHextileEncoding(true)
            setEnableCopyrectEncoding(true)
            setEnableRreEncoding(true)
            // Zlib is CPU-heavier on phone decode but usually less Tailscale bandwidth than raw/hextile alone.
            setEnableZLibEncoding(opts.zlib)
            setScreenUpdateListener { bmp -> onFrame(bmp) }
            setErrorListener { err ->
                onRunningChanged(false)
                onError(err)
            }
        }
        client = VernacularClient(config)
    }

    fun connect(host: String, port: Int) {
        if (!started.compareAndSet(false, true)) {
            throw IllegalStateException("VNC session already started")
        }
        try {
            client.start(host, port)
            onRunningChanged(client.isRunning)
        } catch (t: Throwable) {
            started.set(false)
            onRunningChanged(false)
            onError(t)
            throw t
        }
    }

    override fun sendMouseMove(x: Int, y: Int) {
        client.moveMouse(x, y)
    }

    override fun sendMouseButton(button: Int, pressed: Boolean) {
        client.updateMouseButton(button, pressed)
    }

    override fun sendMouseClick(x: Int, y: Int, button: Int) {
        client.moveMouse(x, y)
        client.click(button)
    }

    override fun sendMouseWheel(deltaY: Int) {
        when {
            deltaY > 0 -> client.click(4)
            deltaY < 0 -> client.click(5)
        }
    }

    override fun sendKey(keySym: Int, pressed: Boolean) {
        client.updateKey(keySym, pressed)
    }

    override fun sendClipboardText(text: String) {
        client.copyText(text)
    }

    override fun pause() {
    }

    override fun resume() {
    }

    override fun close() {
        try {
            client.stop()
        } finally {
            started.set(false)
            onRunningChanged(false)
        }
    }
}
