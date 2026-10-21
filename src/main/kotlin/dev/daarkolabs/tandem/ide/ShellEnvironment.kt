package dev.daarkolabs.tandem.ide

import com.intellij.util.EnvironmentUtil
import org.jetbrains.annotations.TestOnly

/**
 * The environment of the user's login shell, as the platform loads it ([EnvironmentUtil.getEnvironmentMap]).
 *
 * An IDE started from the Dock, Finder or a desktop launcher gets the session manager's minimal environment: on macOS launchd's
 * `PATH` (`/usr/bin:/bin:/usr/sbin:/sbin`), without `/opt/homebrew/bin`, `~/.local/bin` or what the user's shell profile adds. The
 * platform runs the login shell once at startup and keeps what it printed; that is where a terminal finds `claude`, and what it gives
 * the CLI. When the platform did not load it (the IDE was started from a terminal, or the shell failed), it is the IDE's own.
 */
internal object ShellEnvironment {
    private val platform: () -> Map<String, String> = EnvironmentUtil::getEnvironmentMap

    /** Where [get] reads the environment: tests put an environment of their own here, and [reset] afterwards. */
    @Volatile
    @set:TestOnly
    internal var source: () -> Map<String, String> = platform

    /**
     * The login shell's environment. Blocks while the platform is still running the shell, which it does once, at startup, for up
     * to its timeout: never call on the EDT.
     */
    fun get(): Map<String, String> = source()

    @TestOnly
    internal fun reset() {
        source = platform
    }
}
