package fsm.sample.flat

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: TrafficLight **domain** effect ADT — teaches domain vs cross-cutting.
 *
 * - [Audit]: **cross-cutting** observability ([AuditEffect] → [TransitionLog])
 * - [DriveLamp]: **domain-specific** hardware command (drive the signal lamp).
 *   Only this machine declares it; Connection/File/Door cannot emit DriveLamp.
 *
 * Pure [step] declares both on every color change; [interpretLight] splits sinks:
 * audit → log, domain → [LightPort].
 */
sealed class LightEffect {
    /** Cross-cutting audit wrapper. */
    data class Audit(val audit: AuditEffect) : LightEffect()

    /**
     * Domain-only: drive the physical (or simulated) lamp to [colorLabel].
     * Declared by pure steps; applied only via [interpretLight] → [LightPort].
     */
    data class DriveLamp(val colorLabel: String) : LightEffect()
}

/** PURPOSE: In-memory domain port for lamp commands (no real hardware). */
class LightPort {
    private val _commands = mutableListOf<String>()
    val commands: List<String> get() = _commands.toList()

    fun drive(colorLabel: String) {
        _commands += colorLabel
    }

    fun clear() {
        _commands.clear()
    }

    fun snapshot(): String = "lampCommands=$commands"
}

/** PURPOSE: Declare a light transition audit without nesting constructors at call sites. */
fun lightTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): LightEffect = LightEffect.Audit(auditTransition(machine, from, event, to))

/**
 * PURPOSE: Local boundary for TrafficLight.
 * Cross-cutting audit → [log]; domain [DriveLamp] → [port].
 */
fun interpretLight(
    effects: List<LightEffect>,
    log: TransitionLog? = null,
    port: LightPort? = null,
) {
    for (effect in effects) {
        when (effect) {
            is LightEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
            is LightEffect.DriveLamp -> port?.drive(effect.colorLabel)
        }
    }
}

/** PURPOSE: Export short name (pure label for Effects column). */
fun LightEffect.exportLabel(): String = when (this) {
    is LightEffect.Audit -> audit.exportLabel()
    is LightEffect.DriveLamp -> "DriveLamp($colorLabel)"
}
