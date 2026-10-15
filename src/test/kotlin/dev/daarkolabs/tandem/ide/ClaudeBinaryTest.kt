package dev.daarkolabs.tandem.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeBinaryTest {
    @TempDir
    lateinit var tmp: Path

    private fun executable(dir: String, name: String = "claude"): String =
        File(tmp.toFile(), dir).apply { mkdirs() }.resolve(name).apply { writeText("#!/bin/sh\n"); setExecutable(true) }.path

    @Test
    fun `nothing found when PATH and the usual locations are empty`() {
        assertNull(ClaudeBinary.find(override = null, path = "$tmp/a:$tmp/b", home = "$tmp/home", windows = false))
    }

    @Test
    fun `PATH order wins, then the install locations under home`() {
        val second = executable("b")
        val home = executable("home/.local/bin")
        assertEquals(second, ClaudeBinary.find(null, "$tmp/a:$tmp/b", "$tmp/home", windows = false))
        assertEquals(home, ClaudeBinary.find(null, "$tmp/a", "$tmp/home", windows = false))
    }

    @Test
    fun `an executable override wins and a missing one is ignored`() {
        val onPath = executable("a")
        val custom = executable("custom", "my-claude")
        assertEquals(custom, ClaudeBinary.find(custom, "$tmp/a", "$tmp/home", windows = false))
        assertEquals(onPath, ClaudeBinary.find("$tmp/missing", "$tmp/a", "$tmp/home", windows = false))
    }

    @Test
    fun `non-executable files are skipped`() {
        File(tmp.toFile(), "a").apply { mkdirs() }.resolve("claude").writeText("not executable")
        assertNull(ClaudeBinary.find(null, "$tmp/a", "$tmp/home", windows = false))
    }
}
