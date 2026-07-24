package fsm.sample.gof

/**
 * PURPOSE: Connection **domain** effect ADT — session/heartbeat intents only.
 *
 * Cross-cutting is a plain `List<CrossCuttingEffect>` assembled by [planTransition]
 * / [declaredEffects], not nested here.
 *
 * Domain-specific (this machine only):
 * - [IncHeartbeat] — Online superstate reaction
 * - [OpenSession] — session lifecycle when entering Online composite
 * - [CloseSession] — session lifecycle when leaving Online composite
 *
 * Separate sealed hierarchy: [fsm.sample.flat.LightEffect] cannot hold these cases.
 * All domain application goes through [ConnectionPort] (including heartbeats).
 */
sealed class ConnectionEffect {
    data object IncHeartbeat : ConnectionEffect()

    /** Domain: open network session (paired with Online enter). */
    data object OpenSession : ConnectionEffect()

    /** Domain: close network session (paired with Online exit). */
    data object CloseSession : ConnectionEffect()
}

/** PURPOSE: Domain session ops recorded on [ConnectionPort]. */
sealed class SessionOp {
    data object Opened : SessionOp()
    data object Closed : SessionOp()
    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: In-memory domain port for connection session + heartbeat.
 * Single sink for all Connection domain effects (no separate heartbeat callback).
 */
class ConnectionPort {
    private val _sessions = mutableListOf<SessionOp>()
    val sessions: List<SessionOp> get() = _sessions.toList()

    /** Heartbeat counter applied only via [incHeartbeat] at the domain boundary. */
    var heartbeats: Int = 0
        private set

    fun record(op: SessionOp) {
        _sessions += op
    }

    fun incHeartbeat() {
        heartbeats += 1
    }

    fun clear() {
        _sessions.clear()
        heartbeats = 0
    }

    fun snapshot(): String =
        "sessions=${sessions.map { it.label() }} heartbeats=$heartbeats"
}

/**
 * PURPOSE: Local domain boundary for Connection.
 * All domain cases → [ConnectionPort]; audit is applied separately.
 */
fun applyDomainConnection(domain: List<ConnectionEffect>, port: ConnectionPort) {
    for (effect in domain) {
        when (effect) {
            ConnectionEffect.IncHeartbeat -> port.incHeartbeat()
            ConnectionEffect.OpenSession -> port.record(SessionOp.Opened)
            ConnectionEffect.CloseSession -> port.record(SessionOp.Closed)
        }
    }
}

/** PURPOSE: Export short name (pure label for Effects column). */
fun ConnectionEffect.exportLabel(): String = when (this) {
    ConnectionEffect.IncHeartbeat -> "IncHeartbeat"
    ConnectionEffect.OpenSession -> "OpenSession"
    ConnectionEffect.CloseSession -> "CloseSession"
}
