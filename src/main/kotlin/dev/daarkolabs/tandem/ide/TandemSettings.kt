package dev.daarkolabs.tandem.ide

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoredPropertyBase
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlin.reflect.KProperty

/**
 * Tandem's settings for this machine, edited in Settings | Tools | Tandem ([TandemSettingsConfigurable]). Kept in `tandem.xml` and
 * never roamed: an executable's path is one machine's.
 */
@Service(Service.Level.APP)
@State(name = "TandemSettings", storages = [Storage("tandem.xml", roamingType = RoamingType.DISABLED)])
class TandemSettings : SimplePersistentStateComponent<TandemSettings.State>(TandemSettings.State()) {
    /** What is stored. */
    class State : BaseState() {
        /**
         * The `claude` to run, stored [normalisedPath] whoever sets it: trimmed, and null for a blank one. Null: [ClaudeBinary.find]
         * looks on `PATH` and in the usual install locations.
         */
        var claudePath: String? by Normalised(string())

        /** At most this many sessions run a process at once. Read it with [maxLiveSessions], which keeps it in range. */
        var maxLiveSessions: Int by property(4)

        /** Whether the chat shows a row for each thinking block (spec 4.5); open chats take it on [TandemSessions.settingsChanged]. */
        var showThinking: Boolean by property(true)

        /**
         * Whether Claude replies to a shell escape's output (MCP spec 12): on, as the CLI's `respondToBashCommands` defaults; off, the
         * output is only added to the conversation. Read when each command ends.
         */
        var respondToShellOutput: Boolean by property(true)

        /**
         * Whether the chat's working spark stands still (spec 4.9); open chats take it on [TandemSessions.settingsChanged]. Tandem's
         * own: the IDE has no reduce-motion setting (`UISettings.animateWindows` is the tool windows' slide, off by default).
         */
        var reduceMotion: Boolean by property(false)
    }

    /**
     * [stored], a property of the state, which takes every value [normalisedPath]. Its name and its place among the state's properties
     * are [stored]'s, so the state is saved and compared as before.
     */
    private class Normalised(private val stored: StoredPropertyBase<String?>) {
        operator fun provideDelegate(thisRef: BaseState, property: KProperty<*>): Normalised {
            stored.provideDelegate(thisRef, property)
            return this
        }

        operator fun getValue(thisRef: BaseState, property: KProperty<*>): String? = stored.getValue(thisRef, property)

        operator fun setValue(thisRef: BaseState, property: KProperty<*>, value: String?) {
            stored.setValue(thisRef, property, normalisedPath(value))
        }
    }

    /**
     * Takes [state] with its path [normalisedPath], whatever the file says: a hand-edited path may have spaces around it, and reading
     * the file may set the stored value without [State.claudePath]'s setter.
     */
    override fun loadState(state: State) {
        state.claudePath = normalisedPath(state.claudePath)
        super.loadState(state)
    }

    /** [State.maxLiveSessions], within [MAX_LIVE_SESSIONS] whatever the file says. */
    fun maxLiveSessions(): Int = state.maxLiveSessions.coerceIn(MAX_LIVE_SESSIONS)

    companion object {
        /** The values the cap on live sessions takes. */
        val MAX_LIVE_SESSIONS: IntRange = 1..16

        fun getInstance(): TandemSettings = service()

        /** [path] as it is stored: trimmed, and null when that leaves nothing. */
        fun normalisedPath(path: String?): String? = path?.trim()?.ifEmpty { null }
    }
}
