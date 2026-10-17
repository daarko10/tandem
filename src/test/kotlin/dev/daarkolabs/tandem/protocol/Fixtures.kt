package dev.daarkolabs.tandem.protocol

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** Recorded CLI transcripts from `src/test/resources/fixtures/`. */
object Fixtures {
    private val dir: File = File(System.getProperty("tandem.fixtures") ?: "src/test/resources/fixtures")

    fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(".ndjson") }.orEmpty().map { it.nameWithoutExtension }.sorted()

    fun lines(name: String): List<String> = File(dir, "$name.ndjson").readLines().filter { it.isNotBlank() }

    fun decode(name: String): List<CliMessage> = lines(name).map(Ndjson::decode)

    /**
     * The CLI's own on-disk transcript (`<session id>.jsonl`) recorded with the stream fixture of the same name, from
     * `transcripts/`. `<name>-origin` is the session a fork started from.
     */
    fun transcript(name: String): Path {
        val path = dir.toPath().resolve("transcripts").resolve("$name.jsonl")
        require(path.isRegularFile()) { "no transcript fixture $name: $path is not a file" }
        return path
    }
}
