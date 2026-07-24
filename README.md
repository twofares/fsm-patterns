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
| `src/main/kotlin/fsm/sample/flat/` | Sealed states/events, pure `State+Event→State` / `StepResult`, exhaustive `when` |
| `src/main/kotlin/fsm/sample/gof/` | GoF State, hierarchical parent delegation, entry/exit/heartbeat as effects |
| `src/main/kotlin/fsm/sample/orthogonal/` | Orthogonal Component composition + effect-declared logging |
| `src/main/kotlin/fsm/sample/csp/` | Explicit alphabet, menus, refusal of non-menu events |
| `src/main/kotlin/fsm/sample/effect/` | Sealed `Effect` + pure `StepResult` + thin `EffectInterpreter` boundary |
| `src/main/kotlin/fsm/sample/logging/` | Consistent transition/entry/exit/refusal log |
| `src/main/kotlin/fsm/sample/export/` | Stdlib product evaluation → Markdown tables (next-state + declared effects) + Mermaid |
| `src/main/kotlin/fsm/sample/DemoMain.kt` | Fixed demo script exercising all patterns |
| `src/main/kotlin/fsm/sample/ExportMain.kt` | Launch path for generated tables/diagrams |

## Export tables and effects

`gradle runExport` product-evaluates each sample machine over its finite
(state × event) domain using **shipped pure** APIs only:

| Machine | Pure API used for rows |
|---------|------------------------|
| TrafficLight | `step(s, e)` → next + effects |
| FileProtocol | `stepOffer(s, e)` → accept/refuse + effects |
| Latch / Swing | `step(s, e)` → next + effects |
| Connection | probe `onEvent` + `planTransition` (or same-leaf handler effects) |

Markdown tables include an **Effects** column (comma-joined short names such as
`LogTransition`, `LogRefusal`, `IncHeartbeat`, `LogEntry(Idle)`). Export never
calls `handle` / `offer` / `EffectInterpreter` — so logs and counters are not
mutated while generating documentation. Mermaid diagrams remain **event-labeled**
edges only; declared effects appear only in the Markdown tables.

Regenerate the checked-in sample artifact with quiet Gradle so task banners and
`BUILD SUCCESSFUL` do not pollute the file (non-quiet `runExport` writes those to stdout):

```bash
gradle -q runExport > export.md
```

Every non-trivial type and path is documented with **PURPOSE** comments in source.
