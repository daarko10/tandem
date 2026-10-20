package dev.daarkolabs.tandem.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What the host answers a `can_use_tool` request with ([ClaudeSession.answer]). */
sealed interface PermissionAnswer {
    /** Lets the tool run with [updatedInput]; [updatedPermissions] go to the CLI's permission state (`addRules`, `setMode`). */
    data class Allow(val updatedInput: JsonObject, val updatedPermissions: List<JsonObject> = emptyList()) : PermissionAnswer

    /** Refuses the call; the CLI hands [message] to the model as the tool's result. */
    data class Deny(val message: String) : PermissionAnswer
}

/** The answers the chat's permission, question and plan cards send. Pure: the frames themselves are `HostMessages.allow/deny`. */
object PermissionAnswers {
    const val DENIED: String = "The user denied this request."
    const val CANCELLED: String = "cancelled"

    /** The CLI's own label for this choice, sent when the user gave no feedback. */
    const val KEEP_PLANNING: String = "No, keep planning"

    private val START_CODING: JsonObject = buildJsonObject {
        put("type", "setMode")
        put("mode", "acceptEdits")
        put("destination", "session")
    }

    fun allowOnce(p: PendingPermission): PermissionAnswer.Allow = PermissionAnswer.Allow(p.input)

    /** Allows [p] and the same again for this session: its `addRules` suggestions only ([sessionPermissionUpdates]). */
    fun allowForSession(p: PendingPermission): PermissionAnswer.Allow = PermissionAnswer.Allow(p.input, sessionPermissionUpdates(p.suggestions))

    fun deny(): PermissionAnswer.Deny = PermissionAnswer.Deny(DENIED)

    /**
     * Answers an `AskUserQuestion` request: its `questions` exactly as received, plus [answers] (question text to the picked label;
     * several labels of a multi-select joined with ", ", or the text typed for "Other").
     */
    fun questionAnswers(p: PendingPermission, answers: Map<String, String>): PermissionAnswer.Allow =
        PermissionAnswer.Allow(
            buildJsonObject {
                p.input["questions"]?.let { put("questions", it) }
                putJsonObject("answers") { answers.forEach { (question, answer) -> put(question, answer) } }
            },
        )

    fun cancelQuestion(): PermissionAnswer.Deny = PermissionAnswer.Deny(CANCELLED)

    /** Approves an `ExitPlanMode` request; [startCoding] also switches the session to `acceptEdits`. */
    fun approvePlan(p: PendingPermission, startCoding: Boolean): PermissionAnswer.Allow =
        PermissionAnswer.Allow(p.input, if (startCoding) listOf(START_CODING) else emptyList())

    /** Rejects a plan: the user's [feedback] is the message, or the CLI's own label when there is none. */
    fun keepPlanning(feedback: String): PermissionAnswer.Deny = PermissionAnswer.Deny(feedback.trim().ifBlank { KEEP_PLANNING })
}
