package fsm.sample.flat

import fsm.sample.effect.StepResult
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Classical finite-state machine encoded as ordinary Kotlin — no engine.
 *
 * Pure [step] returns next state + [LightEffect] list mixing:
 * - cross-cutting [LightEffect.Audit] (transition log)
 * - domain [LightEffect.DriveLamp] (signal hardware intent)
 * Boundary [handle] assigns state then [interpretLight] to dual sinks (log + [LightPort]).
 */

/** Closed set of traffic-light modes. Sealed = no external subclasses. */
sealed class LightState {
    data object Red : LightState()
    data object Green : LightState()
    data object Yellow : LightState()

    fun label(): String = when (this) {
        Red -> "Red"
        Green -> "Green"
        Yellow -> "Yellow"
    }
}

/** Closed set of inputs that may affect the light. */
sealed class LightEvent {
    data object Tick : LightEvent()
    data object Emergency : LightEvent()

    fun label(): String = when (this) {
        Tick -> "Tick"
        Emergency -> "Emergency"
    }
}

/**
 * PURPOSE: Pure transition function State + Event → State.
 * No side effects. Export and [step] share this math.
 */
fun transition(state: LightState, event: LightEvent): LightState = when (event) {
    LightEvent.Tick -> when (state) {
        LightState.Red -> LightState.Green
        LightState.Green -> LightState.Yellow
        LightState.Yellow -> LightState.Red
    }
    LightEvent.Emergency -> LightState.Red
}

/**
 * PURPOSE: Pure step — next + audit (cross-cutting) + DriveLamp (domain).
 * Does not touch log or [LightPort].
 */
fun step(
    state: LightState,
    event: LightEvent,
    machineName: String = "TrafficLight",
): StepResult<LightState, LightEffect> {
    val next = transition(state, event)
    return StepResult(
        next = next,
        effects = listOf(
            lightTransition(
                machine = machineName,
                from = state.label(),
                event = event.label(),
                to = next.label(),
            ),
            LightEffect.DriveLamp(next.label()),
        ),
    )
}

/**
 * PURPOSE: Boundary holder — assign state, then interpret to log + port.
 */
class TrafficLight(
    initial: LightState = LightState.Red,
    private val log: TransitionLog? = null,
    val port: LightPort = LightPort(),
    private val machineName: String = "TrafficLight",
) {
    var current: LightState = initial
        private set

    fun handle(event: LightEvent): LightState {
        val result = step(current, event, machineName)
        current = result.next
        interpretLight(result.effects, log, port)
        return current
    }
}
