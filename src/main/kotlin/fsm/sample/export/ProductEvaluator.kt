package fsm.sample.export

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.transition
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnState
import fsm.sample.gof.ConnectionContext
import fsm.sample.gof.HandleResult
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Online
import fsm.sample.gof.Transferring
import fsm.sample.orthogonal.LatchEvent
import fsm.sample.orthogonal.LatchMachine
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingEvent
import fsm.sample.orthogonal.SwingMachine
import fsm.sample.orthogonal.SwingState

/**
 * PURPOSE: Method 1 from research — exhaustive product evaluation of pure
 * State + Event → State APIs over finite sealed domains.
 *
 * Enumerates every (state, event) pair, calls the **shipped** pure transition /
 * menu functions (never re-implements rules), and builds [TransitionTable] rows.
 * Stdlib-only: no reflection required; domains listed explicitly from sealed sets.
 *
 * Effects ([fsm.sample.effect]) are intentionally omitted: export tables are
 * next-state-only so product eval stays pure and free of log/counter side effects.
 */

// --- Generic product helpers -------------------------------------------------

/**
 * PURPOSE: Evaluate a total pure transition on the Cartesian product.
 * Every pair is ACCEPTED with successor = [transition](s, e).
 */
fun <S, E> productEvaluateTotal(
    machine: String,
    states: List<S>,
    events: List<E>,
    stateLabel: (S) -> String,
    eventLabel: (E) -> String,
    transition: (S, E) -> S,
): TransitionTable {
    val rows = mutableListOf<TransitionRow>()
    for (s in states) {
        for (e in events) {
            val next = transition(s, e)
            rows += TransitionRow(
                from = stateLabel(s),
                event = eventLabel(e),
                to = stateLabel(next),
                kind = TransitionKind.ACCEPTED,
            )
        }
    }
    return TransitionTable(machine, rows)
}

/**
 * PURPOSE: CSP-style product: in-menu → pure successor; out-of-menu → REFUSED self.
 * Calls [menu] and [whenAccepted] from the shipped process — no second oracle.
 */
fun <S, E> productEvaluateWithMenu(
    machine: String,
    states: List<S>,
    alphabet: List<E>,
    stateLabel: (S) -> String,
    eventLabel: (E) -> String,
    menu: (S) -> Set<E>,
    whenAccepted: (S, E) -> S,
): TransitionTable {
    val rows = mutableListOf<TransitionRow>()
    for (s in states) {
        val offered = menu(s)
        for (e in alphabet) {
            if (e in offered) {
                val next = whenAccepted(s, e)
                rows += TransitionRow(
                    from = stateLabel(s),
                    event = eventLabel(e),
                    to = stateLabel(next),
                    kind = TransitionKind.ACCEPTED,
                )
            } else {
                rows += TransitionRow(
                    from = stateLabel(s),
                    event = eventLabel(e),
                    to = stateLabel(s), // self-successor under refusal
                    kind = TransitionKind.REFUSED,
                    note = "not in menu",
                )
            }
        }
    }
    return TransitionTable(machine, rows)
}

// --- Sample machines: product evaluation via shipped pure APIs ---------------

/**
 * PURPOSE: Complete transition table for TrafficLight via [transition].
 * Domain: all LightState × all LightEvent.
 */
fun evaluateTrafficLight(): TransitionTable = productEvaluateTotal(
    machine = "TrafficLight",
    states = listOf(LightState.Red, LightState.Green, LightState.Yellow),
    events = listOf(LightEvent.Tick, LightEvent.Emergency),
    stateLabel = { it.label() },
    eventLabel = { it.label() },
    transition = { s, e -> transition(s, e) },
)

/**
 * PURPOSE: Complete CSP table for FileProtocol via [FileProtocol.menu] and
 * [FileProtocol.pureWhenAccepted]. Non-menu alphabet events are REFUSED.
 */
fun evaluateFileProtocol(): TransitionTable {
    // Stateless probe instance: only pure methods are used.
    val protocol = FileProtocol()
    return productEvaluateWithMenu(
        machine = "FileProtocol",
        states = listOf(FileState.Closed, FileState.Opened),
        alphabet = listOf(FileEvent.Open, FileEvent.Read, FileEvent.Close),
        stateLabel = { it.label() },
        eventLabel = { it.label() },
        menu = { s -> protocol.menu(s) },
        whenAccepted = { s, e -> protocol.pureWhenAccepted(s, e) },
    )
}

/**
 * PURPOSE: Latch region pure product via [LatchMachine.pureTransition].
 */
fun evaluateLatchRegion(): TransitionTable {
    val machine = LatchMachine()
    return productEvaluateTotal(
        machine = "Latch",
        states = listOf(LatchState.Locked, LatchState.Unlocked),
        events = listOf(LatchEvent.Lock, LatchEvent.Unlock),
        stateLabel = { it.label() },
        eventLabel = { it.label() },
        transition = { s, e -> machine.pureTransition(s, e) },
    )
}

/**
 * PURPOSE: Swing region pure product via [SwingMachine.pureTransition].
 */
fun evaluateSwingRegion(): TransitionTable {
    val machine = SwingMachine()
    return productEvaluateTotal(
        machine = "Swing",
        states = listOf(SwingState.Closed, SwingState.Open),
        events = listOf(SwingEvent.OpenDoor, SwingEvent.CloseDoor),
        stateLabel = { it.label() },
        eventLabel = { it.label() },
        transition = { s, e -> machine.pureTransition(s, e) },
    )
}

/**
 * PURPOSE: Hierarchical product for Connection via leaf × event probing.
 *
 * Uses shipped [ConnState.onEvent] with hierarchical parent walk (same discipline
 * as [ConnectionContext] dispatch). Each leaf is the initial state of a throwaway
 * context so Heartbeat can read ctx.current. Not a full runtime engine — only
 * product evaluation for export.
 */
fun evaluateConnectionHierarchy(): TransitionTable {
    val leaves = listOf(Offline, Idle, Transferring)
    val events = listOf(
        ConnEvent.Connect,
        ConnEvent.Disconnect,
        ConnEvent.StartTransfer,
        ConnEvent.FinishTransfer,
        ConnEvent.Heartbeat,
    )
    val rows = mutableListOf<TransitionRow>()
    for (leaf in leaves) {
        for (event in events) {
            val result = probeConnection(leaf, event)
            when (result) {
                is HandleResult.Handled -> rows += TransitionRow(
                    from = leaf.name,
                    event = event.label(),
                    to = result.next.name,
                    kind = TransitionKind.ACCEPTED,
                )
                HandleResult.Unhandled -> rows += TransitionRow(
                    from = leaf.name,
                    event = event.label(),
                    to = leaf.name,
                    kind = TransitionKind.REFUSED,
                    note = "no state in hierarchy accepted event",
                )
            }
        }
    }
    return TransitionTable("Connection", rows)
}

/**
 * PURPOSE: Probe hierarchical dispatch without retaining machine state.
 * Mirrors ConnectionContext.dispatchWithDelegation using shipped onEvent.
 */
fun probeConnection(from: ConnState, event: ConnEvent): HandleResult {
    val ctx = ConnectionContext(initial = from, log = null)
    var node: ConnState? = from
    while (node != null) {
        when (val r = node.onEvent(ctx, event)) {
            is HandleResult.Handled -> return r
            HandleResult.Unhandled -> node = node.parent
        }
    }
    return HandleResult.Unhandled
}

/**
 * PURPOSE: Explicit hierarchy tree for Mermaid nesting (structure method 2).
 * Built from shipped parent links: Idle/Transferring → Online; Offline root leaf.
 */
fun connectionHierarchyTree(): StateNode = StateNode(
    name = "ConnectionRoot",
    children = listOf(
        StateNode("Offline"),
        StateNode(
            name = "Online",
            children = listOf(
                StateNode("Idle"),
                StateNode("Transferring"),
            ),
        ),
    ),
)

/** PURPOSE: Known composite superstate name(s) for Mermaid nesting. */
fun connectionCompositeNames(): Set<String> = setOf(Online.name)

/** PURPOSE: Leaves of the connection hierarchy (product evaluation domain). */
fun connectionLeaves(): List<ConnState> = listOf(Offline, Idle, Transferring)
