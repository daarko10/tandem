package dev.daarkolabs.tandem.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.junit5.TestApplication
import dev.daarkolabs.tandem.ui.TandemToolWindowFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Boots a headless IDE application with Tandem loaded from its plugin.xml. */
@TestApplication
class TandemPluginTest {
    @Test
    fun `plugin loads with the expected id and is enabled`() {
        val plugin = assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.daarkolabs.tandem")))
        assertEquals("Tandem", plugin.name)
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun `tool window is registered with its factory`() {
        val toolWindow = ToolWindowEP.EP_NAME.extensionList.singleOrNull { it.id == "Tandem" }
        assertEquals(TandemToolWindowFactory::class.java.name, assertNotNull(toolWindow).factoryClass)
    }
}
