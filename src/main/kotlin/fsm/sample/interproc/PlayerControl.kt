package fsm.sample.interproc

import fsm.sample.effect.OfferOutcome
import fsm.sample.effect.acceptTransitionCrossCutting
import fsm.sample.effect.metricInc
import fsm.sample.effect.refuseCrossCutting

/**
 * PURPOSE: Pure partial (CSP-style) player-control machine for **inter-proc** teaching.
 *
 * This package is the **stateless / inter-process boundary** vignette family —
 * contrast with in-process live contexts in `flat/`, `csp/`, `gof/`, `orthogonal/`.
 * Pure surfaces stay the same shape (`stepOffer` → [OfferOutcome]); the boundary
 * still uses [fsm.sample.machine.applyOffer] with different assign / domain bindings
 * (external store + remote thin player + boundary-owned timers).
 *
 * Story (teaching analogy, not a product integration):
 * - Server brain: load session → pure [stepOffer] → [applyOffer] (persist + deliver)
 * - Thin player: only Play / Pause / Unpause / Next
 * - Track-end: pure [TimerIntent] → boundary schedule; due → re-engage as [PlayerEvent.TrackEnded]
 *
 * No live `var current`, no process-local timer field on a machine context.
 * See `docs/in-process-vs-interproc.md`.
 */

sealed class PlayerState {
    /** No active playback session. */
    data object Idle : PlayerState()

    /** Thin player is playing; track-end timer may be armed. */
    data object Playing : PlayerState()

    /** Thin player is paused; track-end timer cancelled. */
    data object Paused : PlayerState()

    fun label(): String = when (this) {
        Idle -> "Idle"
        Playing -> "Playing"
        Paused -> "Paused"
    }
}

sealed class PlayerEvent {
    /** Start playback from idle (or restart policy from idle only in this sample). */
    data object Play : PlayerEvent()

    /** Pause while playing. */
    data object Pause : PlayerEvent()

    /** Resume while paused. */
    data object Resume : PlayerEvent()

    /** Skip to next track (server decides what “next” is; player only hears Next). */
    data object Skip : PlayerEvent()

    /** Timer re-entry when the armed track duration elapses. */
    data object TrackEnded : PlayerEvent()

    fun label(): String = when (this) {
        Play -> "Play"
        Pause -> "Pause"
        Resume -> "Resume"
        Skip -> "Skip"
        TrackEnded -> "TrackEnded"
    }
}

/** Pure mapper: [TimerIntent.Arm.eventKey] → sealed [PlayerEvent]. */
fun playerEventFromTimerKey(eventKey: String): PlayerEvent? = when (eventKey) {
    PlayerEvent.TrackEnded.label() -> PlayerEvent.TrackEnded
    else -> null
}

/**
 * PURPOSE: Menu of legal ops per state (CSP partial).
 * Illegal events are refused with no domain list.
 */
fun menu(state: PlayerState): Set<PlayerEvent> = when (state) {
    PlayerState.Idle -> setOf(PlayerEvent.Play)
    PlayerState.Playing -> setOf(PlayerEvent.Pause, PlayerEvent.Skip, PlayerEvent.TrackEnded)
    PlayerState.Paused -> setOf(PlayerEvent.Resume, PlayerEvent.Skip)
}

/**
 * PURPOSE: Pure next-state on accepted engagements only.
 * Call only when [event] is in [menu]; otherwise stepOffer refuses first.
 */
fun pureWhenAccepted(state: PlayerState, event: PlayerEvent): PlayerState = when (state) {
    PlayerState.Idle -> when (event) {
        PlayerEvent.Play -> PlayerState.Playing
        PlayerEvent.Pause, PlayerEvent.Resume, PlayerEvent.Skip, PlayerEvent.TrackEnded ->
            error("pureWhenAccepted outside menu: $state + $event")
    }
    PlayerState.Playing -> when (event) {
        PlayerEvent.Pause -> PlayerState.Paused
        PlayerEvent.Skip, PlayerEvent.TrackEnded -> PlayerState.Playing
        PlayerEvent.Play, PlayerEvent.Resume ->
            error("pureWhenAccepted outside menu: $state + $event")
    }
    PlayerState.Paused -> when (event) {
        PlayerEvent.Resume -> PlayerState.Playing
        PlayerEvent.Skip -> PlayerState.Playing
        PlayerEvent.Play, PlayerEvent.Pause, PlayerEvent.TrackEnded ->
            error("pureWhenAccepted outside menu: $state + $event")
    }
}

/**
 * PURPOSE: Pure offer step — next + multi-family crossCutting + domain on accept only.
 * Does not touch session stores, thin player ports, timer bags, clocks, or CC env.
 *
 * Accept domain (order matters for remote + schedule fan-out):
 * - Play (Idle→Playing): Cancel, Arm, Remote.Play
 * - Pause: Cancel, Remote.Pause
 * - Resume: Cancel, Arm, Remote.Unpause
 * - Skip while Playing / TrackEnded: Cancel, Arm, Remote.Next
 * - Skip while Paused: Cancel, Arm, Remote.Next, Remote.Unpause
 *   (server → Playing; Next before Unpause so device does not resume current track)
 *
 * Refuse: cross-cutting only — domain unrepresentable on [OfferOutcome.Refused].
 */
fun stepOffer(
    state: PlayerState,
    event: PlayerEvent,
    machineName: String = "PlayerControl",
): OfferOutcome<PlayerState, PlayerEffect> {
    val offered = menu(state)
    if (event !in offered) {
        val reason =
            "event not in current menu ${offered.map { it.label() }}; refused"
        return OfferOutcome.Refused(
            reason = reason,
            next = state,
            crossCutting = refuseCrossCutting(
                machine = machineName,
                state = state.label(),
                event = event.label(),
                reason = reason,
                metricName = "player.refuse",
            ),
        )
    }
    val next = pureWhenAccepted(state, event)
    val domain = when (event) {
        PlayerEvent.Play -> domainStartPlayback()
        PlayerEvent.Pause -> domainPause()
        PlayerEvent.Resume -> domainResume()
        PlayerEvent.TrackEnded -> domainAdvanceTrack()
        PlayerEvent.Skip -> when (state) {
            PlayerState.Paused -> domainSkipFromPaused()
            PlayerState.Playing -> domainAdvanceTrack()
            PlayerState.Idle -> error("Skip accepted outside menu: Idle")
        }
    }
    return OfferOutcome.Accepted(
        next = next,
        crossCutting = acceptTransitionCrossCutting(
            machine = machineName,
            from = state.label(),
            event = event.label(),
            to = next.label(),
            extras = listOf(metricInc("player.accept")),
        ),
        domain = domain,
    )
}
