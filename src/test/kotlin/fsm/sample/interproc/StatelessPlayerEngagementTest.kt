package fsm.sample.interproc

import fsm.sample.effect.TimerIntent
import fsm.sample.logging.RecordKind
import fsm.sample.machine.VirtualClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Prove inter-proc boundary recipes — load→step→applyOffer(store→CC→domain),
 * accept delivers to thin player, refuse delivers nothing, timer Arm → boundary bag,
 * due timeout re-enters pure step via engage. One session per engagement instance;
 * multi-session = N instances (each owns store / player / timers / CC).
 *
 * Pure [stepOffer] / menu / domain-label oracles live in [PlayerControlTest].
 */
class StatelessPlayerEngagementTest {

    // -------------------------------------------------------------------------
    // load → step → store (via applyOffer assign)
    // -------------------------------------------------------------------------

    @Test
    fun `engage loads missing session as Idle then stores Playing after Play`() {
        val boundary = StatelessPlayerEngagement(sessionId = "sess-1")
        assertEquals(null, boundary.state())

        val result = boundary.engage(PlayerEvent.Play)

        assertTrue(result.accepted)
        assertEquals(PlayerState.Idle, result.previous)
        assertEquals(PlayerState.Playing, result.next)
        assertEquals(PlayerState.Playing, boundary.state())
        assertTrue(boundary.store.isPresent())
        assertEquals("sess-1", result.sessionId)
    }

    @Test
    fun `second engage loads stored state not a fresh Idle`() {
        val boundary = StatelessPlayerEngagement()
        boundary.engage(PlayerEvent.Play)
        val result = boundary.engage(PlayerEvent.Pause)
        assertTrue(result.accepted)
        assertEquals(PlayerState.Playing, result.previous)
        assertEquals(PlayerState.Paused, result.next)
        assertEquals(PlayerState.Paused, boundary.state())
    }

    @Test
    fun `first-contact refuse still stores Idle session row`() {
        val boundary = StatelessPlayerEngagement()
        val result = boundary.engage(PlayerEvent.Pause)
        assertFalse(result.accepted)
        assertEquals(PlayerState.Idle, boundary.state())
        assertTrue(boundary.store.isPresent())
    }

    // -------------------------------------------------------------------------
    // Domain delivery only on accept
    // -------------------------------------------------------------------------

    @Test
    fun `accept delivers ordered thin-player commands`() {
        val boundary = StatelessPlayerEngagement()
        boundary.engage(PlayerEvent.Play)
        assertEquals(listOf(ThinPlayerCommand.Play), boundary.player.commands)

        boundary.engage(PlayerEvent.Pause)
        assertEquals(
            listOf(ThinPlayerCommand.Play, ThinPlayerCommand.Pause),
            boundary.player.commands,
        )

        boundary.engage(PlayerEvent.Resume)
        assertEquals(
            listOf(
                ThinPlayerCommand.Play,
                ThinPlayerCommand.Pause,
                ThinPlayerCommand.Unpause,
            ),
            boundary.player.commands,
        )
    }

    @Test
    fun `accept Skip while Playing delivers Next only`() {
        val boundary = StatelessPlayerEngagement()
        boundary.engage(PlayerEvent.Play)
        boundary.player.clear()
        val result = boundary.engage(PlayerEvent.Skip)
        assertTrue(result.accepted)
        assertEquals(listOf(ThinPlayerCommand.Next), result.delivered)
        assertEquals(listOf(ThinPlayerCommand.Next), boundary.player.commands)
        assertEquals(PlayerState.Playing, boundary.state())
    }

    @Test
    fun `accept Skip while Paused delivers Next then Unpause and leaves Playing`() {
        val boundary = StatelessPlayerEngagement()
        boundary.engage(PlayerEvent.Play)
        boundary.engage(PlayerEvent.Pause)
        boundary.player.clear()

        val result = boundary.engage(PlayerEvent.Skip)

        assertTrue(result.accepted)
        assertEquals(PlayerState.Playing, result.next)
        assertEquals(
            listOf(ThinPlayerCommand.Next, ThinPlayerCommand.Unpause),
            result.delivered,
        )
        assertEquals(
            listOf(ThinPlayerCommand.Next, ThinPlayerCommand.Unpause),
            boundary.player.commands,
        )
        assertEquals(1, boundary.timers.pending().size)
    }

    @Test
    fun `accept Play records TRANSITION log and player accept metric`() {
        val boundary = StatelessPlayerEngagement()
        boundary.engage(PlayerEvent.Play)

        val transitions = boundary.crossCutting.log.snapshot()
            .filter { it.kind == RecordKind.TRANSITION }
        assertEquals(1, transitions.size)
        assertEquals("Play", transitions.single().event)
        assertEquals("Idle", transitions.single().from)
        assertEquals("Playing", transitions.single().to)
        assertEquals(1, boundary.crossCutting.metrics.get("player.accept"))
        assertEquals(1, boundary.crossCutting.metrics.get("fsm.transitions"))
    }

    // -------------------------------------------------------------------------
    // Refuse delivers nothing
    // -------------------------------------------------------------------------

    @Test
    fun `refuse delivers no thin-player commands and leaves schedule empty`() {
        val boundary = StatelessPlayerEngagement()
        val beforeCommands = boundary.player.commands.toList()
        val result = boundary.engage(PlayerEvent.Pause)

        assertFalse(result.accepted)
        assertNotNull(result.reason)
        assertTrue(result.reason!!.contains("menu"), "reason=${result.reason}")
        assertTrue(result.delivered.isEmpty())
        assertEquals(beforeCommands, boundary.player.commands)
        assertTrue(boundary.timers.pending().isEmpty())
        assertEquals(PlayerState.Idle, boundary.state())
        assertTrue(
            boundary.crossCutting.log.snapshot().any { it.kind == RecordKind.REFUSAL },
        )
        assertEquals(1, boundary.crossCutting.metrics.get("player.refuse"))
    }

    @Test
    fun `refuse after Play does not append domain or cancel armed timer via refuse path`() {
        val boundary = StatelessPlayerEngagement()
        boundary.engage(PlayerEvent.Play)
        val pendingAfterPlay = boundary.timers.pending()
        assertEquals(1, pendingAfterPlay.size)

        val commandsAfterPlay = boundary.player.commands.toList()
        // Resume is illegal while Playing
        val result = boundary.engage(PlayerEvent.Resume)
        assertFalse(result.accepted)
        assertNotNull(result.reason)
        assertEquals(commandsAfterPlay, boundary.player.commands)
        // Refuse must not apply domain — schedule unchanged
        assertEquals(pendingAfterPlay, boundary.timers.pending())
        assertEquals(PlayerState.Playing, boundary.state())
    }

    // -------------------------------------------------------------------------
    // Timer Arm → boundary bag; re-arm; due re-enters pure step
    // -------------------------------------------------------------------------

    @Test
    fun `Play arms track timer on boundary TimerPort not a live machine field`() {
        val clock = VirtualClock()
        val boundary = StatelessPlayerEngagement(clock = clock)
        boundary.engage(PlayerEvent.Play)

        val pending = boundary.timers.pending()
        assertEquals(1, pending.size)
        assertEquals(PLAYER_TRACK_TIMER_ID, pending.single().id)
        assertEquals(PlayerEvent.TrackEnded.label(), pending.single().eventKey)
        assertEquals(TRACK_DURATION_MS, pending.single().dueAtMs)
    }

    @Test
    fun `Resume re-arms track timer after Pause cancel`() {
        val clock = VirtualClock()
        val boundary = StatelessPlayerEngagement(clock = clock)
        boundary.engage(PlayerEvent.Play)
        boundary.engage(PlayerEvent.Pause)
        assertTrue(boundary.timers.pending().isEmpty())

        clock.advance(1_000L)
        boundary.engage(PlayerEvent.Resume)

        val pending = boundary.timers.pending()
        assertEquals(1, pending.size)
        assertEquals(clock.nowMs() + TRACK_DURATION_MS, pending.single().dueAtMs)
    }

    @Test
    fun `Skip while Playing re-arms track timer`() {
        val clock = VirtualClock()
        val boundary = StatelessPlayerEngagement(clock = clock)
        boundary.engage(PlayerEvent.Play)
        clock.advance(50_000L)
        boundary.engage(PlayerEvent.Skip)

        val pending = boundary.timers.pending()
        assertEquals(1, pending.size)
        assertEquals(clock.nowMs() + TRACK_DURATION_MS, pending.single().dueAtMs)
    }

    @Test
    fun `due TrackEnded re-enters engage via boundary and delivers Next`() {
        val clock = VirtualClock()
        val boundary = StatelessPlayerEngagement(clock = clock)
        boundary.engage(PlayerEvent.Play)
        boundary.player.clear()

        clock.advance(TRACK_DURATION_MS)
        val fired = boundary.fireDueTimeouts()

        assertEquals(listOf(PlayerEvent.TrackEnded), fired.map { it.event })
        assertTrue(fired.single().result.accepted)
        assertEquals(listOf(ThinPlayerCommand.Next), boundary.player.commands)
        assertEquals(PlayerState.Playing, boundary.state())
        // Re-armed for the next track
        assertEquals(1, boundary.timers.pending().size)
        assertEquals(
            TRACK_DURATION_MS * 2,
            boundary.timers.pending().single().dueAtMs,
        )
    }

    @Test
    fun `Pause cancels external track timer`() {
        val clock = VirtualClock()
        val boundary = StatelessPlayerEngagement(clock = clock)
        boundary.engage(PlayerEvent.Play)
        assertEquals(1, boundary.timers.pending().size)

        boundary.engage(PlayerEvent.Pause)
        assertTrue(boundary.timers.pending().isEmpty())
        assertEquals(PlayerState.Paused, boundary.state())
    }

    @Test
    fun `fireDueTimeouts does nothing when nothing is due`() {
        val clock = VirtualClock()
        val boundary = StatelessPlayerEngagement(clock = clock)
        boundary.engage(PlayerEvent.Play)
        clock.advance(TRACK_DURATION_MS - 1)
        assertTrue(boundary.fireDueTimeouts().isEmpty())
        assertEquals(listOf(ThinPlayerCommand.Play), boundary.player.commands)
    }

    @Test
    fun `two engagement instances with shared clock drain independently`() {
        val clock = VirtualClock()
        val a = StatelessPlayerEngagement(sessionId = "a", clock = clock)
        val b = StatelessPlayerEngagement(sessionId = "b", clock = clock)
        a.engage(PlayerEvent.Play)
        b.engage(PlayerEvent.Play)
        a.player.clear()
        b.player.clear()

        clock.advance(TRACK_DURATION_MS)
        val firedA = a.fireDueTimeouts()

        assertEquals(listOf(PlayerEvent.TrackEnded), firedA.map { it.event })
        assertTrue(firedA.single().result.accepted)
        assertEquals(PlayerState.Playing, a.state())
        assertEquals(PlayerState.Playing, b.state())
        // a re-armed at now + TRACK_DURATION; b still has original due (not polled yet)
        assertEquals(1, a.timers.pending().size)
        assertEquals(
            clock.nowMs() + TRACK_DURATION_MS,
            a.timers.pending().single().dueAtMs,
        )
        assertEquals(1, b.timers.pending().size)
        assertEquals(TRACK_DURATION_MS, b.timers.pending().single().dueAtMs)
        assertEquals(listOf(ThinPlayerCommand.Next), a.player.commands)
        assertTrue(b.player.commands.isEmpty())

        // b drains independently on the same shared clock
        val firedB = b.fireDueTimeouts()
        assertEquals(listOf(PlayerEvent.TrackEnded), firedB.map { it.event })
        assertTrue(firedB.single().result.accepted)
        assertEquals(listOf(ThinPlayerCommand.Next), b.player.commands)
        assertEquals(PlayerState.Playing, b.state())
        assertEquals(1, b.timers.pending().size)
        assertEquals(
            clock.nowMs() + TRACK_DURATION_MS,
            b.timers.pending().single().dueAtMs,
        )
        // a unchanged by b's drain
        assertEquals(listOf(ThinPlayerCommand.Next), a.player.commands)
        assertEquals(
            clock.nowMs() + TRACK_DURATION_MS,
            a.timers.pending().single().dueAtMs,
        )
    }

    @Test
    fun `unknown timer eventKey on fireDueTimeouts throws clear message`() {
        val boundary = StatelessPlayerEngagement(clock = VirtualClock())
        boundary.timers.apply(
            TimerIntent.Arm(id = "x", delayMs = 0L, eventKey = "NotAPlayerEvent"),
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            boundary.fireDueTimeouts()
        }
        assertTrue(ex.message!!.contains("unknown timer eventKey=NotAPlayerEvent"))
    }

    @Test
    fun `fireDueTimeouts re-engage can refuse when schedule desynced from state`() {
        // TrackEnded is not on the Paused menu — arming it while Paused is a desync.
        val clock = VirtualClock()
        val boundary = StatelessPlayerEngagement(clock = clock)
        boundary.engage(PlayerEvent.Play)
        boundary.engage(PlayerEvent.Pause)
        assertEquals(PlayerState.Paused, boundary.state())
        assertTrue(boundary.timers.pending().isEmpty())
        boundary.player.clear()
        val refuseLogsBefore = boundary.crossCutting.log.snapshot()
            .count { it.kind == RecordKind.REFUSAL }

        // Manually inject a stale track-end schedule while state is Paused.
        boundary.timers.apply(
            TimerIntent.Arm(
                id = PLAYER_TRACK_TIMER_ID,
                delayMs = 10L,
                eventKey = PlayerEvent.TrackEnded.label(),
            ),
        )
        clock.advance(10L)
        val fired = boundary.fireDueTimeouts()

        assertEquals(1, fired.size)
        assertEquals(PlayerEvent.TrackEnded, fired.single().event)
        assertFalse(fired.single().result.accepted)
        assertTrue(fired.single().result.delivered.isEmpty())
        assertEquals(PlayerState.Paused, boundary.state())
        assertTrue(boundary.timers.pending().isEmpty())
        assertTrue(boundary.player.commands.isEmpty())
        assertEquals(
            refuseLogsBefore + 1,
            boundary.crossCutting.log.snapshot().count { it.kind == RecordKind.REFUSAL },
        )
    }

    // -------------------------------------------------------------------------
    // Multi-session = multiple engagement instances
    // -------------------------------------------------------------------------

    /**
     * Proves isolation by constructing two engagements (optionally sharing a clock).
     * Each instance owns its store, timer bag, player port, and CC env.
     */
    @Test
    fun `two engagement instances are independent for state timers and player`() {
        val clock = VirtualClock()
        val a = StatelessPlayerEngagement(sessionId = "a", clock = clock)
        val b = StatelessPlayerEngagement(sessionId = "b", clock = clock)
        a.engage(PlayerEvent.Play)
        b.engage(PlayerEvent.Play)
        a.engage(PlayerEvent.Pause)

        assertEquals(PlayerState.Paused, a.state())
        assertEquals(PlayerState.Playing, b.state())
        assertTrue(a.timers.pending().isEmpty())
        assertEquals(1, b.timers.pending().size)
        assertEquals(
            listOf(ThinPlayerCommand.Play, ThinPlayerCommand.Pause),
            a.player.commands,
        )
        assertEquals(listOf(ThinPlayerCommand.Play), b.player.commands)
    }

    // -------------------------------------------------------------------------
    // Boundary postconditions after applyOffer (order proven in BoundaryApplyTest)
    // -------------------------------------------------------------------------

    @Test
    fun `accept path stores state applies CC and delivers domain`() {
        val boundary = StatelessPlayerEngagement()
        val result = boundary.engage(PlayerEvent.Play)
        assertTrue(result.accepted)
        assertEquals(PlayerState.Playing, boundary.state())
        assertEquals(1, boundary.crossCutting.metrics.get("player.accept"))
        assertTrue(
            boundary.crossCutting.log.snapshot().any { it.kind == RecordKind.TRANSITION },
        )
        assertEquals(listOf(ThinPlayerCommand.Play), result.delivered)
        assertEquals(1, boundary.timers.pending().size)
    }

    @Test
    fun `refuse path stores self-successor and CC without domain`() {
        val boundary = StatelessPlayerEngagement()
        val result = boundary.engage(PlayerEvent.Pause)
        assertFalse(result.accepted)
        assertEquals(PlayerState.Idle, boundary.state())
        assertTrue(boundary.store.isPresent())
        assertTrue(
            boundary.crossCutting.log.snapshot().any { it.kind == RecordKind.REFUSAL },
        )
        assertEquals(1, boundary.crossCutting.metrics.get("player.refuse"))
        assertTrue(result.delivered.isEmpty())
        assertTrue(boundary.player.commands.isEmpty())
        assertTrue(boundary.timers.pending().isEmpty())
    }
}
