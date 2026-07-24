package fsm.sample.effect

/**
 * PURPOSE: Generic pure step outcome — successor state plus **domain-typed** effects.
 *
 * [E] is the machine's sealed effect ADT (not a global kitchen-sink). Pure steps
 * return [next] + [effects]; a per-machine interpreter applies effects at the
 * boundary. Export product evaluation reads [effects] labels without interpreting.
 *
 * [next] is always defined (refusal steps keep the prior state as next).
 * [effects] is empty when nothing observable should happen at the boundary.
 */
data class StepResult<S, E>(
    val next: S,
    val effects: List<E> = emptyList(),
)
