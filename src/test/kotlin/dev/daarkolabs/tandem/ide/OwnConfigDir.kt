package dev.daarkolabs.tandem.ide

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gives each test a config directory of its own, never the user's: the `tandem.configDir` test hook names a new empty directory
 * before the test, and is cleared after it. Register it with `@JvmField @RegisterExtension`.
 */
class OwnConfigDir : BeforeEachCallback, AfterEachCallback {
    /** This test's config directory. */
    lateinit var path: Path
        private set

    override fun beforeEach(context: ExtensionContext) {
        path = Files.createTempDirectory("tandem-config")
        System.setProperty("tandem.configDir", path.toString())
    }

    override fun afterEach(context: ExtensionContext) {
        System.clearProperty("tandem.configDir")
        path.toFile().deleteRecursively()
    }
}
