package dev.daarkolabs.tandem.ide

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.openProjectAsync
import com.intellij.testFramework.useProjectAsync
import dev.daarkolabs.tandem.chat.Cards
import dev.daarkolabs.tandem.chat.TextRow
import dev.daarkolabs.tandem.chat.ThinkingRow
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.NewSessionOptions
import dev.daarkolabs.tandem.session.OwnFakeCliProcesses
import dev.daarkolabs.tandem.session.PermissionAnswers
import dev.daarkolabs.tandem.session.SessionStatus
import dev.daarkolabs.tandem.session.transcript.TranscriptFolder
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import dev.daarkolabs.tandem.ui.ChatPresenter
import dev.daarkolabs.tandem.ui.RenderedChat
import dev.daarkolabs.tandem.ui.cards.picksOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [TandemSessions] inside a real (headless) project, against `src/test/resources/fake-claude.py`, which the test task passes as
 * `tandem.claudePath`. Transcripts come from a config directory of the test's own (the `tandem.configDir` test hook), never the
 * user's.
 */
@TestApplication
class TandemSessionsTest {
    companion object {
        private val tempDir = tempPathFixture()

        /** A session with a recorded transcript (`history-reply`, whose own title is "Pong"). */
        private const val SEEDED = "66666666-6666-4666-8666-666666666666"

        /** A session the workspace remembers, whose transcript is gone. */
        private const val GONE = "77777777-7777-4777-8777-777777777777"

        /** Another session with a recorded transcript. */
        private const val OTHER = "88888888-8888-4888-8888-888888888888"

        /** And another. */
        private const val THIRD = "99999999-9999-4999-8999-999999999999"

        /** A session with a transcript that fails to open in a way nothing expects. */
        private const val BROKEN = "55555555-5555-4555-8555-555555555555"
    }

    private val ownProcesses = OwnFakeCliProcesses()

    @JvmField
    @RegisterExtension
    val ownConfigDir: OwnConfigDir = OwnConfigDir()

    private val configDir: Path get() = ownConfigDir.path

    private fun ownFakeProcesses(): List<ProcessHandle> = ownProcesses.list()

    /** Closing the project must end every process; the timeout of the enclosing test bounds the wait. */
    private suspend fun awaitNoOwnFakeCli() {
        while (ownFakeProcesses().isNotEmpty()) delay(20)
    }

    /** Writes the `history-reply` transcript as session [id]'s, in the CLI's folder for the project in [dir]. */
    private fun seed(dir: Path, id: String) {
        // The CLI keys the folder on its physical working directory.
        val folder = configDir.resolve("projects").resolve(TranscriptFolder.key(dir.toRealPath().toString())).createDirectories()
        Fixtures.transcript("history-reply").copyTo(folder.resolve("$id.jsonl"))
    }

    private fun ChatState.prompts(): List<String> = entries.filterIsInstance<ChatEntry.User>().map { it.text }

    private fun ChatState.replyText(): String =
        entries.filterIsInstance<ChatEntry.Assistant>().last().blocks.filterIsInstance<AssistantBlock.Text>().joinToString("") { it.text }

    @Test
    fun `a created session streams the reply into its state`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("reply").createDirectories()
        val (state, remembered) = openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            val manager = sessions.manager
            val handle = manager.create(NewSessionOptions())
            manager.select(handle.id)
            manager.send(handle.id, "Reply with the single word: pong")
            val state = handle.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant && !it.streaming } }
            // The workspace follows the open sessions and the selection, so that the next IDE run opens them again.
            val workspace = TandemWorkspaceState.getInstance(project).state
            while (workspace.openSessionIds != listOf(handle.id) || workspace.selectedSessionId != handle.id) delay(20)
            state to handle.id
        }
        assertEquals(listOf("Reply with the single word: pong"), state.prompts())
        assertEquals("pong", state.replyText())
        assertEquals("claude-sonnet-5-5", state.activeModel)
        assertEquals(remembered, state.sessionId)
        awaitNoOwnFakeCli()
    }

    @Test
    fun aQuestionAnsweredFromTheServiceReachesTheFake() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("question").createDirectories()
        val log = tempDir.get().resolve("question-sent.ndjson")
        openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            sessions.extraEnvironment = mapOf("TANDEM_FAKE_FIXTURE" to "ask-single", "TANDEM_FAKE_LOG" to log.toString())
            sessions.ensureStarted()
            val handle = sessions.manager.create(NewSessionOptions())
            sessions.manager.send(handle.id, "Ask me a colour")
            val p = handle.state.first { it.pendingPermissions.isNotEmpty() }.pendingPermissions.single()
            val card = Cards.question(p)!!
            val answers = Cards.answers(card, picksOf(card, mapOf(0 to setOf(1)), emptyMap())!!)
            sessions.manager.answer(handle.id, p.requestId, PermissionAnswers.questionAnswers(p, answers))
            handle.state.first { !it.busy }
        }
        // As the other tests of the class: no fake CLI outlives the project.
        awaitNoOwnFakeCli()
        // As SessionManagerTest.frames: the last piece is empty, or a line the fake is still writing.
        val frames = log.readText().split('\n').dropLast(1).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        val answer = frames.single { it["type"]?.jsonPrimitive?.content == "control_response" }["response"]!!.jsonObject
        assertEquals("<request_id-27>", answer["request_id"]!!.jsonPrimitive.content)
        val response = answer["response"]!!.jsonObject
        assertEquals("allow", response["behavior"]!!.jsonPrimitive.content)
        assertEquals("<id-12>", response["toolUseID"]!!.jsonPrimitive.content)
        assertEquals("Blue", response["updatedInput"]!!.jsonObject["answers"]!!.jsonObject["Which colour?"]!!.jsonPrimitive.content)
    }

    @Test
    @OptIn(ExperimentalJewelApi::class) // the presenter's rows carry Jewel's Markdown blocks
    fun aPresenterRebuildsWhenShowThinkingChanges() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("thinking").createDirectories()
        openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            // A turn that thinks with summaries, then answers.
            sessions.extraEnvironment = mapOf("TANDEM_FAKE_FIXTURE" to "think-display-summarized")
            sessions.ensureStarted()
            val handle = sessions.manager.create(NewSessionOptions())
            sessions.manager.send(handle.id, "What is 47 times 83 minus 19 squared?")
            handle.state.first { s -> !s.busy && s.entries.any { it is ChatEntry.Assistant && !it.streaming } }
            val presenterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val presenter = ChatPresenter(presenterScope, handle.state, sessions.rowOptions)
                // Never chat.value, which can precede the first emission.
                val shown = presenter.chat.first { it.rows.any { r -> r.row is ThinkingRow } }
                TandemSettings.getInstance().state.showThinking = false
                sessions.settingsChanged()
                val hidden = presenter.chat.first { it.rows.none { r -> r.row is ThinkingRow } }
                fun keys(chat: RenderedChat): List<String> =
                    chat.rows.map { it.row }.filter { it is ToolRow || it is TextRow }.map { it.key }
                assertTrue(keys(shown).isNotEmpty(), keys(shown).toString())
                assertEquals(keys(shown), keys(hidden), "the other rows keep their keys")
            } finally {
                // The settings are the application's: no other test may see showThinking = false.
                TandemSettings.getInstance().loadState(TandemSettings.State())
                sessions.settingsChanged()
                presenterScope.cancel()
            }
        }
        awaitNoOwnFakeCli()
    }

    @Test
    fun `the models a session's CLI lists are remembered for the next IDE run`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("models").createDirectories()
        openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            // A recording whose `initialize` response lists the models.
            sessions.extraEnvironment = mapOf("TANDEM_FAKE_FIXTURE" to "permission-mode")
            sessions.ensureStarted()
            val handle = sessions.manager.create(NewSessionOptions())
            val models = handle.state.first { it.models.isNotEmpty() }.models
            // The new-session popup offers them before any session of the next run has answered `initialize`.
            val workspace = TandemWorkspaceState.getInstance(project)
            while (workspace.knownModels() != models) delay(20)
        }
        awaitNoOwnFakeCli()
    }

    @Test
    fun `a session's CLI starts from the login shell's environment`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("shell-env").createDirectories()
        val log = configDir.resolve("fake-cli.log")
        // The fake logs the CLAUDE* variables it was given. The IDE's own environment has neither of these.
        ShellEnvironment.source = { System.getenv() + mapOf("CLAUDE_TANDEM_SHELL_PROBE" to "from-shell", "CLAUDECODE" to "1") }
        try {
            openProjectAsync(dir).useProjectAsync { project ->
                val sessions = TandemSessions.get(project)
                sessions.extraEnvironment = mapOf("TANDEM_FAKE_LOG" to log.toString())
                sessions.ensureStarted()
                sessions.manager.create(NewSessionOptions())
                while (!(log.exists() && log.readLines().any { "__spawn__" in it })) delay(20)
            }
        } finally {
            ShellEnvironment.reset()
        }
        val spawn = log.readLines().first { "__spawn__" in it }
        assertTrue("\"CLAUDE_TANDEM_SHELL_PROBE\":\"from-shell\"" in spawn, spawn)
        assertFalse("\"CLAUDECODE\"" in spawn, "the parent-session variables are still dropped: $spawn")
        awaitNoOwnFakeCli()
    }

    @Test
    fun `a claude whose interpreter is beside it, outside PATH, starts`() = timeoutRunBlocking(30.seconds) {
        // As npm, Volta or Bun install it: `#!/usr/bin/env node`, with node in the same directory, which PATH does not list.
        val fake = System.getProperty("tandem.claudePath")
        val bin = tempDir.get().resolve("install-location").createDirectories()
        bin.resolve("tandem-test-node").writeText("#!/bin/sh\nshift\nexec python3 '$fake' \"\$@\"\n")
        bin.resolve("claude").writeText("#!/usr/bin/env tandem-test-node\n")
        listOf("tandem-test-node", "claude").forEach { bin.resolve(it).toFile().setExecutable(true) }
        val dir = tempDir.get().resolve("interpreter-beside").createDirectories()
        System.setProperty("tandem.claudePath", bin.resolve("claude").toString())
        try {
            val state = openProjectAsync(dir).useProjectAsync { project ->
                val sessions = TandemSessions.get(project)
                sessions.ensureStarted()
                val handle = sessions.manager.create(NewSessionOptions())
                sessions.manager.send(handle.id, "Reply with the single word: pong")
                handle.state.first { s -> s.ended || s.entries.any { it is ChatEntry.Assistant && !it.streaming } }
            }
            assertTrue(state.entries.any { it is ChatEntry.Assistant }, "no reply: ${state.entries}")
            assertEquals("pong", state.replyText())
        } finally {
            System.setProperty("tandem.claudePath", fake)
        }
        awaitNoOwnFakeCli()
    }

    @Test
    fun `closing the project stops every live session`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("close").createDirectories()
        val started = openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            repeat(2) { sessions.manager.create(NewSessionOptions()) }
            var mine = emptyList<ProcessHandle>()
            while (mine.size < 2) {
                mine = ownFakeProcesses()
                delay(20)
            }
            mine
        }
        // useProjectAsync has closed the project, which cancels the service's scope: that must end both processes.
        while (started.any { it.isAlive }) delay(20)
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `open sessions are restored from workspace state without spawning`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("restore").createDirectories()
        seed(dir, SEEDED)
        val log = configDir.resolve("fake-cli.log")
        val (restored, processes) = openProjectAsync(dir).useProjectAsync { project ->
            TandemWorkspaceState.getInstance(project).state.apply {
                openSessionIds = mutableListOf(SEEDED)
                selectedSessionId = SEEDED
            }
            val sessions = TandemSessions.get(project)
            sessions.extraEnvironment = mapOf("TANDEM_FAKE_LOG" to log.toString())
            sessions.ensureStarted()
            sessions.manager.selected.first { it == SEEDED } // the last step of restoring
            delay(500) // time for a process that must not start to show up
            sessions.manager.sessions.value.single() to ownFakeProcesses()
        }
        assertEquals(SEEDED, restored.id)
        assertEquals(SessionStatus.STOPPED, restored.status.value)
        assertEquals(listOf("Reply with the single word: pong"), restored.state.value.prompts())
        assertEquals("pong", restored.state.value.replyText())
        assertEquals(emptyList(), processes.map { it.pid() })
        assertFalse(log.exists() && "__spawn__" in log.readText(), "no process was started")
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `a project opened through a link finds the transcripts the CLI keys on the physical directory`() = timeoutRunBlocking(30.seconds) {
        // As /tmp/x is /private/tmp/x on macOS: the CLI runs in the link, and keys its folder on the directory the link resolves to.
        val real = tempDir.get().resolve("linked-real").createDirectories()
        val link = Files.createSymbolicLink(tempDir.get().resolve("linked-link"), real)
        seed(real, SEEDED)
        val restored = openProjectAsync(link).useProjectAsync { project ->
            TandemWorkspaceState.getInstance(project).state.apply {
                openSessionIds = mutableListOf(SEEDED)
                selectedSessionId = SEEDED
            }
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            sessions.manager.selected.first { it == SEEDED }
            sessions.manager.sessions.value.map { it.id }
        }
        assertEquals(listOf(SEEDED), restored)
    }

    @Test
    fun `transcripts are looked for in the CLAUDE_CONFIG_DIR of the login shell`() = timeoutRunBlocking(30.seconds) {
        // Exported only in the shell profile: the CLI, which gets the shell's environment, writes there, and the IDE's own
        // environment does not have it.
        val dir = tempDir.get().resolve("shell-config").createDirectories()
        seed(dir, SEEDED) // into configDir, which only the shell names from here on
        System.clearProperty("tandem.configDir") // deliberately, inside the test: with the hook set, the shell would not be asked
        ShellEnvironment.source = { System.getenv() + ("CLAUDE_CONFIG_DIR" to configDir.toString()) }
        try {
            val restored = openProjectAsync(dir).useProjectAsync { project ->
                TandemWorkspaceState.getInstance(project).state.apply {
                    openSessionIds = mutableListOf(SEEDED)
                    selectedSessionId = SEEDED
                }
                val sessions = TandemSessions.get(project)
                sessions.ensureStarted()
                withTimeoutOrNull(10.seconds) { sessions.manager.selected.first { it == SEEDED } }
                sessions.manager.sessions.value.map { it.id }
            }
            assertEquals(listOf(SEEDED), restored)
        } finally {
            ShellEnvironment.reset()
        }
    }

    @Test
    fun `a remembered session whose transcript is gone is left out`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("gone").createDirectories()
        seed(dir, SEEDED)
        val (open, selected) = openProjectAsync(dir).useProjectAsync { project ->
            val workspace = TandemWorkspaceState.getInstance(project).state
            workspace.openSessionIds = mutableListOf(GONE, SEEDED)
            workspace.selectedSessionId = GONE
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            // Restoring selects one session, once; the workspace forgets the one that is gone.
            val selected = sessions.manager.selected.first { it != null }
            while (workspace.openSessionIds != listOf(SEEDED)) delay(20)
            sessions.manager.sessions.value.map { it.id } to listOf(selected, workspace.selectedSessionId)
        }
        assertEquals(listOf(SEEDED), open)
        // The selected session is gone, so the first one restored is selected in its place, and remembered.
        assertEquals(listOf(SEEDED, SEEDED), selected)
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `the selected session is restored first, then the others in their saved order`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("order").createDirectories()
        seed(dir, SEEDED)
        seed(dir, OTHER)
        seed(dir, THIRD)
        val (open, selected) = openProjectAsync(dir).useProjectAsync { project ->
            TandemWorkspaceState.getInstance(project).state.apply {
                openSessionIds = mutableListOf(THIRD, SEEDED, OTHER) // not in the order of their ids
                selectedSessionId = SEEDED // the second one: it shows before the others' transcripts are read
            }
            val sessions = TandemSessions.get(project)
            sessions.ensureStarted()
            val first = sessions.manager.selected.first { it != null } // restoring selects one session, once
            val openWhenSelected = sessions.manager.sessions.value.map { it.id }
            while (sessions.manager.sessions.value.size < 3) delay(20) // the others follow
            sessions.manager.sessions.value.map { it.id } to listOf(first, openWhenSelected.first(), sessions.manager.selected.value)
        }
        assertEquals(listOf(SEEDED, THIRD, OTHER), open)
        // Selected as soon as it was opened, ahead of the others, and still selected once they are open.
        assertEquals(listOf(SEEDED, SEEDED, SEEDED), selected)
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `a session that fails to open is left out and the others are restored`() = timeoutRunBlocking(30.seconds) {
        val dir = tempDir.get().resolve("broken").createDirectories()
        seed(dir, BROKEN)
        seed(dir, SEEDED)
        seed(dir, OTHER)
        val (open, remembered) = openProjectAsync(dir).useProjectAsync { project ->
            val workspace = TandemWorkspaceState.getInstance(project).state
            workspace.openSessionIds = mutableListOf(BROKEN, SEEDED, OTHER)
            workspace.selectedSessionId = OTHER
            val sessions = TandemSessions.get(project)
            // Not a transcript that cannot be read, which opens with a warning in its chat (SessionManagerTest), but a failure
            // nothing expects, such as a bug in the reader.
            sessions.readTranscript = { path -> if (path.name.startsWith(BROKEN)) error("a bug") else TranscriptReader.read(path) }
            sessions.ensureStarted()
            sessions.manager.selected.first { it == OTHER }
            // The workspace follows the open sessions from then on, so it forgets the one left out.
            while (workspace.openSessionIds != listOf(OTHER, SEEDED)) delay(20)
            sessions.manager.sessions.value.map { it.id } to workspace.selectedSessionId
        }
        assertEquals(listOf(OTHER, SEEDED), open)
        assertEquals(OTHER, remembered)
        assertEquals(emptyList(), ownFakeProcesses().map { it.pid() })
    }

    @Test
    fun `the shutdown hook stays until every session's process has ended`() = timeoutRunBlocking(60.seconds) {
        val dir = tempDir.get().resolve("hook").createDirectories()
        val log = configDir.resolve("fake-cli.log")
        val (sessions, process) = openProjectAsync(dir).useProjectAsync { project ->
            val sessions = TandemSessions.get(project)
            // The fake sleeps this long before its permission request and reads nothing meanwhile, so once its input is closed,
            // ending it takes about 2 s: ClaudeSession waits that long before it terminates the process.
            sessions.extraEnvironment = mapOf(
                "TANDEM_FAKE_LOG" to log.toString(),
                "TANDEM_FAKE_FIXTURES" to "permission",
                "TANDEM_FAKE_DELAY_MS" to "30000",
            )
            sessions.ensureStarted()
            assertTrue(sessions.shutdownHookRegistered)
            val handle = sessions.manager.create(NewSessionOptions())
            sessions.manager.send(handle.id, "Create the fixture file")
            while (!(log.exists() && log.readLines().any { "\"type\":\"user\"" in it })) delay(20) // it has the prompt, and sleeps
            sessions to ownFakeProcesses().single()
        }
        // The project is closed and the service's scope cancelled, but the platform does not wait for the process to end.
        assertTrue(process.isAlive, "the process is still being ended")
        assertTrue(sessions.shutdownHookRegistered, "the hook must stay while a process is still being ended")
        while (process.isAlive) delay(20)
        // Once the last process has ended, the scope completes, and the hook goes.
        while (sessions.shutdownHookRegistered) delay(20)
    }
}
