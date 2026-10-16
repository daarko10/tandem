package dev.daarkolabs.tandem.session

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CliEnvironmentTest {
    @Test
    fun `drops variables that identify a parent session and keeps the rest`() {
        val parent = mapOf(
            "CLAUDECODE" to "1", "CLAUDE_PID" to "7", "CLAUDE_CODE_SESSION_ID" to "x", "CLAUDE_CODE_CHILD_SESSION" to "1",
            "CLAUDE_CODE_SESSION_ATTENDED" to "1", "CLAUDE_CODE_CONTAINER_ID" to "c", "CLAUDE_CODE_WORKER_EPOCH" to "3",
            "CLAUDE_SESSION_INGRESS_TOKEN_FILE" to "/t", "CLAUDE_CODE_REMOTE" to "1", "CLAUDE_CODE_REMOTE_SESSION_ID" to "r",
            "CLAUDE_CODE_MESSAGING_SOCKET" to "s", "CLAUDE_CODE_ENVIRONMENT_KIND" to "k", "NODE_OPTIONS" to "--inspect",
            "CLAUDE_CONFIG_DIR" to "/cfg", "CLAUDE_CODE_USE_BEDROCK" to "1", "PATH" to "/bin", "CLAUDE_CODE_ENTRYPOINT" to "cli",
        )
        assertEquals(
            mapOf("CLAUDE_CONFIG_DIR" to "/cfg", "CLAUDE_CODE_USE_BEDROCK" to "1", "PATH" to "/bin", "CLAUDE_CODE_ENTRYPOINT" to "sdk-tandem"),
            cliEnvironment(parent),
        )
    }
}
