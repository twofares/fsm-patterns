package fsm.sample.gof

import fsm.sample.effect.TimerIntent
import fsm.sample.effect.exportLabel
import fsm.sample.machine.RecordingPort
import fsm.sample.machine.TimerPort

/**
 * PURPOSE: Connection **domain** effect ADT — session/heartbeat intents + timer scheduling.
 *
 * Cross-cutting is a plain `List<CrossCuttingEffect>` assembled into
 * [fsm.sample.machine.FragmentedStepEffects] by shared [fsm.sample.machine.planTransition]
 * / vignette [declaredEffects], not nested here.
 *
 * Domain-specific (this machine only):
 * - [IncHeartbeat] — Online superstate reaction
 * - [OpenSession] — session lifecycle when entering Online composite
 * - [CloseSession] — session lifecycle when leaving Online composite
 * - [Timer] — Online watchdog arm/cancel ([TimerIntent]); applied to [TimerPort], not [ConnectionPort]
 *
 * Separate sealed hierarchy: [fsm.sample.flat.LightEffect] cannot hold these cases.
 * Multi-port fan-out of **one** domain list (session port + timer port) — not a third outcome field.
 */
sealed class ConnectionEffect {
    data object IncHeartbeat : ConnectionEffect()

    /** Domain: open network session (paired with Online enter). */
    data object OpenSession : ConnectionEffect()

    /** Domain: close network session (paired with Online exit). */
    data object CloseSession : ConnectionEffect()

    /** Domain scheduling intent — applied to [TimerPort], not [ConnectionPort]. */
    data class Timer(val intent: TimerIntent) : ConnectionEffect()
}

/** PURPOSE: Domain session ops recorded on [ConnectionPort]. */
sealed class SessionOp {
    data object Opened : SessionOp()
    data object Closed : SessionOp()
    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: In-memory domain port for connection session + heartbeat.
 * Single sink for session/heartbeat domain effects (timers use [TimerPort]).
 *
 * **Is** the session [RecordingPort] bag; heartbeat counter stays specialized here.
 * No private bag + pass-through shell.
 */
class ConnectionPort : RecordingPort<SessionOp>() {
    val sessions: List<SessionOp> get() = items

    /** Heartbeat counter applied only via [incHeartbeat] at the domain boundary. */
    var heartbeats: Int = 0
        private set

    fun incHeartbeat() {
        heartbeats += 1
    }

    override fun clear() {
        super.clear()
        heartbeats = 0
    }

    fun snapshot(): String =
        "sessions=${sessions.map { it.label() }} heartbeats=$heartbeats"
}

/**
 * PURPOSE: Local domain boundary for Connection — multi-port fan-out of one domain list.
 * Session/heartbeat cases → [ConnectionPort]; timer intents → [TimerPort] **inline**
 * in list order (Cancel before CloseSession is load-bearing).
 */
fun applyDomainConnection(
    domain: List<ConnectionEffect>,
    port: ConnectionPort,
    timers: TimerPort,
) {
    for (effect in domain) {
        when (effect) {
            ConnectionEffect.IncHeartbeat -> port.incHeartbeat()
            ConnectionEffect.OpenSession -> port.record(SessionOp.Opened)
            ConnectionEffect.CloseSession -> port.record(SessionOp.Closed)
            is ConnectionEffect.Timer -> timers.apply(effect.intent)
        }
    }
}

/** PURPOSE: Export short name (pure label for Effects column). */
fun ConnectionEffect.exportLabel(): String = when (this) {
    ConnectionEffect.IncHeartbeat -> "IncHeartbeat"
    ConnectionEffect.OpenSession -> "OpenSession"
    ConnectionEffect.CloseSession -> "CloseSession"
    is ConnectionEffect.Timer -> intent.exportLabel()
}

/** Single Online watchdog timer id for this machine (teaching default: one id per machine). */
const val CONNECTION_WATCHDOG_ID = "connection.online.watchdog"

/** Absolute Online watchdog delay (ms) — pure constant, not read from a clock. */
const val CONNECTION_WATCHDOG_MS = 30_000L
