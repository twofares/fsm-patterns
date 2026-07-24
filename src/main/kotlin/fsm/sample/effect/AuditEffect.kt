package fsm.sample.effect

/**
 * PURPOSE: **Cross-cutting** audit vocabulary shared by multiple machines.
 *
 * Not a domain effect algebra — only the observability shape that every sample
 * machine records into [fsm.sample.logging.TransitionLog] (transition / entry /
 * exit / refusal). Machines wrap these in their own sealed ADTs (or use them
 * via local interpreters) so domain-only effects (e.g. Connection heartbeat)
 * stay out of a global god type.
 *
 * Pure steps *declare* [AuditEffect] values; [AuditInterpreter.applyOne]
 * applies them at boundaries (via per-machine interpreters). Export labels
 * come from [exportLabel] without I/O.
 */
sealed class AuditEffect {
    /** Record a successful mode change (from --event--> to). */
    data class LogTransition(
        val machine: String,
        val from: String,
        val event: String,
        val to: String,
    ) : AuditEffect()

    /** Record hierarchical/leaf entry observation. */
    data class LogEntry(
        val machine: String,
        val state: String,
        val detail: String? = null,
    ) : AuditEffect()

    /** Record hierarchical/leaf exit observation. */
    data class LogExit(
        val machine: String,
        val state: String,
        val detail: String? = null,
    ) : AuditEffect()

    /** Record CSP-style or hierarchical refusal (state unchanged). */
    data class LogRefusal(
        val machine: String,
        val state: String,
        val event: String,
        val reason: String,
    ) : AuditEffect()
}

/** PURPOSE: Factory for transition audit (readable call sites in pure steps). */
fun auditTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): AuditEffect.LogTransition =
    AuditEffect.LogTransition(machine, from, event, to)

/** PURPOSE: Factory for entry audit. */
fun auditEntry(
    machine: String,
    state: String,
    detail: String? = null,
): AuditEffect.LogEntry =
    AuditEffect.LogEntry(machine, state, detail)

/** PURPOSE: Factory for exit audit. */
fun auditExit(
    machine: String,
    state: String,
    detail: String? = null,
): AuditEffect.LogExit =
    AuditEffect.LogExit(machine, state, detail)

/** PURPOSE: Factory for refusal audit. */
fun auditRefusal(
    machine: String,
    state: String,
    event: String,
    reason: String,
): AuditEffect.LogRefusal =
    AuditEffect.LogRefusal(machine, state, event, reason)

/**
 * PURPOSE: Short export labels for audit effects (type + useful params).
 * Used in Markdown Effects columns; pure mapping only — not an interpreter.
 */
fun AuditEffect.exportLabel(): String = when (this) {
    is AuditEffect.LogTransition -> "LogTransition"
    is AuditEffect.LogEntry -> "LogEntry($state)"
    is AuditEffect.LogExit -> "LogExit($state)"
    is AuditEffect.LogRefusal -> "LogRefusal"
}
