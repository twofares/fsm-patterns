package fsm.sample.flat

import fsm.sample.effect.Effect
import fsm.sample.effect.EffectInterpreter
import fsm.sample.effect.StepResult
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Classical finite-state machine encoded as ordinary Kotlin — no engine.
 *
 * Discipline demonstrated:
 * - Sealed/closed state and event hierarchies (finite, compile-time known set)
 * - Pure transition of the form State + Event → State
 * - Pure step of the form State + Event → StepResult(next, effects)
 * - Exhaustive `when` so missing handlers are rejected without an `else`
 * - Boundary assigns state and interprets declared effects (logging)
 *
 * Domain vignette: a simple traffic light cycle. Flat (non-hierarchical) FSM
 * only — hierarchy is shown in the GoF/hierarchical package.
 */

/** Closed set of traffic-light modes. Sealed = no external subclasses. */
sealed class LightState {
    data object Red : LightState()
    data object Green : LightState()
    data object Yellow : LightState()

    /** Stable label for logging and tests. */
    fun label(): String = when (this) {
        Red -> "Red"
        Green -> "Green"
        Yellow -> "Yellow"
    }
}

/** Closed set of inputs that may affect the light. */
sealed class LightEvent {
    /** Advance one step in the fixed cycle. */
    data object Tick : LightEvent()

    /** Emergency: force red regardless of current color. */
    data object Emergency : LightEvent()

    fun label(): String = when (this) {
        Tick -> "Tick"
        Emergency -> "Emergency"
    }
}

/**
 * PURPOSE: Pure transition function State + Event → State.
 *
 * No side effects, no mutation, no I/O. All cycle rules live here so the
 * discipline is visible as ordinary sequential code and is unit-testable in
 * isolation. Exhaustive `when` on both sealed hierarchies is intentional:
 * adding a new state/event without updating this function fails compilation.
 *
 * Kept for export product evaluation (next-state-only tables).
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
 * PURPOSE: Pure step that declares next state + effects without running them.
 *
 * Uses [transition] for successor math; wraps a [Effect.LogTransition] so
 * tests can assert the effect list without a log or I/O.
 */
fun step(
    state: LightState,
    event: LightEvent,
    machineName: String = "TrafficLight",
): StepResult<LightState> {
    val next = transition(state, event)
    return StepResult(
        next = next,
        effects = listOf(
            Effect.LogTransition(
                machine = machineName,
                from = state.label(),
                event = event.label(),
                to = next.label(),
            ),
        ),
    )
}

/**
 * PURPOSE: Thin mutable holder that applies pure [step] and interprets effects.
 *
 * Separates pure step math from the single side-effect boundary
 * (assign current + [EffectInterpreter]). Not a reusable "engine" — just a
 * context that owns current state for demo/test convenience.
 */
class TrafficLight(
    initial: LightState = LightState.Red,
    private val log: TransitionLog? = null,
    private val machineName: String = "TrafficLight",
) {
    var current: LightState = initial
        private set

    /**
     * Apply one pure step, assign the result, and interpret declared effects.
     * @return the new current state (same reference as [current] after call)
     */
    fun handle(event: LightEvent): LightState {
        val result = step(current, event, machineName)
        current = result.next
        EffectInterpreter.apply(result.effects, log)
        return current
    }
}
