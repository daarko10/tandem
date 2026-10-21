package dev.daarkolabs.tandem.protocol

/** CLI permission modes, by their wire names. */
enum class PermissionMode(val wire: String) {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    BYPASS_PERMISSIONS("bypassPermissions"),
    DONT_ASK("dontAsk"),
    AUTO("auto"),
    ;

    companion object {
        fun fromWire(value: String?): PermissionMode? = entries.firstOrNull { it.wire == value }
    }
}
