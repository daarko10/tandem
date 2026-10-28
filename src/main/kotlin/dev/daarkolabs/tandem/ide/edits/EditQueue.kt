package dev.daarkolabs.tandem.ide.edits

import dev.daarkolabs.tandem.session.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * The project's edit proposals waiting for the user (spec 3.4): one diff at a time, the others in arrival order across the project's
 * sessions. The [open] one is the head; when its decision completes (from the diff, [resolve], [fail] or [cancel]) the next queued
 * one opens. A queued one whose decision completes leaves the queue. Proposals are told apart by identity.
 */
class EditQueue {
    private class Entry(val proposal: EditProposal, val decision: CompletableDeferred<Decision>)

    private val lock = Any()

    /** The open entry, then the queued ones in arrival order. Guarded by [lock]. */
    private val entries = ArrayList<Entry>()

    private val mutableOpen = MutableStateFlow<EditProposal?>(null)
    private val mutableQueued = MutableStateFlow<List<EditProposal>>(emptyList())

    /** The proposal whose diff is (to be) shown, null when none waits. */
    val open: StateFlow<EditProposal?> = mutableOpen.asStateFlow()

    /** The proposals waiting behind [open], in arrival order. */
    val queued: StateFlow<List<EditProposal>> = mutableQueued.asStateFlow()

    /** Adds [proposal] at the end; it opens at once when nothing else is open. Its decision completes when the user (or a cancel) decides. */
    fun enqueue(proposal: EditProposal): CompletableDeferred<Decision> {
        val decision = CompletableDeferred<Decision>()
        val entry = Entry(proposal, decision)
        synchronized(lock) {
            entries += entry
            publishLocked()
        }
        decision.invokeOnCompletion { remove(entry) }
        return decision
    }

    /** Completes [proposal]'s decision with [decision]; false when it is no longer in the queue (decided or cancelled already). */
    fun resolve(proposal: EditProposal, decision: Decision): Boolean = entryOf(proposal)?.decision?.complete(decision) ?: false

    /** Completes [proposal]'s decision with [error] (the diff could not be opened); false when it is no longer in the queue. */
    fun fail(proposal: EditProposal, error: Throwable): Boolean = entryOf(proposal)?.decision?.completeExceptionally(error) ?: false

    /**
     * The CLI withdrew the call [requestId] of [session] (`notifications/cancelled`): its proposal, open or queued, is decided
     * [Decision.Stopped]. False when no such proposal waits.
     */
    fun cancel(session: SessionId, requestId: JsonElement?): Boolean {
        val entry = synchronized(lock) {
            entries.firstOrNull { it.proposal.call.session == session && it.proposal.call.requestId == requestId }
        } ?: return false
        return entry.decision.complete(Decision.Stopped)
    }

    /** Every proposal of [session] (or of every session, when null) is decided [Decision.Stopped]; returns how many were. */
    fun cancelAll(session: SessionId?): Int {
        val matching = synchronized(lock) { entries.filter { session == null || it.proposal.call.session == session } }
        return matching.count { it.decision.complete(Decision.Stopped) }
    }

    /** [proposal]'s decision while it is in the queue, else null. */
    fun decisionOf(proposal: EditProposal): Deferred<Decision>? = entryOf(proposal)?.decision

    private fun entryOf(proposal: EditProposal): Entry? = synchronized(lock) { entries.firstOrNull { it.proposal === proposal } }

    private fun remove(entry: Entry) {
        synchronized(lock) {
            if (entries.remove(entry)) publishLocked()
        }
    }

    private fun publishLocked() {
        mutableOpen.value = entries.firstOrNull()?.proposal
        mutableQueued.value = entries.drop(1).map { it.proposal }
    }
}
