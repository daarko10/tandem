package dev.daarkolabs.tandem.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.chat.MessageActions
import dev.daarkolabs.tandem.ui.rows.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The hover row's Copy (polish spec 2.6), through the IDE's clipboard. */
@TestApplication
class MessageActionsTest {
    @Test
    fun `Copy puts the reply's Markdown on the clipboard`() = timeoutRunBlocking(30.seconds) {
        val actions = MessageActions("e1", "# Hi\n\n`x`", null)
        val copied = withContext(Dispatchers.EDT) {
            copyToClipboard(actions.markdown)
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        }
        assertEquals("# Hi\n\n`x`", copied)
    }
}
