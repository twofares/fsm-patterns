package fsm.sample.effect

import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: **Cross-cutting** boundary that applies [AuditEffect]s to [TransitionLog].
 *
 * Shared by every per-machine interpreter for the audit subset of its ADT.
 * Domain-only effects (heartbeat, CSP control, etc.) are handled by those
 * local interpreters — never here.
 *
 * Ordinary Kotlin — not an effect runtime. Null [log] silently drops audit
 * (useful for pure probes / export construction). Per-machine interpreters
 * call [applyOne] once per wrapped audit effect.
 */
object AuditInterpreter {

    /** Apply a single audit effect to [log] (or drop if null). */
    fun applyOne(effect: AuditEffect, log: TransitionLog? = null) {
        when (effect) {
            is AuditEffect.LogTransition ->
                log?.recordTransition(
                    machine = effect.machine,
                    from = effect.from,
                    event = effect.event,
                    to = effect.to,
                )
            is AuditEffect.LogEntry ->
                log?.recordEntry(
                    machine = effect.machine,
                    state = effect.state,
                    detail = effect.detail,
                )
            is AuditEffect.LogExit ->
                log?.recordExit(
                    machine = effect.machine,
                    state = effect.state,
                    detail = effect.detail,
                )
            is AuditEffect.LogRefusal ->
                log?.recordRefusal(
                    machine = effect.machine,
                    state = effect.state,
                    event = effect.event,
                    reason = effect.reason,
                )
        }
    }
}
