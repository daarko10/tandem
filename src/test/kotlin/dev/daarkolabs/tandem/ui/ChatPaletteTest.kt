package dev.daarkolabs.tandem.ui

import androidx.compose.ui.graphics.Color
import com.intellij.openapi.editor.markup.TextAttributes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The editor scheme colours of the Bash body (polish spec 2.4), read through [schemeColor]: pure, no application. */
class ChatPaletteTest {
    @Test
    fun `shell colours fall back to the palette when the scheme has none`() {
        assertEquals(Color.Red, schemeColor(null, Color.Red))
        assertEquals(Color.Red, schemeColor(TextAttributes(), Color.Red))
        assertEquals(
            Color(0xFF56A8F5),
            schemeColor(TextAttributes().apply { foregroundColor = java.awt.Color(0x56A8F5) }, Color.Red),
        )
    }
}
