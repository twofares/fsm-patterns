package fsm.sample.csp

import fsm.sample.logging.TransitionLog

/**
 * PURPOSE: CSP-style process discipline in ordinary sequential code — no engine.
 *
 * Communicating Sequential Processes (Hoare) decomposes systems into processes
 * that interact only through synchronized events, not shared-variable interference.
 * The portable encoding used here:
 * - Explicit event alphabet for the whole process
 * - Per-state "menu" (acceptance set): only events in the menu are offered
 * - Refusal of non-menu events (logged, state unchanged)
 * - Prefix / choice encoded as pure State + Event → State when accepted
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
 * PURPOSE: Result of offering an event to the process.
 * [Accepted] carries the successor state; [Refused] keeps state and records why.
 */
sealed class OfferResult {
    data class Accepted(val next: FileState) : OfferResult()
    data class Refused(val reason: String) : OfferResult()
}

/**
 * PURPOSE: CSP-style process with explicit alphabet and per-state menus.
 *
 * [alphabet] is the process-wide event set.
 * [menu] is the currently offered events (state-dependent acceptance).
 * [offer] refuses anything outside the current menu without mutating state.
 */
class FileProtocol(
    initial: FileState = FileState.Closed,
    private val log: TransitionLog? = null,
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
     * Exhaustive for accepted pairs; illegal pairs are never called from [offer].
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
     * PURPOSE: Offer an event (CSP engagement). Refuse non-menu events.
     * Also refuse events outside the process alphabet (extra safety).
     */
    fun offer(event: FileEvent): OfferResult {
        if (event !in alphabet) {
            val reason = "event outside process alphabet"
            log?.recordRefusal(machineName, current.label(), event.label(), reason)
            return OfferResult.Refused(reason)
        }
        val offered = menu(current)
        if (event !in offered) {
            val reason =
                "event not in current menu ${offered.map { it.label() }}; refused"
            log?.recordRefusal(machineName, current.label(), event.label(), reason)
            return OfferResult.Refused(reason)
        }
        val from = current
        val next = pureWhenAccepted(from, event)
        current = next
        log?.recordTransition(
            machine = machineName,
            from = from.label(),
            event = event.label(),
            to = next.label(),
        )
        return OfferResult.Accepted(next)
    }
}
