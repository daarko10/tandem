package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.Cards
import dev.daarkolabs.tandem.chat.PlanSource
import dev.daarkolabs.tandem.ui.cards.planFileToOpen
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the plan approval card opens (spec 4.14). */
class PlanCardTest {
    @Test
    fun `open plan file needs an absolute path from the request`(@TempDir tmp: Path) {
        val approve = stateAt("plan-approve", 88)
        val p = approve.pendingPermissions.single()
        // The redacted "<path>" is not absolute.
        assertNull(planFileToOpen(Cards.plan(p, approve)))
        // Absolute on every OS.
        val file = tmp.resolve("plans").resolve("x.md")
        val patched = p.copy(input = JsonObject(p.input + ("planFilePath" to JsonPrimitive(file.toString()))))
        assertEquals(file, planFileToOpen(Cards.plan(patched, approve)))
        // A draft's Write failed, so its file does not exist: even an absolute path opens nothing.
        assertNull(planFileToOpen(Cards.plan(patched, approve).copy(source = PlanSource.PLAN_FILE_DRAFT)))
        // NONE: no plan file to open.
        val disallowed = stateAt("plan-disallowed", 173)
        assertNull(planFileToOpen(Cards.plan(disallowed.pendingPermissions.single(), disallowed)))
    }
}
