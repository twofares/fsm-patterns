package fsm.sample.csp

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.OfferOutcome
import fsm.sample.effect.acceptTransitionCrossCutting
import fsm.sample.effect.exportLabels
import fsm.sample.effect.metricInc
import fsm.sample.effect.refuseCrossCutting
import fsm.sample.machine.applyOffer

/**
 * PURPOSE: CSP process with multi-family **cross-cutting** + domain I/O intents.
 *
 * Cross-cutting (shared [CrossCuttingEnv]):
 * - Audit.LogTransition / LogRefusal
 * - Metric.Inc("csp.accept" | "csp.refuse")
 * - Trace.Span on refuse (policy diagnostic) — a second non-audit family
 *
 * Domain (accept only): OpenHandle / ReadChunk / CloseHandle → [FilePort]
 * Refuse: cross-cutting only (metrics + trace + audit), never domain I/O.
 */

sealed class FileState {
    data object Closed : FileState()
    data object Opened : FileState()
    fun label(): String = when (this) {
        Closed -> "Closed"
        Opened -> "Opened"
    }
}

sealed class FileEvent {
    data object Open : FileEvent()
    data object Read : FileEvent()
    data object Close : FileEvent()
    fun label(): String = this::class.simpleName ?: toString()
}

/** PURPOSE: CSP-local client DTO from [FileProtocol.offer] (not the pure outcome). */
sealed class OfferResult {
    data class Accepted(val next: FileState) : OfferResult()
    data class Refused(val reason: String) : OfferResult()
}

/** PURPOSE: Parameterless export labels for FileProtocol pure offers. */
fun OfferOutcome<FileState, FileEffect>.exportLabels(): List<String> =
    exportLabels(FileEffect::exportLabel)

class FileProtocol(
    initial: FileState = FileState.Closed,
    val crossCutting: CrossCuttingEnv = CrossCuttingEnv(),
    val port: FilePort = FilePort(),
    private val machineName: String = "FileProtocol",
) {
    var current: FileState = initial
        private set

    val alphabet: Set<FileEvent> = setOf(
        FileEvent.Open,
        FileEvent.Read,
        FileEvent.Close,
    )

    fun menu(state: FileState = current): Set<FileEvent> = when (state) {
        FileState.Closed -> setOf(FileEvent.Open)
        FileState.Opened -> setOf(FileEvent.Read, FileEvent.Close)
    }

    fun pureWhenAccepted(state: FileState, event: FileEvent): FileState = when (state) {
        FileState.Closed -> when (event) {
            FileEvent.Open -> FileState.Opened
            FileEvent.Read, FileEvent.Close ->
                error("pureWhenAccepted called outside menu: $state + $event")
        }
        FileState.Opened -> when (event) {
            FileEvent.Read -> FileState.Opened
            FileEvent.Close -> FileState.Closed
            FileEvent.Open ->
                error("pureWhenAccepted called outside menu: $state + $event")
        }
    }

    fun stepOffer(state: FileState, event: FileEvent): OfferOutcome<FileState, FileEffect> {
        if (event !in alphabet) {
            val reason = "event outside process alphabet"
            return OfferOutcome.Refused(
                reason = reason,
                next = state,
                crossCutting = refuseCrossCutting(
                    machine = machineName,
                    state = state.label(),
                    event = event.label(),
                    reason = reason,
                    metricName = "csp.refuse",
                ),
            )
        }
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
                    metricName = "csp.refuse",
                ),
            )
        }
        val next = pureWhenAccepted(state, event)
        val domain = when (event) {
            FileEvent.Open -> FileEffect.OpenHandle
            FileEvent.Read -> FileEffect.ReadChunk
            FileEvent.Close -> FileEffect.CloseHandle
        }
        return OfferOutcome.Accepted(
            next = next,
            crossCutting = acceptTransitionCrossCutting(
                machine = machineName,
                from = state.label(),
                event = event.label(),
                to = next.label(),
                extras = listOf(metricInc("csp.accept")),
            ),
            domain = listOf(domain),
        )
    }

    fun offer(event: FileEvent): OfferResult {
        val result = stepOffer(current, event)
        applyOffer(result, crossCutting, port, ::applyDomainFile) { current = it }
        return when (result) {
            is OfferOutcome.Accepted -> OfferResult.Accepted(result.next)
            is OfferOutcome.Refused -> OfferResult.Refused(result.reason)
        }
    }
}
