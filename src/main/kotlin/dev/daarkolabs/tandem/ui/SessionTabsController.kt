package dev.daarkolabs.tandem.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import dev.daarkolabs.tandem.chat.SessionTab
import dev.daarkolabs.tandem.chat.TabSource
import dev.daarkolabs.tandem.chat.sessionTabs
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent
import kotlin.coroutines.CoroutineContext

private val LOG = logger<SessionTabsController>()

/** The session a tab's content stands for; the placeholder has none. */
private val SESSION_TAB: Key<SessionId> = Key.create("dev.daarkolabs.tandem.sessionTab")

/** What the tabs are made from (desktop-parity spec 18.4): the open sessions' title, status and attention, and the selection. */
internal interface TabsSource {
    val tabs: Flow<List<TabSource>>
    val selected: StateFlow<SessionId?>
}

/** [TabsSource] over [manager]: its open sessions, each with its title, status and attention as they change, and its selection. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun managerTabsSource(manager: SessionManager): TabsSource = object : TabsSource {
    override val tabs: Flow<List<TabSource>> = manager.sessions.flatMapLatest { open ->
        if (open.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(open.map { handle -> combine(handle.title, handle.status, handle.attention) { t, s, a -> TabSource(handle.id, t, s, a) } }) {
                it.toList()
            }
        }
    }
    override val selected: StateFlow<SessionId?> = manager.selected
}

/**
 * Keeps the tool window's content tabs in step with the sessions (desktop-parity spec 18): one content per session tab
 * ([sessionTabs]), every one with the same [panel] as its component, and, with no tab or no session selected, a placeholder content
 * with an empty name. The tabs are worked out on [compute] and only the Swing calls run on [apply], in an order that never leaves
 * the content manager empty or without a selected content: new contents, changed ones, the selection, then the removals (spec 18.4).
 * The controller selects with `addSelectedContent` then `removeFromSelection`, never `setSelectedContent`, so its own selections
 * never take the shared panel out of the tool window (spec 18.3).
 *
 * The other way round: a tab the platform selects (a click, the tab keys, `ShowContent`) shows its session ([TabActions.show]) and
 * gives it the focus ([focus]), since the platform's switch took the panel out and put it back; a tab the platform removes (its ×,
 * a middle click, the close items, `CloseContent`) is vetoed and its session closed ([TabActions.close]): the tab leaves once the
 * source no longer has it. The controller's own changes never call back.
 *
 * Built on the EDT; [close] stops it.
 */
internal class SessionTabsController(
    private val contentManager: ContentManager,
    private val panel: JComponent,
    source: TabsSource,
    private val actions: TabActions,
    private val focus: (SessionId) -> Unit,
    scope: CoroutineScope,
    compute: CoroutineDispatcher = Dispatchers.Default,
    private val apply: CoroutineContext = Dispatchers.EDT,
) {
    private val own: CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
    private val mutableTabs = MutableStateFlow<List<SessionId>>(emptyList())

    /** The session ids whose tabs were last applied, in order. */
    val tabs: StateFlow<List<SessionId>> = mutableTabs.asStateFlow()

    /** Each session tab's content, by session id. Read and written on the EDT only. */
    private val contents = LinkedHashMap<SessionId, Content>()
    private var placeholder: Content? = null

    /** Set while the controller changes the content manager itself, so that its listener lets those changes be. */
    private var applying = false

    private val listener = object : ContentManagerListener {
        override fun selectionChanged(event: ContentManagerEvent) {
            if (applying || event.operation != ContentManagerEvent.ContentOperation.add) return
            val id = event.content.getUserData(SESSION_TAB) ?: return
            LOG.debug("tab of session $id selected in the header")
            actions.show(id)
            focus(id)
        }

        override fun contentRemoveQuery(event: ContentManagerEvent) {
            if (applying) return
            val id = event.content.getUserData(SESSION_TAB) ?: return
            event.consume()
            LOG.debug("tab of session $id closed in the header")
            actions.close(id)
        }
    }

    init {
        withApplying {
            val shown = ensurePlaceholder()
            select(shown)
        }
        contentManager.addContentManagerListener(listener)
        own.launch(compute) {
            combine(source.tabs, source.selected) { open, selected -> sessionTabs(open, selected) to selected }
                .conflate()
                .collect { (tabs, selected) -> withContext(apply) { applyTabs(tabs, selected) } }
        }
    }

    /** Stops following the sessions. */
    fun close() {
        own.cancel()
        contentManager.removeContentManagerListener(listener)
    }

    private fun applyTabs(tabs: List<SessionTab>, selected: SessionId?) {
        withApplying {
            tabs.forEachIndexed { index, tab ->
                val existing = contents[tab.id]
                if (existing == null) add(tab, if (index == 0) null else tabs[index - 1].id) else update(existing, tab)
            }
            val target = selected?.takeIf { id -> tabs.any { it.id == id } }?.let(contents::get) ?: ensurePlaceholder()
            select(target)
            val kept = tabs.mapTo(HashSet()) { it.id }
            for (id in contents.keys.filter { it !in kept }) {
                val content = contents.remove(id) ?: continue
                contentManager.removeContent(content, true)
                LOG.debug("tab of session $id removed")
            }
            val shown = placeholder
            if (shown != null && target !== shown) {
                placeholder = null
                contentManager.removeContent(shown, true)
            }
        }
        mutableTabs.value = tabs.map { it.id }
    }

    /** Adds [tab]'s content right after the content of the tab [previous] (first when none), so the tabs keep the sessions' order. */
    private fun add(tab: SessionTab, previous: SessionId?) {
        val content = ContentFactory.getInstance().createContent(panel, tab.label, false).apply {
            setShouldDisposeContent(false)
            isCloseable = true
            description = tab.tooltip
            icon = SessionTabIcon(tab.status, tab.attention)
            putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
            // Else the platform draws an unselected tab's dot and ring at half opacity (spec 18.2).
            putUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT, false)
            putUserData(SESSION_TAB, tab.id)
        }
        val after = previous?.let { contentManager.getIndexOfContent(contents.getValue(it)) } ?: -1
        contentManager.addContent(content, after + 1)
        contents[tab.id] = content
        LOG.debug("tab of session ${tab.id} added")
    }

    private fun update(content: Content, tab: SessionTab) {
        if (content.displayName != tab.label) content.displayName = tab.label
        if (content.description != tab.tooltip) content.description = tab.tooltip
        val icon = SessionTabIcon(tab.status, tab.attention)
        if (content.icon != icon) content.icon = icon
    }

    /** Selects [content] when it is not: added to the selection first, then the old one taken out, so the selection is never empty. */
    private fun select(content: Content) {
        if (contentManager.selectedContent === content && contentManager.selectedContents.size == 1) return
        val old = contentManager.selectedContents.filter { it !== content }
        if (!contentManager.isSelected(content)) contentManager.addSelectedContent(content)
        old.forEach { contentManager.removeFromSelection(it) }
        LOG.debug("tab of session ${content.getUserData(SESSION_TAB) ?: "none"} selected")
    }

    /** The placeholder content, added when missing: an empty name, not closeable (spec 18.2). */
    private fun ensurePlaceholder(): Content {
        placeholder?.let { return it }
        val content = ContentFactory.getInstance().createContent(panel, "", false).apply {
            setShouldDisposeContent(false)
            isCloseable = false
        }
        contentManager.addContent(content)
        placeholder = content
        return content
    }

    private inline fun withApplying(block: () -> Unit) {
        val before = applying
        applying = true
        try {
            block()
        } finally {
            applying = before
        }
    }
}
