package fsm.sample.csp

import fsm.sample.effect.AuditEffect
import fsm.sample.effect.AuditInterpreter
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.exportLabel
import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: CSP-style process discipline in ordinary sequential code — no engine.
 *
 * Communicating Sequential Processes (Hoare) decomposes systems into processes
 * that interact only through synchronized events, not shared-variable interference.
 * The portable encoding used here:
 * - Explicit event alphabet for the whole process
 * - Per-state "menu" (acceptance set): only events in the menu are offered
 * - Refusal of non-menu events (plain [AuditEffect] refusal, state unchanged)
 * - Prefix / choice encoded as pure State + Event → State when accepted
 * - Pure [stepOffer] returns sealed [OfferStep] (control + split audit/domain);
 *   boundary [offer] assigns + applies audit/domain without scanning log vocabulary
 *
 * Domain vignette: a tiny file protocol process
 *   Closed --Open--> Opened --Read--> Opened --Close--> Closed
 *   From Closed, Read and Close are refused; from Opened, Open is refused.
 *
 * This is not a full FDR/CSP model checker — it is the *discipline* (explicit
 * alphabet + refusal) expressed as readable Kotlin so agents can copy the pattern.
 */

/** PURPOSE: Process states (modes of the file protocol). */
sealed class FileState {
    data object Closed : FileState()
    data object Opened : FileState()
    fun label(): String = when (this) {
        Closed -> "Closed"
        Opened -> "Opened"
    }
}

/**
 * PURPOSE: Full event alphabet of the process.
 * CSP processes declare which events they may ever engage in.
 */
sealed class FileEvent {
    data object Open : FileEvent()
    data object Read : FileEvent()
    data object Close : FileEvent()
    fun label(): String = this::class.simpleName ?: toString()
}

/**
 * PURPOSE: Result of offering an event to the process (boundary API).
 * [Accepted] carries the successor state; [Refused] keeps state and records why.
 */
sealed class OfferResult {
    data class Accepted(val next: FileState) : OfferResult()
    data class Refused(val reason: String) : OfferResult()
}

/**
 * PURPOSE: Pure CSP offer step outcome — control flow separate from dual sinks.
 *
 * [Accepted] carries split [audit] + [domain]; [Refused] is **audit-only** (no domain
 * field — refuse cannot declare I/O intents). [next] is always defined (refusal keeps
 * prior state). Logging vocabulary stays observational and is not used to classify
 * accept vs refuse at the boundary.
 */
sealed class OfferStep {
    abstract val next: FileState
    abstract val audit: List<AuditEffect>

    data class Accepted(
        override val next: FileState,
        override val audit: List<AuditEffect>,
        val domain: List<FileEffect>,
    ) : OfferStep()

    data class Refused(
        val reason: String,
        override val next: FileState,
        override val audit: List<AuditEffect>,
    ) : OfferStep()
}

/** PURPOSE: Ordered export labels — Accepted: audit then domain; Refused: audit only. */
fun OfferStep.exportLabels(): List<String> = when (this) {
    is OfferStep.Accepted ->
        audit.map { it.exportLabel() } + domain.map { it.exportLabel() }
    is OfferStep.Refused ->
        audit.map { it.exportLabel() }
}

/**
 * PURPOSE: CSP-style process with explicit alphabet and per-state menus.
 *
 * [alphabet] is the process-wide event set.
 * [menu] is the currently offered events (state-dependent acceptance).
 * [stepOffer] is pure: sealed [OfferStep] with next + split audit/domain.
 * [offer] is the boundary: assign state + apply audit/domain.
 */
class FileProtocol(
    initial: FileState = FileState.Closed,
    private val log: TransitionLog? = null,
    val port: FilePort = FilePort(),
    private val machineName: String = "FileProtocol",
) {
    var current: FileState = initial
        private set

    /** PURPOSE: Explicit full alphabet of this process (CSP discipline). */
    val alphabet: Set<FileEvent> = setOf(
        FileEvent.Open,
        FileEvent.Read,
        FileEvent.Close,
    )

    /**
     * PURPOSE: Current menu — events the process is willing to engage in now.
     * Events in the alphabet but not in the menu are refused (CSP refusal set).
     */
    fun menu(state: FileState = current): Set<FileEvent> = when (state) {
        FileState.Closed -> setOf(FileEvent.Open)
        FileState.Opened -> setOf(FileEvent.Read, FileEvent.Close)
    }

    /**
     * PURPOSE: Pure transition when [event] is in the current menu.
     * Exhaustive for accepted pairs; illegal pairs are never called from [stepOffer].
     * Kept pure for next-state math; export product tables prefer [stepOffer].
     */
    fun pureWhenAccepted(state: FileState, event: FileEvent): FileState = when (state) {
        FileState.Closed -> when (event) {
            FileEvent.Open -> FileState.Opened
            FileEvent.Read, FileEvent.Close ->
                error("pureWhenAccepted called outside menu: $state + $event")
        }
        FileState.Opened -> when (event) {
            FileEvent.Read -> FileState.Opened // prefix self-loop: stay open after read
            FileEvent.Close -> FileState.Closed
            FileEvent.Open ->
                error("pureWhenAccepted called outside menu: $state + $event")
        }
    }

    /**
     * PURPOSE: Pure CSP offer step — sealed control outcome + split audit/domain.
     * Does not mutate [current] or touch log/port; tests assert [OfferStep] lists.
     */
    fun stepOffer(
        state: FileState,
        event: FileEvent,
    ): OfferStep {
        if (event !in alphabet) {
            val reason = "event outside process alphabet"
            return OfferStep.Refused(
                reason = reason,
                next = state,
                audit = listOf(
                    auditRefusal(machineName, state.label(), event.label(), reason),
                ),
            )
        }
        val offered = menu(state)
        if (event !in offered) {
            val reason =
                "event not in current menu ${offered.map { it.label() }}; refused"
            return OfferStep.Refused(
                reason = reason,
                next = state,
                audit = listOf(
                    auditRefusal(machineName, state.label(), event.label(), reason),
                ),
            )
        }
        val next = pureWhenAccepted(state, event)
        // Accept: cross-cutting audit + domain I/O intent for this engagement.
        val domain = when (event) {
            FileEvent.Open -> FileEffect.OpenHandle
            FileEvent.Read -> FileEffect.ReadChunk
            FileEvent.Close -> FileEffect.CloseHandle
        }
        return OfferStep.Accepted(
            next = next,
            audit = listOf(
                auditTransition(
                    machine = machineName,
                    from = state.label(),
                    event = event.label(),
                    to = next.label(),
                ),
            ),
            domain = listOf(domain),
        )
    }

    /**
     * PURPOSE: Offer an event (CSP engagement). Boundary: assign + apply sinks.
     * Maps sealed [OfferStep] to [OfferResult] without scanning log vocabulary.
     * Accept applies audit + domain; refuse applies audit only (no domain field).
     */
    fun offer(event: FileEvent): OfferResult {
        return when (val result = stepOffer(current, event)) {
            is OfferStep.Accepted -> {
                current = result.next
                AuditInterpreter.apply(result.audit, log)
                applyDomainFile(result.domain, port)
                OfferResult.Accepted(result.next)
            }
            is OfferStep.Refused -> {
                current = result.next
                AuditInterpreter.apply(result.audit, log)
                OfferResult.Refused(result.reason)
            }
        }
    }
}
