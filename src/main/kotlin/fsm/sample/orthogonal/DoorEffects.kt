package fsm.sample.orthogonal

/**
 * PURPOSE: Orthogonal door **domain** effects — region actuators only.
 *
 * Cross-cutting audit is a plain `List<AuditEffect>` on [fsm.sample.effect.StepResult]
 * (regions) or container guard refusals — never nested on these ADTs.
 *
 * Domain-specific (region success; container refusals are audit-only):
 * - [LatchEffect.EnergizeStrike] on Unlock engagement
 * - [LatchEffect.EngageDeadbolt] on Lock engagement
 * - [SwingEffect.SoundChime] on OpenDoor engagement
 *
 * Shared [DoorPort] collects actuators for the whole door system.
 * No thin `DoorEffect` ADT: guard refusals return `List<AuditEffect>` directly.
 *
 * Domain policy (total / engagement-driven): actuators fire for every accepted
 * region engagement, including self-loops (e.g. Lock while already Locked).
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
    /** Domain: unlock hardware strike. */
    data object EnergizeStrike : LatchEffect()

    /** Domain: engage deadbolt. */
    data object EngageDeadbolt : LatchEffect()
}

/**
 * PURPOSE: Local domain boundary for Latch.
 * Audit is applied separately via [fsm.sample.effect.AuditInterpreter].
 */
fun applyDomainLatch(domain: List<LatchEffect>, port: DoorPort) {
    for (effect in domain) {
        when (effect) {
            LatchEffect.EnergizeStrike -> port.record(DoorActuator.EnergizeStrike)
            LatchEffect.EngageDeadbolt -> port.record(DoorActuator.EngageDeadbolt)
        }
    }
}

fun LatchEffect.exportLabel(): String = when (this) {
    LatchEffect.EnergizeStrike -> "EnergizeStrike"
    LatchEffect.EngageDeadbolt -> "EngageDeadbolt"
}

// --- Swing -------------------------------------------------------------------

sealed class SwingEffect {
    /** Domain: chime when door opens. */
    data object SoundChime : SwingEffect()
}

/**
 * PURPOSE: Local domain boundary for Swing.
 * Audit is applied separately via [fsm.sample.effect.AuditInterpreter].
 */
fun applyDomainSwing(domain: List<SwingEffect>, port: DoorPort) {
    for (effect in domain) {
        when (effect) {
            SwingEffect.SoundChime -> port.record(DoorActuator.SoundChime)
        }
    }
}

fun SwingEffect.exportLabel(): String = when (this) {
    SwingEffect.SoundChime -> "SoundChime"
}
