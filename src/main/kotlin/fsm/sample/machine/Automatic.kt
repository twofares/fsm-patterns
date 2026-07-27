package fsm.sample.machine

/**
 * PURPOSE: Pure bounded drain of eventless / completion transitions.
 *
 * [automaticStep] returns null when no eventless edge is enabled from the current state.
 * Does not apply sinks — the caller applies each microstep result.
 *
 * Bound / cycle policy: ALWAYS throw [AutomaticBoundExceededException].
 * No soft truncated flag — fail loud in the sample (K7).
 *
 * Hierarchy drain wiring is out of scope here; this helper is generic and pure.
 */

/** Default microstep cap for [drainAutomatic] (K7). */
const val DEFAULT_MAX_MICROSTEPS: Int = 32

/**
 * PURPOSE: Successful pure drain outcome — final state and ordered microstep results.
 * No [truncated] field; bound/cycle always throw.
 */
data class AutomaticDrainResult<S, R>(
    val finalState: S,
    /** Ordered pure results of each microstep (not including the external event that preceded drain). */
    val microsteps: List<R>,
)

/**
 * PURPOSE: Bound or cycle failure while draining eventless edges.
 *
 * [reason] distinguishes max-microstep bound from revisiting a state already seen in this drain.
 */
class AutomaticBoundExceededException(
    message: String,
    val stepsTaken: Int,
    val reason: Reason,
) : IllegalStateException(message) {
    enum class Reason { BOUND, CYCLE }
}

/**
 * PURPOSE: Drain eventless transitions from [start] until quiescent, bound, or cycle.
 *
 * - Empty drain: [automaticStep] returns null immediately → finalState = start, microsteps empty.
 * - Cycle: if [detectCycles] and [nextOf] yields a state already visited in this drain → [Reason.CYCLE].
 * - Bound: if an edge remains enabled after [maxMicrosteps] microsteps → [Reason.BOUND].
 *
 * Pure: no ports, no clocks. Callers apply sinks for each entry in [AutomaticDrainResult.microsteps].
 */
fun <S, R> drainAutomatic(
    start: S,
    automaticStep: (S) -> R?,
    nextOf: (R) -> S,
    detectCycles: Boolean = true,
    maxMicrosteps: Int = DEFAULT_MAX_MICROSTEPS,
): AutomaticDrainResult<S, R> {
    val microsteps = mutableListOf<R>()
    val seen = linkedSetOf(start)
    var current = start
    repeat(maxMicrosteps) {
        val step = automaticStep(current)
            ?: return AutomaticDrainResult(current, microsteps)
        val next = nextOf(step)
        microsteps += step
        if (detectCycles && next in seen) {
            throw AutomaticBoundExceededException(
                "automatic cycle detected at state=$next after ${microsteps.size} microsteps",
                stepsTaken = microsteps.size,
                reason = AutomaticBoundExceededException.Reason.CYCLE,
            )
        }
        seen += next
        current = next
    }
    if (automaticStep(current) != null) {
        throw AutomaticBoundExceededException(
            "automatic microstep bound $maxMicrosteps exceeded",
            stepsTaken = microsteps.size,
            reason = AutomaticBoundExceededException.Reason.BOUND,
        )
    }
    return AutomaticDrainResult(current, microsteps)
}
