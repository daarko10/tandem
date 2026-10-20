package dev.daarkolabs.tandem.chat

/** Maps a tool's wire name to its [ToolKind] (spec 4.6.2). */
object ToolKinds {
    /** Tandem's own MCP tools by name prefix: Phase 3 adds its read and write tools here. */
    private val tandemTools: List<Pair<String, ToolKind>> = listOf("mcp__tandem__edit" to ToolKind.EDIT)

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
