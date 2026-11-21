package fsm.sample.interproc

import fsm.sample.effect.OfferOutcome
import fsm.sample.testsupport.assertRefusedOutcomeHasNoDomain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Pure [stepOffer] / menu / refuse matrix / domain-label oracles for
 * PlayerControl — no store, ports, clocks, or engagement boundary.
 */
class PlayerControlTest {

    // -------------------------------------------------------------------------
    // Menu / refuse matrix (CSP-style)
    // -------------------------------------------------------------------------

    @Test
    fun `menu per state matches legal ops`() {
        assertEquals(setOf(PlayerEvent.Play), menu(PlayerState.Idle))
        assertEquals(
            setOf(PlayerEvent.Pause, PlayerEvent.Skip, PlayerEvent.TrackEnded),
            menu(PlayerState.Playing),
        )
        assertEquals(
            setOf(PlayerEvent.Resume, PlayerEvent.Skip),
            menu(PlayerState.Paused),
        )
    }

    @Test
    fun `refuse matrix covers out-of-menu edges`() {
        val cases = listOf(
            PlayerState.Idle to PlayerEvent.Pause,
            PlayerState.Idle to PlayerEvent.Resume,
            PlayerState.Idle to PlayerEvent.Skip,
            PlayerState.Idle to PlayerEvent.TrackEnded,
            PlayerState.Playing to PlayerEvent.Play,
            PlayerState.Playing to PlayerEvent.Resume,
            PlayerState.Paused to PlayerEvent.Play,
            PlayerState.Paused to PlayerEvent.Pause,
            PlayerState.Paused to PlayerEvent.TrackEnded,
        )
        for ((state, event) in cases) {
            val outcome = stepOffer(state, event)
            assertRefusedOutcomeHasNoDomain(outcome)
            assertEquals(state, outcome.next, "refuse self-successor for $state + $event")
        }
    }

    // -------------------------------------------------------------------------
    // Pure stepOffer accept domain oracles
    // -------------------------------------------------------------------------

    @Test
    fun `pure stepOffer Play from Idle accepts with remote Play and track Arm`() {
        val outcome = stepOffer(PlayerState.Idle, PlayerEvent.Play)
        assertTrue(outcome is OfferOutcome.Accepted)
        val accepted = outcome as OfferOutcome.Accepted
        assertEquals(PlayerState.Playing, accepted.next)
        assertEquals(
            listOf(
                "Timer.Cancel(player.track)",
                "Timer.Arm(player.track,${TRACK_DURATION_MS}ms,TrackEnded)",
                "Remote.Play",
            ),
            accepted.domain.map { it.exportLabel() },
        )
    }

    @Test
    fun `pure stepOffer Pause from Playing cancels timer and Remote Pause`() {
        val outcome = stepOffer(PlayerState.Playing, PlayerEvent.Pause)
        assertTrue(outcome is OfferOutcome.Accepted)
        val accepted = outcome as OfferOutcome.Accepted
        assertEquals(PlayerState.Paused, accepted.next)
        assertEquals(
            listOf("Timer.Cancel(player.track)", "Remote.Pause"),
            accepted.domain.map { it.exportLabel() },
        )
    }

    @Test
    fun `pure stepOffer Resume from Paused arms and Unpause`() {
        val outcome = stepOffer(PlayerState.Paused, PlayerEvent.Resume)
        assertTrue(outcome is OfferOutcome.Accepted)
        val accepted = outcome as OfferOutcome.Accepted
        assertEquals(PlayerState.Playing, accepted.next)
        assertEquals(
            listOf(
                "Timer.Cancel(player.track)",
                "Timer.Arm(player.track,${TRACK_DURATION_MS}ms,TrackEnded)",
                "Remote.Unpause",
            ),
            accepted.domain.map { it.exportLabel() },
        )
    }

    @Test
    fun `pure stepOffer Skip from Playing is Next only`() {
        val outcome = stepOffer(PlayerState.Playing, PlayerEvent.Skip)
        assertTrue(outcome is OfferOutcome.Accepted)
        val accepted = outcome as OfferOutcome.Accepted
        assertEquals(PlayerState.Playing, accepted.next)
        assertEquals(
            listOf(
                "Timer.Cancel(player.track)",
                "Timer.Arm(player.track,${TRACK_DURATION_MS}ms,TrackEnded)",
                "Remote.Next",
            ),
            accepted.domain.map { it.exportLabel() },
        )
    }

    @Test
    fun `pure stepOffer Skip from Paused is Next then Unpause`() {
        val outcome = stepOffer(PlayerState.Paused, PlayerEvent.Skip)
        assertTrue(outcome is OfferOutcome.Accepted)
        val accepted = outcome as OfferOutcome.Accepted
        assertEquals(PlayerState.Playing, accepted.next)
        assertEquals(
            listOf(
                "Timer.Cancel(player.track)",
                "Timer.Arm(player.track,${TRACK_DURATION_MS}ms,TrackEnded)",
                "Remote.Next",
                "Remote.Unpause",
            ),
            accepted.domain.map { it.exportLabel() },
        )
    }

    @Test
    fun `pure stepOffer TrackEnded from Playing is Next only`() {
        val outcome = stepOffer(PlayerState.Playing, PlayerEvent.TrackEnded)
        assertTrue(outcome is OfferOutcome.Accepted)
        val accepted = outcome as OfferOutcome.Accepted
        assertEquals(PlayerState.Playing, accepted.next)
        assertEquals(
            listOf(
                "Timer.Cancel(player.track)",
                "Timer.Arm(player.track,${TRACK_DURATION_MS}ms,TrackEnded)",
                "Remote.Next",
            ),
            accepted.domain.map { it.exportLabel() },
        )
    }

    @Test
    fun `playerEventFromTimerKey maps TrackEnded and rejects unknown`() {
        assertEquals(PlayerEvent.TrackEnded, playerEventFromTimerKey("TrackEnded"))
        assertEquals(null, playerEventFromTimerKey("bogus"))
        assertEquals(null, playerEventFromTimerKey(""))
    }
}
