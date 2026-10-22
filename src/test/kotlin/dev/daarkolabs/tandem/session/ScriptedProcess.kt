package dev.daarkolabs.tandem.session

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/** A real process for input and ending ([real]), whose output is what the test writes to [script], line by line. */
class ScriptedProcess(private val real: Process) : Process() {
    private val output = PipedInputStream(64 * 1024)
    val script: PrintStream = PrintStream(PipedOutputStream(output), true, Charsets.UTF_8)

    /** The thread that last read [getErrorStream], once one has: a test can tell when it waits in the pipe's read itself. */
    @Volatile
    var stderrReader: Thread? = null
        private set

    /** The failure of a read of [getErrorStream], once one has failed. */
    @Volatile
    var stderrFailure: IOException? = null
        private set

    override fun getOutputStream(): OutputStream = real.outputStream
    override fun getInputStream(): InputStream = output

    override fun getErrorStream(): InputStream = object : FilterInputStream(real.errorStream) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            stderrReader = Thread.currentThread()
            return try {
                super.read(b, off, len)
            } catch (e: IOException) {
                stderrFailure = e
                throw e
            }
        }
    }

    override fun waitFor(): Int = real.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = real.waitFor(timeout, unit)
    override fun exitValue(): Int = real.exitValue()
    override fun isAlive(): Boolean = real.isAlive
    override fun pid(): Long = real.pid()
    override fun toHandle(): ProcessHandle = real.toHandle()
    override fun descendants() = real.descendants()

    override fun destroy() {
        real.destroy()
        script.close()
    }

    override fun destroyForcibly(): Process {
        real.destroyForcibly()
        script.close()
        return this
    }

    companion object {
        /** A scripted process whose real one reads its input and does nothing else until it is ended. */
        fun quiet(): ScriptedProcess = ScriptedProcess(ProcessBuilder("sh", "-c", "cat > /dev/null").start())
    }
}
