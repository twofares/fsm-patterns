# In-process vs inter-proc / stateless boundaries

This sample teaches **two boundary styles** that share the same pure dual-sink
step APIs **and** the same `applyOffer` / `applyAccepted` / `applyFragments`
helpers. Readers should not confuse live single-process vignettes with the
stateless (Alexa-style) teaching package — the difference is **what assign and
domain lambdas close over**, not a forked apply ladder.

| Style | Packages | Ownership of state | Domain apply |
|-------|----------|--------------------|--------------|
| **In-process** | `flat/`, `csp/`, `gof/`, `orthogonal/`, `machine/` | Live `var current` on a context | Same-stack ports + `TimerPort` via `applyAccepted` / `applyOffer` / `applyFragments` |
| **Inter-proc / stateless** | `interproc/` | External [SessionStore](../src/main/kotlin/fsm/sample/interproc/SessionStore.kt) load/store | Remote [ThinPlayerPort](../src/main/kotlin/fsm/sample/interproc/PlayerDomain.kt) + boundary-owned [TimerPort](../src/main/kotlin/fsm/sample/machine/TimerPort.kt) via the **same** `applyOffer` |

High-level overview: [README](../README.md). Authoring: [authoring-guide.md](authoring-guide.md).
Timers (in-process): [timers-and-automatic.md](timers-and-automatic.md).

---

## What stays the same

Pure declare surfaces are **unchanged** and re-executable on any stored `S`:

| Concern | Same API / rule |
|---------|-----------------|
| Total flat | `step(S, E) → StepResult(next, crossCutting, domain)` |
| Partial / CSP | `stepOffer(S, E) → OfferOutcome` — `Accepted` has domain; **`Refused` has no domain** |
| Hierarchical | `connectionStep(leaf, event) → HierarchyStep` |
| Timers | Pure `TimerIntent.Arm` / `Cancel` inside domain lists (not a third outcome field) |
| Automatic (ε) | Pure `automaticStep` + `drainAutomatic` (extension of host kind) |
| Dual-sink | Cross-cutting = observability families; domain = machine-local sealed intents |
| Refuse hygiene | Domain I/O unrepresentable on refuse / `applyRefusal` paths |
| Apply helpers | `applyAccepted` / `applyOffer` / `applyFragments` — order contracts unchanged |
| Export | Product tables from pure steps only (no live ports or clocks) |

The teaching point: **control core stays pure**. Load/store, RPC, and schedule
ownership are **boundary-only** — expressed as the closures passed into the
shared helpers.

---

## What differs

| Concern | In-process | Inter-proc / stateless |
|---------|------------|------------------------|
| State | Mutable `current` on `TrafficLight` / `FileProtocol` / `ConnectionContext` / `DoorSystem` | Load `S` from store → pure step → **persist** `next` |
| Assign binding | Lambda `{ current = it }` inside `applyOffer` / `applyAccepted` | `store.store(next)` (one store per engagement) |
| Domain binding | Co-located `RecordingPort` / lamp / file / session ports | **Deliver** ordered domain as remote commands + boundary `TimerPort` |
| Shared apply helpers | `machine.applyAccepted`, `applyOffer`, `applyFragments` | **Same** `applyOffer` — different assign / domain closures |
| Cross-cutting | `CrossCuttingEnv` co-located with the live machine | Still applied **server-side** in the sample (brain observability); **not** shipped to the thin player |
| Timers | `TimerPort(clock)` field on live context + same-stack `fireDueTimeouts` → `handle` | Boundary-owned `TimerPort` on the engagement instance; due → **re-enter via `engage` on the same instance** with sealed event. Multi-session = **N engagement instances** (each owns its bag; optionally share a `VirtualClock`) |
| Automatic drain | Live `handle` drains ε microsteps before return | (Not shown in player vignette; same pure drain, apply each microstep as separate persist/deliver if needed) |
| Multi-session | One live instance | **One session per engagement instance**; multi-session demos construct **N instances** (optionally sharing a `VirtualClock`) |
| Process model | Single JVM teaching process | Teaching **analogy** of server brain + thin device (no real network) |

### Mapping table (research → code)

| In-process piece | Inter-proc replacement |
|------------------|------------------------|
| Live context + `var current` | [SessionStore](../src/main/kotlin/fsm/sample/interproc/SessionStore.kt) (one holder per engagement) |
| `applyOffer` assign→CC→domain | Same [applyOffer](../src/main/kotlin/fsm/sample/machine/BoundaryApply.kt); assign = store, domain = thin player + timers |
| Vignette `RecordingPort` / `applyDomain*` local | [ThinPlayerPort](../src/main/kotlin/fsm/sample/interproc/PlayerDomain.kt) + `applyDomainPlayerRemote` |
| `TimerPort` + `VirtualClock` co-located | Boundary-owned `TimerPort` + `VirtualClock` on the engagement (same types) |
| `fireDueTimeouts` on live instance | `StatelessPlayerEngagement.fireDueTimeouts()` → `engage` |
| Domain list on accept | Deliverable payload (ordered remote commands + schedule intents) |
| Refuse path | No domain delivery; CC only (`applyOffer` skips domain on refuse) |

---

## When to use each

**In-process** — default for this repo’s classical FSM / statechart / CSP teaching:

- One object owns mode, ports, and timers
- Same-stack dual-sink apply order is the lesson (`BoundaryApply`)
- Demos and most unit tests (`DemoMain`, `TrafficLight.handle`, …)

**Inter-proc / stateless** — when control must re-run without a live machine field:

- Session state lives in external storage (here: an in-memory holder stand-in)
- Domain work is remote (device / worker) and must not run on refuse
- Timers are scheduled outside the request stack and re-enter as ordinary events
- Multiple sessions = multiple engagement instances (each with own store / player / timers / CC)

You can still unit-test pure `step` / `stepOffer` without either boundary.

---

## How the inter-proc vignettes relate to existing ones

| Existing (in-process) | Inter-proc counterpart |
|-----------------------|------------------------|
| `csp/FileProtocol` — partial menu, refuse without domain | `interproc/PlayerControl.stepOffer` — same `OfferOutcome` shape; pause while idle refuses with empty deliver list |
| `flat/TrafficLight` — dwell `TimerIntent` + `TimerPort` | Track-end Arm/Cancel → boundary `TimerPort`; due → `TrackEnded` event |
| `machine/BoundaryApply` — assign + ports | Same helpers; [StatelessPlayerEngagement](../src/main/kotlin/fsm/sample/interproc/StatelessPlayerEngagement.kt) binds store + remote deliver |
| Domain sealed ADTs (`LightEffect`, `FileEffect`, …) | `PlayerEffect.Remote` / `PlayerEffect.Timer` — remote half is the thin-player command set only |

Pure free functions (`TrafficLight.step`, `connectionStep`, `FileProtocol.stepOffer`) were already state-in/state-out. The music-player machine exists only to make the **server brain + thin player** story obvious; the boundary pattern would work the same over `FileProtocol.stepOffer`, flat `step`, or hierarchical `connectionStep` (+ fragment deliver) if you wrapped them with load/store/deliver. Only the CSP-shaped player is fully demoed under `interproc/` (scope).

---

## Alexa / streaming-player analogy (teaching only)

**Motivation, not a product integration:**

```text
┌─────────────────────────────┐         ┌──────────────────────┐
│  Server “brain” (stateless) │         │  Thin player device  │
│  load S → pure stepOffer    │  RPC    │  Play / Pause        │
│  applyOffer(store, deliver) │ ──────► │  Unpause / Next      │
│  schedule track-end on bag  │         │  (no playlist FSM)   │
│  CC audit/metrics locally   │         └──────────────────────┘
└─────────────────────────────┘
```

- Playlist / station / “what is next?” decisions stay on the server (outside the
  thin command ADT — this sample only advances with `Next`).
- The device executes play/pause/unpause/next only.
- Pure control can re-run on any worker that loads the same session blob.
- No Alexa skill SDK, no network sockets, no secrets — ports record commands.

---

## Engagement recipe (inter-proc)

One **engagement instance** = one session. Multi-session demos construct multiple
instances (optionally sharing a `VirtualClock`).

```text
engage(event):
  previous = store.load() ?: Idle
  outcome  = stepOffer(previous, event)     // pure
  applyOffer(
    outcome, serverEnv, Unit,
    applyDomain = { domain, _ -> applyDomainPlayerRemote(domain, thinPlayer, timers) },
    assign = { store.store(it) },
  )
  // assign → CC → domain if Accepted; refuse: assign + CC, no domain

fireDueTimeouts():
  // pollDue removes all currently due first (parity with TrafficLight/Connection);
  // if engage fails mid-loop, remaining dues are already gone
  for due in timers.pollDue():
      result = engage(eventFromKey(due.eventKey))
      // returns DueFireResult(event, result) — “fired” means re-engaged;
      // result.accepted may be false if state/schedule desynced
```

Store always runs (including refuse self-successor and first-contact refuse).

---

## Must / must not (inter-proc extras)

| Must | Must not |
|------|----------|
| Keep pure step free of store / RPC / clocks | Put load/store inside pure `stepOffer` |
| Deliver domain **only** on accept (`applyOffer`) | Deliver thin-player commands on refuse |
| Treat domain list order as deliver order | Reorder Cancel/Arm/Remote casually |
| Use `applyOffer` with store + remote domain bindings | Hand-roll a second assign→CC→domain ladder |
| One session per engagement (N instances for multi-session) | Share one store/player/timers across “sessions” without documenting the shared ownership |
| Label teaching packages so readers see the split | Invent a third apply **order** for inter-proc (bindings differ; order does not) |

---

## See also

- [README](../README.md) — package map and machine kinds  
- [authoring-guide.md](authoring-guide.md) — how to add machines (in-process default)  
- [timers-and-automatic.md](timers-and-automatic.md) — in-process `TimerPort` recipes  
- [glossary.md](glossary.md) — dual-sink, menu, offer  
- Source: `src/main/kotlin/fsm/sample/interproc/`  
- Tests: `src/test/kotlin/fsm/sample/interproc/`
