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
 * Pure [step] / [automaticStep] declare crossCutting + domain; boundary applies
 * [CrossCuttingInterpreter] then domain → multi-port ([LightPort] + [TimerPort]).
 *
 * Domain policy:
 * - Enter dwell color (Red/Green/Yellow): Cancel then Arm then DriveLamp.
 * - Enter Flash (Emergency or Flash self-loop): Cancel then DriveLamp(Flash); no Arm.
 * Domain tracks engagement (including self-loops), not only identity changes of state.
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

/**
 * Dwell delay for a color state (pure table; no clock).
 * [LightState.Flash] has no dwell — [automaticStep] owns exit.
 */
fun dwellMsFor(state: LightState): Long? = when (state) {
    LightState.Red -> DWELL_RED_MS
    LightState.Green -> DWELL_GREEN_MS
    LightState.Yellow -> DWELL_YELLOW_MS
    LightState.Flash -> null
}

fun cancelDwell(): LightEffect = LightEffect.Timer(TimerIntent.Cancel(LIGHT_DWELL_ID))

fun armDwell(forState: LightState): LightEffect {
    val ms = dwellMsFor(forState) ?: error("no dwell for $forState")
    return LightEffect.Timer(
        TimerIntent.Arm(
            id = LIGHT_DWELL_ID,
            delayMs = ms,
            eventKey = LightEvent.DwellElapsed.label(),
        ),
    )
}

/** Domain for entering a dwell color: always Cancel then Arm then DriveLamp. */
fun domainEnterDwellColor(color: LightState): List<LightEffect> = listOf(
    cancelDwell(),
    armDwell(color),
    LightEffect.DriveLamp(color.label()),
)

/** Domain for entering Flash: Cancel dwell (idempotent); DriveLamp Flash; no Arm. */
fun domainEnterFlash(): List<LightEffect> = listOf(
    cancelDwell(),
    LightEffect.DriveLamp(LightState.Flash.label()),
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
            is LightEffect.Timer -> timers.apply(effect.intent)
        }
    }
}

/** PURPOSE: Export short name (pure label for Effects column). */
fun LightEffect.exportLabel(): String = when (this) {
    is LightEffect.DriveLamp -> "DriveLamp($colorLabel)"
    is LightEffect.Timer -> intent.exportLabel()
}
