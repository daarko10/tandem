package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonObject

// The answers to the queue's control requests (queue-cancel:25, :56; docs/superpowers/specs/2026-10-22-desktop-parity-design.md 12.6).

/** The `interrupt` answer under `interrupt_cancel_queued_v1`: the command uuids still queued and those the interrupt cancelled. */
data class InterruptReceipt(val stillQueued: List<String>, val cancelled: List<String>)

/** An absent or mistyped list reads as empty; a non-string entry is skipped. */
fun interruptReceipt(payload: JsonObject?): InterruptReceipt =
    InterruptReceipt(payload?.strings("still_queued").orEmpty(), payload?.strings("cancelled").orEmpty())

/** The `cancel_async_message` answer's `cancelled`; null when absent or not a boolean. */
fun cancelAsyncAnswer(payload: JsonObject?): Boolean? = payload?.boolean("cancelled")
