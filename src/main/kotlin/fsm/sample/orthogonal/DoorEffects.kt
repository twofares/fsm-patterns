package fsm.sample.orthogonal

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Latch region **domain** effect ADT — region transition audit only.
 * Cross-cutting audit applied via [AuditInterpreter] (same log format as peers).
 */
sealed class LatchEffect {
    data class Audit(val audit: AuditEffect) : LatchEffect()
}

fun latchTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): LatchEffect = LatchEffect.Audit(auditTransition(machine, from, event, to))

fun interpretLatch(effects: List<LatchEffect>, log: TransitionLog? = null) {
    for (effect in effects) {
        when (effect) {
            is LatchEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
        }
    }
}

fun LatchEffect.exportLabel(): String = when (this) {
    is LatchEffect.Audit -> audit.exportLabel()
}

/**
 * PURPOSE: Swing region **domain** effect ADT — region transition audit only.
 */
sealed class SwingEffect {
    data class Audit(val audit: AuditEffect) : SwingEffect()
}

fun swingTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): SwingEffect = SwingEffect.Audit(auditTransition(machine, from, event, to))

fun interpretSwing(effects: List<SwingEffect>, log: TransitionLog? = null) {
    for (effect in effects) {
        when (effect) {
            is SwingEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
        }
    }
}

fun SwingEffect.exportLabel(): String = when (this) {
    is SwingEffect.Audit -> audit.exportLabel()
}

/**
 * PURPOSE: DoorSystem container **domain** effect ADT — guard refusal audit.
 *
 * Orthogonal guards declare [Audit] LogRefusal when policy blocks dispatch;
 * region machines keep their own [LatchEffect] / [SwingEffect] lists.
 */
sealed class DoorEffect {
    data class Audit(val audit: AuditEffect) : DoorEffect()
}

fun doorRefusal(
    machine: String,
    state: String,
    event: String,
    reason: String,
): DoorEffect = DoorEffect.Audit(auditRefusal(machine, state, event, reason))

fun interpretDoor(effects: List<DoorEffect>, log: TransitionLog? = null) {
    for (effect in effects) {
        when (effect) {
            is DoorEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
        }
    }
}

fun DoorEffect.exportLabel(): String = when (this) {
    is DoorEffect.Audit -> audit.exportLabel()
}
