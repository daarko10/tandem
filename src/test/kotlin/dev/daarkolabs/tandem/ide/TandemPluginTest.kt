@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.ui.TandemBlockRenderer
import dev.daarkolabs.tandem.ui.TandemToolWindowFactory
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.intui.markdown.bridge.styling.create
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughRendererExtension
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Boots a headless IDE application with Tandem loaded from its plugin.xml. */
@TestApplication
class TandemPluginTest {
    @Test
    fun `plugin loads with the expected id and is enabled`() {
        val plugin = assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.daarkolabs.tandem")))
        assertEquals("Tandem", plugin.name)
        assertFalse(PluginManagerCore.isDisabled(plugin.pluginId))
    }

    @Test
    fun `tool window is registered with its factory`() {
        val toolWindow = ToolWindowEP.EP_NAME.extensionList.singleOrNull { it.id == "Tandem" }
        assertEquals(TandemToolWindowFactory::class.java.name, assertNotNull(toolWindow).factoryClass)
    }

    // spec: the tool window lets its tabs close
    @Test
    fun `the tool window lets its tabs close`() {
        assertTrue(ToolWindowEP.EP_NAME.extensionList.single { it.id == "Tandem" }.canCloseContents)
    }

    @Test
    fun `the GFM table and strikethrough modules are visible to the plugin`() {
        val loader = assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.daarkolabs.tandem"))).pluginClassLoader
        Class.forName("org.jetbrains.jewel.markdown.extensions.github.tables.GitHubTableProcessorExtension", false, loader)
        Class.forName("org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughProcessorExtension", false, loader)
        // The test task runs from the project directory, as Fixtures does.
        val xml = Path.of("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue("<module name=\"intellij.platform.jewel.markdown.extensions.gfmTables\"/>" in xml)
        assertTrue("<module name=\"intellij.platform.jewel.markdown.extensions.gfmStrikethrough\"/>" in xml)
    }

    // Spec 4.4: plus (and createCopy) return a TandemBlockRenderer, so a copy Jewel makes keeps the fence copy button.
    @Test
    fun `the block renderer stays a TandemBlockRenderer when extended`() {
        // The bridge styling loads Skia, whose JVM-wide "Reference Cleaner" thread lives as long as the JVM: not a leak of this test.
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner")
        val r = TandemBlockRenderer(MarkdownStyling.create(), emptyList())
        assertIs<TandemBlockRenderer>(r.plus(GitHubStrikethroughRendererExtension))
        assertIs<TandemBlockRenderer>(r.createCopy(null, null, null))
    }
}
