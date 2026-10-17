package dev.daarkolabs.tandem.ide

import com.intellij.configurationStore.saveSettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import javax.swing.JLabel
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [TandemSettings], its page in Settings | Tools | Tandem ([TandemSettingsConfigurable]) and [TandemWorkspaceState], in a headless
 * IDE. The test task passes the fake CLI as `tandem.claudePath`.
 */
@TestApplication
class TandemSettingsTest {
    companion object {
        private val tempDir = tempPathFixture()
    }

    /** The settings are the application's, shared with every other test: each test leaves the defaults behind. */
    @AfterEach
    fun restoreDefaults() {
        TandemSettings.getInstance().loadState(TandemSettings.State())
    }

    @Test
    fun `settings round-trip and the path override wins`() {
        // tandem.claudePath names an executable already, so the override has to be another one to show that it wins.
        val custom = tempDir.get().resolve("custom").createDirectories().resolve("my-claude").also {
            it.writeText("#!/bin/sh\n")
            it.toFile().setExecutable(true)
        }.toString()
        val settings = TandemSettings.getInstance()
        settings.loadState(
            TandemSettings.State().apply {
                claudePath = custom
                maxLiveSessions = 2
            },
        )
        assertEquals(custom, ClaudeBinary.find())
        assertEquals(custom, settings.state.claudePath)
        assertEquals(2, settings.state.maxLiveSessions)
        assertEquals(2, settings.maxLiveSessions())
    }

    @Test
    fun `a blank path falls back to the test hook and the cap stays in range`() {
        val settings = TandemSettings.getInstance()
        settings.loadState(
            TandemSettings.State().apply {
                claudePath = "  "
                maxLiveSessions = 40
            },
        )
        assertEquals(System.getProperty("tandem.claudePath"), ClaudeBinary.find())
        assertEquals(16, settings.maxLiveSessions(), "a hand-edited file cannot raise the cap past 16")
        settings.state.maxLiveSessions = 0
        assertEquals(1, settings.maxLiveSessions())
        assertEquals(4, TandemSettings.State().maxLiveSessions, "the default cap")
    }

    @Test
    fun `the page is registered under Tools`() {
        val pages = Configurable.APPLICATION_CONFIGURABLE.extensionList
        val page = assertNotNull(pages.singleOrNull { it.id == "dev.daarkolabs.tandem.settings" })
        assertEquals("tools", page.parentId)
        assertEquals(TandemSettingsConfigurable::class.java.name, page.instanceClass)
    }

    @Test
    fun `the settings page writes the path and cap`() = timeoutRunBlocking {
        val configurable = TandemSettingsConfigurable()
        try {
            val (before, after) = withContext(Dispatchers.EDT) {
                val page = configurable.createComponent()
                configurable.reset()
                val unchanged = configurable.isModified
                val path = assertNotNull(UIUtil.findComponentOfType(page, TextFieldWithBrowseButton::class.java))
                val cap = UIUtil.findComponentsOfType(page, JBTextField::class.java).single { it !== path.textField }
                path.text = "/opt/claude/bin/claude"
                // A 1 typed in front of the 4: replacing the text would empty the field for a moment, which headless mode logs as invalid.
                cap.document.insertString(0, "1", null)
                val changed = configurable.isModified
                configurable.apply()
                unchanged to changed
            }
            assertFalse(before, "nothing to apply right after reset")
            assertTrue(after)
            val state = TandemSettings.getInstance().state
            assertEquals("/opt/claude/bin/claude", state.claudePath)
            assertEquals(14, state.maxLiveSessions)
        } finally {
            withContext(Dispatchers.EDT) { configurable.disposeUIResources() }
        }
    }

    @Test
    fun `the page shows the executable it detects`() = timeoutRunBlocking {
        val fake = System.getProperty("tandem.claudePath")
        val configurable = TandemSettingsConfigurable()
        try {
            val page = withContext(Dispatchers.EDT) { configurable.createComponent() }
            // Found on a background thread, then shown: the label starts as "Detecting…".
            suspend fun shown(): Boolean =
                withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(page, JLabel::class.java).any { it.text == fake } }
            while (!shown()) delay(20)
        } finally {
            withContext(Dispatchers.EDT) { configurable.disposeUIResources() }
        }
    }

    @Test
    fun `workspace state round-trips`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("workspace").createDirectories()
        openProjectAsync(dir).useProjectAsync { project ->
            TandemWorkspaceState.getInstance(project).state.apply {
                openSessionIds = mutableListOf("one", "two")
                selectedSessionId = "two"
                sidebarCollapsed = true
                lastModel = "opus"
                lastEffort = "high"
                lastPermissionMode = "plan"
            }
            saveSettings(project, forceSavingAllSettings = true)
        }
        // Read back from the workspace file, as the next IDE run does.
        val loaded = openProjectAsync(dir).useProjectAsync { project ->
            val state = TandemWorkspaceState.getInstance(project).state
            listOf(
                state.openSessionIds.toList(),
                state.selectedSessionId,
                state.sidebarCollapsed,
                state.lastModel,
                state.lastEffort,
                state.lastPermissionMode,
            )
        }
        assertEquals(listOf(listOf("one", "two"), "two", true, "opus", "high", "plan"), loaded)
    }
}
