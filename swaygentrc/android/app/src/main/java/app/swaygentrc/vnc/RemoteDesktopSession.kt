package app.swaygentrc.vnc

import java.io.Closeable

/** Protocol-agnostic remote desktop input surface (ideas from Haven; our MIT client). */
interface RemoteDesktopSession : Closeable {
    fun sendMouseMove(x: Int, y: Int)
    fun sendMouseButton(button: Int, pressed: Boolean)
    fun sendMouseClick(x: Int, y: Int, button: Int = 1)
    fun sendMouseWheel(deltaY: Int)
    fun sendKey(keySym: Int, pressed: Boolean)
    fun sendClipboardText(text: String)
    fun pause()
    fun resume()
}
