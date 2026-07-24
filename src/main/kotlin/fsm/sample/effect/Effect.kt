package fsm.sample.effect

/**
 * PURPOSE: Stdlib-only sealed effect vocabulary for pure steps that *declare*
 * side effects without performing them.
 *
 * Discipline: pure step returns next state + [Effect] list; a boundary
 * ([EffectInterpreter]) interprets those effects (log, counters). Not a typed
 * effect algebra, free monad, or reusable runtime — just ordinary sealed data
 * tests can assert without real I/O.
 *
 * Export product evaluation records declared effect *labels* from pure steps
 * (see export package); the interpreter still runs only at machine boundaries.
 */
sealed class Effect {
    /** Record a successful mode change (from --event--> to). */
    data class LogTransition(
        val machine: String,
        val from: String,
        val event: String,
        val to: String,
    ) : Effect()

    /** Record hierarchical/leaf entry observation. */
    data class LogEntry(
        val machine: String,
        val state: String,
        val detail: String? = null,
    ) : Effect()

    /** Record hierarchical/leaf exit observation. */
    data class LogExit(
        val machine: String,
        val state: String,
        val detail: String? = null,
    ) : Effect()

    /** Record CSP-style or hierarchical refusal (state unchanged). */
    data class LogRefusal(
        val machine: String,
        val state: String,
        val event: String,
        val reason: String,
    ) : Effect()

    /**
     * Superstate-only reaction: bump a heartbeat counter at the boundary.
     * Declared by pure-ish handlers; never mutates during step evaluation.
     */
    data object IncHeartbeat : Effect()
}

/**
 * PURPOSE: Pure step outcome — successor state plus effects to run later.
 *
 * [next] is always defined (refusal steps keep the prior state as next).
 * [effects] is empty when nothing observable should happen at the boundary.
 */
data class StepResult<S>(
    val next: S,
    val effects: List<Effect> = emptyList(),
)
