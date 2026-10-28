package dev.daarkolabs.tandem.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The `@` palette's form (spec 3.5): what a row reads, and how tall the list is. */
class MentionPaletteViewTest {
    @Test
    fun `a row reads the file's name, then its folder`() {
        assertEquals("Foo.kt" to "src/main/kotlin/", mentionRowParts("src/main/kotlin/Foo.kt"))
        assertEquals("hello.txt" to "", mentionRowParts("hello.txt"))
    }

    @Test
    fun `the list shows ten rows at most and never outgrows the room above the box`() {
        assertEquals(3 * 24, mentionListHeight(rows = 3, rowPx = 24, maxPx = 1000))
        assertEquals(10 * 24, mentionListHeight(rows = 40, rowPx = 24, maxPx = 1000))
        assertEquals(100, mentionListHeight(rows = 40, rowPx = 24, maxPx = 100))
        assertEquals(0, mentionListHeight(rows = 40, rowPx = 24, maxPx = -8))
    }
}
