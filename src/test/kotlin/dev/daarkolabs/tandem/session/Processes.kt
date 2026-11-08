package dev.daarkolabs.tandem.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Processes the tests start without a shell, so they run on Windows as on Unix: Python is a test prerequisite already (the fake
 * CLI). Paths go to the snippets as arguments (`sys.argv[1:]`), never quoted into the code.
 */
object Processes {
    /**
     * `python3`, `python`, then `py -3`: the first that answers within 5 s (a developer's Windows has only the Store alias under
     * `python3`), as the interpreter it runs itself. A launcher (`py`, a venv's or a tool cache's `python3.exe`) puts the real
     * interpreter and its children in a job that dies with the launcher, so a test that kills its process would kill the children
     * it means to outlive it.
     */
    val python: List<String> by lazy {
        val candidates: List<List<String>> = listOf(listOf("python3"), listOf("python"), listOf("py", "-3"))
        candidates.firstNotNullOfOrNull(::interpreter)
            ?: error("no Python 3 found: tried ${candidates.joinToString { it.joinToString(" ") }}")
    }

    private fun interpreter(command: List<String>): List<String>? = try {
        val code = "import sys;print(getattr(sys,'_base_executable',None) or sys.executable) if sys.version_info[0]==3 else None"
        val process: Process = ProcessBuilder(command + listOf("-c", code)).redirectErrorStream(true).start()
        process.outputStream.close()
        val done: Boolean = process.waitFor(5, TimeUnit.SECONDS)
        if (!done) process.destroyForcibly()
        val printed: String = if (done && process.exitValue() == 0) process.inputStream.bufferedReader().readText().trim() else ""
        when {
            printed.isEmpty() || printed == "None" -> null
            java.io.File(printed).isFile -> {
                // A Windows tool cache's python3.exe beside python.exe may be such a launcher: take python.exe.
                val beside = java.io.File(printed).resolveSibling("python.exe")
                listOf(if (printed.endsWith("python3.exe", ignoreCase = true) && beside.isFile) beside.path else printed)
            }
            else -> command
        }
    } catch (_: java.io.IOException) {
        null
    }

    /**
     * `python -c code` with [args] as `sys.argv[1:]`. The code and the arguments travel base64-encoded inside a `-c` with neither
     * spaces nor quotes: Java on Windows does not escape a `"` inside an argument, so `-c 'print("x")'` would reach Python mangled.
     */
    fun builder(code: String, vararg args: String): ProcessBuilder {
        val encoder: Base64.Encoder = Base64.getEncoder()
        val argv: String = encoder.encodeToString(JsonArray(args.map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8))
        val source: String = encoder.encodeToString(code.toByteArray(Charsets.UTF_8))
        val wrapper = "import base64,json,sys;sys.argv[1:]=json.loads(base64.b64decode('$argv'));" +
            "exec(compile(base64.b64decode('$source').decode('utf-8'),'<test>','exec'))"
        return ProcessBuilder(python + listOf("-c", wrapper))
    }

    /** Reads stdin to its end and exits 0: `sys.stdin.buffer.read()`. In place of `sh -c "cat > /dev/null"`. */
    fun sink(): Process = builder(SINK).start()

    /** Prints [lines] (each with "\n"), flushes, then copies stdin to [file] until its end. In place of `printf …; cat > file`. */
    fun recorder(file: Path, vararg lines: String): ProcessBuilder = builder(
        "import sys\n" +
            "out = sys.stdout.buffer\n" +
            "for line in sys.argv[2:]:\n" +
            "    out.write((line + '\\n').encode('utf-8'))\n" +
            "out.flush()\n" +
            COPY_TO_ARGV1,
        file.toString(), *lines,
    )

    /** Polls every 50 ms until [gate] exists, then exits [code]. */
    fun exitAfterGate(gate: Path, code: Int): ProcessBuilder = builder(
        "import os, sys, time\nwhile not os.path.exists(sys.argv[1]):\n    time.sleep(0.05)\nsys.exit(int(sys.argv[2]))",
        gate.toString(), code.toString(),
    )

    /** Prints [file]'s text, flushes, then reads stdin to its end (`cat fixture; cat > /dev/null`). */
    fun catThenSink(file: Path): ProcessBuilder = builder(CAT_ARGV1 + SINK, file.toString())

    /** Writes [file]'s bytes to stdout and flushes. */
    const val CAT_ARGV1: String =
        "import sys\nwith open(sys.argv[1], 'rb') as f:\n    sys.stdout.buffer.write(f.read())\nsys.stdout.buffer.flush()\n"

    /** Reads stdin to its end. */
    const val SINK: String = "import sys\nsys.stdin.buffer.read()\n"

    /** Copies stdin to the file `sys.argv[1]` as it comes, until its end. */
    const val COPY_TO_ARGV1: String =
        "import os, sys\n" +
            "with open(sys.argv[1], 'wb') as f:\n" +
            "    while True:\n" +
            "        chunk = os.read(0, 65536)\n" +
            "        if not chunk:\n" +
            "            break\n" +
            "        f.write(chunk)\n" +
            "        f.flush()\n"
}
