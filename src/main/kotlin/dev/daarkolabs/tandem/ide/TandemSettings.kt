package dev.daarkolabs.tandem.ide

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Tandem's settings for this machine, edited in Settings | Tools | Tandem ([TandemSettingsConfigurable]). Kept in `tandem.xml` and
 * never roamed: an executable's path is one machine's.
 */
@Service(Service.Level.APP)
@State(name = "TandemSettings", storages = [Storage("tandem.xml", roamingType = RoamingType.DISABLED)])
class TandemSettings : SimplePersistentStateComponent<TandemSettings.State>(TandemSettings.State()) {
    /** What is stored. */
    class State : BaseState() {
        /** The `claude` to run. Null or blank: [ClaudeBinary.find] looks on `PATH` and in the usual install locations. */
        var claudePath: String? by string()

        /** At most this many sessions run a process at once. Read it with [maxLiveSessions], which keeps it in range. */
        var maxLiveSessions: Int by property(4)
    }

    /** [State.maxLiveSessions], within [MAX_LIVE_SESSIONS] whatever the file says. */
    fun maxLiveSessions(): Int = state.maxLiveSessions.coerceIn(MAX_LIVE_SESSIONS)

    companion object {
        /** The values the cap on live sessions takes. */
        val MAX_LIVE_SESSIONS: IntRange = 1..16

        fun getInstance(): TandemSettings = service()
    }
}
