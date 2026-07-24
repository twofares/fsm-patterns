package fsm.sample.csp

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: FileProtocol **domain** effect ADT (CSP accept / refuse observability).
 *
 * Pure [FileProtocol.stepOffer] declares accept ([Audit] transition) or refuse
 * ([Audit] refusal). Control outcome stays on sealed [OfferStep]; effects are
 * observational only. Cross-cutting audit is applied via [AuditInterpreter].
 */
sealed class FileEffect {
    /** Cross-cutting audit (LogTransition on accept, LogRefusal on refuse). */
    data class Audit(val audit: AuditEffect) : FileEffect()
}

/** PURPOSE: CSP accept → transition audit. */
fun fileTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): FileEffect = FileEffect.Audit(auditTransition(machine, from, event, to))

/** PURPOSE: CSP refuse → refusal audit. */
fun fileRefusal(
    machine: String,
    state: String,
    event: String,
    reason: String,
): FileEffect = FileEffect.Audit(auditRefusal(machine, state, event, reason))

/**
 * PURPOSE: Local boundary for FileProtocol — maps audit effects to [TransitionLog].
 */
fun interpretFile(effects: List<FileEffect>, log: TransitionLog? = null) {
    for (effect in effects) {
        when (effect) {
            is FileEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
        }
    }
}

/** PURPOSE: Export short name for Effects column. */
fun FileEffect.exportLabel(): String = when (this) {
    is FileEffect.Audit -> audit.exportLabel()
}
