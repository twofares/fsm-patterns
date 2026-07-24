package fsm.sample.csp

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: FileProtocol **domain** effect ADT — CSP accept/refuse + I/O intents.
 *
 * Cross-cutting:
 * - [Audit] LogTransition on accept, LogRefusal on refuse → [TransitionLog]
 *
 * Domain-specific (only when accepted — refuse is audit-only):
 * - [OpenHandle] / [ReadChunk] / [CloseHandle] → [FilePort] ops
 *
 * Teaching: refusal never emits domain I/O intents.
 */
sealed class FileEffect {
    data class Audit(val audit: AuditEffect) : FileEffect()

    /** Domain: open the file handle (accepted Open). */
    data object OpenHandle : FileEffect()

    /** Domain: read a chunk (accepted Read). */
    data object ReadChunk : FileEffect()

    /** Domain: close the handle (accepted Close). */
    data object CloseHandle : FileEffect()
}

/** PURPOSE: Sealed domain ops recorded on [FilePort] (no real FS). */
sealed class FileOp {
    data object Open : FileOp()
    data object Read : FileOp()
    data object Close : FileOp()
    fun label(): String = this::class.simpleName ?: toString()
}

/** PURPOSE: In-memory domain port for file protocol I/O intents. */
class FilePort {
    private val _ops = mutableListOf<FileOp>()
    val ops: List<FileOp> get() = _ops.toList()

    fun record(op: FileOp) {
        _ops += op
    }

    fun clear() {
        _ops.clear()
    }

    fun snapshot(): String = "fileOps=${ops.map { it.label() }}"
}

fun fileTransition(
    machine: String,
    from: String,
    event: String,
    to: String,
): FileEffect = FileEffect.Audit(auditTransition(machine, from, event, to))

fun fileRefusal(
    machine: String,
    state: String,
    event: String,
    reason: String,
): FileEffect = FileEffect.Audit(auditRefusal(machine, state, event, reason))

/**
 * PURPOSE: Local boundary — audit → log; domain I/O intents → [FilePort].
 */
fun interpretFile(
    effects: List<FileEffect>,
    log: TransitionLog? = null,
    port: FilePort? = null,
) {
    for (effect in effects) {
        when (effect) {
            is FileEffect.Audit -> AuditInterpreter.applyOne(effect.audit, log)
            FileEffect.OpenHandle -> port?.record(FileOp.Open)
            FileEffect.ReadChunk -> port?.record(FileOp.Read)
            FileEffect.CloseHandle -> port?.record(FileOp.Close)
        }
    }
}

fun FileEffect.exportLabel(): String = when (this) {
    is FileEffect.Audit -> audit.exportLabel()
    FileEffect.OpenHandle -> "OpenHandle"
    FileEffect.ReadChunk -> "ReadChunk"
    FileEffect.CloseHandle -> "CloseHandle"
}
