# FSM / Statechart / CSP Patterns Sample (Kotlin, no engines)

Didactic Kotlin sample that encodes classical FSM, Harel-style hierarchical and
orthogonal disciplines, and CSP-style process refusal **as ordinary code** —
no third-party state-machine, statechart, or FSM library.

**What you learn:** pure step functions declare outcomes; boundaries apply dual
sinks (cross-cutting vs domain); export tables come from pure APIs only.

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
| [`docs/authoring-guide.md`](docs/authoring-guide.md) | Detailed how-to: add a machine, boundaries, ports, export registration |
| [`docs/timers-and-automatic.md`](docs/timers-and-automatic.md) | Detailed how-to: `TimerIntent`, `TimerPort`, `automaticStep` / `drainAutomatic` |
| [`export.md`](export.md) | **Generated** product tables + Mermaid (do not hand-edit; regenerate) |
| [`docs/machine-examples-visual.html`](docs/machine-examples-visual.html) | Optional structural diagrams (open in a browser) |

## Package layout

| Path | Role |
|------|------|
| `flat/` | TrafficLight — total flat FSM, dwell timers, Flash + ε |
| `csp/` | FileProtocol — partial / menu offer + refuse |
| `gof/` | Connection — hierarchical GoF + Online watchdog |
| `orthogonal/` | DoorSystem — Latch ∥ Swing + container refuse |
| `effect/` | Pure outcomes (`StepResult`, `OfferOutcome`, `HierarchyStep`), CC vocabulary, bundles, `TimerIntent` |
| `machine/` | Boundary apply, hierarchy helpers, `TimerPort` / `VirtualClock`, `drainAutomatic`, `RecordingPort` |
| `export/` | Product evaluation → Markdown + Mermaid |
| `logging/` | Transition audit log (audit sink under CC env) |
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

**Pick the outcome shape by kind:**

- **Total** (every event accepted) → `StepResult` + `applyAccepted`
- **Refuse-capable** (menus, guards) → `OfferOutcome` (`Refused` has **no** domain) + `applyOffer`
- **Hierarchy** → vignette `HandleResult` / `declaredEffects` / `connectionStep`; shared fragments + LCA — **not** a flat `StepResult`
- **Timers / automatic** are **not** a third outcome kind — same dual-sink lists; multi-port fan-out only at apply time

## Dual-sink model

Cross-cutting is multi-family (not audit-only):

| Family | Type | Sink |
|--------|------|------|
| Audit | `CrossCuttingEffect.Audit.*` | `TransitionLog` |
| Metric | `CrossCuttingEffect.Metric.Inc` | `MetricsPort` |
| Trace | `CrossCuttingEffect.Trace.Span` | `TracePort` |

Domain stays **machine-local** sealed ADTs → machine ports (lamp, file ops, session, actuators, timer schedule).

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
| Machine-local domain ADT + port | Nest CC in domain; third outcome field for timers |
| Bundles with machine `extras` / `traceDetail` | Hand-roll accept/refuse without bundles |
| Export from pure APIs + manual registration | Reflection; export from interpreters; construct `ConnectionContext` for export |
| Timers: `Arm(..., eventKey = Event.label())` + pure mapper | `Thread.sleep`; clocks in pure step |
| Automatic: pure `automaticStep` + `drainAutomatic` (throw on bound/cycle) | Soft refuse on bound; public `Completion` alphabet unless truly external |

## Next steps

- **Author a machine or change a boundary:** [`docs/authoring-guide.md`](docs/authoring-guide.md)
- **Timers / Flash ε / watchdog:** [`docs/timers-and-automatic.md`](docs/timers-and-automatic.md)
- **Visual structure:** open [`docs/machine-examples-visual.html`](docs/machine-examples-visual.html)
- **Product tables:** regenerate `export.md` as above; inspect effects column for dual-sink labels
