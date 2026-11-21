package fsm.sample.interproc

import fsm.sample.effect.TimerIntent
import fsm.sample.effect.exportLabel
import fsm.sample.machine.RecordingPort
import fsm.sample.machine.TimerPort

/**
 * PURPOSE: Music-player **domain** ADT for the inter-proc vignette.
 *
 * Domain splits into:
 * - [Remote]: commands delivered to a **thin remote player** (play / pause / unpause / next)
 * - [Timer]: pure [TimerIntent] Arm/Cancel applied to a boundary-owned [TimerPort]
 *   (not a live machine field; the engagement instance owns the bag)
 *
 * Cross-cutting stays server-side ([fsm.sample.effect.CrossCuttingEnv]); it is never
 * nested here. Pure [stepOffer] only declares; [StatelessPlayerEngagement] delivers
 * via [fsm.sample.machine.applyOffer] domain lambda.
 *
 * Teaching analogy: Alexa-style architecture — playlist / station decisions live on
 * the server brain; the device only executes thin player commands. Not a real skill.
 */

/**
 * Commands a thin remote player may execute.
 * Playlist/station selection is **not** in this ADT — server-side only.
 */
sealed class ThinPlayerCommand {
    data object Play : ThinPlayerCommand()
    data object Pause : ThinPlayerCommand()
    data object Unpause : ThinPlayerCommand()
    data object Next : ThinPlayerCommand()

    fun label(): String = when (this) {
        Play -> "Play"
        Pause -> "Pause"
        Unpause -> "Unpause"
        Next -> "Next"
    }
}

/**
 * Machine-local domain intents declared by pure [stepOffer].
 * On accept, the boundary fans out: [Remote] → [ThinPlayerPort], [Timer] → [TimerPort].
 */
sealed class PlayerEffect {
    /** Deliver [command] to the thin remote player. */
    data class Remote(val command: ThinPlayerCommand) : PlayerEffect()

    /** External schedule intent — boundary-owned bag, not co-located live field. */
    data class Timer(val intent: TimerIntent) : PlayerEffect()
}

/** Single track-end timer id for this teaching machine. */
const val PLAYER_TRACK_TIMER_ID = "player.track"

/** Default track length (ms) — pure constant, not read from a clock. */
const val TRACK_DURATION_MS = 180_000L

fun cancelTrackTimer(): PlayerEffect =
    PlayerEffect.Timer(TimerIntent.Cancel(PLAYER_TRACK_TIMER_ID))

fun armTrackTimer(): PlayerEffect =
    PlayerEffect.Timer(
        TimerIntent.Arm(
            id = PLAYER_TRACK_TIMER_ID,
            delayMs = TRACK_DURATION_MS,
            eventKey = PlayerEvent.TrackEnded.label(),
        ),
    )

/** Domain when entering Playing from Idle (first play). */
fun domainStartPlayback(): List<PlayerEffect> = listOf(
    cancelTrackTimer(),
    armTrackTimer(),
    PlayerEffect.Remote(ThinPlayerCommand.Play),
)

/** Domain when pausing: stop remote playback and cancel track-end. */
fun domainPause(): List<PlayerEffect> = listOf(
    cancelTrackTimer(),
    PlayerEffect.Remote(ThinPlayerCommand.Pause),
)

/** Domain when resuming from pause. */
fun domainResume(): List<PlayerEffect> = listOf(
    cancelTrackTimer(),
    armTrackTimer(),
    PlayerEffect.Remote(ThinPlayerCommand.Unpause),
)

/**
 * Domain for skip / track-end while **already Playing**:
 * next track on the thin player, re-arm track timer (playlist advance is server-side).
 * Player is already unpaused — only Next.
 */
fun domainAdvanceTrack(): List<PlayerEffect> = listOf(
    cancelTrackTimer(),
    armTrackTimer(),
    PlayerEffect.Remote(ThinPlayerCommand.Next),
)

/**
 * Domain for Skip while **Paused** → Playing:
 * Next then Unpause so the thin player advances track before leaving pause
 * (avoids briefly resuming the current track). Server state becomes Playing.
 * Order: Cancel → Arm → Next → Unpause (schedule first, then remote commands).
 */
fun domainSkipFromPaused(): List<PlayerEffect> = listOf(
    cancelTrackTimer(),
    armTrackTimer(),
    PlayerEffect.Remote(ThinPlayerCommand.Next),
    PlayerEffect.Remote(ThinPlayerCommand.Unpause),
)

/**
 * PURPOSE: Recording stand-in for a remote thin player RPC.
 * Records [ThinPlayerCommand]s in order — no real network I/O.
 * **Is** the [RecordingPort] bag (same pattern as in-process vignette ports).
 */
class ThinPlayerPort : RecordingPort<ThinPlayerCommand>() {
    val commands: List<ThinPlayerCommand> get() = items

    fun snapshot(): String = "playerCommands=${commands.map { it.label() }}"
}

/**
 * PURPOSE: Inter-proc domain fan-out — remote commands + boundary timer intents.
 * Called from the [fsm.sample.machine.applyOffer] domain lambda on accept only.
 * Cross-cutting is applied by [fsm.sample.machine.applyOffer], not here.
 */
fun applyDomainPlayerRemote(
    domain: List<PlayerEffect>,
    player: ThinPlayerPort,
    timers: TimerPort,
) {
    for (effect in domain) {
        when (effect) {
            is PlayerEffect.Remote -> player.record(effect.command)
            is PlayerEffect.Timer -> timers.apply(effect.intent)
        }
    }
}

/** PURPOSE: Export short name (pure label). */
fun PlayerEffect.exportLabel(): String = when (this) {
    is PlayerEffect.Remote -> "Remote.${command.label()}"
    is PlayerEffect.Timer -> intent.exportLabel()
}
