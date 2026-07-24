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
| `src/main/kotlin/fsm/sample/effect/` | **Generalized** `CrossCuttingEffect` + env + interpreter; `StepResult` |
| `src/main/kotlin/fsm/sample/logging/` | Transition audit log (one cross-cutting sink) |
| `src/main/kotlin/fsm/sample/export/` | Product evaluation → Markdown + Mermaid |
| `DemoMain.kt` / `ExportMain.kt` | Dual-surface demos |

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

## Export tables

```bash
gradle -q runExport > export.md
```

Markdown tables include an **Effects** column (comma-joined labels such as
`Audit.LogTransition`, `Metric.Inc(fsm.transitions)`, `Trace.Span(...)`, `DriveLamp(Green)`).

Every non-trivial type and path is documented with **PURPOSE** comments in source.
