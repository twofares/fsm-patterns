package fsm.sample.flat

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.StepResult
import fsm.sample.effect.acceptTransitionCrossCutting
import fsm.sample.effect.metricInc
import fsm.sample.effect.traceSpan
import fsm.sample.machine.TimerPort
import fsm.sample.machine.VirtualClock
import fsm.sample.machine.applyAccepted

/**
 * PURPOSE: Flat FSM — pure step declares **multiple cross-cutting families** + domain.
 *
 * Cross-cutting (shared [CrossCuttingEnv]):
 * - Audit.LogTransition — mode change log
 * - Metric.Inc("fsm.transitions") — process-wide transition counter
 * - Metric.Inc("light.ticks") or Trace.Span on Emergency — disparate families
 *
 * Domain: [LightEffect.DriveLamp] → [LightPort]; [LightEffect.Timer] → [TimerPort]
 * (one domain list, multi-port apply — not a third [StepResult] field).
 *
 * Boundary uses [applyAccepted]; accept preamble via [acceptTransitionCrossCutting]
 * with machine-specific extras (`light.ticks` / `light.emergency`).
 *
 * Dwell timers: pure step declares Cancel then Arm([LIGHT_DWELL_ID]) on every color
 * engagement; [TimerPort] schedules via [VirtualClock]; due timeouts re-enter as
 * [LightEvent.DwellElapsed]. No arm on construction (K16).
 */

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

sealed class LightEvent {
    data object Tick : LightEvent()
    data object Emergency : LightEvent()
    /** Timer re-entry when the dwell armed for the current color elapses. */
    data object DwellElapsed : LightEvent()

    fun label(): String = when (this) {
        Tick -> "Tick"
        Emergency -> "Emergency"
        DwellElapsed -> "DwellElapsed"
    }
}

/** Pure mapper: [TimerIntent.Arm.eventKey] → sealed [LightEvent] (no reflection). */
fun lightEventFromTimerKey(eventKey: String): LightEvent? = when (eventKey) {
    LightEvent.DwellElapsed.label() -> LightEvent.DwellElapsed
    else -> null
}

fun transition(state: LightState, event: LightEvent): LightState = when (event) {
    LightEvent.Tick, LightEvent.DwellElapsed -> when (state) {
        LightState.Red -> LightState.Green
        LightState.Green -> LightState.Yellow
        LightState.Yellow -> LightState.Red
    }
    LightEvent.Emergency -> LightState.Red
}

/**
 * PURPOSE: Pure step — next + multi-family crossCutting + domain (Cancel+Arm+DriveLamp).
 * Does not touch [CrossCuttingEnv], [LightPort], [TimerPort], or clocks.
 */
fun step(
    state: LightState,
    event: LightEvent,
    machineName: String = "TrafficLight",
): StepResult<LightState, LightEffect> {
    val next = transition(state, event)
    val extras = when (event) {
        LightEvent.Tick, LightEvent.DwellElapsed -> listOf(metricInc("light.ticks"))
        LightEvent.Emergency -> listOf(traceSpan("light.emergency", "forced ${next.label()}"))
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
        // Every engagement into a dwell color: Cancel then Arm then DriveLamp (list order load-bearing).
        domain = domainEnterDwellColor(next),
    )
}

/**
 * PURPOSE: Boundary — assign state; apply cross-cutting env + multi-port domain
 * via [applyAccepted] (assign → CC → domain). Domain lambda closes over [timers].
 *
 * No init arm for Red dwell (K16): [timers] starts empty until first engagement.
 */
class TrafficLight(
    initial: LightState = LightState.Red,
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    val port: LightPort = LightPort(),
    val clock: VirtualClock = VirtualClock(),
    val timers: TimerPort = TimerPort(clock),
    private val machineName: String = "TrafficLight",
) {
    var current: LightState = initial
        private set

    fun handle(event: LightEvent): LightState {
        val result = step(current, event, machineName)
        applyAccepted(
            result,
            crossCutting,
            port,
            { d, p -> applyDomainLight(d, p, timers) },
        ) { current = it }
        return current
    }

    /**
     * PURPOSE: Drain due timeouts from [timers], map eventKey → sealed event, re-enter [handle].
     * Demo/test boundary recipe — not used by product export.
     */
    fun fireDueTimeouts(): List<LightEvent> {
        val fired = mutableListOf<LightEvent>()
        for (due in timers.pollDue()) {
            val event = lightEventFromTimerKey(due.eventKey)
                ?: error("unknown timer eventKey=${due.eventKey}")
            handle(event)
            fired += event
        }
        return fired
    }
}
