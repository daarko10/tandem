package dev.daarkolabs.tandem.ide

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.daarkolabs.tandem.protocol.SlashCommand
import dev.daarkolabs.tandem.session.CommandCatalogue
import dev.daarkolabs.tandem.session.ModelOption

/**
 * What Tandem remembers about a project between IDE runs, in its workspace file (never shared): the sessions that were open, the one
 * selected, the sidebar, the last choices for a new session, and the models and slash commands the CLI last listed. [TandemSessions]
 * keeps the sessions, the selection, the models and the commands up to date.
 */
@Service(Service.Level.PROJECT)
@State(name = "TandemWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TandemWorkspaceState : SimplePersistentStateComponent<TandemWorkspaceState.State>(TandemWorkspaceState.State()) {
    /** What is stored. */
    class State : BaseState() {
        /** The ids of the open sessions, in the order they were opened. */
        var openSessionIds: MutableList<String> by list()

        /** The id of the selected session, if one was. */
        var selectedSessionId: String? by string()

        /** Whether the user collapsed the session sidebar. */
        var sidebarCollapsed: Boolean by property(false)

        /** The model last picked for a new session; null for the CLI's default. */
        var lastModel: String? by string()

        /** The effort last picked for a new session; null for the CLI's default. */
        var lastEffort: String? by string()

        /** The permission mode last picked for a new session, as the CLI names it (`default`, `acceptEdits`, `plan`). */
        var lastPermissionMode: String? by string()

        /** The models a session's CLI last listed in its `initialize` response ([knownModels]). */
        var knownModels: MutableList<StoredModel> by list()

        /** The slash commands a session's CLI last listed in its `initialize` response ([knownCommands]). */
        var knownCommands: MutableList<StoredCommand> by list()

        /** The commands a session's CLI last said work only in its terminal (`terminal_slash_commands`, [knownCommands]). */
        var knownTerminalCommands: MutableList<String> by list()
    }

    /** A slash command as [State.knownCommands] stores it: a [SlashCommand]. */
    class StoredCommand : BaseState() {
        var name: String? by string()
        var description: String? by string()
        var argumentHint: String? by string()
        var builtin: Boolean by property(false)
        var aliases: MutableList<String> by list()
    }

    /** A model as [State.knownModels] stores it: a [ModelOption]. */
    class StoredModel : BaseState() {
        var value: String? by string()
        var displayName: String? by string()
        var description: String? by string()
        var resolvedModel: String? by string()
        var supportsAutoMode: Boolean by property(false)
        var effortLevels: MutableList<String> by list()
    }

    /**
     * The models a session's CLI listed last, in this IDE run or an earlier one: what the new-session popup offers until a live
     * session lists them again. An entry without a value or a name, which only a hand-edited file has, is left out.
     */
    fun knownModels(): List<ModelOption> = state.knownModels.mapNotNull { stored ->
        val value = stored.value ?: return@mapNotNull null
        val name = stored.displayName ?: return@mapNotNull null
        ModelOption(value, name, stored.description, stored.resolvedModel, stored.supportsAutoMode, stored.effortLevels.toList())
    }

    /** Remembers [models], which a session's CLI listed, as [knownModels]; an empty list leaves the last one as it was. */
    fun rememberModels(models: List<ModelOption>) {
        if (models.isEmpty() || models == knownModels()) return
        state.knownModels = models.mapTo(ArrayList()) { model ->
            StoredModel().apply {
                value = model.value
                displayName = model.displayName
                description = model.description
                resolvedModel = model.resolvedModel
                supportsAutoMode = model.supportsAutoMode
                effortLevels = model.effortLevels.toMutableList()
            }
        }
    }

    /**
     * The slash commands a session's CLI offered last, in this IDE run or an earlier one: what the palette of a session without its own
     * offers until its process answers. An entry without a name, which only a hand-edited file has, is left out.
     */
    fun knownCommands(): CommandCatalogue = CommandCatalogue(
        commands = state.knownCommands.mapNotNull { stored ->
            val name = stored.name ?: return@mapNotNull null
            SlashCommand(name, stored.description.orEmpty(), stored.argumentHint.orEmpty(), stored.builtin, stored.aliases.toList())
        },
        terminal = state.knownTerminalCommands.toSet(),
    )

    /** Remembers [catalogue], which a session's CLI offered, as [knownCommands]; an empty part leaves the last one as it was. */
    fun rememberCommands(catalogue: CommandCatalogue) {
        val known = knownCommands()
        if (catalogue.commands.isNotEmpty() && catalogue.commands != known.commands) {
            state.knownCommands = catalogue.commands.mapTo(ArrayList()) { command ->
                StoredCommand().apply {
                    name = command.name
                    description = command.description
                    argumentHint = command.argumentHint
                    builtin = command.builtin
                    aliases = command.aliases.toMutableList()
                }
            }
        }
        if (catalogue.terminal.isNotEmpty() && catalogue.terminal != known.terminal) {
            state.knownTerminalCommands = catalogue.terminal.sorted().toMutableList()
        }
    }

    companion object {
        fun getInstance(project: Project): TandemWorkspaceState = project.service()
    }
}
