package dev.daarkolabs.tandem.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.daarkolabs.tandem.session.SessionHandle
import dev.daarkolabs.tandem.session.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.plus

/** The presenters of the [open] sessions, which stop when their session closes or the tool window's content goes away. */
@Composable
internal fun rememberChatRowsPresenters(open: List<SessionHandle>): ChatRowsPresenters {
    val scope = rememberCoroutineScope()
    val presenters = remember(scope) { ChatRowsPresenters(scope) }
    LaunchedEffect(presenters, open) { presenters.retain(open) }
    return presenters
}

/**
 * One [ChatRowsPresenter] per open session, by id, made the first time the session is shown. Each runs in a job of its own in
 * [scope], so that it stops alone when its session closes ([retain]). Used on the EDT only.
 */
internal class ChatRowsPresenters(private val scope: CoroutineScope) {
    private class Entry(val handle: SessionHandle, val job: Job, val rows: StateFlow<List<ChatRow>>)

    private val entries = HashMap<SessionId, Entry>()

    /** The rows of [handle]'s chat; a session closed and opened again is a new handle, and gets a new presenter. */
    fun rows(handle: SessionHandle): StateFlow<List<ChatRow>> {
        entries[handle.id]?.takeIf { it.handle === handle }?.let { return it.rows }
        entries.remove(handle.id)?.job?.cancel()
        val job = SupervisorJob(scope.coroutineContext.job)
        val presenter = ChatRowsPresenter(scope + job, handle.state.map { it.toTranscript() })
        entries[handle.id] = Entry(handle, job, presenter.rows)
        return presenter.rows
    }

    /** Stops the presenters of the sessions that are not [open] any more. */
    fun retain(open: List<SessionHandle>) {
        val current = open.associateBy { it.id }
        val gone = entries.filterValues { current[it.handle.id] !== it.handle }
        gone.forEach { (id, entry) ->
            entry.job.cancel()
            entries.remove(id)
        }
    }
}
