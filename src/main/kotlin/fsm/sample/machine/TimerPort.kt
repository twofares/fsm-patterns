package fsm.sample.machine

import fsm.sample.effect.TimerIntent

/** One pending delayed delivery. */
data class ScheduledTimeout(
    val id: String,
    val dueAtMs: Long,
    val eventKey: String,
)

/**
 * PURPOSE: Boundary scheduler for [TimerIntent] — not an FSM engine.
 * [VirtualClock] is the test/demo default; wall clock is optional demo-only.
 */
interface Clock {
    /** Monotonic sample time in milliseconds (virtual or wall). */
    fun nowMs(): Long
}

/** Deterministic clock for tests and pure demos. */
class VirtualClock(startMs: Long = 0L) : Clock {
    private var now: Long = startMs
    override fun nowMs(): Long = now
    fun advance(deltaMs: Long) {
        require(deltaMs >= 0)
        now += deltaMs
    }
}

/**
 * PURPOSE: In-memory timer bag. Applies Arm/Cancel; exposes due timeouts.
 * Does not call machine.handle — the boundary drains due events explicitly
 * so re-entry stays a sealed event through the normal pure step path.
 *
 * Teaching default: one timer id per machine (`light.dwell`, `connection.online.watchdog`).
 * Multi-id is supported; [pollDue] returns due entries sorted by (dueAtMs, id).
 */
class TimerPort(private val clock: Clock = VirtualClock()) {
    private val pending = linkedMapOf<String, ScheduledTimeout>()

    fun apply(intents: List<TimerIntent>) {
        for (intent in intents) {
            when (intent) {
                is TimerIntent.Arm -> {
                    pending[intent.id] = ScheduledTimeout(
                        id = intent.id,
                        dueAtMs = clock.nowMs() + intent.delayMs,
                        eventKey = intent.eventKey,
                    )
                }
                is TimerIntent.Cancel -> pending.remove(intent.id)
            }
        }
    }

    fun pending(): List<ScheduledTimeout> = pending.values.toList()

    /**
     * Remove and return all timeouts with dueAtMs <= now.
     * Order: sorted by (dueAtMs ascending, id ascending) — not insertion order.
     * Caller maps eventKey → sealed event via vignette mapper and calls handle.
     */
    fun pollDue(): List<ScheduledTimeout> {
        val now = clock.nowMs()
        val due = pending.values
            .filter { it.dueAtMs <= now }
            .sortedWith(compareBy({ it.dueAtMs }, { it.id }))
        due.forEach { pending.remove(it.id) }
        return due
    }

    fun clear() = pending.clear()
}

fun applyTimerIntents(intents: List<TimerIntent>, port: TimerPort) = port.apply(intents)
