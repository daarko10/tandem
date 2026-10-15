package dev.daarkolabs.tandem.ide

import com.intellij.openapi.util.SystemInfo
import java.io.File

/** Finds the user's `claude` executable. Call off the EDT: it touches the file system. */
internal object ClaudeBinary {
    fun find(): String? = find(
        override = System.getProperty("tandem.claudePath"),
        path = System.getenv("PATH").orEmpty(),
        home = System.getProperty("user.home"),
        windows = SystemInfo.isWindows,
    )

    /** [override] wins when it is executable; then each `PATH` entry; then the CLI's usual install locations under [home]. */
    fun find(override: String?, path: String, home: String, windows: Boolean): String? {
        override?.takeIf { File(it).canExecute() }?.let { return it }
        val names = if (windows) listOf("claude.exe", "claude.cmd", "claude") else listOf("claude")
        val dirs = path.split(File.pathSeparator).filter { it.isNotBlank() } + listOf("$home/.local/bin", "$home/.claude/local")
        return dirs.asSequence()
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path
    }
}
