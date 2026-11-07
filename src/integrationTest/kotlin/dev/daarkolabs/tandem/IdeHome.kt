package dev.daarkolabs.tandem

import com.intellij.ide.starter.ide.DefaultIdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The IDE under test for [project]: the release we compile against ([ideVersion]), downloaded and unpacked under out/ide-tests, or with
 * `tandem.ideHome` set, the IDE already unpacked there (the Gradle cache's `idea-<version>`, say), which saves the download's 1.3 GB
 * and the unpacked copy's 4 GB on a small disk.
 */
internal fun ideTestCase(project: Path, ideVersion: String): TestCase<LocalProjectInfo> {
    val home = System.getProperty("tandem.ideHome").orEmpty()
    // withVersion puts the public downloader back, so it is only for the downloaded IDE.
    if (home.isBlank()) return TestCase(IdeProductProvider.IU, LocalProjectInfo(project)).withVersion(ideVersion)
    return TestCase(IdeProductProvider.IU.copy(getInstaller = { InPlaceIdeInstaller(Path.of(home)) }), LocalProjectInfo(project))
}

/** Runs the IDE unpacked at [home] where it is: the Starter's own ExistingIdeInstaller copies it into out/ide-tests first. */
private class InPlaceIdeInstaller(private val home: Path) : IdeInstaller {
    override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> {
        val build = Regex("\"buildNumber\"\\s*:\\s*\"([^\"]+)\"").find(home.resolve("product-info.json").readText())?.groupValues?.get(1)
            ?: error("no buildNumber in ${home.resolve("product-info.json")}")
        return build to DefaultIdeDistributionFactory.installIDE(home.toFile(), ideInfo.executableFileName)
    }
}
