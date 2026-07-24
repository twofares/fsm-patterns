package fsm.sample.logging

/**
 * PURPOSE: Consistent state/event logging without a DSL runtime.
 *
 * Research discipline: "react to every state assignment or log inside entry and
 * transition handlers so every mode change is recorded the same way."
 *
 * This logger is shared by flat, hierarchical, and orthogonal demos so that
 * every successful (and refused) step produces a uniform [TransitionRecord].
 * Tests assert against the log rather than re-implementing transition logic.
 */
class TransitionLog {
    private val records = mutableListOf<TransitionRecord>()

    /** Append a completed transition (from → to on event). */
    fun recordTransition(machine: String, from: String, event: String, to: String) {
        records += TransitionRecord(
            kind = RecordKind.TRANSITION,
            machine = machine,
            from = from,
            event = event,
            to = to,
            detail = null,
        )
    }

    /** Append an entry-action observation (state entered). */
    fun recordEntry(machine: String, state: String, detail: String? = null) {
        records += TransitionRecord(
            kind = RecordKind.ENTRY,
            machine = machine,
            from = null,
            event = null,
            to = state,
            detail = detail,
        )
    }

    /** Append an exit-action observation (state left). */
    fun recordExit(machine: String, state: String, detail: String? = null) {
        records += TransitionRecord(
            kind = RecordKind.EXIT,
            machine = machine,
            from = state,
            event = null,
            to = null,
            detail = detail,
        )
    }

    /**
     * PURPOSE: CSP-style refusal observability.
     * Records that an event was outside the current menu / alphabet acceptance.
     */
    fun recordRefusal(machine: String, state: String, event: String, reason: String) {
        records += TransitionRecord(
            kind = RecordKind.REFUSAL,
            machine = machine,
            from = state,
            event = event,
            to = state,
            detail = reason,
        )
    }

    fun snapshot(): List<TransitionRecord> = records.toList()

    fun clear() {
        records.clear()
    }

    fun formatAll(): String =
        records.joinToString(separator = "\n") { it.formatLine() }
}

enum class RecordKind {
    TRANSITION,
    ENTRY,
    EXIT,
    REFUSAL,
}

/**
 * PURPOSE: Immutable audit line for one mode change, entry, exit, or refusal.
 * Enables tests and the demo main to assert consistent traces without an engine.
 */
data class TransitionRecord(
    val kind: RecordKind,
    val machine: String,
    val from: String?,
    val event: String?,
    val to: String?,
    val detail: String?,
) {
    fun formatLine(): String = when (kind) {
        RecordKind.TRANSITION ->
            "[$machine] TRANSITION $from --($event)--> $to"
        RecordKind.ENTRY ->
            "[$machine] ENTRY $to${detail?.let { " ($it)" } ?: ""}"
        RecordKind.EXIT ->
            "[$machine] EXIT $from${detail?.let { " ($it)" } ?: ""}"
        RecordKind.REFUSAL ->
            "[$machine] REFUSAL in $from event=$event: $detail"
    }
}
