package fsm.sample.orthogonal

import fsm.sample.effect.CrossCuttingEffect
import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.CrossCuttingInterpreter
import fsm.sample.effect.StepResult
import fsm.sample.effect.acceptTransitionCrossCutting
import fsm.sample.effect.metricInc
import fsm.sample.effect.refuseCrossCutting
import fsm.sample.machine.applyAccepted

/**
 * PURPOSE: Orthogonal door — multi-family cross-cutting + domain actuators.
 *
 * Cross-cutting ([CrossCuttingEnv] shared by regions + container):
 * - Audit transitions / refusals
 * - Metrics (fsm.transitions, door.refuse, door.latch.*, door.swing.open)
 * - Trace on policy refuse (disparate family from audit)
 *
 * Domain → [DoorPort] actuators only on successful region steps.
 * Container refuse: cross-cutting only (no domain).
 *
 * Region boundaries use [applyAccepted]; accept preambles via
 * [acceptTransitionCrossCutting] with latch/swing extras; container guards via
 * [refuseCrossCutting] (`door.refuse` / `door.policy`).
 */

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

class LatchMachine(
    initial: LatchState = LatchState.Locked,
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    val port: DoorPort = DoorPort(),
    private val machineName: String = "Latch",
) {
    var current: LatchState = initial
        private set

    fun pureTransition(state: LatchState, event: LatchEvent): LatchState = when (event) {
        LatchEvent.Lock -> LatchState.Locked
        LatchEvent.Unlock -> LatchState.Unlocked
    }

    fun step(
        state: LatchState,
        event: LatchEvent,
    ): StepResult<LatchState, LatchEffect> {
        val next = pureTransition(state, event)
        val domain = when (event) {
            LatchEvent.Unlock -> LatchEffect.EnergizeStrike
            LatchEvent.Lock -> LatchEffect.EngageDeadbolt
        }
        val metricName = when (event) {
            LatchEvent.Unlock -> "door.latch.unlock"
            LatchEvent.Lock -> "door.latch.lock"
        }
        return StepResult(
            next = next,
            crossCutting = acceptTransitionCrossCutting(
                machine = machineName,
                from = state.label(),
                event = event.label(),
                to = next.label(),
                extras = listOf(metricInc(metricName)),
            ),
            domain = listOf(domain),
        )
    }

    fun handle(event: LatchEvent): LatchState {
        val result = step(current, event)
        applyAccepted(result, crossCutting, port, ::applyDomainLatch) { current = it }
        return current
    }
}

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

class SwingMachine(
    initial: SwingState = SwingState.Closed,
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    val port: DoorPort = DoorPort(),
    private val machineName: String = "Swing",
) {
    var current: SwingState = initial
        private set

    fun pureTransition(state: SwingState, event: SwingEvent): SwingState = when (event) {
        SwingEvent.OpenDoor -> SwingState.Open
        SwingEvent.CloseDoor -> SwingState.Closed
    }

    fun step(
        state: SwingState,
        event: SwingEvent,
    ): StepResult<SwingState, SwingEffect> {
        val next = pureTransition(state, event)
        val domain = if (event is SwingEvent.OpenDoor) {
            listOf(SwingEffect.SoundChime)
        } else {
            emptyList()
        }
        val extras = if (event is SwingEvent.OpenDoor) {
            listOf(metricInc("door.swing.open"))
        } else {
            emptyList()
        }
        return StepResult(
            next = next,
            crossCutting = acceptTransitionCrossCutting(
                machine = machineName,
                from = state.label(),
                event = event.label(),
                to = next.label(),
                extras = extras,
            ),
            domain = domain,
        )
    }

    fun handle(event: SwingEvent): SwingState {
        val result = step(current, event)
        applyAccepted(result, crossCutting, port, ::applyDomainSwing) { current = it }
        return current
    }
}

sealed class DoorEvent {
    data object Unlock : DoorEvent()
    data object Lock : DoorEvent()
    data object Open : DoorEvent()
    data object Close : DoorEvent()
    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: Orthogonal container — **shared** [CrossCuttingEnv] + [DoorPort]
 * across regions so metrics/trace/audit accumulate process-wide for the door.
 */
class DoorSystem(
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    val port: DoorPort = DoorPort(),
    val latch: LatchMachine = LatchMachine(crossCutting = crossCutting, port = port),
    val swing: SwingMachine = SwingMachine(crossCutting = crossCutting, port = port),
) {
    val machineName: String = "DoorSystem"

    fun configuration(): String =
        "latch=${latch.current.label()}, swing=${swing.current.label()}"

    /**
     * PURPOSE: Pure guard — multi-family **cross-cutting only** (audit + metric + trace).
     * No domain actuators on refuse. Built via [refuseCrossCutting]
     * (`door.refuse` metric, `door.policy` trace, detail = reason).
     */
    fun guardRefusalEffects(
        event: DoorEvent,
        latchState: LatchState = latch.current,
        swingState: SwingState = swing.current,
        config: String = "latch=${latchState.label()}, swing=${swingState.label()}",
    ): List<CrossCuttingEffect> = when (event) {
        DoorEvent.Open ->
            if (latchState is LatchState.Locked) {
                val reason = "cannot open while locked"
                refuseCrossCutting(
                    machine = machineName,
                    state = config,
                    event = event.label(),
                    reason = reason,
                    metricName = "door.refuse",
                    traceName = "door.policy",
                    traceDetail = reason,
                )
            } else {
                emptyList()
            }
        DoorEvent.Lock ->
            if (swingState is SwingState.Open) {
                val reason = "cannot lock while door is open"
                refuseCrossCutting(
                    machine = machineName,
                    state = config,
                    event = event.label(),
                    reason = reason,
                    metricName = "door.refuse",
                    traceName = "door.policy",
                    traceDetail = reason,
                )
            } else {
                emptyList()
            }
        DoorEvent.Unlock, DoorEvent.Close -> emptyList()
    }

    fun handle(event: DoorEvent): Boolean {
        val refusal = guardRefusalEffects(event)
        if (refusal.isNotEmpty()) {
            CrossCuttingInterpreter.apply(refusal, crossCutting)
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
