package fsm.sample.export

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.exportLabel
import fsm.sample.effect.OfferOutcome
import fsm.sample.effect.StepResult
import fsm.sample.effect.exportLabels
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.exportLabel
import fsm.sample.flat.step
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnState
import fsm.sample.gof.ConnectionEffect
import fsm.sample.gof.HandleResult
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Online
import fsm.sample.gof.Transferring
import fsm.sample.gof.declaredEffects
import fsm.sample.gof.dispatchWithDelegation
import fsm.sample.gof.exportLabel
import fsm.sample.machine.FragmentedStepEffects
import fsm.sample.orthogonal.LatchEvent
import fsm.sample.orthogonal.LatchMachine
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingEvent
import fsm.sample.orthogonal.SwingMachine
import fsm.sample.orthogonal.SwingState
import fsm.sample.orthogonal.exportLabel

/**
 * PURPOSE: Method 1 from research — exhaustive product evaluation of pure
 * State + Event → next + effects APIs over finite sealed domains.
 *
 * Enumerates every (state, event) pair, calls the **shipped** pure step /
 * offer / hierarchy functions (never re-implements rules), and builds
 * [TransitionTable] rows including short effect labels. Stdlib-only: no
 * reflection; domains listed explicitly from sealed sets.
 *
 * Shared helpers:
 * - [productEvaluateTotal] — total flat / region steps ([StepResult])
 * - [productEvaluateOffer] — partial / menu steps ([OfferOutcome])
 * - [productEvaluateHierarchy] — leaf × event via probe + [FragmentedStepEffects]
 *
 * Never calls per-machine interpreters, handle, or offer during export — only
 * pure step / stepOffer / planTransition / declaredEffects, plus pure hierarchical
 * [dispatchWithDelegation] (no [fsm.sample.gof.ConnectionContext]).
 */

// --- Generic product helpers -------------------------------------------------

/**
 * PURPOSE: Evaluate a total pure step on the Cartesian product.
 * Every pair is ACCEPTED; [effects] come from audit labels + domain labels.
 */
fun <S, Ev, D> productEvaluateTotal(
    machine: String,
    states: List<S>,
    events: List<Ev>,
    stateLabel: (S) -> String,
    eventLabel: (Ev) -> String,
    step: (S, Ev) -> StepResult<S, D>,
    domainLabel: (D) -> String,
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
                effects = result.exportLabels(domainLabel),
            )
        }
    }
    return TransitionTable(machine, rows)
}

/**
 * PURPOSE: Evaluate a partial / menu pure offer on the Cartesian product.
 *
 * [OfferOutcome.Accepted] → [TransitionKind.ACCEPTED] (domain labels included).
 * [OfferOutcome.Refused] → [TransitionKind.REFUSED] (cross-cutting only; no domain).
 * Caller supplies the pure [stepOffer]; export never calls boundary [applyOffer].
 */
fun <S, E, D> productEvaluateOffer(
    machine: String,
    states: List<S>,
    events: List<E>,
    stateLabel: (S) -> String,
    eventLabel: (E) -> String,
    stepOffer: (S, E) -> OfferOutcome<S, D>,
    domainLabel: (D) -> String,
    refusalNote: String = "not in menu",
): TransitionTable {
    val rows = mutableListOf<TransitionRow>()
    for (s in states) {
        for (e in events) {
            when (val result = stepOffer(s, e)) {
                is OfferOutcome.Accepted -> rows += TransitionRow(
                    from = stateLabel(s),
                    event = eventLabel(e),
                    to = stateLabel(result.next),
                    kind = TransitionKind.ACCEPTED,
                    effects = result.exportLabels(domainLabel),
                )
                is OfferOutcome.Refused -> rows += TransitionRow(
                    from = stateLabel(s),
                    event = eventLabel(e),
                    to = stateLabel(result.next),
                    kind = TransitionKind.REFUSED,
                    effects = result.exportLabels(domainLabel),
                    note = refusalNote,
                )
            }
        }
    }
    return TransitionTable(machine, rows)
}

/**
 * PURPOSE: Evaluate hierarchical leaf × event product via pure probe + declared effects.
 *
 * [probe] yields a vignette handle-result [R] (e.g. Connection [HandleResult]).
 * [declaredEffects] stays **caller-supplied** so same-leaf / refuse policy remains
 * outside this helper. Never constructs context classes that run init side effects.
 *
 * Unhandled → [TransitionKind.REFUSED] self-successor with [refusalNote].
 * Handled → [TransitionKind.ACCEPTED] with [nextOf] and fragmented export labels.
 */
fun <S, E, D, R> productEvaluateHierarchy(
    machine: String,
    leaves: List<S>,
    events: List<E>,
    stateLabel: (S) -> String,
    eventLabel: (E) -> String,
    probe: (S, E) -> R,
    isUnhandled: (R) -> Boolean,
    nextOf: (R) -> S,
    declaredEffects: (S, E, R) -> FragmentedStepEffects<D>,
    domainLabel: (D) -> String,
    refusalNote: String = "no state in hierarchy accepted event",
): TransitionTable {
    val rows = mutableListOf<TransitionRow>()
    for (leaf in leaves) {
        for (event in events) {
            val result = probe(leaf, event)
            val effects = declaredEffects(leaf, event, result)
            if (isUnhandled(result)) {
                rows += TransitionRow(
                    from = stateLabel(leaf),
                    event = eventLabel(event),
                    to = stateLabel(leaf),
                    kind = TransitionKind.REFUSED,
                    effects = effects.exportLabels(domainLabel),
                    note = refusalNote,
                )
            } else {
                rows += TransitionRow(
                    from = stateLabel(leaf),
                    event = eventLabel(event),
                    to = stateLabel(nextOf(result)),
                    kind = TransitionKind.ACCEPTED,
                    effects = effects.exportLabels(domainLabel),
                )
            }
        }
    }
    return TransitionTable(machine, rows)
}

// --- Sample machines: product evaluation via shipped pure APIs ---------------

/**
 * PURPOSE: Complete transition table for TrafficLight via pure [step].
 * Domain: all LightState × all LightEvent; effects from split StepResult lists.
 */
fun evaluateTrafficLight(): TransitionTable = productEvaluateTotal(
    machine = "TrafficLight",
    states = listOf(LightState.Red, LightState.Green, LightState.Yellow),
    events = listOf(LightEvent.Tick, LightEvent.Emergency),
    stateLabel = { it.label() },
    eventLabel = { it.label() },
    step = { s, e -> step(s, e) },
    domainLabel = { it.exportLabel() },
)

/**
 * PURPOSE: Complete CSP table for FileProtocol via pure [FileProtocol.stepOffer].
 * Thin adapter over [productEvaluateOffer]. No [FileProtocol.offer] / interpreter.
 */
fun evaluateFileProtocol(): TransitionTable {
    // Stateless probe instance: only pure methods are used.
    val protocol = FileProtocol()
    return productEvaluateOffer(
        machine = "FileProtocol",
        states = listOf(FileState.Closed, FileState.Opened),
        events = listOf(FileEvent.Open, FileEvent.Read, FileEvent.Close),
        stateLabel = { it.label() },
        eventLabel = { it.label() },
        stepOffer = { s, e -> protocol.stepOffer(s, e) },
        domainLabel = { it.exportLabel() },
    )
}

/**
 * PURPOSE: Latch region pure product via [LatchMachine.step] (next + split lists).
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
        domainLabel = { it.exportLabel() },
    )
}

/**
 * PURPOSE: Swing region pure product via [SwingMachine.step] (next + split lists).
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
        domainLabel = { it.exportLabel() },
    )
}

/**
 * PURPOSE: Hierarchical product for Connection via leaf × event probing.
 *
 * Thin adapter over [productEvaluateHierarchy]: pure [probeConnection] +
 * vignette-local [declaredEffects]. Never calls handle or apply sinks.
 */
fun evaluateConnectionHierarchy(): TransitionTable = productEvaluateHierarchy(
    machine = "Connection",
    leaves = connectionLeaves(),
    events = listOf(
        ConnEvent.Connect,
        ConnEvent.Disconnect,
        ConnEvent.StartTransfer,
        ConnEvent.FinishTransfer,
        ConnEvent.Heartbeat,
    ),
    stateLabel = { it.name },
    eventLabel = { it.label() },
    probe = { leaf, event -> probeConnection(leaf, event) },
    isUnhandled = { it is HandleResult.Unhandled },
    nextOf = { (it as HandleResult.Handled).next },
    declaredEffects = { leaf, event, result -> declaredEffects(leaf, event, result) },
    domainLabel = ConnectionEffect::exportLabel,
)

/**
 * PURPOSE: Pure hierarchical probe — no context, no interpreters, no ports.
 *
 * Uses shipped [dispatchWithDelegation] only. Heartbeat stays on the probed
 * leaf via [currentLeaf] without needing [ConnectionContext.current].
 */
fun probeConnection(from: ConnState, event: ConnEvent): HandleResult =
    dispatchWithDelegation(from, event, currentLeaf = from)

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
