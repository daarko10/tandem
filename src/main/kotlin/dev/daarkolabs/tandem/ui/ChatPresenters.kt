package dev.daarkolabs.tandem.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.protocol.ImageBlock
import dev.daarkolabs.tandem.session.NoticeLevel
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionId
import dev.daarkolabs.tandem.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.plus

/**
 * The presenters, senders and UI states of the [open] sessions, which go when their session closes or the tool window's content goes
 * away. Prompts go to [manager].
 */
@Composable
internal fun rememberChatPresenters(manager: SessionManager, open: List<SessionHandle>, options: StateFlow<RowOptions>): ChatPresenters {
    val scope = rememberCoroutineScope()
    val presenters = remember(scope, manager, options) { ChatPresenters(scope, options, manager::send) }
    LaunchedEffect(presenters, open) { presenters.retain(open) }
    return presenters
}

/**
 * One [ChatPresenter], one [ComposerSender] and one [ChatUiState] per open session, by id (spec 5.6, polish spec 3.4): the presenter
 * is made the first time the session is shown and runs in a job of its own in [scope], so that it stops alone when its session closes
 * ([retain]); the sender, which hands prompts to [send], runs in the same job and stops with it; the UI state lives as long, so a
 * session switched away from and back keeps its open rows and scroll position. Used on the EDT only.
 */
internal class ChatPresenters(
    private val scope: CoroutineScope,
    private val options: StateFlow<RowOptions>,
    private val send: (SessionId, String, List<ImageBlock>) -> Unit = { _, _, _ -> },
) {
    private inner class Entry(val handle: SessionHandle, val job: Job, val presenter: ChatPresenter) {
        /** Made on the first prompt: a session never typed in has none. */
        val sender: ComposerSender by lazy(LazyThreadSafetyMode.NONE) {
            ComposerSender(
                scope + job,
                send = { text, images -> send(handle.id, text, images) },
                dropped = { handle.notice(NoticeLevel.WARNING, it) },
            )
        }
    }

    private val entries = HashMap<SessionId, Entry>()
    private val uis = HashMap<SessionId, ChatUiState>()

    /** The presenter of [handle]'s chat; a session closed and opened again is a new handle, and gets a new presenter. */
    fun presenter(handle: SessionHandle): ChatPresenter = entry(handle).presenter

    /** The sender of [handle]'s prompts, which stops with the session's presenter ([retain]). */
    fun sender(handle: SessionHandle): ComposerSender = entry(handle).sender

    private fun entry(handle: SessionHandle): Entry {
        entries[handle.id]?.takeIf { it.handle === handle }?.let { return it }
        entries.remove(handle.id)?.job?.cancel()
        val job = SupervisorJob(scope.coroutineContext.job)
        val presenter = ChatPresenter(scope + job, handle.state, options)
        val entry = Entry(handle, job, presenter)
        entries[handle.id] = entry
        ui(handle.id).onExpand = presenter::expandBody
        return entry
    }

    /** The UI state of session [id]'s chat, made on first use. */
    fun ui(id: SessionId): ChatUiState = uis.getOrPut(id) { ChatUiState() }

    /** Stops the presenters and senders, and forgets the UI states, of the sessions that are not [open] any more. */
    fun retain(open: List<SessionHandle>) {
        val current = open.associateBy { it.id }
        val gone = entries.filterValues { current[it.handle.id] !== it.handle }
        gone.forEach { (id, entry) ->
            entry.job.cancel()
            entries.remove(id)
        }
        uis.keys.retainAll(current.keys)
    }
}
