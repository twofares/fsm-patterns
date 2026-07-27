package fsm.sample.flat

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.StepResult
import fsm.sample.effect.acceptTransitionCrossCutting
import fsm.sample.effect.metricInc
import fsm.sample.effect.traceSpan
import fsm.sample.machine.TimerPort
import fsm.sample.machine.VirtualClock
import fsm.sample.machine.applyAccepted
import fsm.sample.machine.drainAutomatic

/**
 * PURPOSE: Flat FSM — pure step declares **multiple cross-cutting families** + domain.
 *
 * Cross-cutting (shared [CrossCuttingEnv]):
 * - Audit.LogTransition — mode change log
 * - Metric.Inc("fsm.transitions") — process-wide transition counter
 * - Metric.Inc("light.ticks") / Trace.Span on Emergency / Metric.Inc("light.completion") on ε
 *
 * Domain: [LightEffect.DriveLamp] → [LightPort]; [LightEffect.Timer] → [TimerPort]
 * (one domain list, multi-port apply — not a third [StepResult] field).
 *
 * Boundary uses [applyAccepted]; accept preamble via [acceptTransitionCrossCutting]
 * with machine-specific extras (`light.ticks` / `light.emergency` / `light.completion`).
 *
 * Dwell timers: pure step declares Cancel then Arm([LIGHT_DWELL_ID]) on every dwell-color
 * engagement; [TimerPort] schedules via [VirtualClock]; due timeouts re-enter as
 * [LightEvent.DwellElapsed]. No arm on construction (K16).
 *
 * Automatic completion: [LightState.Flash] exits only via pure [automaticStep] (ε → Red);
 * [handle] applies the external step then [drainAutomatic] applies each microstep.
 */

sealed class LightState {
    data object Red : LightState()
    data object Green : LightState()
    data object Yellow : LightState()
    /** Transient emergency flash; pure total state — exit is eventless [automaticStep]. */
    data object Flash : LightState()

    fun label(): String = when (this) {
        Red -> "Red"
        Green -> "Green"
        Yellow -> "Yellow"
        Flash -> "Flash"
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

/**
 * PURPOSE: Pure next-state table (total machine including [LightState.Flash]).
 *
 * Flash self-loops on every sealed event; only [automaticStep] leaves Flash.
 * Emergency from any dwell color → Flash (not Red).
 */
fun transition(state: LightState, event: LightEvent): LightState = when (event) {
    LightEvent.Tick, LightEvent.DwellElapsed -> when (state) {
        LightState.Red -> LightState.Green
        LightState.Green -> LightState.Yellow
        LightState.Yellow -> LightState.Red
        LightState.Flash -> LightState.Flash
    }
    LightEvent.Emergency -> LightState.Flash
}

/**
 * PURPOSE: Pure step — next + multi-family crossCutting + domain per Flash × event policy.
 * Does not touch [CrossCuttingEnv], [LightPort], [TimerPort], or clocks.
 *
 * Domain: Flash next → Cancel + DriveLamp(Flash); dwell color → Cancel + Arm + DriveLamp.
 */
fun step(
    state: LightState,
    event: LightEvent,
    machineName: String = "TrafficLight",
): StepResult<LightState, LightEffect> {
    val next = transition(state, event)
    val extras = when (event) {
        LightEvent.Tick, LightEvent.DwellElapsed -> listOf(metricInc("light.ticks"))
        LightEvent.Emergency -> listOf(
            traceSpan("light.emergency", "emergency interrupt → Flash"),
        )
    }
    val domain = when (next) {
        LightState.Flash -> domainEnterFlash()
        LightState.Red, LightState.Green, LightState.Yellow -> domainEnterDwellColor(next)
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

/**
 * PURPOSE: Pure eventless / completion step — only [LightState.Flash] → Red with event `ε`.
 * Returns null when no automatic edge is enabled (Red / Green / Yellow).
 * Domain: Cancel + Arm(Red) + DriveLamp(Red) so the timed-light story continues after emergency.
 * Does not touch ports or clocks.
 */
fun automaticStep(
    state: LightState,
    machineName: String = "TrafficLight",
): StepResult<LightState, LightEffect>? = when (state) {
    LightState.Flash -> {
        val next = LightState.Red
        StepResult(
            next = next,
            crossCutting = acceptTransitionCrossCutting(
                machine = machineName,
                from = state.label(),
                event = "ε",
                to = next.label(),
                extras = listOf(metricInc("light.completion")),
            ),
            domain = domainEnterDwellColor(next),
        )
    }
    LightState.Red, LightState.Green, LightState.Yellow -> null
}

/**
 * PURPOSE: Boundary — assign state; apply cross-cutting env + multi-port domain
 * via [applyAccepted] (assign → CC → domain). Domain lambda closes over [timers].
 * After each external engagement, [drainAutomatic] applies pure [automaticStep] microsteps
 * (Flash → Red) with the same dual-sink apply.
 *
 * No init arm for Red dwell (K16): [timers] starts empty until first engagement.
 *
 * **Clock / timers coupling:** inject only [clock]. [timers] is always
 * `TimerPort(clock)` so `clock.advance` and `timers.pollDue` / [fireDueTimeouts]
 * stay in sync. Do not construct a separate [TimerPort] with a different clock.
 */
class TrafficLight(
    initial: LightState = LightState.Red,
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    val port: LightPort = LightPort(),
    val clock: VirtualClock = VirtualClock(),
    private val machineName: String = "TrafficLight",
) {
    /** Scheduler for this machine — always backed by [clock] (single injection point). */
    val timers: TimerPort = TimerPort(clock)

    var current: LightState = initial
        private set

    fun handle(event: LightEvent): LightState {
        // Multi-port: domain lambda closes over [port] + [timers]; P is required by applyAccepted only.
        val applyDomain: (List<LightEffect>, LightPort) -> Unit =
            { domain, _ -> applyDomainLight(domain, port, timers) }
        val result = step(current, event, machineName)
        applyAccepted(result, crossCutting, port, applyDomain) { current = it }
        val drain = drainAutomatic(
            start = current,
            automaticStep = { s -> automaticStep(s, machineName) },
            nextOf = { it.next },
        )
        for (micro in drain.microsteps) {
            applyAccepted(micro, crossCutting, port, applyDomain) { current = it }
        }
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
