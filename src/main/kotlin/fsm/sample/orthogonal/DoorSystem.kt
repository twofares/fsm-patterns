package fsm.sample.orthogonal

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
 * Consistent logging records every region mode change through [TransitionLog].
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
 * Pure transition + assignment logging — same flat discipline as TrafficLight,
 * kept separate so the orthogonal container composes objects, not product states.
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

    fun handle(event: LatchEvent): LatchState {
        val from = current
        val next = pureTransition(from, event)
        current = next
        log?.recordTransition(machineName, from.label(), event.label(), next.label())
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

    fun handle(event: SwingEvent): SwingState {
        val from = current
        val next = pureTransition(from, event)
        current = next
        log?.recordTransition(machineName, from.label(), event.label(), next.label())
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
 * flat 4-state product machine.
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
     * Dispatch an external event into the appropriate orthogonal component(s).
     * @return true if the event was accepted by a component (or fully applied)
     */
    fun handle(event: DoorEvent): Boolean {
        return when (event) {
            DoorEvent.Unlock -> {
                latch.handle(LatchEvent.Unlock)
                true
            }
            DoorEvent.Lock -> {
                // Policy: cannot lock while open — refuse at container level.
                if (swing.current is SwingState.Open) {
                    log.recordRefusal(
                        machineName,
                        configuration(),
                        event.label(),
                        reason = "cannot lock while door is open",
                    )
                    false
                } else {
                    latch.handle(LatchEvent.Lock)
                    true
                }
            }
            DoorEvent.Open -> {
                // Guarded coupling between regions without product-state explosion.
                if (latch.current is LatchState.Locked) {
                    log.recordRefusal(
                        machineName,
                        configuration(),
                        event.label(),
                        reason = "cannot open while locked",
                    )
                    false
                } else {
                    swing.handle(SwingEvent.OpenDoor)
                    true
                }
            }
            DoorEvent.Close -> {
                swing.handle(SwingEvent.CloseDoor)
                true
            }
        }
    }
}
