package dev.daarkolabs.tandem.chat

/** Maps a tool's wire name to its [ToolKind] (spec 4.6.2). */
object ToolKinds {
    /** Tandem's own MCP tools by name prefix (spec 3.4): `edit_file` edits, `write_file` creates or replaces a file. */
    private val tandemTools: List<Pair<String, ToolKind>> = listOf(
        "mcp__tandem__edit" to ToolKind.EDIT,
        "mcp__tandem__write" to ToolKind.CREATE,
    )

    /** The wire prefix of Tandem's own tools (`mcp__tandem__edit_file`, `mcp__tandem__write_file`). */
    const val TANDEM_PREFIX: String = "mcp__tandem__"

    /** Whether [name] is one of Tandem's own tools, which have their own labels, states and bodies (spec 3.4). */
    fun isTandem(name: String): Boolean = name.startsWith(TANDEM_PREFIX)

    fun of(name: String): ToolKind = when (name) {
        "Read" -> ToolKind.READ
        "Edit", "MultiEdit", "NotebookEdit" -> ToolKind.EDIT
        "Write" -> ToolKind.CREATE
        "Bash", "PowerShell" -> ToolKind.SHELL
        "Grep" -> ToolKind.SEARCH
        "Glob" -> ToolKind.FIND
        "WebFetch" -> ToolKind.WEB_FETCH
        "WebSearch" -> ToolKind.WEB_SEARCH
        // The CLI lists the tool as Task in system/init and names its blocks Agent.
        "Agent", "Task" -> ToolKind.AGENT
        "AskUserQuestion" -> ToolKind.QUESTION
        "ExitPlanMode" -> ToolKind.PLAN
        "EnterPlanMode" -> ToolKind.PLAN_ENTER
        "TaskCreate", "TaskUpdate", "TaskGet", "TaskList" -> ToolKind.TASKS
        "ToolSearch" -> ToolKind.TOOL_SEARCH
        else -> tandemTools.firstOrNull { name.startsWith(it.first) }?.second
            ?: if (name.startsWith("mcp__")) ToolKind.MCP else ToolKind.OTHER
    }
}
