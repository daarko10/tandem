package dev.daarkolabs.tandem.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.daarkolabs.tandem.chat.RowOptions
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.plus

/** The presenters and UI states of the [open] sessions, which go when their session closes or the tool window's content goes away. */
@Composable
internal fun rememberChatPresenters(open: List<SessionHandle>, options: StateFlow<RowOptions>): ChatPresenters {
    val scope = rememberCoroutineScope()
    val presenters = remember(scope, options) { ChatPresenters(scope, options) }
    LaunchedEffect(presenters, open) { presenters.retain(open) }
    return presenters
}

/**
 * One [ChatPresenter] and one [ChatUiState] per open session, by id (spec 5.6): the presenter is made the first time the session is
 * shown and runs in a job of its own in [scope], so that it stops alone when its session closes ([retain]); the UI state lives as
 * long, so a session switched away from and back keeps its open rows and scroll position. Used on the EDT only.
 */
internal class ChatPresenters(private val scope: CoroutineScope, private val options: StateFlow<RowOptions>) {
    private class Entry(val handle: SessionHandle, val job: Job, val presenter: ChatPresenter)

    private val entries = HashMap<SessionId, Entry>()
    private val uis = HashMap<SessionId, ChatUiState>()

    /** The presenter of [handle]'s chat; a session closed and opened again is a new handle, and gets a new presenter. */
    fun presenter(handle: SessionHandle): ChatPresenter {
        entries[handle.id]?.takeIf { it.handle === handle }?.let { return it.presenter }
        entries.remove(handle.id)?.job?.cancel()
        val job = SupervisorJob(scope.coroutineContext.job)
        val presenter = ChatPresenter(scope + job, handle.state, options)
        entries[handle.id] = Entry(handle, job, presenter)
        ui(handle.id).onExpand = presenter::expandBody
        return presenter
    }

    /** The UI state of session [id]'s chat, made on first use. */
    fun ui(id: SessionId): ChatUiState = uis.getOrPut(id) { ChatUiState() }

    /** Stops the presenters, and forgets the UI states, of the sessions that are not [open] any more. */
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
