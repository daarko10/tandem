package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.RowOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatUiStateTest {
    @Test
    fun expansionIsKeptPerSessionKey() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val presenters = ChatPresenters(scope, MutableStateFlow(RowOptions(showThinking = true)))
            val first = presenters.ui("a").expanded("tool:1")
            first.value = true

            presenters.ui("b")
            val again = presenters.ui("a").expanded("tool:1")

            assertSame(first, again)
            assertTrue(again.value)
            assertFalse(presenters.ui("b").expanded("tool:1").value)
        } finally {
            scope.cancel()
        }
    }
}
