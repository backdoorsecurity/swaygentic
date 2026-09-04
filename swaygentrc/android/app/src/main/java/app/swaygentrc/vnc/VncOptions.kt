package app.swaygentrc.vnc

import com.shinyhut.vernacular.client.rendering.ColorDepth

/**
 * Client-side VNC performance knobs (RFB SetPixelFormat / SetEncodings / update rate).
 * Applied at connect time — changing them reconnects. Server wayvnc FPS cap is separate.
 *
 * Later UX: stepped FPS presets (1, 2, 4, 8, 16, 32, maybe 64) for slow networks;
 * today's default 15 + coerceIn(5, 30) is a stopgap (see SWAYGENTRC.md Phase 4).
 */
data class VncOptions(
    /** 16 = faster on Tailscale; 24 = sharper. */
    val bits: Int = 16,
    val zlib: Boolean = true,
    val targetFps: Int = 15,
) {
    fun colorDepth(): ColorDepth = when (bits) {
        24 -> ColorDepth.BPP_24_TRUE
        8 -> ColorDepth.BPP_8_TRUE
        else -> ColorDepth.BPP_16_TRUE
    }

    fun normalized(): VncOptions = copy(
        bits = when (bits) {
            8, 24 -> bits
            else -> 16
        },
        // Stopgap until stepped presets land (1/2/4/8/16/32/64).
        targetFps = targetFps.coerceIn(5, 30),
    )

    fun label(): String = "C$bits ${if (zlib) "ZLIB" else "NOZ"} ${targetFps}fps"
}
