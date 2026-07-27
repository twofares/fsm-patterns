# Glossary — dual-sink FSM sample

Terms as used in this repository. Overview: [README](../README.md).  
How-to: [authoring-guide.md](authoring-guide.md), [timers-and-automatic.md](timers-and-automatic.md).

For a teaching comparison of **total vs partial** machines, see
[README — Total vs partial](../README.md#total-vs-partial-the-main-fork).

Entries are alphabetical. **API names** are Kotlin identifiers under `src/`.  
Everyday and academic words used in the teaching docs are defined first under
[Background terms](#background-terms).

---

## Background terms

These are ordinary English or CS vocabulary as this sample uses them—not
Kotlin APIs.

**ADT (algebraic data type)**  
A type made of a fixed set of cases you can switch on exhaustively. In this
sample that almost always means a Kotlin **`sealed class` / `sealed interface`**
(or a small data class). Example: `LightEffect` is a sealed ADT with cases like
`DriveLamp` and `Timer(...)`. Saying “domain ADT” means “the machine’s own sealed
list of I/O intents,” not a library type.

**alphabet**  
The fixed set of events the machine knows about (and, loosely, the set of
states). “Sealed alphabet” means those sets are closed in the type system: you
cannot invent a new event without adding a sealed case. A **total** machine
handles every event in that set in every state.

**CSP (Communicating Sequential Processes)**  
A classical concurrency / process model (Hoare and others). This sample only
borrows one idea: a process may **offer** some events and **refuse** others,
rather than treating every input as always legal. It is **not** a CSP runtime
or channel library.

**engagement**  
Whether the machine **takes** an event (accept) or **declines** it (refuse /
unhandled). Total machines always engage; partial machines may refuse.

**FSM (finite-state machine)**  
A program that is always in one of a finite set of **states**, and changes
state when it sees an **event** (input). This repo implements FSMs as ordinary
Kotlin functions—no third-party “state machine engine.”

**GoF (Gang of Four)**  
Shorthand for the classic *Design Patterns* book. The Connection sample is
inspired by hierarchical TCP-connection-style state machines often shown in
that tradition—not a claim that the code is a textbook reprint.

**guard**  
A check that says “this event is illegal in the current situation,” so the
machine refuses instead of changing domain state. Example: DoorSystem refuses
opening the door while the latch is locked.

**I/O**  
Side effects outside pure calculation: driving a lamp, opening a file handle,
opening a network session, scheduling a timer. In this sample those intents are
listed in the **domain** sink and performed only at the **boundary**.

**menu**  
Informal name for “which operations are available **right now**.” Like a UI
menu that greys out illegal actions: when Closed, `Read` is not on the menu;
when Opened, a second `Open` is not on the menu. FileProtocol encodes that idea
as accept vs refuse.

**offer**  
To present an event as something the machine is willing to handle. In the
partial / CSP sample, the pure step is an **offer**: it returns Accepted or
Refused. “Not offered” means refuse—not a silent no-op with domain work.

**protocol**  
Here: a small **conversation ruleset** between caller and machine (what you may
do in which state)—not HTTP, TLS, or a wire format. FileProtocol is a toy file
session: open → read/close while open → refuse nonsense. Connection is a toy
session hierarchy with connect / transfer / disconnect.

**sealed (Kotlin)**  
A class hierarchy the compiler knows completely: all subtypes are listed in the
same module. Exhaustive `when` catches missing cases. Used for states, events,
and domain intents so the product tables and steps stay closed.

**self-loop**  
A transition that “handles” an event but stays in the same state (or returns
the same successor). Still an **accept**—useful for re-arming timers or
re-recording metrics without changing the mode.

**sink**  
Where an effect list is delivered. **Dual-sink** means two destinations:
cross-cutting (observability) and domain (machine I/O). A pure step only
**declares** lists; the boundary **pours** them into the sinks.

**statechart**  
Harel-style extension of FSMs: hierarchy (parent/child states), orthogonal
(concurrent) regions, and related ideas. This sample encodes a few of those
disciplines in code; it is not a full SCXML interpreter.

---

## A–C

**applyAccepted**  
Boundary helper for total machines: write the next state, run cross-cutting
effects, then run domain effects. Used by TrafficLight and orthogonal regions.
Do not use for hierarchy (wrong order; loses per-fragment pairing).

**applyFragments**  
Boundary helper for hierarchical machines: for each planned fragment, run
cross-cutting then domain, **then** write the leaf state if it changed. Used by
Connection.

**applyOffer**  
Boundary helper for refuse-capable machines: like accept when the outcome is
`Accepted`; on `Refused`, run cross-cutting only (no domain I/O).

**applyRefusal**  
Boundary helper that runs cross-cutting only—no domain, no state assign. Used
for orthogonal **container** guards (e.g. “cannot open while locked”).

**Arm / Cancel**  
Cases of [`TimerIntent`](#timerintent). The pure step **declares** them inside
the domain list; [`TimerPort`](#timerport) **schedules** them. Cancel before
CloseSession (and similar) is intentional under inline multi-port apply.

**assign**  
Writing the machine’s current state field after (or around) effect application.
Flat/CSP usually assign **before** applying effects; hierarchy applies
fragments **first**, then assigns the leaf.

**automatic transition (ε)**  
A step that runs **without** a new external event—completion of an intermediate
state. Pure code: `automaticStep(state)` returns a `StepResult` or `null` (no
edge). After handling an external event, [`drainAutomatic`](#drainautomatic)
runs those microsteps. Export labels the event column `ε`. Not a third effect
list on the outcome types.

**AutomaticBoundExceededException**  
Thrown if automatic draining hits a step limit or detects a cycle. The sample
fails loudly; it does not soft-refuse with a “truncated” flag.

**boundary**  
The impure shell around a pure step: apply effects, talk to ports/clocks/logs,
update stored state. Pure steps must not call interpreters, ports, or clocks.

**bundle (cross-cutting)**  
Small helpers that build the usual list of audit / metric / trace effects for
an accept or refuse path (e.g. `acceptTransitionCrossCutting`,
`refuseCrossCutting`), including machine-specific extras.

**Clock / VirtualClock**  
Source of “now” for timers. Tests and demos **advance** a virtual clock instead
of sleeping. Pure steps never read clocks. Inject only the clock; construct
`TimerPort(clock)` on the boundary object so the port and clock cannot desync.

**connectionStep**  
Connection’s pure hierarchical function: dispatch the event, attach planned
effects, return a [`HierarchyStep`](#hierarchystep). Used by both live handle
and export (export must not construct `ConnectionContext`, which runs entry
effects on init).

**cross-cutting (CC)**  
Effects shared across machines for **observability**: audit log lines, metrics
increments, trace spans. Implemented as `CrossCuttingEffect` applied into
`CrossCuttingEnv`. Not domain I/O and not timer scheduling.

**CSP / partial / offer**  
Machine kind where some events may be **refused** (see [menu](#menu),
[protocol](#protocol), [offer](#offer)). Pure function returns
[`OfferOutcome`](#offeroutcome). Sample: FileProtocol.  
Contrast with [total](#flat-total): refuse is a real branch of the type;
`Refused` has no domain list so “refuse but still open a file” cannot be
written by accident.

---

## D–F

**domain**  
This machine’s private list of **what to do to the world** if the step is
accepted: drive a lamp, open/read/close a file, open a session, arm a timer,
etc. Represented as a machine-local [ADT](#adt-algebraic-data-type). Applied
only through a vignette [port](#port) / timer port. Must not embed
cross-cutting cases inside domain types.

**drainAutomatic**  
Pure loop: from a start state, keep calling `automaticStep` until it returns
null, with a max step count and cycle detection. Returns pure microsteps; the
caller applies each with the normal boundary helper.

**dual-sink**  
Each pure outcome carries (or may carry) **two** separate lists:

1. **cross-cutting** — observability  
2. **domain** — machine I/O / scheduling  

Timers and automatic edges still use those two lists. There is no third
`timers` field on `StepResult`.

**dwell**  
TrafficLight policy: when entering Red/Green/Yellow, cancel any previous dwell
timer, arm a new one, and drive the lamp. When the timer fires, the light
advances. Flash cancels dwell and does not re-arm.

**ε (epsilon)**  
Teaching / export name for an eventless automatic transition. See
[automatic transition](#automatic-transition-ε).

**eventKey**  
String stored when arming a timer. Must equal the sealed timeout event’s
`label()` so the boundary can map the string back to a real event with a plain
`when` (no reflection). Example: `LightEvent.DwellElapsed.label()`.

**export / product evaluation**  
Building tables (and Mermaid diagrams) by calling the **pure** step for many
state × event combinations—without running ports or clocks. Helpers:
`productEvaluateTotal`, `productEvaluateOffer`, `productEvaluateHierarchy`,
`productEvaluateAutomatic`. Machines are registered by hand in
`exportAllDocument()`.

**exportLabel / label**  
Stable human-readable string for tables and logs. States and events implement
`label()`; domain effects and timer intents implement `exportLabel()`.

**EffectFragment / FragmentedStepEffects**  
In hierarchy, one transition is a **sequence of packages** (exit parent, exit
leaf, enter parent, enter leaf, handler…). Each fragment holds its own
cross-cutting + domain pair so enter Online can open a session in the same
package as its audit line.

**fireDueTimeouts**  
Demo/test recipe: ask the timer port what is due, map each `eventKey` to an
event, call `handle`. Not used inside export.

**flat (total)**  
A single-level machine where **every** sealed event is accepted in every state
(possibly as a [self-loop](#self-loop)). Pure type: [`StepResult`](#stepresult).
Sample: TrafficLight.  
Contrast with [partial](#csp--partial--offer).

---

## G–O

**HandleResult**  
Connection-only intermediate pure result from leaf dispatch—before effects are
fully assembled. The shared product type for hierarchy is
[`HierarchyStep`](#hierarchystep), not `HandleResult`.

**hierarchical**  
States form a **tree** (parent/child). Leaving a leaf may exit parents; entering
may enter parents. Planning uses [LCA](#lca-lowest-common-ancestor). Sample:
Connection. Boundary: [`applyFragments`](#applyfragments), not
`applyAccepted`.

**HierarchyStep**  
Pure hierarchy outcome: `Handled(next, effects)` or `Unhandled(effects)`.
Peer of `StepResult` and `OfferOutcome`.

**LCA (lowest common ancestor)**  
In the state tree, the deepest state that is an ancestor of both the current
leaf and the target leaf. Exit everything up to (but not including) that
ancestor, then enter down the other branch. Same-parent moves skip parent
exit/enter (so an Online watchdog can stay armed while Idle ↔ Transferring).

**microstep**  
One automatic pure step (one hop of [`drainAutomatic`](#drainautomatic)).
Applied like a normal step after the external event has already been applied.

**OfferOutcome**  
Pure result for partial machines:

- `Accepted(next, crossCutting, domain)` — take the event; domain I/O allowed  
- `Refused(reason, next, crossCutting)` — decline; **no domain field**

**orthogonal**  
Two (or more) machines that run **together** as regions of one system (like two
independent state machines side by side). Sample: DoorSystem = Latch ∥ Swing.
A **container** may still refuse combinations that are illegal as a whole
([guard](#guard)).

---

## P–S

**planTransition**  
Pure hierarchy planner: compute paths, LCA, and the ordered exit/enter
fragments for a leaf change.

**pollDue**  
On `TimerPort`: return timers whose deadline has passed, remove them from the
pending set, **do not** call `handle` yourself.

**port**  
Boundary object that **performs** domain intents (records ops, increments
heartbeats, etc.). Prefer subclassing [`RecordingPort`](#recordingport).
When one step lists both “drive lamp” and “arm timer,” the apply lambda fans
out to multiple ports.

**pure step**  
A function of state (and usually event) that returns dual-sink outcomes **only**.
No logs, no ports, no clocks, no `Thread.sleep`. Easy to test and to feed into
export tables.

**RecordingPort**  
Shared helper base class: a list bag (`record` / `items`) for demo ports.
Subclass it; do not wrap a private list that only re-exports the same API.

**refuse**  
Decline an event: may still emit audit/metric/trace, but must **not** perform
domain I/O. Types: `OfferOutcome.Refused`, hierarchy `Unhandled`, or
`applyRefusal` for container guards.

**StepResult**  
Pure result for total machines: successor state + cross-cutting list + domain
list. Always “accepted” by type—there is no refuse case.

---

## T–Z

**TimerIntent**  
Pure scheduling request: `Arm(id, delayMs, eventKey)` or `Cancel(id)`. Stored
in the **domain** list (with other machine I/O), not under cross-cutting. The
port turns Arm into a future event injection.

**TimerPort**  
Boundary schedule: owns pending timers, uses an injected clock, applies
Arm/Cancel, later yields due work via `pollDue`.

**vignette**  
One teaching example machine and its package (`flat/`, `csp/`, `gof/`,
`orthogonal/`) with its own states, events, domain types, and ports. Shared
machinery lives in `effect/`, `machine/`, and `export/`.

**watchdog**  
A timer that means “if nothing healthy happens in time, fail.” Connection arms
one when entering Online and cancels it when leaving Online. Heartbeats do not
reset that deadline in this sample (absolute deadline from Online enter).

---

## Outcome shapes (quick map)

| Kind | Pure type | Boundary | Engagement in plain language |
|------|-----------|----------|------------------------------|
| Total flat | `StepResult` | `applyAccepted` | Every known event is always handled |
| Partial / CSP | `OfferOutcome` | `applyOffer` | Event may be on the menu or refused |
| Hierarchical | `HierarchyStep` | `applyFragments` then assign | Handled or unhandled, with parent exit/enter |
| Orthogonal region | `StepResult` | `applyAccepted` | Usually total inside each region |
| Orthogonal guard | refuse CC list | `applyRefusal` | Whole system says “no” without domain I/O |
| Timers / ε | same as host kind | multi-port / drain after external apply | When steps run, not a separate accept/refuse kind |

Full prose comparison: [README — Total vs partial](../README.md#total-vs-partial-the-main-fork).

---

## See also

- [README](../README.md) — kinds, dual-sink rules, package map  
- [authoring-guide.md](authoring-guide.md) — checklist and recipes  
- [timers-and-automatic.md](timers-and-automatic.md) — dwell, watchdog, Flash ε  
- [machine-examples-visual.html](machine-examples-visual.html) — structural diagrams  
- [export.md](../export.md) — generated product tables  
