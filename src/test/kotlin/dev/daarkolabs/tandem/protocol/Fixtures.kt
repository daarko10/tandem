package dev.daarkolabs.tandem.protocol

import java.io.File

/** Recorded CLI transcripts from `src/test/resources/fixtures/`. */
internal object Fixtures {
    private val dir: File = File(System.getProperty("tandem.fixtures") ?: "src/test/resources/fixtures")

    fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(".ndjson") }.orEmpty().map { it.nameWithoutExtension }.sorted()

    fun lines(name: String): List<String> = File(dir, "$name.ndjson").readLines().filter { it.isNotBlank() }

    fun decode(name: String): List<CliMessage> = lines(name).map(Ndjson::decode)
}
