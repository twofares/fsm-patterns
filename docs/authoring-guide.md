# Authoring guide — as-built dual-sink machines

Detailed how-to for extending this sample. High-level orientation lives in the
[README](../README.md). Timers and automatic transitions:
[timers-and-automatic.md](timers-and-automatic.md).

## Shared surfaces (as-built)

| Concern | Package / API |
|---------|----------------|
| Pure total outcome | `effect/StepResult` |
| Pure partial outcome | `effect/OfferOutcome` (`Accepted` / `Refused`) |
| Pure hierarchy fuse | `effect/HierarchyStep` + vignette `connectionStep` |
| CC families + env | `effect/CrossCuttingEffect`, `CrossCuttingEnv`, `CrossCuttingInterpreter` |
| Accept / refuse list builders | `effect/acceptTransitionCrossCutting`, `refuseCrossCutting` |
| Boundary apply | `machine/applyAccepted`, `applyOffer`, `applyRefusal`, `applyFragments` |
| Hierarchy structure | `machine/pathFromRoot`, `commonPrefixLength`, `planTransition`, `dispatchWithDelegation` |
| Fragments | `machine/EffectFragment`, `FragmentedStepEffects` |
| List recording bag | `machine/RecordingPort` — **subclass** for vignette ports |
| Product export | `export/productEvaluateTotal` / `Offer` / `Hierarchy` / `Automatic`; `SampleExports.exportAllDocument` |
| Dual-sink test helpers | `src/test/.../testsupport/` |

Call hierarchy helpers from `machine/` **directly**. Do not re-export thin public free-function wrappers named like `pathFromRoot` / `planTransition` / `dispatchWithDelegation` from a vignette package.

---

## Checklist — any machine

1. Sealed **state** + **event** with `label()`.
2. Machine-local **domain ADT** (if any I/O) with `exportLabel()` — never nest `CrossCuttingEffect`.
3. Domain **port** + `applyDomainX` — only place that mutates domain hardware/session I/O.
4. **Pure** step surface matching kind (below). Pure code must not touch env / port / log / clocks.
5. **Boundary** only via the kind-correct shared `apply*` helper.
6. Cross-cutting via `acceptTransitionCrossCutting` / `refuseCrossCutting` **with machine-specific extras** / `traceDetail`.
7. **Export:** explicit state/event lists + product helper + optional Mermaid; never construct side-effecting context classes for export (`ConnectionContext` runs entry on `init`).
8. **Tests:** pure-API vs export row parity; refuse has no domain; handle mutates env/port; purity of step.

---

## Total flat (copy TrafficLight)

**Files:** `flat/TrafficLight.kt`, `flat/LightEffect.kt`

1. `step(S, E) → StepResult` with `acceptTransitionCrossCutting(..., extras = …)` + domain list.
2. Boundary: `applyAccepted(result, env, port, applyDomain, assign)`.
3. Multi-port (if timers): inject only `clock`; `val timers = TimerPort(clock)`;  
   `applyAccepted(..., { d, _ -> applyDomainLight(d, port, timers) }) { current = it }`.
4. Export: `productEvaluateTotal` + `exportTrafficLight` + append to `exportAllDocument()`.

```text
pure step → assign next → CrossCuttingInterpreter → applyDomain
```

---

## Refuse-capable / CSP (copy FileProtocol)

**Files:** `csp/FileProtocol.kt`, `csp/FileEffect.kt`

1. `stepOffer(S, E) → OfferOutcome` — construct / `is` / `when` on **`OfferOutcome.Accepted` / `Refused`** only (no `OfferStep` typealias).
2. Refuse via `refuseCrossCutting` (metric + trace + audit); accept extras include machine metrics (e.g. `csp.accept`).
3. Boundary: `applyOffer` — domain **only** on `Accepted`.
4. Client DTO `OfferResult` may map from pure outcome after apply; keep pure path free of ports.
5. Export: `productEvaluateOffer`.

---

## Hierarchical (not “copy TrafficLight”)

**Files:** `gof/ConnectionMachine.kt`, `gof/ConnectionEffect.kt`

1. Leaves + `parent` links; pure `onEvent` → vignette-local `HandleResult`.
2. Pure enter/exit fragments on states; vignette-local **`declaredEffects`**:
   - real leaf change → `machine.planTransition(...)` with ConnState lambdas (optional `transitionExtras`)
   - same-leaf handled → one accept fragment + machine metrics + handler domain
   - unhandled → `refuseCrossCutting` with `traceDetail = event.label()`
3. Pure fuse: `connectionStep = dispatchConnection + declaredEffects → HierarchyStep` (shared by handle and export).
4. Boundary: **`applyFragments` then assign** if leaf changed — never `applyAccepted`.
5. Inject only `clock` if timers; multi-port lambda closes over `port` + `timers`.
6. Export: `productEvaluateHierarchy(step = { l, e -> connectionStep(l, e) })` — no `ConnectionContext`.

```text
pure plan → for each fragment (CC → domain) → assign leaf
```

Conn-local only: `HandleResult`, `declaredEffects`, `onEvent`, Heartbeat / same-leaf metrics, enter/exit domain payloads. Structural dispatch lives in `machine.dispatchWithDelegation` (bound once via `internal dispatchConnection` if useful).

---

## Orthogonal (copy DoorSystem)

**Files:** `orthogonal/DoorSystem.kt`, `orthogonal/DoorEffects.kt`

1. Regions use the total-flat recipe + `applyAccepted`.
2. Container pure guards: `refuseCrossCutting` then **`applyRefusal`** (CC only; no domain, no assign).
3. Share one `CrossCuttingEnv` + domain port across regions when teaching process-wide CC.
4. Export: compose region tables (`exportDoorSystem`); not a full product of concurrent configurations.

---

## Domain ports

Ports are **vignette-local** — named accessors and `applyDomain*` stay next to the domain ADT.

List-shaped recording: **subclass** `RecordingPort` (do not wrap a private bag that only re-exports `record`/`clear`/`items`):

```kotlin
class MyPort : RecordingPort<MyOp>() {
    val ops: List<MyOp> get() = items
    fun snapshot(): String = "ops=${ops.map { it.label() }}"
}
```

Specialized fields (e.g. `ConnectionPort.heartbeats`) live on the same subclass. Do **not** invent a shared DomainPort hierarchy or generic apply engine.

---

## Manual export registration

Export discovery is **manual**. After adding `exportMyMachine()` / `evaluateMyMachine()`:

1. Wire product evaluation in `export/ProductEvaluator.kt`.
2. Add `exportMyMachine()` in `export/SampleExports.kt`.
3. Append to the list inside **`exportAllDocument()`** — no classpath registration.

```kotlin
fun exportAllDocument(): String {
    val parts = listOf(
        exportTrafficLight(),
        exportFileProtocol(),
        exportConnection(),
        exportDoorSystem(),
        // exportMyMachine(),
    )
    // ...
}
```

Regenerate the committed artifact:

```bash
gradle -q runExport > export.md
```

Never construct `ConnectionContext` for export (init applies entry fragments). Never run clocks or `pollDue` inside product evaluators.

---

## Cross-cutting bundles

Always pass machine-specific extras:

```kotlin
acceptTransitionCrossCutting(
    machine = machineName,
    from = state.label(),
    event = event.label(),
    to = next.label(),
    extras = listOf(metricInc("light.ticks")), // machine-specific
)
refuseCrossCutting(
    machine = machineName,
    state = state.label(),
    event = event.label(),
    reason = "...",
    metricName = "csp.refuse",
    traceName = "csp.refuse",
    traceDetail = event.label(), // or policy reason — be intentional
)
```

---

## See also

- [README](../README.md) — teaching overview  
- [timers-and-automatic.md](timers-and-automatic.md) — timers + ε  
- [machine-examples-visual.html](machine-examples-visual.html) — structural diagrams  
- [export.md](../export.md) — generated tables  
