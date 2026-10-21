package dev.daarkolabs.tandem.session

/** Variables that name the session or hosted environment Tandem itself runs in. A CLI that inherits them acts on that session. */
private val PARENT_SESSION_VARIABLES: Set<String> = setOf(
    "CLAUDECODE",
    "CLAUDE_PID",
    "CLAUDE_CODE_SESSION_ID",
    "CLAUDE_CODE_CHILD_SESSION",
    "CLAUDE_CODE_SESSION_ATTENDED",
    "CLAUDE_CODE_CONTAINER_ID",
    "CLAUDE_CODE_WORKER_EPOCH",
    "CLAUDE_SESSION_INGRESS_TOKEN_FILE",
)

private val PARENT_SESSION_PREFIXES: List<String> = listOf("CLAUDE_CODE_REMOTE", "CLAUDE_CODE_MESSAGING_", "CLAUDE_CODE_ENVIRONMENT_")

/**
 * The environment for a spawned `claude`: [parent] without the variables that identify a parent session or hosted
 * environment and without `NODE_OPTIONS`, and with `CLAUDE_CODE_ENTRYPOINT` set to Tandem's own value.
 * Everything else passes through unchanged, in particular `CLAUDE_CONFIG_DIR` and provider settings such as Bedrock or Vertex.
 */
fun cliEnvironment(parent: Map<String, String>): Map<String, String> {
    val kept = parent.filterKeys { name ->
        // NODE_OPTIONS goes too, as in the SDK (docs/protocol.md 1.2): flags meant for another Node process must not reach the CLI.
        name != "NODE_OPTIONS" && name !in PARENT_SESSION_VARIABLES && PARENT_SESSION_PREFIXES.none(name::startsWith)
    }
    // Tandem's own value; some other values switch CLI behaviour (docs/protocol.md 1.2).
    return kept + ("CLAUDE_CODE_ENTRYPOINT" to "sdk-tandem")
}
