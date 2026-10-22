package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.ModelOption
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a sent slash command does (spec 3.4's interception table), on the trimmed text, palette or not. */
class SlashInterceptTest {
    private val models: List<ModelOption> = listOf(
        ModelOption("default", "Default (recommended)", null),
        ModelOption("opus", "Opus", null),
        ModelOption("sonnet", "Sonnet 5.5", null),
    )

    @Test
    fun `the interception table`() {
        assertEquals(SlashIntercept.OpenMcp, slashIntercept("/mcp", models))
        assertEquals(SlashIntercept.OpenMcp, slashIntercept("  /mcp \n", models))
        assertEquals(SlashIntercept.Send("/mcp reconnect x"), slashIntercept("/mcp reconnect x", models))
        assertEquals(SlashIntercept.PickModel, slashIntercept("/model", models))
        assertEquals(SlashIntercept.PickModel, slashIntercept(" /model ", models))
        assertEquals(SlashIntercept.SetModel("opus"), slashIntercept("/model opus", models))
        assertEquals(SlashIntercept.SetModel("default"), slashIntercept("/model default", models))
        assertEquals(SlashIntercept.Send("/model nosuch"), slashIntercept("/model nosuch", models))
        assertEquals(SlashIntercept.Send("/model opus"), slashIntercept("/model opus", emptyList()))
        assertEquals(SlashIntercept.PickPermissionMode, slashIntercept("/permissions", models))
        assertEquals(SlashIntercept.PickPermissionMode, slashIntercept("/mode", models))
        assertEquals(SlashIntercept.Send("/mode plan"), slashIntercept("/mode plan", models))
        assertEquals(SlashIntercept.Send("/permissions x"), slashIntercept("/permissions x", models))
        // A project command, another built-in, an unknown command and plain text are sent as typed.
        for (text in listOf("/hello", "/help", "/usage", "/context", "/nosuch", "/models", "/mcpx", "hello /mcp", "")) {
            assertEquals(SlashIntercept.Send(text), slashIntercept(text, models), text)
        }
    }

    @Test
    fun `a draft Tandem intercepts is one the table does not send`() {
        for (draft in listOf("/mcp", " /model ", "/model opus", "/permissions", "/mode\n")) assertTrue(intercepts(draft, models), draft)
        for (draft in listOf("/model nosuch", "/mcp x", "/hello", "! /mcp", "hello", "")) assertFalse(intercepts(draft, models), draft)
    }

    @Test
    fun `a session that has not listed its models intercepts a CLI alias`() {
        // Restored with no process: the composer offers the CLI's aliases, so `/model opus` picks as the footer's menu would.
        val composer = composerModel(ChatState())
        assertEquals(SlashIntercept.SetModel("opus"), slashIntercept("/model opus", composer.models))
        assertTrue(intercepts("/model opus", composer.models))
        assertEquals(SlashIntercept.Send("/model nosuch"), slashIntercept("/model nosuch", composer.models))
    }

    @Test
    fun `model Opus is case-insensitive`() {
        assertEquals(SlashIntercept.SetModel("opus"), slashIntercept("/model Opus", models))
        assertEquals(SlashIntercept.SetModel("opus"), slashIntercept("/model OPUS", models))
        assertEquals(SlashIntercept.SetModel("sonnet"), slashIntercept("/model sonnet 5.5", models))
    }

    @Test
    fun `mcp with arguments is sent`() {
        assertEquals(SlashIntercept.Send("/mcp x"), slashIntercept("/mcp x", models))
        assertEquals(SlashIntercept.Send("/mcp disable all"), slashIntercept("/mcp disable all", models))
    }

    @Test
    fun `a display name with a space matches as a whole`() {
        val sonnet = listOf(ModelOption("sonnet", "Sonnet 5.5", null))
        assertEquals(SlashIntercept.SetModel("sonnet"), slashIntercept("/model Sonnet 5.5", sonnet))
        assertEquals(SlashIntercept.SetModel("sonnet"), slashIntercept("/model   Sonnet 5.5  ", sonnet))
        assertEquals(SlashIntercept.Send("/model Sonnet 5"), slashIntercept("/model Sonnet 5", sonnet))
        assertEquals(SlashIntercept.Send("/model Sonnet 5.5 please"), slashIntercept("/model Sonnet 5.5 please", sonnet))
    }
}
