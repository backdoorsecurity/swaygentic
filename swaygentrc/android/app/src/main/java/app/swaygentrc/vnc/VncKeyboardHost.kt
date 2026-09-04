package app.swaygentrc.vnc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Hidden focus sink: soft/hardware keyboard KeyEvents (incl. Ctrl/Alt/Shift)
 * are forwarded as VNC keysyms. Uses the system IME (e.g. Hacker's Keyboard
 * if the user installed it) — we do not embed a custom keyboard APK.
 */
@Composable
fun VncKeyboardHost(
    active: Boolean,
    controller: VncController,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sentinel = "\u200B" // zero-width space; keeps IME happy without visible text
    var value by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length))) }

    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    Box(modifier = modifier.size(1.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                val old = value.text
                val neu = next.text
                if (neu.length > old.length) {
                    val inserted = neu.substring(old.length)
                    for (ch in inserted) {
                        val sym = charToKeySym(ch) ?: continue
                        controller.sendKey(sym, true)
                        controller.sendKey(sym, false)
                    }
                } else if (neu.length < old.length) {
                    // Backspace from IME delete
                    controller.sendKey(Xk.BACKSPACE, true)
                    controller.sendKey(Xk.BACKSPACE, false)
                }
                value = TextFieldValue(sentinel, TextRange(sentinel.length))
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val sym = keyToKeySym(event.key) ?: return@onPreviewKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            controller.sendKey(sym, true)
                            true
                        }
                        KeyEventType.KeyUp -> {
                            controller.sendKey(sym, false)
                            true
                        }
                        else -> false
                    }
                },
        )
    }
}
