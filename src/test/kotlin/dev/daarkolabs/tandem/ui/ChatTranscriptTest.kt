package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTranscriptTest {
    @Test
    fun `permission transcript shows tool calls, results and replies in order`() {
        val state = Fixtures.decode("permission").fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }
        val transcript = state.toTranscript()
        assertEquals(
            listOf(ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT),
            transcript.map { it.role },
        )
        assertTrue(transcript[0].markdown.startsWith("**Bash** `touch tandem-fixture.txt`"), transcript[0].markdown)
        assertTrue(transcript[4].markdown.startsWith("**Failed:**"), transcript[4].markdown)
    }

    @Test
    fun `warnings and errors are marked`() {
        val state = reduce(ChatState(), SessionEvent.Ended("Claude Code exited with code 2.", isError = true))
        assertEquals("**Error:** Claude Code exited with code 2.", state.toTranscript().single().markdown)
    }
}
