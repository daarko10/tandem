import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.intellij.platform")
    // Coverage of the unit, fixture and platform tests; build-time only, nothing of it goes into the plugin.
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    jvmToolchain(21)
}

// IDE Starter tests: boot a real IDE with the built plugin installed and drive it with the UI Driver.
// Slow (downloads and starts an IDE), so they run in their own task: xvfb-run ./gradlew integrationTest
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val integrationTestImplementation: Configuration = configurations.getByName("integrationTestImplementation")
val integrationTestRuntimeOnly: Configuration = configurations.getByName("integrationTestRuntimeOnly")

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

    integrationTestImplementation(platform("org.junit:junit-bom:5.14.4"))
    integrationTestImplementation("org.junit.jupiter:junit-jupiter")
    integrationTestImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Reads the frames the fake CLI logged; same version as the plugin uses.
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The test JVM (not the plugin) needs coroutines: the version IDE Starter itself declares.
    integrationTestImplementation("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:1.10.2-intellij-1")
    // Only to satisfy the Compose compiler plugin, which applies to every source set; the tests contain no Compose code.
    "integrationTestCompileOnly"("org.jetbrains.compose.runtime:runtime-desktop:1.12.1")

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
        testFramework(TestFrameworkType.Starter, configurationName = integrationTestImplementation.name)
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

// Coverage of protocol/ and session/ (with session/transcript), whose target is 90% of lines (CLAUDE.md):
// ./gradlew koverLog prints it per package; koverHtmlReport and koverXmlReport write build/reports/kover/; koverVerify, part of check,
// holds each package to it.
kover {
    currentProject {
        // Measures the test task only: the IDE Starter tests run the plugin in another JVM, and reports must never start them.
        instrumentation {
            disabledForTestTasks.add("integrationTest")
            // Only Tandem's classes: by default the agent instruments every class the test JVM loads, the whole IntelliJ Platform.
            includedClasses.add("dev.daarkolabs.tandem.*")
        }
    }
    reports {
        filters {
            includes {
                packages("dev.daarkolabs.tandem.protocol", "dev.daarkolabs.tandem.session")
            }
        }
        total {
            // Part of check: fails the build when a package falls below 90% of lines.
            verify {
                rule("90% of lines in each package of protocol/ and session/") {
                    groupBy = GroupingEntityType.PACKAGE
                    bound {
                        minValue = 90
                        coverageUnits = CoverageUnit.LINE
                        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    }
                }
            }
            log {
                groupBy = GroupingEntityType.PACKAGE
                coverageUnits = CoverageUnit.LINE
                aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                format = "<entity> line coverage: <value>%"
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
        // Platform tests start sessions against a fake CLI that replays a fixture, never a real `claude`.
        systemProperty("tandem.claudePath", layout.projectDirectory.file("src/test/resources/fake-claude.py").asFile.path)
        // Set by the nightly CLI contract workflow: a directory of freshly recorded live transcripts.
        systemProperty("tandem.contractDir", providers.systemProperty("tandem.contractDir").getOrElse(""))
        // A GC log, which CI uploads with the test IDE's logs when the build fails: it shows what the test JVM's heap did, such as
        // the platform's leak check filling the heap when the test application closes. Quoted: a Windows path has a colon.
        jvmArgs("-Xlog:gc*:file=\"${layout.buildDirectory.file("test-gc.log").get().asFile.path}\":time,uptime")
        // The platform's leak check, when the test application closes, fills the whole heap to flush soft references: with the
        // platform's default of 2 GB that alone took 18 s of the close's 20 s on a 2-CPU CI runner. The tests need far less: the
        // heap holds about 150 MB live and grows to about 600 MB during a run.
        maxHeapSize = "1g"
    }

    register<Test>("integrationTest") {
        description = "Starts a real IDE with the built plugin ZIP installed and drives it (needs a display: xvfb-run)."
        group = "verification"
        // Each IDE test is slow and runs once in CI: list every result in the log.
        testLogging { events("passed", "skipped", "failed") }
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform()
        val pluginZip = buildPlugin.flatMap { it.archiveFile }
        inputs.file(pluginZip)
        val fakeClaude = layout.projectDirectory.file("src/test/resources/fake-claude.py").asFile.path
        val ideVersion = providers.gradleProperty("platformVersion")
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Dtandem.pluginZip=${pluginZip.get().asFile.path}",
                "-Dtandem.fakeClaude=$fakeClaude",
                "-Dtandem.ideVersion=${ideVersion.get()}",
            )
        }
        // IDE Starter downloads the IDE under test and keeps its logs and screenshots in out/ide-tests (gitignored).
    }
}
