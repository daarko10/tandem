import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Tree API only (JsonElement). No serialization compiler plugin, so unknown fields never break parsing.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }

    // JUnit 5 for platform tests (@TestApplication fixtures); the vintage engine keeps the JUnit 4 tests running.
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Compose + Jewel bundled modules (Jewel foundation/ui, IDE LaF bridge, Compose runtime).
        composeUI()
        // Jewel Markdown (parsing, IDE-styled rendering, plain-URL links); bundled with the IDE.
        bundledModule("intellij.platform.jewel.markdown.core")
        bundledModule("intellij.platform.jewel.markdown.ideLafBridgeStyling")
        bundledModule("intellij.platform.jewel.markdown.extensions.autolink")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Every stable IntelliJ IDEA release from sinceBuild up to the newest one.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = providers.gradleProperty("pluginSinceBuild")
            }
        }
    }
}

tasks {
    runIde {
        // Development-only runs, also usable headless under xvfb-run (see ui/dev/):
        //   ./gradlew runIde -Ptandem.spike=true -Ptandem.openProject=<dir>       Jewel rendering spike
        //   ./gradlew runIde -Ptandem.autoPrompt="..." -Ptandem.openProject=<dir> sends one real prompt on startup
        // The extra flags skip first-run dialogs in the sandbox IDE.
        val spike = providers.gradleProperty("tandem.spike").orNull == "true"
        val autoPrompt = providers.gradleProperty("tandem.autoPrompt").orNull
        if (spike || autoPrompt != null) {
            jvmArgs(
                "-Didea.trust.all.projects=true",
                "-Djb.consents.confirmation.enabled=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Didea.initially.ask.config=never",
            )
        }
        if (spike) jvmArgs("-Dtandem.spike=true")
        if (autoPrompt != null) jvmArgs("-Dtandem.autoPrompt=$autoPrompt")
        providers.gradleProperty("tandem.openProject").orNull?.let { args(it) }
    }

    test {
        useJUnitPlatform()
        // Protocol tests run against recorded CLI transcripts; they never spawn a real `claude`.
        systemProperty("tandem.fixtures", layout.projectDirectory.dir("src/test/resources/fixtures").asFile.path)
        // Platform tests start TandemChat against a fake CLI that replays a fixture, never a real `claude`.
        systemProperty("tandem.claudePath", layout.projectDirectory.file("src/test/resources/fake-claude.sh").asFile.path)
        // Set by the nightly CLI contract workflow: a directory of freshly recorded live transcripts.
        systemProperty("tandem.contractDir", providers.systemProperty("tandem.contractDir").getOrElse(""))
    }
}
