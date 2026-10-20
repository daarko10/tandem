package dev.daarkolabs.tandem.chat

/**
 * A pending request the chat docks above the composer as a card (spec 4.12–4.14): the permission card, the question card or the
 * plan card. [agentDescription] is set when a subagent asks: its task's description.
 */
sealed interface PendingCard {
    val requestId: String
    val toolUseId: String?
    val agentDescription: String?
}
