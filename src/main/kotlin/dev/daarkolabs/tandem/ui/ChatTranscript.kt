package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NoticeLevel
import kotlinx.serialization.json.JsonPrimitive

/** Projects [ChatState] into Markdown transcript entries. Pure; runs off the EDT inside [ChatRowsPresenter]'s flow. */
internal fun ChatState.toTranscript(): List<TranscriptEntry> = entries.mapNotNull { entry ->
    when (entry) {
        is ChatEntry.User -> TranscriptEntry(entry.id, ChatRole.USER, entry.text, streaming = false)
        is ChatEntry.Assistant -> {
            val markdown = entry.blocks.mapNotNull(::blockMarkdown).joinToString("\n\n")
            if (markdown.isEmpty() && !entry.streaming) null else TranscriptEntry(entry.id, ChatRole.ASSISTANT, markdown, entry.streaming)
        }
        is ChatEntry.ToolResult -> TranscriptEntry(
            entry.id,
            ChatRole.TOOL,
            (if (entry.isError) "**Failed:** " else "") + fence(entry.text.lines().take(MAX_RESULT_LINES).joinToString("\n")),
            streaming = false,
        )
        is ChatEntry.Notice -> TranscriptEntry(
            entry.id,
            ChatRole.NOTICE,
            when (entry.level) {
                NoticeLevel.INFO -> entry.text
                NoticeLevel.WARNING -> "⚠ ${entry.text}"
                NoticeLevel.ERROR -> "**Error:** ${entry.text}"
            },
            streaming = false,
        )
        is ChatEntry.TurnEnded -> null
    }
}

private const val MAX_RESULT_LINES = 12

private fun blockMarkdown(block: AssistantBlock): String? = when (block) {
    is AssistantBlock.Text -> block.text.ifEmpty { null }
    is AssistantBlock.Thinking -> null
    is AssistantBlock.ToolUse -> {
        val command = (block.input?.get("command") as? JsonPrimitive)?.content
        val path = (block.input?.get("file_path") as? JsonPrimitive)?.content
        "**${block.name}** " + listOfNotNull(command, path).joinToString(" ") { "`${it.replace("`", "'")}`" }
    }
    is AssistantBlock.Other -> null
}

private fun fence(text: String): String = "```\n${text.replace("```", "'''")}\n```"
