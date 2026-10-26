package dev.daarkolabs.tandem.ui

import androidx.compose.ui.graphics.Color
import dev.daarkolabs.tandem.chat.ContextTone

/** Tandem's own colours: the only literal colours in the chat column (spec 4.2). Everything else comes from the theme. */
internal object TandemColors {
    /** The coral of the working row's orbs (the spark). */
    val spark: Color = Color(0xFFD97757)

    /**
     * A context category's colour in the popover's bar and swatches (desktop-parity spec 3.1), light or [dark]: the four categories
     * the desktop colours; null for [ContextTone.NEUTRAL] and [ContextTone.FREE], which take the theme's dim colour and the track.
     */
    fun contextTone(tone: ContextTone, dark: Boolean): Color? = when (tone) {
        ContextTone.MESSAGES -> if (dark) Color(0xFF548AF7) else Color(0xFF3574F0)
        ContextTone.MCP_TOOLS -> if (dark) Color(0xFFF08A6C) else Color(0xFFE06C4C)
        ContextTone.MEMORY_FILES -> if (dark) Color(0xFF5FB865) else Color(0xFF4FA562)
        ContextTone.SYSTEM_PROMPT -> if (dark) Color(0xFFE5B85C) else Color(0xFFD9A343)
        ContextTone.NEUTRAL, ContextTone.FREE -> null
    }
}
