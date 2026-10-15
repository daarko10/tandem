package dev.daarkolabs.tandem.ide

import com.intellij.openapi.util.SystemInfo
import java.io.File

/** Finds the user's `claude` executable. Call off the EDT: it touches the file system. */
internal object ClaudeBinary {
    fun find(): String? {
        System.getProperty("tandem.claudePath")?.takeIf { File(it).canExecute() }?.let { return it }
        val names = if (SystemInfo.isWindows) listOf("claude.exe", "claude.cmd", "claude") else listOf("claude")
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        val home = System.getProperty("user.home")
        val fallbacks = listOf("$home/.local/bin", "$home/.claude/local")
        return (path + fallbacks).asSequence()
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path
    }
}
