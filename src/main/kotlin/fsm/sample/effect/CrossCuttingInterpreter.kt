package fsm.sample.effect

/**
 * PURPOSE: Single boundary for **disparate** cross-cutting effect families.
 *
 * Dispatches by family to the appropriate sink on [CrossCuttingEnv]:
 * Audit → log, Metric → metrics, Trace → traces.
 *
 * Domain effects never pass through here. Pure steps never call [apply].
 */
object CrossCuttingInterpreter {

    fun applyOne(effect: CrossCuttingEffect, env: CrossCuttingEnv) {
        when (effect) {
            is CrossCuttingEffect.Audit.LogTransition ->
                env.log.recordTransition(
                    machine = effect.machine,
                    from = effect.from,
                    event = effect.event,
                    to = effect.to,
                )
            is CrossCuttingEffect.Audit.LogEntry ->
                env.log.recordEntry(
                    machine = effect.machine,
                    state = effect.state,
                    detail = effect.detail,
                )
            is CrossCuttingEffect.Audit.LogExit ->
                env.log.recordExit(
                    machine = effect.machine,
                    state = effect.state,
                    detail = effect.detail,
                )
            is CrossCuttingEffect.Audit.LogRefusal ->
                env.log.recordRefusal(
                    machine = effect.machine,
                    state = effect.state,
                    event = effect.event,
                    reason = effect.reason,
                )
            is CrossCuttingEffect.Metric.Inc ->
                env.metrics.inc(effect.name, effect.delta)
            is CrossCuttingEffect.Trace.Span ->
                env.traces.note(effect.name, effect.detail)
        }
    }

    fun apply(effects: List<CrossCuttingEffect>, env: CrossCuttingEnv) {
        for (e in effects) applyOne(e, env)
    }
}
