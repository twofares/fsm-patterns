package fsm.sample.flat

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: TrafficLight **domain** effect ADT (flat light only).
 *
 * This machine has no light-specific side effects beyond observability.
 * Effects wrap the **cross-cutting** [AuditEffect] vocabulary so:
 * - export/log format stays shared ([AuditInterpreter] → [TransitionLog])
 * - the type system prevents nonsense like emitting Connection's IncHeartbeat
 *   (that type is not a [LightEffect])
 */
sealed class LightEffect {
    /** Cross-cutting audit (transition log for this vignette). */
    data class Audit(val audit: AuditEffect) : LightEffect()
}

/** PURPOSE: Declare a light transition audit without nesting constructors at call sites. */
fun lightTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): LightEffect = LightEffect.Audit(auditTransition(machine, from, event, to))

/**
 * PURPOSE: Local boundary for TrafficLight — audit only via [AuditInterpreter].
 * No domain hooks; heartbeat and CSP vocabulary cannot appear here.
 */
fun interpretLight(effects: List<LightEffect>, log: TransitionLog? = null) {
    for (effect in effects) {
        when (effect) {
            is LightEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
        }
    }
}

/** PURPOSE: Export short name (pure label for Effects column). */
fun LightEffect.exportLabel(): String = when (this) {
    is LightEffect.Audit -> audit.exportLabel()
}
