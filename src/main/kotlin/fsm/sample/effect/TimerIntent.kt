package fsm.sample.effect

/**
 * PURPOSE: Pure delayed-send intents — domain-like scheduling, not cross-cutting observability.
 * Pure steps declare; boundaries apply via [fsm.sample.machine.TimerPort].
 * Export labels only — never constructs clocks.
 *
 * [Arm.eventKey] MUST equal the vignette sealed event's [label] (see eventKey recipe).
 */
sealed class TimerIntent {
    /**
     * Arm (or replace) a named timer. When [delayMs] elapses on the boundary clock,
     * the port delivers [eventKey] for re-entry as a sealed machine event.
     */
    data class Arm(
        val id: String,
        val delayMs: Long,
        val eventKey: String,
    ) : TimerIntent() {
        fun exportLabel(): String = "Timer.Arm($id,${delayMs}ms,$eventKey)"
    }

    /** Cancel a previously armed timer if still pending. Idempotent at the port. */
    data class Cancel(val id: String) : TimerIntent() {
        fun exportLabel(): String = "Timer.Cancel($id)"
    }
}

fun TimerIntent.exportLabel(): String = when (this) {
    is TimerIntent.Arm -> exportLabel()
    is TimerIntent.Cancel -> exportLabel()
}
