package dev.daarkolabs.tandem.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentUI
import dev.daarkolabs.tandem.chat.TabSource
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.SessionStatus.IDLE
import dev.daarkolabs.tandem.session.SessionStatus.RUNNING
import dev.daarkolabs.tandem.session.SessionStatus.STOPPED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [SessionTabsController]'s bookkeeping (desktop-parity spec 18, 9.8) on a stand-in [ContentUI] that shows only the selected
 * content's component, as `ToolWindowContentUi.ensureSelectedContentVisible` does; the real header is the IDE tests' part.
 */
@TestApplication
class SessionTabsControllerTest {
    private val project = projectFixture()

    private val compute = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())

    @AfterEach
    fun stop() {
        scope.cancel()
        compute.close()
    }

    /** The shared panel; records its dispose. */
    private class Panel : JPanel(), Disposable {
        var disposed = false

        override fun dispose() {
            disposed = true
        }
    }

    /** Shows only the selected content's component, returns early when it is shown already, and empties itself when none is. */
    private class StandIn : ContentUI {
        val shown = JPanel()
        var removals = 0
        private lateinit var manager: ContentManager

        init {
            shown.addContainerListener(object : ContainerListener {
                override fun componentAdded(e: ContainerEvent) {}

                override fun componentRemoved(e: ContainerEvent) {
                    removals++
                }
            })
        }

        override fun getComponent(): JComponent = shown

        override fun setManager(manager: ContentManager) {
            this.manager = manager
            manager.addContentManagerListener(object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) = ensureSelectedContentVisible()

                override fun contentAdded(event: ContentManagerEvent) = ensureSelectedContentVisible()

                override fun contentRemoved(event: ContentManagerEvent) = ensureSelectedContentVisible()
            })
        }

        private fun ensureSelectedContentVisible() {
            val selected = manager.selectedContent
            if (selected == null) {
                shown.removeAll()
                return
            }
            if (shown.componentCount == 1 && shown.getComponent(0) === selected.component) return
            shown.removeAll()
            shown.add(selected.component)
        }

        override fun isSingleSelection(): Boolean = true

        override fun isToSelectAddedContent(): Boolean = false

        override fun canBeEmptySelection(): Boolean = false

        override fun canChangeSelectionTo(content: Content, implicit: Boolean): Boolean = true

        override fun getCloseActionName(): String = "Close"

        override fun getCloseAllButThisActionName(): String = "Close Others"

        override fun getPreviousContentActionName(): String = "Previous"

        override fun getNextContentActionName(): String = "Next"
    }

    private class Source : TabsSource {
        val open = MutableStateFlow<List<TabSource>>(emptyList())
        override val tabs: Flow<List<TabSource>> = open
        override val selected = MutableStateFlow<SessionId?>(null)
    }

    private class Recording : TabActions {
        val shown = ArrayList<SessionId>()
        val closed = ArrayList<SessionId>()

        override fun show(id: SessionId): Job? {
            shown += id
            return null
        }

        override fun close(id: SessionId) {
            closed += id
        }
    }

    private inner class Harness {
        val standIn = StandIn()
        val manager: ContentManager = ContentFactory.getInstance().createContentManager(standIn, true, project.get())
        val panel = Panel()
        val source = Source()
        val actions = Recording()
        val focused = ArrayList<SessionId>()
        lateinit var controller: SessionTabsController

        init {
            Disposer.register(project.get(), manager)
        }

        suspend fun start(): Harness = withContext(Dispatchers.EDT) {
            controller = SessionTabsController(manager, panel, source, actions, { focused += it }, scope, compute, Dispatchers.EDT)
            this@Harness
        }

        /** The labels the tests give their sessions: the id upper-cased unless a test renames it. */
        val byLabel = HashMap<SessionId, String>()

        fun set(vararg tabs: TabSource, selected: SessionId?) {
            tabs.forEach { byLabel[it.id] = it.title }
            source.open.value = tabs.toList()
            source.selected.value = selected
        }

        suspend fun until(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!withContext(Dispatchers.EDT) { condition() }) {
                if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
                delay(10)
            }
        }
    }

    /** Counts the controller's applies: the apply context's block leaves its thread once it has run, so a count is an apply done. */
    private class AppliedCount : ThreadContextElement<Unit>, AbstractCoroutineContextElement(AppliedCount) {
        companion object Key : CoroutineContext.Key<AppliedCount>

        val count = AtomicInteger()

        override fun updateThreadContext(context: CoroutineContext) {}

        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
            count.incrementAndGet()
        }
    }

    private fun tab(id: SessionId, status: SessionStatus = IDLE, title: String = id.uppercase(), attention: Boolean = false): TabSource =
        TabSource(id, title, status, attention)

    /** Session [id]'s content, found by the label the harness gave it. */
    private fun Harness.of(id: SessionId): Content = assertNotNull(manager.contents.firstOrNull { it.displayName == byLabel[id] }, "no tab for $id")

    private suspend fun Harness.withTabs(vararg tabs: TabSource, selected: SessionId?): Harness {
        set(*tabs, selected = selected)
        until("tabs ${tabs.map { it.id }}") { controller.tabs.value == tabs.map { it.id } && manager.selectedContent?.displayName == (selected?.let { byLabel[it] } ?: "") }
        return this
    }

    // spec: contents follow the tabs
    @Test
    fun `contents follow the tabs`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a", RUNNING, "Alpha"), tab("b", IDLE, "Beta"), selected = "a")
        withContext(Dispatchers.EDT) {
            assertEquals(listOf("Alpha", "Beta"), h.manager.contents.map { it.displayName })
            assertEquals("Alpha · running", h.of("a").description)
            assertEquals("Beta · idle", h.of("b").description)
            assertEquals(SessionTabIcon(RUNNING, false), h.of("a").icon)
            assertEquals(SessionTabIcon(IDLE, false), h.of("b").icon)
        }
        h.set(tab("a", RUNNING, "Gamma"), tab("b", IDLE, "Beta"), selected = "a")
        h.until("a renamed") { h.manager.contents.any { it.displayName == "Gamma" } }
        h.set(tab("a", RUNNING, "Gamma"), tab("b", IDLE, "Beta", attention = true), selected = "a")
        h.until("b's attention") { h.of("b").icon == SessionTabIcon(IDLE, true) }
        withContext(Dispatchers.EDT) {
            assertTrue(h.of("b").description.endsWith(" · needs you"), h.of("b").description)
            for (content in h.manager.contents) {
                assertTrue(content.isCloseable)
                assertEquals(true, content.getUserData(ToolWindow.SHOW_CONTENT_ICON))
                assertEquals(false, content.getUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT))
            }
        }
        h.set(tab("a", RUNNING, "Gamma"), tab("b", STOPPED, "Beta"), selected = "a")
        h.until("b's tab gone") { h.manager.contentCount == 1 }
        withContext(Dispatchers.EDT) { assertEquals("Gamma", h.manager.contents.single().displayName) }
    }

    // spec: a conflated emission never leaves the selection empty
    @Test
    fun `a conflated emission never leaves the selection empty`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), tab("c"), selected = "b")
        val seen = ArrayList<Content?>()
        withContext(Dispatchers.EDT) {
            h.manager.addContentManagerListener(object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) {
                    seen += h.manager.selectedContent
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    seen += h.manager.selectedContent
                }
            })
        }
        // One step on the compute thread, so the controller sees both changes together.
        withContext(compute) { h.set(tab("a"), tab("c"), selected = "c") }
        h.until("c selected, b gone") { h.controller.tabs.value == listOf("a", "c") && h.manager.selectedContent?.displayName == "C" }
        withContext(Dispatchers.EDT) {
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.none { it == null }, "the selection was empty at some point")
            assertNull(h.manager.contents.firstOrNull { it.displayName == "B" })
        }
    }

    // spec: close all and close others are vetoed and close each session once
    @Test
    fun `close all and close others are vetoed and close each session once`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), tab("c"), selected = "b")
        withContext(Dispatchers.EDT) {
            h.manager.removeAllContents(true)
            assertEquals(3, h.manager.contentCount)
            assertEquals(listOf("a", "b", "c"), h.actions.closed)
            // Close Other Tabs on b.
            h.manager.removeContent(h.of("a"), true)
            h.manager.removeContent(h.of("c"), true)
            assertEquals(listOf("a", "b", "c", "a", "c"), h.actions.closed)
            assertSame(h.of("b"), h.manager.selectedContent)
            assertEquals(3, h.manager.contentCount)
        }
    }

    // spec: every content's component is the one panel
    @Test
    fun `every content's component is the one panel`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), tab("c"), selected = "a")
        withContext(Dispatchers.EDT) {
            assertEquals(3, h.manager.contentCount)
            h.manager.contents.forEach { assertSame(h.panel, it.component) }
        }
    }

    // spec: a selection from the source keeps the panel in the stand-in's component
    @Test
    fun `a selection from the source keeps the panel in the stand-in's component`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), selected = "a")
        withContext(Dispatchers.EDT) {
            assertSame(h.standIn.shown, h.panel.parent)
            h.standIn.removals = 0
        }
        for (id in listOf("b", "a", "b")) {
            h.withTabs(tab("a"), tab("b"), selected = id)
            withContext(Dispatchers.EDT) { assertSame(h.standIn.shown, h.panel.parent) }
        }
        withContext(Dispatchers.EDT) {
            assertSame(h.of("b"), h.manager.selectedContent)
            assertEquals(0, h.standIn.removals, "the panel was taken out")
            assertTrue(h.actions.shown.isEmpty())
        }
    }

    // spec: closing the last tab leaves the placeholder and the content manager is never empty
    @Test
    fun `closing the last tab leaves the placeholder and the content manager is never empty`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), selected = "a")
        val counts = ArrayList<Int>()
        withContext(Dispatchers.EDT) {
            h.manager.addContentManagerListener(object : ContentManagerListener {
                override fun contentRemoved(event: ContentManagerEvent) {
                    counts += h.manager.contentCount
                }
            })
        }
        withContext(compute) { h.set(selected = null) }
        h.until("only the placeholder") { h.controller.tabs.value.isEmpty() && h.manager.contentCount == 1 && h.manager.contents.single().displayName == "" }
        withContext(Dispatchers.EDT) {
            assertSame(h.manager.contents.single(), h.manager.selectedContent)
            assertTrue(counts.isNotEmpty() && counts.all { it > 0 }, "the content manager emptied: $counts")
            assertTrue(h.actions.closed.isEmpty())
        }
    }

    // spec: with no tab, one placeholder, not closeable, with an empty name
    @Test
    fun `with no tab, one placeholder, not closeable, with an empty name`() = timeoutRunBlocking(30.seconds) {
        val h = Harness()
        val applied = AppliedCount()
        withContext(Dispatchers.EDT) {
            h.controller = SessionTabsController(h.manager, h.panel, h.source, h.actions, { h.focused += it }, scope, compute, Dispatchers.EDT + applied)
            // Right after construction, before any emission.
            val placeholder = h.manager.contents.single()
            assertEquals("", placeholder.displayName)
            assertFalse(placeholder.isCloseable)
            assertSame(placeholder, h.manager.selectedContent)
            assertEquals(0, applied.count.get(), "nothing applied yet")
        }
        // The empty source's emission, applied: the same single placeholder.
        h.until("the empty source's apply") { applied.count.get() >= 1 }
        withContext(Dispatchers.EDT) {
            val placeholder = h.manager.contents.single()
            assertEquals("", placeholder.displayName)
            assertFalse(placeholder.isCloseable)
            assertSame(placeholder, h.manager.selectedContent)
            assertTrue(h.controller.tabs.value.isEmpty())
        }
        h.withTabs(tab("a"), selected = "a")
        withContext(Dispatchers.EDT) { assertEquals(listOf("A"), h.manager.contents.map { it.displayName }) }
    }

    // spec: a content the platform selects calls show once and requests focus
    @Test
    fun `a content the platform selects calls show once and requests focus`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), selected = "a")
        withContext(Dispatchers.EDT) {
            h.standIn.removals = 0
            h.manager.setSelectedContent(h.of("b")) // a tab click's path
            assertEquals(listOf("b"), h.actions.shown)
            assertEquals(listOf("b"), h.focused)
            assertEquals(1, h.standIn.removals, "the platform's switch takes the panel out once")
            assertSame(h.standIn.shown, h.panel.parent)
            assertFalse(h.panel.disposed)
        }
    }

    // spec: a selection from the source selects its content without calling show
    @Test
    fun `a selection from the source selects its content without calling show`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), selected = "a")
        h.withTabs(tab("a"), tab("b"), selected = "b")
        withContext(Dispatchers.EDT) {
            assertSame(h.of("b"), h.manager.selectedContent)
            assertTrue(h.actions.shown.isEmpty())
            assertTrue(h.focused.isEmpty())
        }
    }

    // spec: a platform removeContent is vetoed and calls close once
    @Test
    fun `a platform removeContent is vetoed and calls close once`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), selected = "b")
        withContext(Dispatchers.EDT) {
            val a = h.of("a")
            h.manager.removeContent(a, true)
            assertTrue(a in h.manager.contents)
            assertEquals(listOf("a"), h.actions.closed)
        }
    }

    // spec: a session the source drops loses its content without a close
    @Test
    fun `a session the source drops loses its content without a close`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), selected = "b")
        h.withTabs(tab("b"), selected = "b")
        withContext(Dispatchers.EDT) {
            assertEquals(listOf("B"), h.manager.contents.map { it.displayName })
            assertTrue(h.actions.closed.isEmpty())
        }
    }

    // spec: no content's removal disposes the panel
    @Test
    fun `no content's removal disposes the panel`() = timeoutRunBlocking(30.seconds) {
        val h = Harness().start().withTabs(tab("a"), tab("b"), tab("c"), selected = "a")
        h.withTabs(tab("a"), tab("c"), selected = "c")
        withContext(compute) { h.set(selected = null) }
        h.until("only the placeholder") { h.manager.contentCount == 1 && h.manager.contents.single().displayName == "" }
        withContext(Dispatchers.EDT) {
            h.manager.removeAllContents(true)
            assertFalse(h.panel.disposed)
        }
        h.controller.close()
        assertFalse(h.panel.disposed)
    }
}
