# Origins and sources

How this repository came about, what it is for, and where its **ideas**
come from. This is provenance and attribution for readers—not an as-built
authoring guide. For how the code works today, start at the
[README](../README.md).

---

## What this project is

A **didactic Kotlin sample** under `~/TwoFares/Workspaces/fsm` that shows several
classical state-machine disciplines as **ordinary application code**:

- no third-party FSM, statechart, or SCXML library
- runtime dependencies limited to the Kotlin stdlib (plus JUnit for tests)
- pure step functions that **declare** outcomes; boundaries that **apply** them
- product tables and Mermaid diagrams generated from pure APIs only

It is a **teaching workspace**, not a production framework and not a research
reference implementation of any one formal model.

---

## How the repo came about

| | |
|--|--|
| **Started** | 2026-07-24 (`Initial snapshot`) |
| **Author (git)** | Brian Stiles (`bstiles@bstiles.net`) |
| **Location** | `~/TwoFares/Workspaces/fsm` (moved from `~/Scratch/fsm` after the dual-sink / docs arc) |
| **Language / build** | Kotlin 2.1.x, Gradle, JDK 17–21 (workspace often pins Corretto 21) |

### Intent from day one

The first README and package layout already stated the goal clearly:

> Encode classical FSM, Harel-style hierarchical and orthogonal disciplines,
> and CSP-style process refusal **as ordinary code** — no third-party engine.

Four vignettes shipped in the initial snapshot:

| Package | Vignette | Discipline taught |
|---------|----------|-------------------|
| `flat/` | TrafficLight | Total flat FSM, sealed states/events, exhaustive `when` |
| `csp/` | FileProtocol | Explicit alphabet, per-state menu, refuse non-menu events |
| `gof/` | Connection | GoF State + hierarchical parent delegation + enter/exit |
| `orthogonal/` | DoorSystem | Concurrent regions (Latch ∥ Swing) + container policy |

Each non-trivial type carried a **PURPOSE** KDoc so the teaching intent stayed
in the source, not only in prose.

### How it grew (high level)

Evolution is in git; this is the story, not a commit list:

1. **Core vignettes + shared audit log** — initial snapshot.
2. **Export** — product evaluation of pure steps → Markdown tables + Mermaid
   (`export/`, committed `export.md`).
3. **Effects model** — from a single effect idea to **dual-sink** outcomes:
   shared cross-cutting (audit / metric / trace) vs machine-local domain I/O.
4. **Shared boundary helpers** — `applyAccepted`, `applyOffer`, `applyFragments`,
   refuse-only apply, CC bundles, hierarchy planners, recording ports—so new
   machines copy a recipe instead of reinventing assign order.
5. **Timers and automatic (ε)** — pure `TimerIntent` + boundary `TimerPort` /
   `VirtualClock`; pure `automaticStep` + `drainAutomatic` (TrafficLight Flash →
   Red; Connection Online watchdog). Design first, then implementation, then
   as-built how-tos.
6. **Documentation refresh** — replace process-heavy design notes with a
   teaching README, authoring guide, timers how-to, glossary, and this origins
   doc; keep optional visual HTML.

Development has used ordinary git commits plus iterative design/implement/review
loops (including agent-assisted work). The **as-built** guides are the contract;
old design files that only tracked that process were removed once the how-tos
caught up.

---

## Sources of the *concepts*

None of the following are dependencies. They are the **intellectual background**
the vignettes deliberately echo. The sample implements *disciplines*, not full
tools or checkers from those traditions.

### Classical finite-state machines

**Idea:** a system has a finite set of modes (states); inputs (events) drive
transitions; behavior is “which mode am I in?”

**In this repo:** `flat/TrafficLight` — sealed states and events, pure
`step(state, event) → …`, total engagement (every sealed event handled).

**Background reading (representative):** any standard automata / discrete
systems text on deterministic finite automata and Mealy/Moore machines; UML
simple state machines in the OMG UML Superstructure (behavioral state machines
chapter).

### Harel statecharts (hierarchy and orthogonality)

**Idea (David Harel):** extend FSMs with **depth** (superstates / substates),
**orthogonality** (concurrent regions), and related constructs so large mode
models stay manageable.

**In this repo:**

- Hierarchy: `gof/Connection` — Offline vs Online, with Online containing Idle
  and Transferring; unhandled events bubble; enter/exit planned via LCA-style
  path comparison (`planTransition`, fragments).
- Orthogonality: `orthogonal/DoorSystem` — Latch and Swing as concurrent
  regions; container guards refuse illegal combinations.

**Not in this repo:** a full statechart interpreter, history connectors,
SCXML semantics, or industrial tools (Rhapsody, etc.). Timers and ε edges later
borrow *names and motivations* from that world (`after`, completion) without
claiming SCXML fidelity.

**Primary concept source:**

- David Harel, “Statecharts: A Visual Formalism for Complex Systems,” *Science
  of Computer Programming*, 1987 (and later Harel/Gery industrial expositions).

**Related industrial / standards lineage (ideas only):**

- UML state machines (OMG) — hierarchical and concurrent regions in modeling
  practice.
- W3C **SCXML** — delayed send, cancel, and eventless/completion transitions
  as *feature checklist* when timers/ε were added; not an SCXML engine here.

### CSP — offer and refusal

**Idea (C. A. R. Hoare and successors):** processes interact through events;
at each moment a process has an **acceptance set** (what it offers). Events
outside that set are **refused**, not silently treated as total transitions.

**In this repo:** `csp/FileProtocol` — explicit alphabet; Closed vs Opened
menus; pure `OfferOutcome.Accepted | Refused`; refuse paths cannot declare
domain I/O. Initial KDoc names Hoare and the portable encoding (alphabet +
menu + refusal + pure accept step).

**Not in this repo:** FDR or any CSP model checker, parallel composition
operators, or true multi-process synchronization. The sample is sequential
Kotlin that **teaches the refusal discipline**.

**Primary concept source:**

- C. A. R. Hoare, *Communicating Sequential Processes* (Prentice Hall /
  later free editions). Companion literature on failures/refusals models
  (Brookes, Hoare, Roscoe, and textbooks such as Roscoe’s CSP work) for the
  formal “refusal” notion.

### GoF State pattern

**Idea (*Design Patterns*, Gamma et al.):** encapsulate state-specific behavior
in objects; the context holds a current state and transitions replace it.

**In this repo:** `gof/ConnectionMachine` — state objects with `onEvent`,
context replaces the current leaf; combined with hierarchical parent links so
the vignette also teaches superstate delegation (beyond a flat GoF State demo).

**Primary concept source:**

- Erich Gamma, Richard Helm, Ralph Johnson, John Vlissides, *Design Patterns:
  Elements of Reusable Object-Oriented Software* (Addison-Wesley, 1994) —
  **State** pattern.

### Dual-sink effects, pure declare / boundary apply

**Idea:** separate “what the transition means for the business” from “what we
log/metric/trace,” and keep step logic free of I/O so tables and tests stay
pure. This is **sample-local architecture**, not a citation of one paper.

**Lineage inside the repo:** effects snapshots → cross-cutting families →
shared `apply*` helpers → multi-port domain apply (timers share the domain
list). Motivation in early design notes: make the correct pattern easy for
**humans and coding agents** to copy without inventing a mini engine.

### Timers and automatic (ε) transitions

**Idea:** delayed events and eventless completion are ubiquitous in SCXML,
UML `after(t)`, and industrial statechart tools. This sample encodes them as
domain `TimerIntent` + `TimerPort`, and pure `automaticStep` + `drainAutomatic`,
still dual-sink.

**Concept sources:** SCXML delayed `<send>` / cancel; UML time events;
statechart completion / eventless transitions. **Implementation** is original
to this repo’s dual-sink model (see [timers-and-automatic.md](timers-and-automatic.md)).

---

## Sources of the *code* (and vignette stories)

| Question | Answer |
|----------|--------|
| Fork of an open-source FSM library? | **No.** Gradle intentionally excludes FSM/statechart engines. |
| Generated from SCXML/UML models? | **No.** Hand-written Kotlin vignettes and shared helpers. |
| Port of a specific public sample repo? | **No known upstream.** Structures are **common teaching shapes**, rewritten here. |
| Who owns the code? | Authored in this workspace (git history; began under Scratch). Treat as the project’s own sample unless a later LICENSE says otherwise. |

### Why these vignette stories?

They are **stock teaching domains**, not product code:

| Vignette | Why it’s familiar |
|----------|-------------------|
| **Traffic light** | Classic total FSM demo (modes + timed advance + emergency). |
| **File open/read/close** | Tiny session **protocol** with a clear **menu** (legal ops per state)—ideal for CSP-style refuse. |
| **Network connection** | Hierarchical session (offline / online idle / transferring)—a common hierarchy + heartbeat/watchdog story; package name `gof/` marks the State-pattern angle. |
| **Door latch ∥ swing** | Two independent concerns that must both be right—classic orthogonal “and” of modes, plus a policy guard. |

Domain ops (drive lamp, open handle, open session, actuators) are **recording
ports** for demos and tests, not real hardware or OS bindings.

### Shared kernel vs vignettes

| Layer | Role | Origin |
|-------|------|--------|
| `flat/`, `csp/`, `gof/`, `orthogonal/` | Story + machine-local types | Teaching vignettes written for this sample |
| `effect/`, `machine/`, `export/`, `logging/` | Shared outcomes, apply, timers, tables | Extracted/refined **in this repo** as patterns repeated across vignettes |
| `docs/` | Teaching + as-built how-tos | Written to match the code after the dual-sink and timer work stabilized |

---

## What this document is *not*

- A full academic survey of FSMs, CSP, or statecharts  
- A claim of formal equivalence to Harel, SCXML, or CSP semantics  
- A changelog (use `git log`)  
- An authoring checklist (use [authoring-guide.md](authoring-guide.md))

If a concept in the code disagrees with a classical definition, **the as-built
Kotlin and current guides win** for this sample; the classical sources explain
*why the vignette exists*, not a compliance certificate.

---

## See also

- [README](../README.md) — how the sample works now  
- [glossary.md](glossary.md) — terms (including FSM, CSP, GoF, statechart)  
- [authoring-guide.md](authoring-guide.md) — extend a machine  
- [timers-and-automatic.md](timers-and-automatic.md) — delayed and ε edges  
- Source PURPOSE KDocs on each vignette package and shared surface  
