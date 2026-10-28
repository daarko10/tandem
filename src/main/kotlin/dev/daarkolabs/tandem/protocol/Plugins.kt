package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonObject

// `system/init` `plugins[]` (parity-add-dir:5; docs/superpowers/specs/2026-10-22-desktop-parity-design.md 4.1).

/** One plugin of the session. [version] is plugin-author text, kept only when it looks like a version, else null. */
data class PluginInfo(val name: String, val path: String?, val source: String?, val version: String?)

/** A plugin that ships inside the CLI. */
val PluginInfo.builtin: Boolean get() = path == "builtin" || source?.endsWith("@builtin") == true

private val PLUGIN_VERSION: Regex = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,31}$")

/** One entry of `init.plugins`; null without a string `name`. */
internal fun pluginInfo(o: JsonObject): PluginInfo? {
    val name = o.string("name") ?: return null
    return PluginInfo(name, o.string("path"), o.string("source"), o.string("version")?.takeIf(PLUGIN_VERSION::matches))
}
