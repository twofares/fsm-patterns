package fsm.sample.effect

import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Thin assignment boundary that runs declared [Effect]s.
 *
 * Ordinary Kotlin — not an effect runtime or algebraic handler. Call sites
 * assign state from [StepResult.next], then [apply] the effect list so log
 * lines and counters stay consistent and testable.
 *
 * [onIncHeartbeat] is optional so machines without heartbeats need no stub.
 */
object EffectInterpreter {

    /**
     * Interpret each effect in order: append to [log], invoke heartbeat hook.
     * Null [log] silently drops log effects (useful for pure probes / export).
     */
    fun apply(
        effects: List<Effect>,
        log: TransitionLog? = null,
        onIncHeartbeat: (() -> Unit)? = null,
    ) {
        for (effect in effects) {
            when (effect) {
                is Effect.LogTransition ->
                    log?.recordTransition(
                        machine = effect.machine,
                        from = effect.from,
                        event = effect.event,
                        to = effect.to,
                    )
                is Effect.LogEntry ->
                    log?.recordEntry(
                        machine = effect.machine,
                        state = effect.state,
                        detail = effect.detail,
                    )
                is Effect.LogExit ->
                    log?.recordExit(
                        machine = effect.machine,
                        state = effect.state,
                        detail = effect.detail,
                    )
                is Effect.LogRefusal ->
                    log?.recordRefusal(
                        machine = effect.machine,
                        state = effect.state,
                        event = effect.event,
                        reason = effect.reason,
                    )
                Effect.IncHeartbeat ->
                    onIncHeartbeat?.invoke()
            }
        }
    }
}
