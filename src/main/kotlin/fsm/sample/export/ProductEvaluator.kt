package fsm.sample.export

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.exportLabel
import fsm.sample.effect.HierarchyStep
import fsm.sample.effect.OfferOutcome
import fsm.sample.effect.StepResult
import fsm.sample.effect.exportLabels
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.automaticStep
import fsm.sample.flat.exportLabel
import fsm.sample.flat.step
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnState
import fsm.sample.gof.ConnectionEffect
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Online
import fsm.sample.gof.Transferring
import fsm.sample.gof.connectionStep
import fsm.sample.gof.exportLabel
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
 * - [productEvaluateHierarchy] — leaf × event via sealed [HierarchyStep]
 * - [productEvaluateAutomatic] — eventless / ε edges via pure [automaticStep]
 *
 * Never calls per-machine interpreters, handle, drainAutomatic, or offer during export — only
 * pure step / stepOffer / hierarchy / automatic step adapters (no [fsm.sample.gof.ConnectionContext]).
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
 * PURPOSE: Evaluate hierarchical leaf × event product via pure [HierarchyStep].
 *
 * Single [step] callback returns sealed handled/unhandled with effects already fused
 * (no separate `isUnhandled` / `nextOf` / `declaredEffects` trio). Never constructs
 * context classes that run init side effects.
 *
 * Unhandled → [TransitionKind.REFUSED] self-successor with [refusalNote].
 * Handled → [TransitionKind.ACCEPTED] with next + fragmented export labels.
 */
fun <S, E, D> productEvaluateHierarchy(
    machine: String,
    leaves: List<S>,
    events: List<E>,
    stateLabel: (S) -> String,
    eventLabel: (E) -> String,
    step: (S, E) -> HierarchyStep<S, D>,
    domainLabel: (D) -> String,
    refusalNote: String = "no state in hierarchy accepted event",
): TransitionTable {
    val rows = mutableListOf<TransitionRow>()
    for (leaf in leaves) {
        for (event in events) {
            when (val result = step(leaf, event)) {
                is HierarchyStep.Unhandled -> rows += TransitionRow(
                    from = stateLabel(leaf),
                    event = eventLabel(event),
                    to = stateLabel(leaf),
                    kind = TransitionKind.REFUSED,
                    effects = result.effects.exportLabels(domainLabel),
                    note = refusalNote,
                )
                is HierarchyStep.Handled -> rows += TransitionRow(
                    from = stateLabel(leaf),
                    event = eventLabel(event),
                    to = stateLabel(result.next),
                    kind = TransitionKind.ACCEPTED,
                    effects = result.effects.exportLabels(domainLabel),
                )
            }
        }
    }
    return TransitionTable(machine, rows)
}

/**
 * PURPOSE: Evaluate pure eventless / completion edges (one [automaticStep] per state).
 *
 * States with no edge yield no row. Event label defaults to `ε`; [note] is `eventless`.
 * Does not call [fsm.sample.machine.drainAutomatic] or any boundary / clock.
 */
fun <S, D> productEvaluateAutomatic(
    machine: String,
    states: List<S>,
    stateLabel: (S) -> String,
    automaticStep: (S) -> StepResult<S, D>?,
    domainLabel: (D) -> String,
    eventLabel: String = "ε",
): List<TransitionRow> = states.mapNotNull { s ->
    val result = automaticStep(s) ?: return@mapNotNull null
    TransitionRow(
        from = stateLabel(s),
        event = eventLabel,
        to = stateLabel(result.next),
        kind = TransitionKind.ACCEPTED,
        effects = result.exportLabels(domainLabel),
        note = "eventless",
    )
}

// --- Sample machines: product evaluation via shipped pure APIs ---------------

/**
 * PURPOSE: Complete transition table for TrafficLight via pure [step] + [automaticStep].
 * Domain: all LightState (incl. Flash) × all LightEvent Cartesian product, plus ε rows.
 * Effects from split StepResult lists — no clocks / ports / handle.
 */
fun evaluateTrafficLight(): TransitionTable {
    val states = listOf(
        LightState.Red,
        LightState.Green,
        LightState.Yellow,
        LightState.Flash,
    )
    val events = listOf(
        LightEvent.Tick,
        LightEvent.Emergency,
        LightEvent.DwellElapsed,
    )
    val total = productEvaluateTotal(
        machine = "TrafficLight",
        states = states,
        events = events,
        stateLabel = { it.label() },
        eventLabel = { it.label() },
        step = { s, e -> step(s, e) },
        domainLabel = { it.exportLabel() },
    )
    val epsilon = productEvaluateAutomatic(
        machine = "TrafficLight",
        states = states,
        stateLabel = { it.label() },
        automaticStep = { s -> automaticStep(s) },
        domainLabel = { it.exportLabel() },
    )
    return TransitionTable(machine = "TrafficLight", rows = total.rows + epsilon)
}

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
 * Thin adapter over [productEvaluateHierarchy]: pure gof [connectionStep]
 * (shared with [fsm.sample.gof.ConnectionContext.handle]). Never calls handle or apply sinks.
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
        ConnEvent.WatchdogTimeout,
    ),
    stateLabel = { it.name },
    eventLabel = { it.label() },
    step = { leaf, event -> connectionStep(leaf, event) },
    domainLabel = ConnectionEffect::exportLabel,
)

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
