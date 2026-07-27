package fsm.sample.machine

import fsm.sample.effect.TimerIntent
import fsm.sample.effect.exportLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Prove [TimerPort] + [VirtualClock] scheduling — arm, replace, cancel,
 * advance/pollDue order. Does not call machine.handle (port is schedule-only).
 */
class TimerPortTest {

    @Test
    fun `arm schedules timeout with dueAtMs equals now plus delayMs`() {
        val clock = VirtualClock(startMs = 1000L)
        val port = TimerPort(clock)

        port.apply(
            listOf(
                TimerIntent.Arm(id = "t1", delayMs = 500L, eventKey = "Elapsed"),
            ),
        )

        val pending = port.pending()
        assertEquals(1, pending.size)
        assertEquals("t1", pending[0].id)
        assertEquals(1500L, pending[0].dueAtMs)
        assertEquals("Elapsed", pending[0].eventKey)
    }

    @Test
    fun `arm replaces same id keeping only latest schedule`() {
        val clock = VirtualClock()
        val port = TimerPort(clock)

        port.apply(
            listOf(
                TimerIntent.Arm(id = "light.dwell", delayMs = 30_000L, eventKey = "DwellElapsed"),
            ),
        )
        port.apply(
            listOf(
                TimerIntent.Arm(id = "light.dwell", delayMs = 5_000L, eventKey = "DwellElapsed"),
            ),
        )

        val pending = port.pending()
        assertEquals(1, pending.size)
        assertEquals("light.dwell", pending[0].id)
        assertEquals(5_000L, pending[0].dueAtMs)
    }

    @Test
    fun `cancel is idempotent when id is missing or already cancelled`() {
        val clock = VirtualClock()
        val port = TimerPort(clock)

        // Cancel missing id — no throw, pending stays empty.
        port.apply(listOf(TimerIntent.Cancel("missing")))
        assertTrue(port.pending().isEmpty())

        port.apply(
            listOf(
                TimerIntent.Arm(id = "t1", delayMs = 100L, eventKey = "E"),
            ),
        )
        assertEquals(1, port.pending().size)

        port.apply(listOf(TimerIntent.Cancel("t1")))
        assertTrue(port.pending().isEmpty())

        // Second cancel is a no-op.
        port.apply(listOf(TimerIntent.Cancel("t1")))
        assertTrue(port.pending().isEmpty())
    }

    @Test
    fun `advance time then pollDue returns and removes due timers only`() {
        val clock = VirtualClock()
        val port = TimerPort(clock)

        port.apply(
            listOf(
                TimerIntent.Arm(id = "soon", delayMs = 100L, eventKey = "SoonKey"),
                TimerIntent.Arm(id = "later", delayMs = 1000L, eventKey = "LaterKey"),
            ),
        )

        clock.advance(100L)
        val due = port.pollDue()
        assertEquals(1, due.size)
        assertEquals("soon", due[0].id)
        assertEquals("SoonKey", due[0].eventKey)

        val stillPending = port.pending()
        assertEquals(1, stillPending.size)
        assertEquals("later", stillPending[0].id)

        // Already-polled timer is gone; second poll is empty until more advance.
        assertTrue(port.pollDue().isEmpty())
    }

    @Test
    fun `pollDue sorts multi-id due by dueAtMs then id`() {
        val clock = VirtualClock()
        val port = TimerPort(clock)

        // Arm in reverse insertion order relative to expected sort.
        // Same dueAt for b and a → id ascending; earlier due for z.
        port.apply(
            listOf(
                TimerIntent.Arm(id = "b", delayMs = 200L, eventKey = "B"),
                TimerIntent.Arm(id = "a", delayMs = 200L, eventKey = "A"),
                TimerIntent.Arm(id = "z", delayMs = 100L, eventKey = "Z"),
            ),
        )

        clock.advance(200L)
        val due = port.pollDue()
        assertEquals(listOf("z", "a", "b"), due.map { it.id })
        assertEquals(listOf("Z", "A", "B"), due.map { it.eventKey })
        assertTrue(port.pending().isEmpty())
    }

    @Test
    fun `single-id teaching path arm cancel re-arm`() {
        // Teaching default: one timer id per machine (e.g. light.dwell).
        val clock = VirtualClock()
        val port = TimerPort(clock)
        val id = "light.dwell"

        port.apply(
            listOf(
                TimerIntent.Arm(id = id, delayMs = 30_000L, eventKey = "DwellElapsed"),
            ),
        )
        assertEquals(listOf(id), port.pending().map { it.id })

        // Color change: Cancel then Arm (idempotent cancel + replace).
        port.apply(
            listOf(
                TimerIntent.Cancel(id),
                TimerIntent.Arm(id = id, delayMs = 25_000L, eventKey = "DwellElapsed"),
            ),
        )
        assertEquals(1, port.pending().size)
        assertEquals(25_000L, port.pending().single().dueAtMs)

        clock.advance(25_000L)
        val due = port.pollDue()
        assertEquals(1, due.size)
        assertEquals(id, due[0].id)
        assertEquals("DwellElapsed", due[0].eventKey)
        assertTrue(port.pending().isEmpty())
    }

    @Test
    fun `exportLabel for Arm and Cancel`() {
        val arm = TimerIntent.Arm(id = "light.dwell", delayMs = 5000L, eventKey = "DwellElapsed")
        val cancel = TimerIntent.Cancel(id = "light.dwell")
        assertEquals("Timer.Arm(light.dwell,5000ms,DwellElapsed)", arm.exportLabel())
        assertEquals("Timer.Cancel(light.dwell)", cancel.exportLabel())
        assertEquals(arm.exportLabel(), (arm as TimerIntent).exportLabel())
        assertEquals(cancel.exportLabel(), (cancel as TimerIntent).exportLabel())
    }

    @Test
    fun `applyTimerIntents helper delegates to port`() {
        val clock = VirtualClock(startMs = 10L)
        val port = TimerPort(clock)
        applyTimerIntents(
            listOf(TimerIntent.Arm(id = "x", delayMs = 5L, eventKey = "X")),
            port,
        )
        assertEquals(15L, port.pending().single().dueAtMs)
    }
}
