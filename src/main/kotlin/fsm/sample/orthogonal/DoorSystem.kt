package fsm.sample.orthogonal

import fsm.sample.effect.StepResult
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Orthogonal Component pattern — concurrent independent state machines
 * composed without a multi-region statechart engine.
 *
 * Research discipline: "compose independent state-machine objects, dispatch
 * events into components, and post events back to the container, instead of
 * one multi-region engine." Orthogonality avoids classical product-state
 * explosion: latch and swing are independent regions.
 *
 * Domain vignette: a door with two independent regions:
 *   Region A (LatchMachine):  Locked ↔ Unlocked
 *   Region B (SwingMachine):  Closed ↔ Open
 *
 * Container [DoorSystem] dispatches external events to the appropriate component(s).
 * Component steps declare [LatchEffect]/[SwingEffect] transition audits; container
 * guards declare [DoorEffect] refusals via pure [guardRefusalEffects]. Cross-cutting
 * audit format is shared ([AuditInterpreter] → [TransitionLog]); effect ADTs stay
 * per-region so domain types do not leak across machines.
 */

// --- Region A: Latch ---------------------------------------------------------

sealed class LatchState {
    data object Locked : LatchState()
    data object Unlocked : LatchState()
    fun label(): String = when (this) {
        Locked -> "Locked"
        Unlocked -> "Unlocked"
    }
}

sealed class LatchEvent {
    data object Lock : LatchEvent()
    data object Unlock : LatchEvent()
    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: Independent component machine for the latch region.
 * Pure transition + pure step with [LatchEffect] transition audit; boundary interprets.
 * Kept separate so the orthogonal container composes objects, not product states.
 */
class LatchMachine(
    initial: LatchState = LatchState.Locked,
    private val log: TransitionLog? = null,
    private val machineName: String = "Latch",
) {
    var current: LatchState = initial
        private set

    fun pureTransition(state: LatchState, event: LatchEvent): LatchState = when (event) {
        LatchEvent.Lock -> LatchState.Locked
        LatchEvent.Unlock -> LatchState.Unlocked
    }

    /**
     * PURPOSE: Pure step for the latch region (next + transition audit).
     * Export product tables call [step] so Effects columns match pure declarations.
     */
    fun step(
        state: LatchState,
        event: LatchEvent,
    ): StepResult<LatchState, LatchEffect> {
        val next = pureTransition(state, event)
        return StepResult(
            next = next,
            effects = listOf(
                latchTransition(
                    machine = machineName,
                    from = state.label(),
                    event = event.label(),
                    to = next.label(),
                ),
            ),
        )
    }

    fun handle(event: LatchEvent): LatchState {
        val result = step(current, event)
        current = result.next
        interpretLatch(result.effects, log)
        return current
    }
}

// --- Region B: Swing ---------------------------------------------------------

sealed class SwingState {
    data object Closed : SwingState()
    data object Open : SwingState()
    fun label(): String = when (this) {
        Closed -> "Closed"
        Open -> "Open"
    }
}

sealed class SwingEvent {
    data object OpenDoor : SwingEvent()
    data object CloseDoor : SwingEvent()
    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: Independent component machine for the swing (open/closed) region.
 * Open-while-locked is refused by the container via a guard, not by merging
 * regions into one product machine.
 */
class SwingMachine(
    initial: SwingState = SwingState.Closed,
    private val log: TransitionLog? = null,
    private val machineName: String = "Swing",
) {
    var current: SwingState = initial
        private set

    fun pureTransition(state: SwingState, event: SwingEvent): SwingState = when (event) {
        SwingEvent.OpenDoor -> SwingState.Open
        SwingEvent.CloseDoor -> SwingState.Closed
    }

    /** PURPOSE: Pure step for the swing region (next + transition audit). */
    fun step(
        state: SwingState,
        event: SwingEvent,
    ): StepResult<SwingState, SwingEffect> {
        val next = pureTransition(state, event)
        return StepResult(
            next = next,
            effects = listOf(
                swingTransition(
                    machine = machineName,
                    from = state.label(),
                    event = event.label(),
                    to = next.label(),
                ),
            ),
        )
    }

    fun handle(event: SwingEvent): SwingState {
        val result = step(current, event)
        current = result.next
        interpretSwing(result.effects, log)
        return current
    }
}

// --- External events for the container ---------------------------------------

/**
 * PURPOSE: Container-level events. The orthogonal system maps these onto
 * component events (and optional guarded refusals).
 */
sealed class DoorEvent {
    data object Unlock : DoorEvent()
    data object Lock : DoorEvent()
    data object Open : DoorEvent()
    data object Close : DoorEvent()
    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: Orthogonal container — owns two independent component machines.
 *
 * Dispatch policy:
 * - Unlock/Lock → LatchMachine (lock refused while swing is Open)
 * - Open → SwingMachine only if latch is Unlocked; else refuse (guarded coupling)
 * - Close → SwingMachine
 *
 * Product configuration is observed as (latch × swing) without implementing a
 * flat 4-state product machine. Guards use pure [guardRefusalEffects]; boundary
 * [handle] interprets refusals or delegates to component [handle].
 */
class DoorSystem(
    val log: TransitionLog = TransitionLog(),
    val latch: LatchMachine = LatchMachine(log = log),
    val swing: SwingMachine = SwingMachine(log = log),
) {
    val machineName: String = "DoorSystem"

    /** Combined configuration label for demo/tests (observability only). */
    fun configuration(): String =
        "latch=${latch.current.label()}, swing=${swing.current.label()}"

    /**
     * PURPOSE: Pure container guard — returns [DoorEffect] refusals when policy
     * blocks [event], empty list when the event may be dispatched to a region.
     * Does not mutate state or log; tests assert the list without the boundary.
     */
    fun guardRefusalEffects(
        event: DoorEvent,
        latchState: LatchState = latch.current,
        swingState: SwingState = swing.current,
        config: String = "latch=${latchState.label()}, swing=${swingState.label()}",
    ): List<DoorEffect> = when (event) {
        DoorEvent.Open ->
            if (latchState is LatchState.Locked) {
                listOf(
                    doorRefusal(
                        machine = machineName,
                        state = config,
                        event = event.label(),
                        reason = "cannot open while locked",
                    ),
                )
            } else {
                emptyList()
            }
        DoorEvent.Lock ->
            if (swingState is SwingState.Open) {
                listOf(
                    doorRefusal(
                        machine = machineName,
                        state = config,
                        event = event.label(),
                        reason = "cannot lock while door is open",
                    ),
                )
            } else {
                emptyList()
            }
        DoorEvent.Unlock, DoorEvent.Close -> emptyList()
    }

    /**
     * Dispatch an external event into the appropriate orthogonal component(s).
     * @return true if the event was accepted by a component (or fully applied)
     */
    fun handle(event: DoorEvent): Boolean {
        val refusal = guardRefusalEffects(event)
        if (refusal.isNotEmpty()) {
            interpretDoor(refusal, log)
            return false
        }
        return when (event) {
            DoorEvent.Unlock -> {
                latch.handle(LatchEvent.Unlock)
                true
            }
            DoorEvent.Lock -> {
                latch.handle(LatchEvent.Lock)
                true
            }
            DoorEvent.Open -> {
                swing.handle(SwingEvent.OpenDoor)
                true
            }
            DoorEvent.Close -> {
                swing.handle(SwingEvent.CloseDoor)
                true
            }
        }
    }
}
