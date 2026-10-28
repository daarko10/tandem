package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry

/** The state machine of spec 4.6.3, as a function of what the state holds for one call. */
object ToolStates {
    /** What the CLI answers for a denied call, which a transcript of an older CLI records without its permission decision. */
    private val denialTexts: Set<String> = setOf("The user denied this command.", "cancelled", "The user denied this request.")

    /**
     * The state of [block] with its [result] (if any). [pending]: a permission request for it is open. [turnOpen]: the turn it belongs
     * to still runs (a turn is open, and the call lies after the last user message). A result decides; else an open request; else a
     * call of a turn that ended was interrupted, also one whose input never completed; else it runs, or still streams its input.
     */
    fun of(block: AssistantBlock.ToolUse, result: ChatEntry.ToolResult?, pending: Boolean, turnOpen: Boolean): ToolState = when {
        result != null -> when {
            !result.isError -> ToolState.DONE
            // The CLI's synthetic result after Stop carries no decision, only this kind (spec 3.4): not a failure.
            result.nonExecutionKind == USER_REJECTED -> ToolState.INTERRUPTED
            // A Reject in Tandem's diff: the CLI records the allow Tandem gave, so the text decides, before the decision.
            isEditRejection(result) -> ToolState.DENIED
            isDenial(result) -> ToolState.DENIED
            else -> ToolState.FAILED
        }
        pending -> ToolState.WAITING
        !turnOpen -> ToolState.INTERRUPTED
        block.input == null -> ToolState.PENDING
        else -> ToolState.RUNNING
    }

    /** The `non_execution_kind` of a call the user stopped before it ran. */
    private const val USER_REJECTED = "user-rejected"

    /** The start of the result Tandem answers when the user rejects an edit in its diff (spec 4.3). */
    internal const val EDIT_REJECTED_PREFIX: String = "The user rejected this edit"

    /** Whether an error [result] is Tandem's own rejection of an edit (spec 3.4), with or without the `<tool_use_error>` tags. */
    private fun isEditRejection(result: ChatEntry.ToolResult): Boolean =
        ToolBodies.withoutErrorTags(result.text).trim().removePrefix("Error: ").startsWith(EDIT_REJECTED_PREFIX)

    /** Whether an error [result] is the user's denial: a reject decision, or without one the CLI's denial text. */
    internal fun isDenial(result: ChatEntry.ToolResult): Boolean {
        val decision = result.decision
        return if (decision != null) decision.decision == "reject" else result.text.trim() in denialTexts
    }
}
