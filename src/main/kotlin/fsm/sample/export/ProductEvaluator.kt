package fsm.sample.export

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.OfferStep
import fsm.sample.effect.StepResult
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.step
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnState
import fsm.sample.gof.ConnectionContext
import fsm.sample.gof.HandleResult
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Online
import fsm.sample.gof.Transferring
import fsm.sample.gof.declaredEffects
import fsm.sample.gof.dispatchWithDelegation
import fsm.sample.orthogonal.LatchEvent
import fsm.sample.orthogonal.LatchMachine
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingEvent
import fsm.sample.orthogonal.SwingMachine
import fsm.sample.orthogonal.SwingState

/**
 * PURPOSE: Method 1 from research — exhaustive product evaluation of pure
 * State + Event → next + effects APIs over finite sealed domains.
 *
 * Enumerates every (state, event) pair, calls the **shipped** pure step /
 * offer / hierarchy functions (never re-implements rules), and builds
 * [TransitionTable] rows including short effect labels. Stdlib-only: no
 * reflection; domains listed explicitly from sealed sets.
 *
 * Never calls [fsm.sample.effect.EffectInterpreter], handle, or offer during
 * export — only pure step / stepOffer / planTransition / declaredEffects, plus
 * hierarchical onEvent probes on contexts with applyInitialEntry=false.
 */

// --- Generic product helpers -------------------------------------------------

/**
 * PURPOSE: Evaluate a total pure step on the Cartesian product.
 * Every pair is ACCEPTED; [effects] come from [StepResult.effects].
 */
fun <S, E> productEvaluateTotal(
    machine: String,
    states: List<S>,
    events: List<E>,
    stateLabel: (S) -> String,
    eventLabel: (E) -> String,
    step: (S, E) -> StepResult<S>,
): TransitionTable {
    val rows = mutableListOf<TransitionRow>()
    for (s in states) {
        for (e in events) {
            val result = step(s, e)
            rows += TransitionRow(
                from = stateLabel(s),
                event = eventLabel(e),
                to = stateLabel(result.next),
                kind = TransitionKind.ACCEPTED,
                effects = effectShortNames(result.effects),
            )
        }
    }
    return TransitionTable(machine, rows)
}

// --- Sample machines: product evaluation via shipped pure APIs ---------------

/**
 * PURPOSE: Complete transition table for TrafficLight via pure [step].
 * Domain: all LightState × all LightEvent; effects from StepResult.
 */
fun evaluateTrafficLight(): TransitionTable = productEvaluateTotal(
    machine = "TrafficLight",
    states = listOf(LightState.Red, LightState.Green, LightState.Yellow),
    events = listOf(LightEvent.Tick, LightEvent.Emergency),
    stateLabel = { it.label() },
    eventLabel = { it.label() },
    step = { s, e -> step(s, e) },
)

/**
 * PURPOSE: Complete CSP table for FileProtocol via pure [FileProtocol.stepOffer].
 * Non-menu alphabet events are REFUSED with LogRefusal effects.
 * No [FileProtocol.offer] / interpreter — pure product only.
 */
fun evaluateFileProtocol(): TransitionTable {
    // Stateless probe instance: only pure methods are used.
    val protocol = FileProtocol()
    val states = listOf(FileState.Closed, FileState.Opened)
    val alphabet = listOf(FileEvent.Open, FileEvent.Read, FileEvent.Close)
    val rows = mutableListOf<TransitionRow>()
    for (s in states) {
        for (e in alphabet) {
            when (val result = protocol.stepOffer(s, e)) {
                is OfferStep.Accepted -> rows += TransitionRow(
                    from = s.label(),
                    event = e.label(),
                    to = result.next.label(),
                    kind = TransitionKind.ACCEPTED,
                    effects = effectShortNames(result.effects),
                )
                is OfferStep.Refused -> rows += TransitionRow(
                    from = s.label(),
                    event = e.label(),
                    to = result.next.label(),
                    kind = TransitionKind.REFUSED,
                    effects = effectShortNames(result.effects),
                    note = "not in menu",
                )
            }
        }
    }
    return TransitionTable("FileProtocol", rows)
}

/**
 * PURPOSE: Latch region pure product via [LatchMachine.step] (next + effects).
 */
fun evaluateLatchRegion(): TransitionTable {
    val machine = LatchMachine()
    return productEvaluateTotal(
        machine = "Latch",
        states = listOf(LatchState.Locked, LatchState.Unlocked),
        events = listOf(LatchEvent.Lock, LatchEvent.Unlock),
        stateLabel = { it.label() },
        eventLabel = { it.label() },
        step = { s, e -> machine.step(s, e) },
    )
}

/**
 * PURPOSE: Swing region pure product via [SwingMachine.step] (next + effects).
 */
fun evaluateSwingRegion(): TransitionTable {
    val machine = SwingMachine()
    return productEvaluateTotal(
        machine = "Swing",
        states = listOf(SwingState.Closed, SwingState.Open),
        events = listOf(SwingEvent.OpenDoor, SwingEvent.CloseDoor),
        stateLabel = { it.label() },
        eventLabel = { it.label() },
        step = { s, e -> machine.step(s, e) },
    )
}

/**
 * PURPOSE: Hierarchical product for Connection via leaf × event probing.
 *
 * Uses shipped [dispatchWithDelegation] / [ConnState.onEvent] for next-state on a
 * probe context ([applyInitialEntry]=false so init never calls the interpreter),
 * then pure [declaredEffects] (planTransition / same-leaf / LogRefusal).
 * Never calls handle or EffectInterpreter.
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
            val effects = declaredEffects(leaf, event, result)
            when (result) {
                is HandleResult.Handled -> rows += TransitionRow(
                    from = leaf.name,
                    event = event.label(),
                    to = result.next.name,
                    kind = TransitionKind.ACCEPTED,
                    effects = effectShortNames(effects),
                )
                HandleResult.Unhandled -> rows += TransitionRow(
                    from = leaf.name,
                    event = event.label(),
                    to = leaf.name,
                    kind = TransitionKind.REFUSED,
                    effects = effectShortNames(effects),
                    note = "no state in hierarchy accepted event",
                )
            }
        }
    }
    return TransitionTable("Connection", rows)
}

/**
 * PURPOSE: Probe hierarchical dispatch without interpreter side effects.
 *
 * Builds a [ConnectionContext] with applyInitialEntry=false and log=null so
 * construction and onEvent never call [fsm.sample.effect.EffectInterpreter].
 * Uses shipped [dispatchWithDelegation] (same walk as handle).
 */
fun probeConnection(from: ConnState, event: ConnEvent): HandleResult {
    val ctx = ConnectionContext(initial = from, log = null, applyInitialEntry = false)
    return dispatchWithDelegation(ctx, from, event)
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
