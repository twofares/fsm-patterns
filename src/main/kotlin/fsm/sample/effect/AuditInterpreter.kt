package fsm.sample.effect

import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: **Cross-cutting** boundary that applies [AuditEffect]s to [TransitionLog].
 *
 * Shared by every machine handle/offer path for the audit list only.
 * Domain-only effects are applied by per-machine applyDomain helpers — never here.
 *
 * Ordinary Kotlin — not an effect runtime. Null [log] drops audit (rare; pure paths
 * never call this). Production handles pass a real [TransitionLog] when observability
 * is wanted; pure steps never invoke [apply] / [applyOne].
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

    /** Apply a list of audit effects in order. */
    fun apply(audit: List<AuditEffect>, log: TransitionLog? = null) {
        for (effect in audit) applyOne(effect, log)
    }
}
