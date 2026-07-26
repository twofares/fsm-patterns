package fsm.sample.flat

import fsm.sample.machine.RecordingPort

/**
 * PURPOSE: TrafficLight **domain** effect ADT — hardware intents only.
 *
 * Cross-cutting is a separate `List<CrossCuttingEffect>` on [fsm.sample.effect.StepResult]
 * (audit / metrics / trace families) — not nested here. Domain case:
 * - [DriveLamp]: drive the signal lamp (this machine only).
 *
 * Pure [step] declares crossCutting + domain; boundary applies
 * [CrossCuttingInterpreter] then domain → [LightPort].
 *
 * Domain policy (total / engagement-driven): [DriveLamp] is declared for every accepted
 * step, including self-loops (e.g. Emergency when already Red). Domain tracks the
 * engagement, not only identity changes of state.
 */
sealed class LightEffect {
    /**
     * Domain-only: drive the physical (or simulated) lamp to [colorLabel].
     * Declared by pure steps; applied only via [applyDomainLight] → [LightPort].
     */
    data class DriveLamp(val colorLabel: String) : LightEffect()
}

/**
 * PURPOSE: In-memory domain port for lamp commands (no real hardware).
 * **Is** the [RecordingPort] bag — no private bag + pass-through shell.
 */
class LightPort : RecordingPort<String>() {
    val commands: List<String> get() = items

    fun drive(colorLabel: String) = record(colorLabel)

    fun snapshot(): String = "lampCommands=$commands"
}

/**
 * PURPOSE: Local domain boundary for TrafficLight.
 * Domain [DriveLamp] → [port]. Cross-cutting is applied separately via [CrossCuttingInterpreter].
 */
fun applyDomainLight(domain: List<LightEffect>, port: LightPort) {
    for (effect in domain) {
        when (effect) {
            is LightEffect.DriveLamp -> port.drive(effect.colorLabel)
        }
    }
}

/** PURPOSE: Export short name (pure label for Effects column). */
fun LightEffect.exportLabel(): String = when (this) {
    is LightEffect.DriveLamp -> "DriveLamp($colorLabel)"
}
