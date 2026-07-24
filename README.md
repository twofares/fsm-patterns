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
| `src/main/kotlin/fsm/sample/flat/` | Sealed states/events, pure `State+Event→State`, exhaustive `when` |
| `src/main/kotlin/fsm/sample/gof/` | GoF State, hierarchical parent delegation, entry/exit |
| `src/main/kotlin/fsm/sample/orthogonal/` | Orthogonal Component composition + logging |
| `src/main/kotlin/fsm/sample/csp/` | Explicit alphabet, menus, refusal of non-menu events |
| `src/main/kotlin/fsm/sample/logging/` | Consistent transition/entry/exit/refusal log |
| `src/main/kotlin/fsm/sample/export/` | Stdlib product evaluation → Markdown tables + Mermaid |
| `src/main/kotlin/fsm/sample/DemoMain.kt` | Fixed demo script exercising all patterns |
| `src/main/kotlin/fsm/sample/ExportMain.kt` | Launch path for generated tables/diagrams |

Every non-trivial type and path is documented with **PURPOSE** comments in source.
