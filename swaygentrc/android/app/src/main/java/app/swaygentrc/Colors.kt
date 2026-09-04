package app.swaygentrc

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

val SLOT_IDS: List<Int> = (1..12).toList()

val SLOT_LABELS: Map<Int, String> = mapOf(
    1 to "BG",
    2 to "DIM",
    3 to "ACCENT",
    4 to "INK",
    5 to "THOUGHT",
    6 to "REPLY",
    7 to "WAIT",
    8 to "ERROR",
    9 to "RUN",
    10 to "KILL",
    11 to "FRAME",
    12 to "TOOL",
)

data class UiScheme(
    val id: String,
    val name: String,
    val slots: Map<Int, Color>,
    val builtin: Boolean = false,
) {
    val ink: Color get() = slot(4)
    val accent: Color get() = slot(3)
    val thought: Color get() = slot(5)

    fun slot(id: Int): Color {
        return slots[id] ?: Color.White
    }

    fun withSlots(slots: Map<Int, Color>): UiScheme {
        return copy(slots = SLOT_IDS.associateWith { slotId ->
            slots[slotId] ?: this.slots[slotId] ?: Color.White
        })
    }
}

private fun scheme(
    id: String,
    name: String,
    ink: Long,
    accent: Long,
    thought: Long,
    reply: Long,
    wait: Long,
    error: Long,
): UiScheme {
    val inkC = Color(ink)
    val waitC = Color(wait)
    val errorC = Color(error)
    return UiScheme(
        id = id,
        name = name,
        builtin = true,
        slots = mapOf(
            1 to Color.Black,
            2 to Color(0xFF242424),
            3 to Color(accent),
            4 to inkC,
            5 to Color(thought),
            6 to Color(reply),
            7 to waitC,
            8 to errorC,
            9 to inkC,
            10 to errorC,
            11 to Color(0xFF1A1A1A),
            12 to waitC,
        ),
    )
}

val UI_SCHEMES: List<UiScheme> = listOf(
    scheme("phosphor", "Phosphor", 0xFF39FF14, 0xFFFF2D95, 0xFF6B9BFF, 0xFFEDEDED, 0xFFFFE566, 0xFFFF3B3B),
    scheme("ice", "Ice", 0xFF7DF9FF, 0xFFF4FBFF, 0xFF7AA2FF, 0xFFD7ECFF, 0xFFFFE566, 0xFFFF5A6A),
    scheme("ember", "Ember", 0xFFFFB020, 0xFFFF6A3D, 0xFFFFD08A, 0xFFFFE8C8, 0xFFFFE566, 0xFFFF3B3B),
    scheme("snow", "Snow", 0xFFF5F5F5, 0xFFFFFFFF, 0xFFB0B0B0, 0xFFE8E8E8, 0xFFFFE566, 0xFFFF5A5A),
    scheme("acid", "Acid", 0xFFC8FF00, 0xFF00FFA6, 0xFF9DFF6A, 0xFFEAFFB0, 0xFFFFE566, 0xFFFF3B3B),
    scheme("kali", "Kali", 0xFFE8EDF2, 0xFFFF2D6A, 0xFF5B8EC9, 0xFFCFD6DE, 0xFFFFC857, 0xFFFF3344),
)

val SCHEME_BY_ID: Map<String, UiScheme> = UI_SCHEMES.associateBy { it.id }

data class UiPalette(
    val schemeId: String = "phosphor",
    val fontSp: Int = 16,
    val slots: Map<Int, Color> = SCHEME_BY_ID.getValue("phosphor").slots,
) {
    fun c(id: Int): Color {
        return slots[id] ?: Color.White
    }

    fun withScheme(scheme: UiScheme): UiPalette {
        return copy(schemeId = scheme.id, slots = scheme.slots)
    }
}

fun schemeSlug(name: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return slug.ifEmpty { "custom" }
}

fun uniqueUserSchemeId(name: String, existing: Collection<String>): String {
    var base = schemeSlug(name)
    if (SCHEME_BY_ID.containsKey(base) || existing.contains(base)) {
        base = "$base-user"
    }
    if (!SCHEME_BY_ID.containsKey(base) && !existing.contains(base)) {
        return base
    }
    var n = 2
    while (SCHEME_BY_ID.containsKey("$base-$n") || existing.contains("$base-$n")) {
        n++
    }
    return "$base-$n"
}

fun parseHexColor(raw: String): Color? {
    val t = raw.trim().removePrefix("#")
    if (t.length != 6 && t.length != 8) return null
    val v = t.toLongOrNull(16) ?: return null
    return if (t.length == 6) {
        Color((0xFF000000L or v).toInt())
    } else {
        Color(v.toInt())
    }
}

fun Color.toHexRgb(): String {
    return String.format("#%06X", 0xFFFFFF and toArgb())
}
