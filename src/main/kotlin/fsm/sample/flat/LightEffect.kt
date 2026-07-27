package fsm.sample.flat

import fsm.sample.effect.TimerIntent
import fsm.sample.effect.exportLabel
import fsm.sample.machine.RecordingPort
import fsm.sample.machine.TimerPort

/**
 * PURPOSE: TrafficLight **domain** effect ADT — hardware + scheduling intents.
 *
 * Cross-cutting is a separate `List<CrossCuttingEffect>` on [fsm.sample.effect.StepResult]
 * (audit / metrics / trace families) — not nested here. Domain cases:
 * - [DriveLamp]: drive the signal lamp (this machine only).
 * - [Timer]: domain-like scheduling intent applied to [TimerPort], not [LightPort].
 *
 * Pure [step] declares crossCutting + domain; boundary applies
 * [CrossCuttingInterpreter] then domain → multi-port ([LightPort] + [TimerPort]).
 *
 * Domain policy (total / engagement-driven): [DriveLamp] is declared for every accepted
 * step, including self-loops (e.g. Emergency when already Red). Domain tracks the
 * engagement, not only identity changes of state. Color advances also Cancel then Arm
 * the single dwell timer ([LIGHT_DWELL_ID]).
 */
sealed class LightEffect {
    /**
     * Domain-only: drive the physical (or simulated) lamp to [colorLabel].
     * Declared by pure steps; applied only via [applyDomainLight] → [LightPort].
     */
    data class DriveLamp(val colorLabel: String) : LightEffect()

    /** Domain scheduling intent — applied to [TimerPort], not [LightPort]. */
    data class Timer(val intent: TimerIntent) : LightEffect()
}

/** Single dwell timer id for this machine (teaching default: one id per machine). */
const val LIGHT_DWELL_ID = "light.dwell"

/** Dwell delays (ms) — pure constants, not read from a clock. */
const val DWELL_RED_MS = 30_000L
const val DWELL_GREEN_MS = 25_000L
const val DWELL_YELLOW_MS = 5_000L

/** Dwell delay for a color state (pure table; no clock). */
fun dwellMsFor(state: LightState): Long = when (state) {
    LightState.Red -> DWELL_RED_MS
    LightState.Green -> DWELL_GREEN_MS
    LightState.Yellow -> DWELL_YELLOW_MS
}

fun cancelDwell(): LightEffect = LightEffect.Timer(TimerIntent.Cancel(LIGHT_DWELL_ID))

fun armDwell(forState: LightState): LightEffect =
    LightEffect.Timer(
        TimerIntent.Arm(
            id = LIGHT_DWELL_ID,
            delayMs = dwellMsFor(forState),
            eventKey = LightEvent.DwellElapsed.label(),
        ),
    )

/** Domain for entering a dwell color: always Cancel then Arm then DriveLamp. */
fun domainEnterDwellColor(color: LightState): List<LightEffect> = listOf(
    cancelDwell(),
    armDwell(color),
    LightEffect.DriveLamp(color.label()),
)

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
 * PURPOSE: Local domain boundary for TrafficLight — multi-port fan-out of one domain list.
 * [DriveLamp] → [port]; [Timer] → [timers] **inline in list order** (not a third StepResult field).
 * Cross-cutting is applied separately via [CrossCuttingInterpreter].
 */
fun applyDomainLight(domain: List<LightEffect>, port: LightPort, timers: TimerPort) {
    for (effect in domain) {
        when (effect) {
            is LightEffect.DriveLamp -> port.drive(effect.colorLabel)
            is LightEffect.Timer -> timers.apply(listOf(effect.intent))
        }
    }
}

/** PURPOSE: Export short name (pure label for Effects column). */
fun LightEffect.exportLabel(): String = when (this) {
    is LightEffect.DriveLamp -> "DriveLamp($colorLabel)"
    is LightEffect.Timer -> intent.exportLabel()
}
