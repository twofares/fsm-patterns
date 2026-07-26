# FSM / Statechart / CSP Patterns Sample (Kotlin, no engines)

Didactic Kotlin sample that encodes classical FSM, Harel-style hierarchical and
orthogonal disciplines, and CSP-style process refusal **as ordinary code** —
no third-party state-machine, statechart, or FSM library.

## Requirements

- JDK 17–21 recommended (Kotlin 2.1.x may fail on JDK 26+)
- Gradle 8+ (system `gradle` is fine)
- This workspace pins Gradle to Corretto 21 via `org.gradle.java.home` in `gradle.properties` (adjust if your paths differ)

## Build & run

```bash
gradle test
gradle run
gradle runExport   # product-evaluated tables + Mermaid diagrams
```

## Layout

| Path | Discipline |
|------|------------|
| `src/main/kotlin/fsm/sample/flat/` | Sealed FSM, `StepResult` + DriveLamp domain |
| `src/main/kotlin/fsm/sample/gof/` | Hierarchical Connection + session domain |
| `src/main/kotlin/fsm/sample/orthogonal/` | Orthogonal door regions + actuators |
| `src/main/kotlin/fsm/sample/csp/` | CSP file protocol + I/O domain intents |
| `src/main/kotlin/fsm/sample/effect/` | Dual-sink outcomes, CC vocabulary, bundles, env, interpreter |
| `src/main/kotlin/fsm/sample/machine/` | Boundary apply + hierarchy structure (not an engine) |
| `src/main/kotlin/fsm/sample/logging/` | Transition audit log (one cross-cutting sink) |
| `src/main/kotlin/fsm/sample/export/` | Product evaluation → Markdown + Mermaid |
| `DemoMain.kt` / `ExportMain.kt` | Dual-surface demos |
| `docs/machine-authoring-surfaces.md` | Full design / agent checklist (PR surfaces) |

## Machine kinds (matrix)

| Kind | Sample | Pure surface | Boundary helper | Export helper |
|------|--------|--------------|-----------------|---------------|
| **Flat (total)** | TrafficLight | `step(S,E) → StepResult` | `applyAccepted` | `productEvaluateTotal` |
| **CSP / partial** | FileProtocol | `stepOffer(S,E) → OfferOutcome` | `applyOffer` | `productEvaluateOffer` |
| **Hierarchical** | Connection | `connectionStep` → `HierarchyStep` (dispatch + `declaredEffects`) | `applyFragments` then assign | `productEvaluateHierarchy` |
| **Orthogonal** | DoorSystem | Region `step` → `StepResult` (+ container pure guards) | Region: `applyAccepted`; container refuse is CC-only | Compose region tables (`exportDoorSystem`) |

**Outcome choice (critical):**

- **Total** machines (every event accepted from every state) → `StepResult` + `applyAccepted`.
- **Refuse-capable** machines (menus, guards, partial alphabets) → `OfferOutcome` (`Accepted` may carry domain; `Refused` has **no** domain field) + `applyOffer`.
- **Hierarchy** keeps vignette-local `HandleResult` / `declaredEffects`; shared structure is fragments + LCA helpers — **not** flattened into one `StepResult`.

## Two boundary families (do not unify)

| Family | Helpers | State assign vs apply | Used by |
|--------|---------|----------------------|---------|
| **Assign-then-apply** | `applyAccepted`, `applyOffer` | Assign `current = next` **first**, then cross-cutting, then domain (if any) | Flat, orthogonal regions, CSP offer |
| **Refuse-only (CC)** | `applyRefusal` | No assign, no domain — cross-cutting only | Orthogonal container guards |
| **Apply-then-assign** | `applyFragments` only | Apply per-fragment CC then domain **while still on old leaf**, then caller assigns if leaf changed | Hierarchical Connection |

Hierarchical machines must **not** call `applyAccepted`: that assigns before exit effects and flattens per-fragment CC↔domain pairing (e.g. Online enter next to `OpenSession`).

```text
Total / offer:  pure step → assign → CrossCuttingInterpreter → applyDomain
Hierarchy:      pure plan → for each fragment (CC → domain) → assign leaf
```

## Shared authoring surfaces (pointers)

| Concern | Where |
|---------|--------|
| Dual-sink boundary apply | [`machine/BoundaryApply.kt`](src/main/kotlin/fsm/sample/machine/BoundaryApply.kt) — `applyAccepted` / `applyOffer` / `applyRefusal` / `applyFragments` |
| Accept / refuse CC bundles | [`effect/CrossCuttingBundles.kt`](src/main/kotlin/fsm/sample/effect/CrossCuttingBundles.kt) — `acceptTransitionCrossCutting` / `refuseCrossCutting` (**pass machine `extras` / `traceDetail`**) |
| Hierarchy path / LCA / plan | [`machine/Hierarchy.kt`](src/main/kotlin/fsm/sample/machine/Hierarchy.kt) — `pathFromRoot`, `commonPrefixLength`, `planTransition` (`transitionExtras`), `dispatchWithDelegation` — **call these directly** (no vignette free-function wrappers) |
| List recording bag | [`machine/RecordingPort.kt`](src/main/kotlin/fsm/sample/machine/RecordingPort.kt) — tiny open list sink; list-shaped vignette ports **subclass** it (not private bag + pass-through) |
| Fragment shapes | [`machine/FragmentedEffects.kt`](src/main/kotlin/fsm/sample/machine/FragmentedEffects.kt) |
| Pure outcomes | [`effect/StepResult.kt`](src/main/kotlin/fsm/sample/effect/StepResult.kt), [`effect/OfferOutcome.kt`](src/main/kotlin/fsm/sample/effect/OfferOutcome.kt), [`effect/HierarchyStep.kt`](src/main/kotlin/fsm/sample/effect/HierarchyStep.kt) |
| Product export | [`export/ProductEvaluator.kt`](src/main/kotlin/fsm/sample/export/ProductEvaluator.kt), [`export/SampleExports.kt`](src/main/kotlin/fsm/sample/export/SampleExports.kt) |
| Full design doc | [`docs/machine-authoring-surfaces.md`](docs/machine-authoring-surfaces.md) |
| Shared dual-sink test helpers | [`src/test/kotlin/fsm/sample/testsupport/`](src/test/kotlin/fsm/sample/testsupport/) |

## How to add a machine

### Checklist (any kind)

1. Sealed **state** + **event** with `label()`.
2. Machine-local **domain ADT** (if any I/O) with `exportLabel()` — never nest `CrossCuttingEffect`.
3. Domain **port** + `applyDomainX` — only place that mutates domain hardware/session I/O.
4. **Pure** step surface matching kind (below). Pure code must not touch env/port/log.
5. **Boundary** only via the kind-correct shared `apply*` helper.
6. Cross-cutting via `acceptTransitionCrossCutting` / `refuseCrossCutting` **with machine-specific extras**.
7. **Export:** explicit domain lists + product helper + optional Mermaid; never construct side-effecting context classes for export (`ConnectionContext` runs entry on `init`).
8. **Tests:** pure-API vs export row parity; refuse has no domain; handle mutates env/port; purity of step.

### Total flat (copy TrafficLight)

1. Skeleton: `flat/TrafficLight.kt` + `LightEffect.kt`.
2. `step(S,E) → StepResult` with `acceptTransitionCrossCutting(..., extras = …)` + domain list.
3. Boundary: `applyAccepted(...)`.
4. Export: `productEvaluateTotal` + `exportX` + **manual** registration (below).

### Refuse-capable / CSP (copy FileProtocol)

1. `stepOffer(S,E) → OfferOutcome<S,D>` — construct/`is`/`when` on **`OfferOutcome.Accepted` / `Refused`** only (no `OfferStep` typealias).
2. Refuse via `refuseCrossCutting`; accept extras include machine metrics (e.g. `csp.accept`).
3. Boundary: `applyOffer` (domain only on Accepted).
4. Export: `productEvaluateOffer`.

### Hierarchical (not “copy TrafficLight”)

1. Leaves + parent links; pure `onEvent` / enter / exit fragments.
2. Vignette-local `declaredEffects` — real leaf change → call **`machine.planTransition`** with ConnState lambdas (optional `transitionExtras`); same-leaf / refuse stay local.
3. Boundary: **`applyFragments` then assign** if leaf changed — never `applyAccepted`.
4. Export: `productEvaluateHierarchy` over the same pure gof `connectionStep` used by handle (no context construction; no dual `isUnhandled`/`nextOf` callbacks).

### Orthogonal (copy DoorSystem)

1. Regions use the total-flat recipe + `applyAccepted`.
2. Container pure guards use `refuseCrossCutting` then **`applyRefusal`** (CC only; no domain on refuse).
3. Share one `CrossCuttingEnv` + domain port across regions when teaching process-wide CC.

### Domain ports (vignette-local)

Ports are **vignette-local by law** — named accessors (`commands`, `ops`, `sessions`, `actuators`) and `applyDomain*` stay next to the domain ADT.

For list-shaped recording only, **be** the shared bag (subclass) — do **not** keep a private `bag` that only re-exports `record`/`clear`/`items`:

```kotlin
// machine/RecordingPort.kt — open list bag only, not an effect engine
open class RecordingPort<T> {
    private val _items = mutableListOf<T>()
    val items: List<T> get() = _items.toList()
    fun record(item: T) { _items += item }
    open fun clear() { _items.clear() }
}

// Vignette port recipe — subclass the bag; rename accessors; add snapshot:
class MyPort : RecordingPort<MyOp>() {
    val ops: List<MyOp> get() = items
    // record / clear inherited
    fun snapshot(): String = "ops=${ops.map { it.label() }}"
}

// Specialized fields (e.g. heartbeat) live on the same subclass:
class ConnectionPort : RecordingPort<SessionOp>() {
    val sessions: List<SessionOp> get() = items
    var heartbeats: Int = 0; private set
    fun incHeartbeat() { heartbeats += 1 }
    override fun clear() { super.clear(); heartbeats = 0 }
}
```

Do **not** invent a shared DomainPort hierarchy or generic apply engine.

### Manual `exportAllDocument` registration

Export discovery is **manual**. After adding `exportMyMachine()` / `evaluateMyMachine()`:

1. Wire product evaluation in `ProductEvaluator.kt` (or a thin adapter).
2. Add `exportMyMachine()` in `SampleExports.kt`.
3. Append the export to the list inside **`exportAllDocument()`** — there is no classpath registration.

```kotlin
// SampleExports.kt — edit the list explicitly
fun exportAllDocument(): String {
    val parts = listOf(
        exportTrafficLight(),
        exportFileProtocol(),
        exportConnection(),
        exportDoorSystem(),
        // exportMyMachine(),  // ← add here
    )
    // ...
}
```

## Cross-cutting vs domain effects

Cross-cutting is **not** audit-only. A single sealed root holds **disparate families**:

| Family | Type | Sink on `CrossCuttingEnv` |
|--------|------|---------------------------|
| **Audit** | `CrossCuttingEffect.Audit.*` | `TransitionLog` |
| **Metric** | `CrossCuttingEffect.Metric.Inc` | `MetricsPort` |
| **Trace** | `CrossCuttingEffect.Trace.Span` | `TracePort` |

Domain effects stay **machine-local** sealed ADTs → machine **ports** (lamp, file ops, session/heartbeat, door actuators).

```text
StepResult(next, crossCutting: List<CrossCuttingEffect>, domain: List<D>)
OfferOutcome.Accepted(next, crossCutting, domain) | Refused(reason, next, crossCutting)  // no domain
```

| Machine | Example cross-cutting | Domain |
|---------|----------------------|--------|
| TrafficLight | Audit transition + `Metric.Inc(fsm.transitions)` + `Metric.Inc(light.ticks)` / `Trace` on Emergency | `DriveLamp` |
| FileProtocol | Accept: audit + metrics; Refuse: audit + `csp.refuse` metric + **Trace** | Open/Read/Close handle (accept only) |
| Connection | Session open/close: audit + metrics + **Trace**; refuse: metric + trace | `OpenSession` / `CloseSession` / `IncHeartbeat` |
| Door | Region metrics; refuse: audit + `door.refuse` + **Trace.Span(door.policy)** | Strike / deadbolt / chime |

Type separation: domain ADTs cannot hold cross-cutting cases; `List<LightEffect>` cannot hold `IncHeartbeat`.

Export product evaluation reads pure effect **labels** only (no interpreters, no env/port mutation).

Hierarchical Connection applies **fragments** (cross-cutting then domain per LCA step) so session domain stays next to Online enter/exit in export order.

## Must / must not (agent defaults)

| Must | Must not |
|------|----------|
| Pure step returns `StepResult` / `OfferOutcome` / fragments | Call `CrossCuttingInterpreter` inside pure step |
| Boundary uses kind-correct shared `apply*` | Apply domain on refuse; route hierarchy through `applyAccepted` |
| Domain ADT machine-local sealed | Nest `CrossCuttingEffect` in domain ADT |
| Bundles **with** extras / correct `traceDetail` | Hand-roll audit-only refuse; bare accept without machine metrics |
| Explicit sealed domain lists for product eval | Reflection over subclasses |
| Export labels from pure declarations | Export from interpreter side effects |
| Hierarchy: apply fragments, assign **after** | Flatten all CC then all domain (breaks Online enter pairing) |

## Export tables

```bash
gradle -q runExport > export.md
```

Markdown tables include an **Effects** column (comma-joined labels such as
`Audit.LogTransition`, `Metric.Inc(fsm.transitions)`, `Trace.Span(...)`, `DriveLamp(Green)`).

Every non-trivial type and path is documented with **PURPOSE** comments in source.
See also [`docs/machine-authoring-surfaces.md`](docs/machine-authoring-surfaces.md) for the full shared-surfaces design.
