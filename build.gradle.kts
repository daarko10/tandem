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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Compose + Jewel bundled modules (Jewel foundation/ui, IDE LaF bridge, Compose runtime).
        composeUI()
        testFramework(TestFrameworkType.Platform)
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
    test {
        // Protocol tests run against recorded CLI transcripts; they never spawn a real `claude`.
        systemProperty("tandem.fixtures", layout.projectDirectory.dir("src/test/resources/fixtures").asFile.path)
        // Set by the nightly CLI contract workflow: a directory of freshly recorded live transcripts.
        systemProperty("tandem.contractDir", providers.systemProperty("tandem.contractDir").getOrElse(""))
    }
}
