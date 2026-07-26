package fsm.sample.csp

import fsm.sample.machine.RecordingPort

/**
 * PURPOSE: FileProtocol **domain** effect ADT — I/O intents only (no audit wrappers).
 *
 * Cross-cutting accept/refuse (audit / metrics / trace) is `List<CrossCuttingEffect>` on
 * [fsm.sample.effect.OfferOutcome]. Domain-specific (only when accepted):
 * - [OpenHandle] / [ReadChunk] / [CloseHandle] → [FilePort] ops
 *
 * Teaching: refusal never emits domain I/O intents (multi-family cross-cutting only —
 * audit + refuse metric + trace, not audit-only).
 *
 * Domain policy (engagement-driven): domain ops fire on every accepted menu
 * engagement, including self-loop Read (state stays Opened).
 */
sealed class FileEffect {
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

/**
 * PURPOSE: In-memory domain port for file protocol I/O intents.
 * **Is** the [RecordingPort] bag — no private bag + pass-through shell.
 */
class FilePort : RecordingPort<FileOp>() {
    val ops: List<FileOp> get() = items

    fun snapshot(): String = "fileOps=${ops.map { it.label() }}"
}

/**
 * PURPOSE: Local domain boundary — I/O intents → [FilePort].
 * Cross-cutting is applied separately via [fsm.sample.effect.CrossCuttingInterpreter].
 */
fun applyDomainFile(domain: List<FileEffect>, port: FilePort) {
    for (effect in domain) {
        when (effect) {
            FileEffect.OpenHandle -> port.record(FileOp.Open)
            FileEffect.ReadChunk -> port.record(FileOp.Read)
            FileEffect.CloseHandle -> port.record(FileOp.Close)
        }
    }
}

/** PURPOSE: Export short name (pure label for Effects column). */
fun FileEffect.exportLabel(): String = when (this) {
    FileEffect.OpenHandle -> "OpenHandle"
    FileEffect.ReadChunk -> "ReadChunk"
    FileEffect.CloseHandle -> "CloseHandle"
}
