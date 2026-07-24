package fsm.sample.orthogonal

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.StepResult
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Orthogonal Component pattern — concurrent independent state machines
 * composed without a multi-region statechart engine.
 *
 * Effects:
 * - **Cross-cutting** region/container audit → shared [TransitionLog]
 * - **Domain** actuators (strike / deadbolt / chime) → shared [DoorPort]
 * - Container **refusals are audit-only** (`List<AuditEffect>`, no DoorEffect ADT)
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
 * PURPOSE: Independent latch region machine.
 * Pure [step] declares audit + domain actuator for every engagement (total-step).
 */
class LatchMachine(
    initial: LatchState = LatchState.Locked,
    private val log: TransitionLog? = null,
    val port: DoorPort = DoorPort(),
    private val machineName: String = "Latch",
) {
    var current: LatchState = initial
        private set

    fun pureTransition(state: LatchState, event: LatchEvent): LatchState = when (event) {
        LatchEvent.Lock -> LatchState.Locked
        LatchEvent.Unlock -> LatchState.Unlocked
    }

    /**
     * PURPOSE: Pure step — audit always; domain actuator for target mode.
     * Unlock → EnergizeStrike; Lock → EngageDeadbolt (engagement-driven, even
     * if already that mode).
     */
    fun step(
        state: LatchState,
        event: LatchEvent,
    ): StepResult<LatchState, LatchEffect> {
        val next = pureTransition(state, event)
        val domain = when (event) {
            LatchEvent.Unlock -> LatchEffect.EnergizeStrike
            LatchEvent.Lock -> LatchEffect.EngageDeadbolt
        }
        return StepResult(
            next = next,
            audit = listOf(
                auditTransition(
                    machine = machineName,
                    from = state.label(),
                    event = event.label(),
                    to = next.label(),
                ),
            ),
            domain = listOf(domain),
        )
    }

    fun handle(event: LatchEvent): LatchState {
        val result = step(current, event)
        current = result.next
        AuditInterpreter.apply(result.audit, log)
        applyDomainLatch(result.domain, port)
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
 * PURPOSE: Independent swing region machine.
 * OpenDoor → SoundChime (domain); CloseDoor → audit only.
 */
class SwingMachine(
    initial: SwingState = SwingState.Closed,
    private val log: TransitionLog? = null,
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
        return StepResult(
            next = next,
            audit = listOf(
                auditTransition(
                    machine = machineName,
                    from = state.label(),
                    event = event.label(),
                    to = next.label(),
                ),
            ),
            domain = domain,
        )
    }

    fun handle(event: SwingEvent): SwingState {
        val result = step(current, event)
        current = result.next
        AuditInterpreter.apply(result.audit, log)
        applyDomainSwing(result.domain, port)
        return current
    }
}

// --- External events for the container ---------------------------------------

sealed class DoorEvent {
    data object Unlock : DoorEvent()
    data object Lock : DoorEvent()
    data object Open : DoorEvent()
    data object Close : DoorEvent()
    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: Orthogonal container — shared log + [DoorPort] for both regions.
 * Refused Open/Lock: audit only, port unchanged. No thin DoorEffect ADT.
 */
class DoorSystem(
    val log: TransitionLog = TransitionLog(),
    val port: DoorPort = DoorPort(),
    val latch: LatchMachine = LatchMachine(log = log, port = port),
    val swing: SwingMachine = SwingMachine(log = log, port = port),
) {
    val machineName: String = "DoorSystem"

    fun configuration(): String =
        "latch=${latch.current.label()}, swing=${swing.current.label()}"

    /**
     * PURPOSE: Pure container guard — refusal effects are **audit-only**.
     * Returns plain [AuditEffect] list (no one-arm DoorEffect sealed type).
     */
    fun guardRefusalEffects(
        event: DoorEvent,
        latchState: LatchState = latch.current,
        swingState: SwingState = swing.current,
        config: String = "latch=${latchState.label()}, swing=${swingState.label()}",
    ): List<AuditEffect> = when (event) {
        DoorEvent.Open ->
            if (latchState is LatchState.Locked) {
                listOf(
                    auditRefusal(
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
                    auditRefusal(
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

    fun handle(event: DoorEvent): Boolean {
        val refusal = guardRefusalEffects(event)
        if (refusal.isNotEmpty()) {
            AuditInterpreter.apply(refusal, log)
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
