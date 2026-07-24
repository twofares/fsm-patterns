package fsm.sample.flat

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.CrossCuttingInterpreter
import fsm.sample.effect.StepResult
import fsm.sample.effect.auditTransition
import fsm.sample.effect.metricInc
import fsm.sample.effect.traceSpan

/**
 * PURPOSE: Flat FSM — pure step declares **multiple cross-cutting families** + domain.
 *
 * Cross-cutting (shared [CrossCuttingEnv]):
 * - Audit.LogTransition — mode change log
 * - Metric.Inc("fsm.transitions") — process-wide transition counter
 * - Metric.Inc("light.ticks") or Trace.Span on Emergency — disparate families
 *
 * Domain: [LightEffect.DriveLamp] → [LightPort] only.
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

    fun label(): String = when (this) {
        Tick -> "Tick"
        Emergency -> "Emergency"
    }
}

fun transition(state: LightState, event: LightEvent): LightState = when (event) {
    LightEvent.Tick -> when (state) {
        LightState.Red -> LightState.Green
        LightState.Green -> LightState.Yellow
        LightState.Yellow -> LightState.Red
    }
    LightEvent.Emergency -> LightState.Red
}

/**
 * PURPOSE: Pure step — next + multi-family crossCutting + DriveLamp domain.
 * Does not touch [CrossCuttingEnv] or [LightPort].
 */
fun step(
    state: LightState,
    event: LightEvent,
    machineName: String = "TrafficLight",
): StepResult<LightState, LightEffect> {
    val next = transition(state, event)
    val cross = buildList {
        add(
            auditTransition(
                machine = machineName,
                from = state.label(),
                event = event.label(),
                to = next.label(),
            ),
        )
        add(metricInc("fsm.transitions"))
        when (event) {
            LightEvent.Tick -> add(metricInc("light.ticks"))
            LightEvent.Emergency -> add(traceSpan("light.emergency", "forced ${next.label()}"))
        }
    }
    return StepResult(
        next = next,
        crossCutting = cross,
        domain = listOf(LightEffect.DriveLamp(next.label())),
    )
}

/**
 * PURPOSE: Boundary — assign state; apply cross-cutting env + domain port.
 */
class TrafficLight(
    initial: LightState = LightState.Red,
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    val port: LightPort = LightPort(),
    private val machineName: String = "TrafficLight",
) {
    var current: LightState = initial
        private set

    fun handle(event: LightEvent): LightState {
        val result = step(current, event, machineName)
        current = result.next
        CrossCuttingInterpreter.apply(result.crossCutting, crossCutting)
        applyDomainLight(result.domain, port)
        return current
    }
}
