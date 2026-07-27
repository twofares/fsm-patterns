# FSM / Statechart / CSP Patterns Sample (Kotlin, no engines)

Teaching sample: classical **finite-state machines** (FSMs), a few **statechart**
ideas (hierarchy and concurrent regions), and **CSP-style refusal** (a process
may decline an event)—all as ordinary Kotlin, with **no** third-party state-machine
library.

**What you learn**

1. A **pure step** only *declares* what should happen next (data).
2. A **boundary** *applies* that data: update state, log/metrics, real I/O.
3. Effects split into **two sinks**: shared observability vs machine-local work.
4. Export tables are built from pure APIs only (no live ports or clocks).

Unfamiliar words (menu, protocol, ADT, alphabet, offer, …) are defined in
[`docs/glossary.md`](docs/glossary.md#background-terms).

## Requirements

- JDK 17–21 recommended (Kotlin 2.1.x may fail on JDK 26+)
- Gradle 8+ (system `gradle` is fine)
- This workspace pins Gradle to Corretto 21 via `org.gradle.java.home` in
  `gradle.properties` (adjust if your paths differ)

## Build & run

```bash
gradle test
gradle run          # DemoMain: dual-sink + VirtualClock dwell/watchdog fire
gradle -q runExport > export.md   # product tables + Mermaid
```

## Documentation map

| Doc | Role |
|-----|------|
| **This README** | High-level teaching overview: kinds, dual-sink rules, package map |
| [`docs/glossary.md`](docs/glossary.md) | Plain-language + as-built terms (menu, protocol, ADT, dual-sink, kinds, …) |
| [`docs/origins-and-sources.md`](docs/origins-and-sources.md) | Why the repo exists; concept lineage (Harel, CSP, GoF, …) and code provenance |
| [`docs/authoring-guide.md`](docs/authoring-guide.md) | Detailed how-to: add a machine, boundaries, ports, export registration |
| [`docs/timers-and-automatic.md`](docs/timers-and-automatic.md) | Detailed how-to: `TimerIntent`, `TimerPort`, `automaticStep` / `drainAutomatic` |
| [`export.md`](export.md) | **Generated** product tables + Mermaid (do not hand-edit; regenerate) |
| [`docs/machine-examples-visual.html`](docs/machine-examples-visual.html) | Optional structural diagrams (open in a browser) |

## Package layout

| Path | Role |
|------|------|
| `flat/` | TrafficLight — total flat FSM, dwell timers, Flash + ε |
| `csp/` | FileProtocol — partial machine (accept or refuse; “menu” of legal ops) |
| `gof/` | Connection — hierarchical session machine + Online watchdog timer |
| `orthogonal/` | DoorSystem — two concurrent regions (Latch ∥ Swing) + container refuse |
| `effect/` | Pure outcome types, cross-cutting vocabulary, `TimerIntent` |
| `machine/` | Boundary apply, hierarchy helpers, timers, automatic drain, recording ports |
| `export/` | Build Markdown + Mermaid tables from pure steps |
| `logging/` | Transition audit log (one cross-cutting sink) |
| `DemoMain.kt` / `ExportMain.kt` | Live demos |

## Machine kinds

| Kind | Sample | Pure surface | Boundary | Export |
|------|--------|--------------|----------|--------|
| **Flat (total)** | TrafficLight | `step(S,E) → StepResult` | `applyAccepted` | `productEvaluateTotal` |
| **CSP / partial** | FileProtocol | `stepOffer → OfferOutcome` | `applyOffer` | `productEvaluateOffer` |
| **Hierarchical** | Connection | `connectionStep → HierarchyStep` | `applyFragments` then assign | `productEvaluateHierarchy` |
| **Orthogonal** | DoorSystem | Region `step` + container guards | Region: `applyAccepted`; guard: `applyRefusal` | Compose region tables |
| **Timers** | Light dwell; Online watchdog | Domain wraps `TimerIntent` Arm/Cancel | Multi-port `applyDomain*` + `TimerPort` | Timer labels (no clocks) |
| **Automatic (ε)** | Flash → Red | `automaticStep(S) → StepResult?` | `drainAutomatic` after external apply | `productEvaluateAutomatic` |

### Total vs partial (the main fork)

**Total** and **partial** answer one question:

> Given the fixed set of events this machine knows about, must **every** event
> be handled in **every** state—or may some events be **declined**?

Think of a traffic light vs a file session:

- A light always has an answer for “timer fired” or “emergency” (even if the
  answer is “stay flashing”). That is **total**.
- A file handle does **not** always accept `Read` or a second `Open`. The set of
  legal operations right now is a **menu**; illegal ones are **refused**. That
  is **partial** (also called refuse-capable; the `csp/` sample nods at CSP
  “offer/refuse,” not a full CSP toolkit).

| | **Total** | **Partial (refuse-capable)** |
|---|-----------|------------------------------|
| **What happens on an event?** | Always a real transition (change state, or a **self-loop** that stays put but still “handles” the event). | Either **accept** (handle it) or **refuse** (decline it). Refuse is part of the pure result, not an afterthought. |
| **Everyday analogy** | Every button always does something. | Greyed-out menu items: the action is not available in this state. |
| **Conversation rules** | Closed set of events; all are always legal. | A small **protocol**: only some moves are legal after others (open → read/close; not open→open). |
| **Pure Kotlin type** | `StepResult(next, crossCutting, domain)` — domain I/O list is always available on the type | `OfferOutcome.Accepted(...)` **or** `Refused(reason, next, crossCutting)` — **`Refused` has no `domain` field** |
| **Boundary helper** | `applyAccepted` — after updating state, always free to run domain work | `applyOffer` — domain work **only** if Accepted |
| **Sample** | TrafficLight — Tick / Emergency / DwellElapsed always handled | FileProtocol — e.g. `Open` while already open is refused |
| **Export tables** | Every state × event cell is an accept row | Same grid, but some cells are refuse (observability labels only, no domain I/O) |

```text
Total:    step(s, e)       →  always StepResult       →  applyAccepted
Partial:  stepOffer(s, e)  →  Accepted | Refused      →  applyOffer
                                                      (domain only if Accepted)
```

**Why not one type with “empty domain means refuse”?**  
If refuse were only “`StepResult` with an empty domain list,” you could still
*type* a refuse path that opens a file or drives hardware. Separate
`OfferOutcome.Refused` makes that illegal domain **unrepresentable**. That is
the teaching point—not a style preference.

**When to pick which**

- Use **total** when every known event is always meaningful (including self-loops
  that re-arm timers or refresh a lamp).
- Use **partial** when the machine has a **menu of legal operations** and illegal
  ones must be declined **without** touching domain ports (open twice, read
  while closed, …).

### Other kinds (not a third “total/partial” column)

These change **shape** (tree of states, concurrent regions) or **when** steps
run (later, or with no new external event). They still sit on the total/partial
fork above.

| Kind | In plain language | Total or partial? |
|------|-------------------|-------------------|
| **Hierarchical** | States form a **tree**. Leaving a leaf can exit parents; entering can enter parents. Effects are planned as ordered **fragments** (exit/enter packages). Pure result is `HierarchyStep` (`Handled` / `Unhandled`); boundary is `applyFragments` then assign—not flat `applyAccepted`. | Unhandled is refuse-*like*. Connection leaves handle some events and pass others up. |
| **Orthogonal** | Two machines run **at once** as regions (Latch ∥ Swing). Each region is usually **total**. The outer **container** can still **guard** illegal combinations (“don’t open while locked”) with refuse-only cross-cutting (`applyRefusal`). | Mix: total regions + partial guards. |
| **Timers** | “Fire this event later.” The pure step lists Arm/Cancel intents; a boundary `TimerPort` + clock schedules them; when due, the event re-enters the normal `handle` path. | Same engagement kind as the host machine (total light dwell; hierarchical watchdog). |
| **Automatic (ε)** | After an external event, an intermediate state may finish **by itself** (no new caller event). Pure `automaticStep` + `drainAutomatic`. Export marks those rows with `ε`. | Same dual-sink shape as the host (TrafficLight: total). Not a third outcome type. |

**Pick the outcome shape by kind:**

- **Total** → `StepResult` + `applyAccepted`
- **Partial** → `OfferOutcome` + `applyOffer` (`Refused` has **no** domain)
- **Hierarchy** → `connectionStep` → `HierarchyStep` + `applyFragments` (not flat `StepResult` / `applyAccepted`)
- **Timers / automatic** → same dual-sink lists as the host kind; ports and drain only at apply time

Plain-language terms: [`docs/glossary.md`](docs/glossary.md#background-terms).  
Authoring recipes: [`docs/authoring-guide.md`](docs/authoring-guide.md).

## Dual-sink model

Cross-cutting is multi-family (not audit-only):

| Family | Type | Sink |
|--------|------|------|
| Audit | `CrossCuttingEffect.Audit.*` | `TransitionLog` |
| Metric | `CrossCuttingEffect.Metric.Inc` | `MetricsPort` |
| Trace | `CrossCuttingEffect.Trace.Span` | `TracePort` |

Domain stays **machine-local**: each vignette defines its own sealed type of I/O
intents (an [ADT](docs/glossary.md#adt-algebraic-data-type)—here, Kotlin sealed
classes) applied only through that machine’s ports (lamp, file ops, session,
actuators, timer schedule).

```text
StepResult(next, crossCutting, domain)
OfferOutcome.Accepted(next, crossCutting, domain) | Refused(reason, next, crossCutting)  // no domain
HierarchyStep.Handled(next, effects) | Unhandled(effects)
```

Pure steps only **declare**. Boundaries **apply** via shared helpers. Export reads pure **labels** only.

## Boundary families (do not unify)

| Family | Helpers | Order | Used by |
|--------|---------|-------|---------|
| Assign-then-apply | `applyAccepted`, `applyOffer` | Assign → CC → domain | Flat, CSP, orthogonal regions |
| Refuse-only (CC) | `applyRefusal` | CC only | Orthogonal container guards |
| Apply-then-assign | `applyFragments` | Per-fragment CC→domain, **then** assign leaf | Hierarchical Connection |

Hierarchy must **not** use `applyAccepted` (wrong assign order; loses per-fragment pairing such as Online enter + `OpenSession`).

## Must / must not

| Must | Must not |
|------|----------|
| Pure step returns dual-sink outcomes only | `CrossCuttingInterpreter` / ports / clocks inside pure step |
| Kind-correct shared `apply*` | Domain on refuse; hierarchy via `applyAccepted` |
| Machine-local domain type (sealed intents) + port | Nest CC in domain; third outcome field for timers |
| Bundles with machine `extras` / `traceDetail` | Hand-roll accept/refuse without bundles |
| Export from pure APIs + manual registration | Reflection; export from interpreters; construct `ConnectionContext` for export |
| Timers: `Arm(..., eventKey = Event.label())` + pure mapper | `Thread.sleep`; clocks in pure step |
| Automatic: pure `automaticStep` + `drainAutomatic` (throw on bound/cycle) | Soft refuse on bound; public `Completion` alphabet unless truly external |

## Next steps

- **Terms:** [`docs/glossary.md`](docs/glossary.md)
- **Origins & sources:** [`docs/origins-and-sources.md`](docs/origins-and-sources.md)
- **Author a machine or change a boundary:** [`docs/authoring-guide.md`](docs/authoring-guide.md)
- **Timers / Flash ε / watchdog:** [`docs/timers-and-automatic.md`](docs/timers-and-automatic.md)
- **Visual structure:** open [`docs/machine-examples-visual.html`](docs/machine-examples-visual.html)
- **Product tables:** regenerate `export.md` as above; inspect effects column for dual-sink labels
