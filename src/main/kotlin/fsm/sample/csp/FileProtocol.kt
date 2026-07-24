package fsm.sample.csp

import fsm.sample.effect.CrossCuttingEffect
import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.CrossCuttingInterpreter
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.metricInc
import fsm.sample.effect.traceSpan

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

sealed class OfferResult {
    data class Accepted(val next: FileState) : OfferResult()
    data class Refused(val reason: String) : OfferResult()
}

/**
 * PURPOSE: Pure offer outcome — [Accepted] has domain I/O; [Refused] is cross-cutting only.
 */
sealed class OfferStep {
    abstract val next: FileState
    abstract val crossCutting: List<CrossCuttingEffect>

    data class Accepted(
        override val next: FileState,
        override val crossCutting: List<CrossCuttingEffect>,
        val domain: List<FileEffect>,
    ) : OfferStep()

    data class Refused(
        val reason: String,
        override val next: FileState,
        override val crossCutting: List<CrossCuttingEffect>,
    ) : OfferStep()
}

fun OfferStep.exportLabels(): List<String> = when (this) {
    is OfferStep.Accepted ->
        crossCutting.map { it.exportLabel() } + domain.map { it.exportLabel() }
    is OfferStep.Refused ->
        crossCutting.map { it.exportLabel() }
}

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

    fun stepOffer(state: FileState, event: FileEvent): OfferStep {
        if (event !in alphabet) {
            val reason = "event outside process alphabet"
            return OfferStep.Refused(
                reason = reason,
                next = state,
                crossCutting = refuseCrossCutting(state, event, reason),
            )
        }
        val offered = menu(state)
        if (event !in offered) {
            val reason =
                "event not in current menu ${offered.map { it.label() }}; refused"
            return OfferStep.Refused(
                reason = reason,
                next = state,
                crossCutting = refuseCrossCutting(state, event, reason),
            )
        }
        val next = pureWhenAccepted(state, event)
        val domain = when (event) {
            FileEvent.Open -> FileEffect.OpenHandle
            FileEvent.Read -> FileEffect.ReadChunk
            FileEvent.Close -> FileEffect.CloseHandle
        }
        return OfferStep.Accepted(
            next = next,
            crossCutting = listOf(
                auditTransition(
                    machine = machineName,
                    from = state.label(),
                    event = event.label(),
                    to = next.label(),
                ),
                metricInc("fsm.transitions"),
                metricInc("csp.accept"),
            ),
            domain = listOf(domain),
        )
    }

    private fun refuseCrossCutting(
        state: FileState,
        event: FileEvent,
        reason: String,
    ): List<CrossCuttingEffect> = listOf(
        auditRefusal(machineName, state.label(), event.label(), reason),
        metricInc("csp.refuse"),
        traceSpan("csp.refuse", reason),
    )

    fun offer(event: FileEvent): OfferResult {
        return when (val result = stepOffer(current, event)) {
            is OfferStep.Accepted -> {
                current = result.next
                CrossCuttingInterpreter.apply(result.crossCutting, crossCutting)
                applyDomainFile(result.domain, port)
                OfferResult.Accepted(result.next)
            }
            is OfferStep.Refused -> {
                current = result.next
                CrossCuttingInterpreter.apply(result.crossCutting, crossCutting)
                OfferResult.Refused(result.reason)
            }
        }
    }
}
