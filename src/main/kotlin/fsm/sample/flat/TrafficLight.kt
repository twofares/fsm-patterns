package fsm.sample.flat

import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Classical finite-state machine encoded as ordinary Kotlin — no engine.
 *
 * Discipline demonstrated:
 * - Sealed/closed state and event hierarchies (finite, compile-time known set)
 * - Pure transition of the form State + Event → State
 * - Exhaustive `when` so missing handlers are rejected without an `else`
 * - Optional logging of every successful mode change at the assignment boundary
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
 * PURPOSE: Thin mutable holder that applies pure [transition] and logs changes.
 *
 * Separates pure transition math from the single side-effect boundary
 * (log assignment of current state). Not a reusable "engine" — just a
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
     * Apply one pure transition, assign the result, and record the mode change.
     * @return the new current state (same reference as [current] after call)
     */
    fun handle(event: LightEvent): LightState {
        val from = current
        val next = transition(from, event)
        current = next
        log?.recordTransition(
            machine = machineName,
            from = from.label(),
            event = event.label(),
            to = next.label(),
        )
        return current
    }
}
