package fsm.sample.orthogonal

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Orthogonal door effects — region audit + **domain** actuators.
 *
 * Cross-cutting: [LatchEffect.Audit] / [SwingEffect.Audit] / [DoorEffect.Audit]
 * → same [TransitionLog] format as other machines.
 *
 * Domain-specific (region success only; container refusals are audit-only):
 * - [LatchEffect.EnergizeStrike] on successful Unlock
 * - [LatchEffect.EngageDeadbolt] on successful Lock
 * - [SwingEffect.SoundChime] on successful Open
 *
 * Shared [DoorPort] collects actuators for the whole door system.
 */

/** PURPOSE: Domain actuator commands (no real hardware). */
sealed class DoorActuator {
    data object EnergizeStrike : DoorActuator()
    data object EngageDeadbolt : DoorActuator()
    data object SoundChime : DoorActuator()
    fun label(): String = this::class.simpleName ?: toString()
}

/** PURPOSE: In-memory domain port for door actuators. */
class DoorPort {
    private val _actuators = mutableListOf<DoorActuator>()
    val actuators: List<DoorActuator> get() = _actuators.toList()

    fun record(a: DoorActuator) {
        _actuators += a
    }

    fun clear() {
        _actuators.clear()
    }

    fun snapshot(): String = "actuators=${actuators.map { it.label() }}"
}

// --- Latch -------------------------------------------------------------------

sealed class LatchEffect {
    data class Audit(val audit: AuditEffect) : LatchEffect()

    /** Domain: unlock hardware strike. */
    data object EnergizeStrike : LatchEffect()

    /** Domain: engage deadbolt. */
    data object EngageDeadbolt : LatchEffect()
}

fun latchTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): LatchEffect = LatchEffect.Audit(auditTransition(machine, from, event, to))

fun interpretLatch(
    effects: List<LatchEffect>,
    log: TransitionLog? = null,
    port: DoorPort? = null,
) {
    for (effect in effects) {
        when (effect) {
            is LatchEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
            LatchEffect.EnergizeStrike -> port?.record(DoorActuator.EnergizeStrike)
            LatchEffect.EngageDeadbolt -> port?.record(DoorActuator.EngageDeadbolt)
        }
    }
}

fun LatchEffect.exportLabel(): String = when (this) {
    is LatchEffect.Audit -> audit.exportLabel()
    LatchEffect.EnergizeStrike -> "EnergizeStrike"
    LatchEffect.EngageDeadbolt -> "EngageDeadbolt"
}

// --- Swing -------------------------------------------------------------------

sealed class SwingEffect {
    data class Audit(val audit: AuditEffect) : SwingEffect()

    /** Domain: chime when door opens. */
    data object SoundChime : SwingEffect()
}

fun swingTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): SwingEffect = SwingEffect.Audit(auditTransition(machine, from, event, to))

fun interpretSwing(
    effects: List<SwingEffect>,
    log: TransitionLog? = null,
    port: DoorPort? = null,
) {
    for (effect in effects) {
        when (effect) {
            is SwingEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
            SwingEffect.SoundChime -> port?.record(DoorActuator.SoundChime)
        }
    }
}

fun SwingEffect.exportLabel(): String = when (this) {
    is SwingEffect.Audit -> audit.exportLabel()
    SwingEffect.SoundChime -> "SoundChime"
}

// --- Container (guards: audit only) ------------------------------------------

/**
 * PURPOSE: DoorSystem container effects — **guard refusals are audit-only**.
 * No domain actuators fire when policy blocks Open/Lock.
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
