package dev.daarkolabs.tandem.ide

import com.intellij.ide.BrowserUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Opens a link in the system browser: an MCP server's sign-in page (docs/superpowers/specs/2026-10-21-mcp-controls-design.md 3.3,
 * 4.4). Injected, so that tests pass a recorder. Never blocks the calling thread; the link is never written to the IDE log.
 */
fun interface BrowserOpener {
    suspend fun open(url: String)
}

/** Test hook: `log` swaps the system browser for a line in the fake CLI's log ([BrowserOpeners.default]). */
private const val OPENER_PROPERTY: String = "tandem.browserOpener"

object BrowserOpeners {
    /**
     * The system browser ([BrowserUtil.browse] on [Dispatchers.IO]; the platform shows its own balloon when none opens). With the
     * system property `tandem.browserOpener=log` (IDE tests only), one `{"type":"__browse__","url":…}` line appended to
     * `$TANDEM_FAKE_LOG` in one `O_APPEND` write instead, beside the frames the fake CLI logs there.
     */
    fun default(): BrowserOpener = if (System.getProperty(OPENER_PROPERTY) == "log") logOpener() else systemOpener()

    private fun systemOpener(): BrowserOpener = BrowserOpener { url -> withContext(Dispatchers.IO) { BrowserUtil.browse(url) } }

    private fun logOpener(): BrowserOpener = BrowserOpener { url ->
        val log = System.getenv("TANDEM_FAKE_LOG") ?: return@BrowserOpener
        val line = buildJsonObject {
            put("type", "__browse__")
            put("url", url)
        }.toString() + "\n"
        withContext(Dispatchers.IO) {
            // One write of the whole line, opened with O_APPEND: the fake CLI appends its own lines to the same file.
            Files.write(Path.of(log), line.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        }
    }
}
