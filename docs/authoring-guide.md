# Authoring guide — as-built dual-sink machines

Detailed how-to for extending this sample. High-level orientation lives in the
[README](../README.md). Term definitions: [glossary.md](glossary.md).
Timers and automatic transitions: [timers-and-automatic.md](timers-and-automatic.md).
In-process vs inter-proc boundaries: [in-process-vs-interproc.md](in-process-vs-interproc.md).

**Default in this guide:** in-process live contexts + `machine/apply*`. For
stateless load→step→store→remote-deliver, see the inter-proc section below and
package `interproc/`.

## Shared surfaces (as-built)

| Concern | Package / API |
|---------|----------------|
| Pure total outcome | `effect/StepResult` |
| Pure partial outcome | `effect/OfferOutcome` (`Accepted` / `Refused`) |
| Pure hierarchy fuse | `effect/HierarchyStep` + vignette `connectionStep` |
| CC families + env | `effect/CrossCuttingEffect`, `CrossCuttingEnv`, `CrossCuttingInterpreter` |
| Accept / refuse list builders | `effect/acceptTransitionCrossCutting`, `refuseCrossCutting` |
| Boundary apply | `machine/applyAccepted`, `applyOffer`, `applyRefusal`, `applyFragments` (in-process **and** inter-proc bindings) |
| Hierarchy structure | `machine/pathFromRoot`, `commonPrefixLength`, `planTransition`, `dispatchWithDelegation` |
| Fragments | `machine/EffectFragment`, `FragmentedStepEffects` |
| List recording bag | `machine/RecordingPort` — **subclass** for vignette ports |
| Inter-proc engagement | `interproc/StatelessPlayerEngagement`, `SessionStore`, `ThinPlayerPort` |
| Product export | `export/productEvaluateTotal` / `Offer` / `Hierarchy` / `Automatic`; `SampleExports.exportAllDocument` |
| Dual-sink test helpers | `src/test/.../testsupport/` |

Call hierarchy helpers from `machine/` **directly**. Do not re-export thin public free-function wrappers named like `pathFromRoot` / `planTransition` / `dispatchWithDelegation` from a vignette package.

---

## Checklist — any machine

1. Sealed **state** + **event** with `label()`.
2. Machine-local **domain type** (if any I/O): a sealed class/interface of intents
   with `exportLabel()` — never nest `CrossCuttingEffect` inside it.
3. Domain **port** / deliver path + `applyDomainX` — only place that performs those
   intents (hardware, files, sessions, remote thin player, …).
4. **Pure** step surface matching kind (below). Pure code must not touch env / port / log / clocks.
5. **Boundary:**
   - **In-process:** kind-correct shared `applyAccepted` / `applyOffer` /
     `applyRefusal` / `applyFragments` only.
   - **Inter-proc / stateless:** same helpers with different bindings — load →
     pure step → `applyOffer` (assign = store, domain = remote + timers). See
     [Inter-proc](#inter-proc--stateless-copy-playercontrol).
6. Cross-cutting via `acceptTransitionCrossCutting` / `refuseCrossCutting` **with machine-specific extras** / `traceDetail`.
7. **Export:** explicit state/event lists + product helper + optional Mermaid; never construct side-effecting context classes for export (`ConnectionContext` runs entry on `init`). Optional for boundary-only inter-proc vignettes.
8. **Tests:** pure-API vs export row parity (when exported); refuse has no domain; handle/engage mutates env/port; purity of step.

---

## Total flat (copy TrafficLight)

**Engagement:** every known event is accepted (staying put counts). No refuse
branch—use [partial](#refuse-capable--csp-copy-fileprotocol) when some operations
must be declined. Teaching contrast:
[README — Total vs partial](../README.md#total-vs-partial-the-main-fork).
Background words: [glossary](glossary.md#background-terms).

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

**Engagement:** some events are refused—like a **menu** of legal operations, or
a small **protocol** of allowed moves (open then read/close, not open twice).
Domain I/O only on `Accepted`; `Refused` has no domain field so declined paths
cannot declare hardware/file work. Prefer this over total when illegal ops must
not touch ports. Teaching contrast:
[README — Total vs partial](../README.md#total-vs-partial-the-main-fork).
Background words: [glossary](glossary.md#background-terms).

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

## Inter-proc / stateless (copy PlayerControl)

**Engagement instance:** no live `var current`. **One session per instance**
(`StatelessPlayerEngagement`). Each request loads pure state, runs pure
`stepOffer`, then **`applyOffer`** with assign = persist and domain = thin remote
player (Play/Pause/Unpause/Next) + boundary-owned `TimerPort`. Teaching analogy
for “server brain + device” — not a real network stack. Multi-session demos
construct **N engagement instances** (each owns store, player, timers, CC;
optionally share a `VirtualClock`). Full contrast:
[in-process-vs-interproc.md](in-process-vs-interproc.md).

**Files:** `interproc/PlayerControl.kt`, `PlayerDomain.kt`, `SessionStore.kt`,
`StatelessPlayerEngagement.kt`.

1. Pure partial machine: `stepOffer(S, E) → OfferOutcome` (same dual-sink rules as FileProtocol).
2. Domain: sealed intents that **map to remote commands** + `TimerIntent` (not co-located lamp/file ports).
3. Boundary: **do** call `applyOffer` — same helper as FileProtocol; different closures:
   - `store.load` → pure step
   - `assign = { store.store(it) }`
   - `applyDomain` closes over thin player + `TimerPort` (`applyDomainPlayerRemote`)
   - refuse hygiene is free (no domain on `Refused`)
4. Timers: Arm/Cancel → boundary-owned `TimerPort`; due → `pollDue` → map `eventKey` → sealed event → **`engage` on the same instance** (not same-stack live `handle`).
5. Tests: pure `stepOffer`/menu in `PlayerControlTest`; load/store/deliver/due/multi-instance in `StatelessPlayerEngagementTest`.
6. Export: optional; this vignette is boundary-focused (no product table required).

```text
load S → pure stepOffer → applyOffer(assign=store, domain=remote+timers)
```

Reuse existing pure steps the same way (`FileProtocol.stepOffer`, `TrafficLight.step`)
if you only need the boundary pattern; the music-player machine exists so the
thin-player command set is obvious.

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
- [glossary.md](glossary.md) — as-built terms  
- [origins-and-sources.md](origins-and-sources.md) — provenance and concept sources  
- [timers-and-automatic.md](timers-and-automatic.md) — timers + ε  
- [in-process-vs-interproc.md](in-process-vs-interproc.md) — in-process vs stateless boundaries  
- [machine-examples-visual.html](machine-examples-visual.html) — structural diagrams  
- [export.md](../export.md) — generated tables  

