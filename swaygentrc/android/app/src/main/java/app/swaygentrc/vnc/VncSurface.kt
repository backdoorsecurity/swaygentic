package app.swaygentrc.vnc

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.swaygentrc.UiPalette
import kotlin.math.abs

/** How 1-finger drag maps to the remote desktop. */
enum class VncDragMode {
    /** Vertical drag → VNC wheel (buttons 4/5). Tap still clicks. Default for phones. */
    SCROLL,

    /** Drag holds mouse button 1 (text select / drag windows). */
    SELECT,
}

@Composable
fun VncSurface(
    palette: UiPalette,
    frame: Bitmap?,
    fbW: Int,
    fbH: Int,
    placeholder: String,
    controller: VncController,
    dragMode: VncDragMode = VncDragMode.SCROLL,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.c(1), RoundedCornerShape(16.dp))
            .border(1.dp, palette.c(11), RoundedCornerShape(16.dp))
            .onSizeChanged { size ->
                widthPx = size.width.toFloat()
                heightPx = size.height.toFloat()
            }
            .pointerInput(fbW, fbH, widthPx, heightPx, dragMode) {
                if (fbW <= 0 || fbH <= 0 || widthPx <= 0f || heightPx <= 0f) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = down.position
                    var moved = false
                    var twoFinger = false
                    var wheelAccum = 0f
                    val start = down.position
                    var selectHeld = false

                    mapAndMove(controller, last, widthPx, heightPx, fbW, fbH)

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (!twoFinger && !moved) {
                                mapAndClick(controller, start, widthPx, heightPx, fbW, fbH)
                            } else if (selectHeld) {
                                controller.sendButton(1, false)
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            twoFinger = true
                            if (selectHeld) {
                                controller.sendButton(1, false)
                                selectHeld = false
                            }
                            val avgY = pressed.map { it.position.y }.average().toFloat()
                            wheelAccum = emitWheel(controller, avgY - last.y, wheelAccum)
                            last = Offset(last.x, avgY)
                            pressed.forEach { it.consume() }
                            continue
                        }
                        val change = pressed.first()
                        val pos = change.position
                        if (change.positionChange() != Offset.Zero) {
                            val dist = (pos - start).getDistance()
                            if (!moved && dist > 8f) {
                                moved = true
                                when (dragMode) {
                                    VncDragMode.SELECT -> {
                                        mapAndMove(controller, pos, widthPx, heightPx, fbW, fbH)
                                        controller.sendButton(1, true)
                                        selectHeld = true
                                    }
                                    VncDragMode.SCROLL -> {
                                        mapAndMove(controller, start, widthPx, heightPx, fbW, fbH)
                                        wheelAccum = emitWheel(controller, pos.y - last.y, wheelAccum)
                                    }
                                }
                            } else if (moved) {
                                when (dragMode) {
                                    VncDragMode.SELECT -> {
                                        mapAndMove(controller, pos, widthPx, heightPx, fbW, fbH)
                                    }
                                    VncDragMode.SCROLL -> {
                                        wheelAccum = emitWheel(controller, pos.y - last.y, wheelAccum)
                                    }
                                }
                            } else {
                                mapAndMove(controller, pos, widthPx, heightPx, fbW, fbH)
                            }
                            change.consume()
                        }
                        last = pos
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "remote desktop",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = placeholder,
                color = palette.c(2),
                fontFamily = FontFamily.Monospace,
                fontSize = palette.fontSp.sp,
            )
        }
    }
}

/** Finger down (positive dy) → wheel down (button 5). Returns leftover accum. */
private fun emitWheel(controller: VncController, dy: Float, accumIn: Float): Float {
    var accum = accumIn + dy
    while (abs(accum) >= 40f) {
        if (accum > 0) {
            controller.sendWheel(-1)
            accum -= 40f
        } else {
            controller.sendWheel(1)
            accum += 40f
        }
    }
    return accum
}

private fun mapAndMove(
    controller: VncController,
    pos: Offset,
    viewW: Float,
    viewH: Float,
    fbW: Int,
    fbH: Int,
) {
    val mapped = screenToFramebuffer(pos.x, pos.y, viewW, viewH, fbW, fbH) ?: return
    controller.sendMove(mapped.first, mapped.second)
}

private fun mapAndClick(
    controller: VncController,
    pos: Offset,
    viewW: Float,
    viewH: Float,
    fbW: Int,
    fbH: Int,
) {
    val mapped = screenToFramebuffer(pos.x, pos.y, viewW, viewH, fbW, fbH) ?: return
    controller.sendClick(mapped.first, mapped.second, 1)
}
