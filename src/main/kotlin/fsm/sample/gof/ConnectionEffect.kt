package fsm.sample.gof

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditEntry
import fsm.sample.effect.auditExit
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: Connection **domain** effect ADT — rich domain vs cross-cutting demo.
 *
 * Cross-cutting ([Audit]):
 * - LogTransition / LogEntry / LogExit / LogRefusal → [TransitionLog]
 *
 * Domain-specific (this machine only):
 * - [IncHeartbeat] — Online superstate reaction
 * - [OpenSession] — session lifecycle when entering Online composite
 * - [CloseSession] — session lifecycle when leaving Online composite
 *
 * Separate sealed hierarchy: [LightEffect] cannot hold these domain cases.
 */
sealed class ConnectionEffect {
    data class Audit(val audit: AuditEffect) : ConnectionEffect()

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

/** PURPOSE: In-memory domain port for connection session lifecycle. */
class ConnectionPort {
    private val _sessions = mutableListOf<SessionOp>()
    val sessions: List<SessionOp> get() = _sessions.toList()

    fun record(op: SessionOp) {
        _sessions += op
    }

    fun clear() {
        _sessions.clear()
    }

    fun snapshot(): String = "sessions=${sessions.map { it.label() }}"
}

fun connTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): ConnectionEffect = ConnectionEffect.Audit(auditTransition(machine, from, event, to))

fun connEntry(
    machine: String,
    state: String,
    detail: String? = null,
): ConnectionEffect = ConnectionEffect.Audit(auditEntry(machine, state, detail))

fun connExit(
    machine: String,
    state: String,
    detail: String? = null,
): ConnectionEffect = ConnectionEffect.Audit(auditExit(machine, state, detail))

fun connRefusal(
    machine: String,
    state: String,
    event: String,
    reason: String,
): ConnectionEffect = ConnectionEffect.Audit(auditRefusal(machine, state, event, reason))

/**
 * PURPOSE: Local Connection interpreter.
 * - Audit → [log]
 * - IncHeartbeat → required [onIncHeartbeat]
 * - OpenSession / CloseSession → [port]
 */
fun interpretConnection(
    effects: List<ConnectionEffect>,
    log: TransitionLog? = null,
    port: ConnectionPort? = null,
    onIncHeartbeat: () -> Unit,
) {
    for (effect in effects) {
        when (effect) {
            is ConnectionEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
            ConnectionEffect.IncHeartbeat -> onIncHeartbeat()
            ConnectionEffect.OpenSession -> port?.record(SessionOp.Opened)
            ConnectionEffect.CloseSession -> port?.record(SessionOp.Closed)
        }
    }
}

fun ConnectionEffect.exportLabel(): String = when (this) {
    is ConnectionEffect.Audit -> audit.exportLabel()
    ConnectionEffect.IncHeartbeat -> "IncHeartbeat"
    ConnectionEffect.OpenSession -> "OpenSession"
    ConnectionEffect.CloseSession -> "CloseSession"
}
