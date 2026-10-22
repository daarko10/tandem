package dev.daarkolabs.tandem.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `updatedPermissions` for "Always allow for this session": the `addRules` entries of a `can_use_tool` request's
 * `permission_suggestions`, each with `destination` set to `session`.
 *
 * The CLI suggests three kinds at once (`misc-state-action.ndjson:21`): `addRules`, `addDirectories` and `setMode`.
 * Echoing a `setMode` switches the live permission mode (`plan-approve.ndjson:90`) and `addDirectories` widens
 * directory access, so both are dropped, as are unknown kinds and entries without a `type`. Persisting rules to
 * settings is a separate, explicit choice, hence `session` on every entry.
 */
internal fun sessionPermissionUpdates(suggestions: List<JsonObject>): List<JsonObject> =
    suggestions
        .filter { (it["type"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "addRules" }
        .map { JsonObject(it.toMutableMap().apply { put("destination", JsonPrimitive("session")) }) }
