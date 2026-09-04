package app.swaygentrc.vnc

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode

/** X11 keysyms for modifiers / navigation (RFB KeyEvent). */
object Xk {
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val META_L = 0xffe7
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val DELETE = 0xffff
}

/**
 * Map Compose/Android keys to X11 keysyms for VNC.
 * Modifiers (Ctrl/Alt/Shift/Meta) are included so IME / Hacker's Keyboard
 * special keys go through the wire — not on-screen buttons.
 */
fun keyToKeySym(key: Key): Int? {
    when (key) {
        Key.ShiftLeft -> return Xk.SHIFT_L
        Key.ShiftRight -> return Xk.SHIFT_R
        Key.CtrlLeft -> return Xk.CONTROL_L
        Key.CtrlRight -> return Xk.CONTROL_R
        Key.AltLeft -> return Xk.ALT_L
        Key.AltRight -> return Xk.ALT_R
        Key.MetaLeft -> return Xk.SUPER_L
        Key.MetaRight -> return Xk.META_L
        Key.Backspace -> return Xk.BACKSPACE
        Key.Tab -> return Xk.TAB
        Key.Enter -> return Xk.RETURN
        Key.NumPadEnter -> return Xk.RETURN
        Key.Escape -> return Xk.ESCAPE
        Key.DirectionLeft -> return Xk.LEFT
        Key.DirectionUp -> return Xk.UP
        Key.DirectionRight -> return Xk.RIGHT
        Key.DirectionDown -> return Xk.DOWN
        Key.MoveHome -> return Xk.HOME
        Key.MoveEnd -> return Xk.END
        Key.PageUp -> return Xk.PAGE_UP
        Key.PageDown -> return Xk.PAGE_DOWN
        Key.Insert -> return Xk.INSERT
        Key.Delete -> return Xk.DELETE
        else -> Unit
    }
    // Function keys F1–F12
    val code = key.nativeKeyCode
    if (code in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12) {
        return 0xffbe + (code - AndroidKeyEvent.KEYCODE_F1)
    }
    return null
}

/** Printable Unicode → Latin-1 / ASCII keysym (same range for BMP latin). */
fun charToKeySym(ch: Char): Int? {
    return when (ch) {
        '\n', '\r' -> Xk.RETURN
        '\t' -> Xk.TAB
        '\u0008' -> Xk.BACKSPACE
        '\u007f' -> Xk.DELETE
        else -> {
            val c = ch.code
            if (c in 0x20..0x7e || c in 0xa0..0xff) c else null
        }
    }
}
