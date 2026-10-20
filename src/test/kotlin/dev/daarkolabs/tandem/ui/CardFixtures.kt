package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.session.ChatState
import dev.daarkolabs.tandem.session.PendingPermission
import dev.daarkolabs.tandem.session.SessionEvent
import dev.daarkolabs.tandem.session.reduce

/** The state after the first [line] lines of fixture [name], reduced as the CLI sent them; no prompt is sent, so no turn is open. */
internal fun stateAt(name: String, line: Int): ChatState =
    Fixtures.lines(name).take(line).map(Ndjson::decode).fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }

/** The one request pending after the first [line] lines of fixture [name]. */
internal fun pendingAt(name: String, line: Int): PendingPermission = stateAt(name, line).pendingPermissions.single()
