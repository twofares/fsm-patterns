package fsm.sample.gof

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditEntry
import fsm.sample.effect.auditExit
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Connection **domain** effect ADT — teaches domain-specific vs cross-cutting.
 *
 * - [IncHeartbeat]: **domain-specific** to this machine only (not on TrafficLight /
 *   FileProtocol / Door). Pure Online superstate handlers declare it; the local
 *   interpreter bumps [ConnectionContext.heartbeatCount].
 * - [Audit]: **cross-cutting** transition/entry/exit/refusal audit shared via
 *   [AuditEffect] + [AuditInterpreter] → same [TransitionLog] format as other machines.
 *
 * Separate sealed hierarchy: a [LightEffect] list cannot hold [IncHeartbeat].
 */
sealed class ConnectionEffect {
    /** Cross-cutting audit wrapper (entry / exit / transition / refusal). */
    data class Audit(val audit: AuditEffect) : ConnectionEffect()

    /**
     * Domain-only: bump heartbeat counter at the Connection boundary.
     * Declared by pure-ish Online handlers; never mutates during step evaluation.
     */
    data object IncHeartbeat : ConnectionEffect()
}

fun connTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): ConnectionEffect = ConnectionEffect.Audit(auditTransition(machine, from, event, to))

fun connEntry(
    machine: String,
    state: String,
    detail: String? = null,
): ConnectionEffect = ConnectionEffect.Audit(auditEntry(machine, state, detail))

fun connExit(
    machine: String,
    state: String,
    detail: String? = null,
): ConnectionEffect = ConnectionEffect.Audit(auditExit(machine, state, detail))

fun connRefusal(
    machine: String,
    state: String,
    event: String,
    reason: String,
): ConnectionEffect = ConnectionEffect.Audit(auditRefusal(machine, state, event, reason))

/**
 * PURPOSE: Local Connection interpreter — audit via cross-cutting path; heartbeat domain-local.
 *
 * [onIncHeartbeat] is **required** (non-null): domain [ConnectionEffect.IncHeartbeat] must not
 * silently vanish. Call sites that only need pure labels must not invoke this interpreter
 * (use [ConnectionEffect.exportLabel] / product evaluation instead).
 * Null [log] still drops audit only (observability optional); heartbeat always runs.
 */
fun interpretConnection(
    effects: List<ConnectionEffect>,
    log: TransitionLog? = null,
    onIncHeartbeat: () -> Unit,
) {
    for (effect in effects) {
        when (effect) {
            is ConnectionEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
            ConnectionEffect.IncHeartbeat -> onIncHeartbeat()
        }
    }
}

/** PURPOSE: Export short names (IncHeartbeat is domain-only; audit labels shared). */
fun ConnectionEffect.exportLabel(): String = when (this) {
    is ConnectionEffect.Audit -> audit.exportLabel()
    ConnectionEffect.IncHeartbeat -> "IncHeartbeat"
}
