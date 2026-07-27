# Timers and automatic (ε) transitions — as-built how-to

How to use delayed events and eventless completion in this sample.  
Overview: [README](../README.md). Terms: [glossary.md](glossary.md).
General authoring: [authoring-guide.md](authoring-guide.md).

Timers and automatic transitions **extend** dual-sink pure steps. They do **not**
add a third list on `StepResult` / `OfferOutcome` / `HierarchyStep`, and they are
**not** a full SCXML interpreter.

---

## Concepts

| Concept | Pure layer | Boundary layer |
|---------|------------|----------------|
| **Delayed send** | Domain wraps `TimerIntent.Arm` / `Cancel` | `TimerPort` + `VirtualClock` schedule; timeouts re-enter as sealed events |
| **Eventless / completion** | `automaticStep(S) → StepResult?` (null = no edge) | After external apply, `drainAutomatic` then apply each microstep |

**Where intents live:** domain-like scheduling, not cross-cutting.  
`CrossCuttingEffect` is observability (audit / metric / trace). Scheduling mutates a port and later injects events — keep that in domain + `TimerPort`.

---

## Shared APIs

| API | File |
|-----|------|
| `TimerIntent.Arm(id, delayMs, eventKey)` / `Cancel(id)` | `effect/TimerIntent.kt` |
| `exportLabel()` → `Timer.Arm(...)` / `Timer.Cancel(...)` | same |
| `Clock`, `VirtualClock`, `TimerPort` | `machine/TimerPort.kt` |
| `TimerPort.apply(list)` / `apply(single)` / `pollDue` / `pending` | same |
| `drainAutomatic`, `AutomaticDrainResult`, `DEFAULT_MAX_MICROSTEPS` (32) | `machine/Automatic.kt` |
| `productEvaluateAutomatic` | `export/ProductEvaluator.kt` |

`pollDue` returns due entries sorted by `(dueAtMs, id)`, removes them from pending, and does **not** call `handle`.

---

## Recipe: timers on a total flat machine (TrafficLight)

**Reference:** `flat/TrafficLight.kt`, `flat/LightEffect.kt`

1. Sealed timeout event with stable `label()` (e.g. `LightEvent.DwellElapsed`).
2. Domain case: `LightEffect.Timer(TimerIntent)`.
3. Pure `step` **declares** Cancel / Arm / DriveLamp — never touches `TimerPort` or clocks.
4. Always: `Arm(..., eventKey = LightEvent.DwellElapsed.label())` — never freehand strings.
5. Pure mapper: `lightEventFromTimerKey(eventKey)`.
6. Context: inject **only** `clock`; `val timers = TimerPort(clock)` (no separate ctor-injected port).
7. `applyDomainLight(domain, port, timers)` applies Timer cases **inline** via `timers.apply(intent)`.
8. Boundary: `{ domain, _ -> applyDomainLight(domain, port, timers) }` into `applyAccepted`.
9. Client / test: `clock.advance(ms)` → `timers.pollDue()` or `fireDueTimeouts()` → mapper → `handle`.
10. Export: include timeout events in Cartesian product; Timer labels via domain `exportLabel`; **no clocks** in evaluators.

### Dwell policy (as-built)

| Path | Domain (order matters) |
|------|------------------------|
| Enter Red / Green / Yellow | `Cancel(light.dwell)` → `Arm(...)` → `DriveLamp` |
| Enter Flash (Emergency or Flash self-loop) | `Cancel` → `DriveLamp(Flash)` — **no Arm** |
| Construction | **No** init arm for Red (first engagement arms next color) |

Constants: `LIGHT_DWELL_ID`, `DWELL_RED_MS` / `GREEN` / `YELLOW`.

Cancel dwell on **every** abort path (especially Emergency → Flash) so no stale `DwellElapsed` fires later.

---

## Recipe: hierarchical cancel-on-exit (Connection watchdog)

**Reference:** `gof/ConnectionMachine.kt`, `gof/ConnectionEffect.kt`

1. Domain: `ConnectionEffect.Timer(TimerIntent)`.
2. Online **enter** domain (order): `OpenSession` then `Arm(CONNECTION_WATCHDOG_ID, …, eventKey = WatchdogTimeout.label())`.
3. Online **exit** domain (order): `Cancel(watchdog)` then `CloseSession` — Cancel first is load-bearing under inline apply.
4. `planTransition` LCA already omits Online exit/enter on Idle ↔ Transferring → watchdog stays armed.
5. `WatchdogTimeout` handled at Online → `Offline` (bubbles from leaves).
6. Heartbeat: domain `IncHeartbeat` only — **does not** re-arm (absolute deadline from Online enter).
7. Multi-port lambda on **init and handle**; inject only `clock`.
8. Optional: `ConnectionContext.fireDueTimeouts()` — same poll → `connEventFromTimerKey` → handle recipe as TrafficLight.
9. Export: include `WatchdogTimeout` in event list; product labels include `Timer.Arm` / `Timer.Cancel` from pure `connectionStep`.

---

## Recipe: automatic completion (TrafficLight Flash → Red)

**Reference:** `flat/TrafficLight.kt` (`automaticStep`, `handle`)

1. Intermediate pure state in the sealed set (`LightState.Flash`) so total product remains exhaustive.
2. Pure `automaticStep(Flash) → StepResult` to Red with event label `ε` and domain Cancel + Arm(Red) + DriveLamp; **null** on Red/Green/Yellow.
3. Define full **total** `step` for Flash × every sealed event (self-loops OK).
4. `handle`: `applyAccepted` on external step, then `drainAutomatic(start = current, automaticStep, nextOf = { it.next })`, then `applyAccepted` each microstep.
5. Bound: default 32 microsteps; cycle detection on; **throw** `AutomaticBoundExceededException` (`BOUND` | `CYCLE`) — no soft `truncated` field.
6. Export: Flash in state list + `productEvaluateAutomatic` for ε rows; do **not** call `drainAutomatic` inside export.

```text
external event → applyAccepted
              → drainAutomatic (pure)
              → applyAccepted each microstep
```

---

## eventKey contract

| Rule | Detail |
|------|--------|
| Arm with `label()` | `eventKey = MyTimeout.label()` only |
| Pure mapper | Explicit `when` — no reflection |
| Unknown key | `error(...)` at boundary / `null` from mapper — do not silent-drop |

```kotlin
fun lightEventFromTimerKey(eventKey: String): LightEvent? = when (eventKey) {
    LightEvent.DwellElapsed.label() -> LightEvent.DwellElapsed
    else -> null
}
```

---

## Client / demo drain (not export)

```kotlin
// Shared recipe on TrafficLight and ConnectionContext
fun fireDueTimeouts(): List<Event> {
    val fired = mutableListOf<Event>()
    for (due in timers.pollDue()) {
        val event = mapper(due.eventKey) ?: error("unknown timer eventKey=${due.eventKey}")
        handle(event)
        fired += event
    }
    return fired
}

// Test / DemoMain
clock.advance(DWELL_GREEN_MS)
fireDueTimeouts()   // or pollDue + handle manually
```

Never use `Thread.sleep` to wait for timeouts in tests.

---

## Must / must not (timers & ε)

| Must | Must not |
|------|----------|
| Declare Arm/Cancel in domain only | Clocks / `TimerPort` / `Thread.sleep` in pure step |
| Inject only `clock`; own `TimerPort(clock)` | Separate ctor-injected `TimerPort` (desync) |
| Inline timer apply in domain list order | Batch timers after all domain (breaks Cancel-before-CloseSession teaching) |
| One domain list → N ports | `StepResult.timers` or `CrossCuttingEffect.Timer` |
| Cancel on Emergency / Online exit | Dangling armed timers after abort |
| `automaticStep` pure + export via `productEvaluateAutomatic` | Run `drainAutomatic` or clocks in product export |
| Throw on bound / cycle | Soft refuse-on-bound as the sample default |

---

## Out of scope (this sample)

Full SCXML RTC interpreter, shallow/deep history, deferred-event queues, orthogonal `done.state.*` / all-regions-done, hierarchy automatic drain on Connection, Heartbeat re-arm of Online watchdog.

---

## See also

- [README](../README.md)  
- [glossary.md](glossary.md)  
- [origins-and-sources.md](origins-and-sources.md) — SCXML/UML motivation vs this sample’s encoding  
- [authoring-guide.md](authoring-guide.md)  
- Code: `flat/TrafficLight.kt`, `gof/ConnectionMachine.kt`, `machine/TimerPort.kt`, `machine/Automatic.kt`  
