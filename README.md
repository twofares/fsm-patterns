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
| `src/main/kotlin/fsm/sample/flat/` | Sealed states/events, pure `StepResult<S, LightEffect>`, dual-sink handle |
| `src/main/kotlin/fsm/sample/gof/` | GoF State, hierarchy, domain `ConnectionEffect` + plain audit lists |
| `src/main/kotlin/fsm/sample/orthogonal/` | Orthogonal regions: `LatchEffect` / `SwingEffect` (actuators only) |
| `src/main/kotlin/fsm/sample/csp/` | CSP alphabet/menus, `FileEffect` domain I/O; refuse = audit only |
| `src/main/kotlin/fsm/sample/effect/` | `StepResult<S, D>` (audit + domain lists), **cross-cutting** `AuditEffect` |
| `src/main/kotlin/fsm/sample/logging/` | Consistent transition/entry/exit/refusal log |
| `src/main/kotlin/fsm/sample/export/` | Stdlib product evaluation → Markdown tables + Mermaid |
| `src/main/kotlin/fsm/sample/DemoMain.kt` | Fixed demo script exercising all patterns |
| `src/main/kotlin/fsm/sample/ExportMain.kt` | Launch path for generated tables/diagrams |

## Domain-specific vs cross-cutting effects

There is **no global kitchen-sink `Effect` ADT** and **no nested `*.Audit` wrappers**.
Each behavior owns a sealed **domain** type; pure steps return split lists:

```kotlin
data class StepResult<S, D>(
  val next: S,
  val audit: List<AuditEffect> = emptyList(),
  val domain: List<D> = emptyList(),
)
```

**Two sinks at the boundary:**

| Sink | Kind | Role |
|------|------|------|
| `TransitionLog` | **Cross-cutting** | Shared audit shape (`AuditEffect` → same TRANSITION/ENTRY/EXIT/REFUSAL records) |
| Machine **ports** | **Domain-specific** | In-memory actuators / I/O intents / session lifecycle (no real OS I/O) |

| Machine | Cross-cutting (audit list) | Domain (ports) |
|---------|---------------------------|----------------|
| TrafficLight | `LogTransition` → log | `DriveLamp(color)` → `LightPort` |
| FileProtocol | accept/refuse audit | `OpenHandle` / `ReadChunk` / `CloseHandle` → `FilePort` (**refuse = audit only**) |
| Connection | entry/exit/transition/refusal | `OpenSession` / `CloseSession` / `IncHeartbeat` → **`ConnectionPort` only** |
| Door regions | latch/swing transition audit | `EnergizeStrike` / `EngageDeadbolt` / `SoundChime` → `DoorPort` |
| Door container | guard **refusal audit only** (`List<AuditEffect>`) | *(none — refused Open does not sound chime)* |

Type system separation: `List<LightEffect>` cannot hold `ConnectionEffect.IncHeartbeat`.
Export product evaluation reads pure effect **labels** only (no interpreters, no port
mutation). Demo (`gradle run`) prints audit log **and** domain port snapshots side by side.

**Domain policy (total / engagement-driven):** domain actuators are declared for every
accepted engagement, including self-loops (e.g. Emergency on Red, Read while Opened,
Lock while Locked). Domain tracks the engagement, not only identity changes of state.

**Pure vs boundary:** pure steps never call interpreters. Flat/CSP/region boundaries
apply `AuditInterpreter.apply(audit, log)` then `applyDomain*(domain, port)`.

**Hierarchical Connection ordering:** `planTransition` builds an ordered list of
fragments (exit / transition / handler / enter). Each fragment is still dual-sink
(audit list + domain list). Runtime apply and the Effects column walk fragments in
LCA order and, within each fragment, audit then domain — so Online enter remains
`LogEntry(Online), OpenSession` before `LogEntry(Idle)` (not a global “all audit then
all domain” batch).

## Export tables and effects

`gradle runExport` product-evaluates each sample machine over its finite
(state × event) domain using **shipped pure** APIs only:

| Machine | Pure API used for rows |
|---------|------------------------|
| TrafficLight | `step(s, e)` → next + audit/domain |
| FileProtocol | `stepOffer(s, e)` → accept/refuse + audit/domain |
| Latch / Swing | `step(s, e)` → next + audit/domain |
| Connection | pure `dispatchWithDelegation` + `planTransition` / `declaredEffects` |

Markdown tables include an **Effects** column (comma-joined short names such as
`LogTransition`, `LogRefusal`, `IncHeartbeat`, `LogEntry(Idle)`). For Connection,
labels follow fragment LCA order (see hierarchical ordering above). Export never
calls `handle` / `offer` / apply sinks — so logs and ports are not mutated while
generating documentation. Mermaid diagrams remain **event-labeled** edges only;
declared effects appear only in the Markdown tables.

Regenerate the checked-in sample artifact with quiet Gradle so task banners and
`BUILD SUCCESSFUL` do not pollute the file (non-quiet `runExport` writes those to stdout):

```bash
gradle -q runExport > export.md
```

Every non-trivial type and path is documented with **PURPOSE** comments in source.
