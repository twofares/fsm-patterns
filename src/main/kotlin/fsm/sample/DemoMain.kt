package fsm.sample

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.FileEffect
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.TrafficLight
import fsm.sample.flat.step
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnectionContext
import fsm.sample.logging.TransitionLog
import fsm.sample.orthogonal.DoorEvent
import fsm.sample.orthogonal.DoorSystem

/**
 * PURPOSE: Runnable demo entry that exercises multiple research disciplines
 * in ordinary Kotlin without any FSM/statechart library or engine.
 *
 * Patterns shown in one fixed script (same inputs every run for consistency):
 * 1. Flat sealed FSM + pure steps + LightEffect audit boundary (TrafficLight)
 * 2. GoF State + hierarchical delegation + ConnectionEffect (audit + IncHeartbeat)
 * 3. Orthogonal Component composition (DoorSystem / Latch / Swing effects)
 * 4. CSP-style alphabet + menu + FileEffect refusal/accept (FileProtocol)
 *
 * **Domain-specific vs cross-cutting:** Connection alone declares IncHeartbeat;
 * all machines share audit → TransitionLog via per-machine interpreters.
 *
 * Output is deterministic so two launches can be compared for consistency.
 */
fun main() {
    val out = StringBuilder()
    fun line(s: String = "") {
        out.appendLine(s)
        println(s)
    }

    line("=== FSM / Statechart / CSP Patterns Sample (no libraries/engines) ===")
    line()

    // --- 1. Flat pure FSM + light-local effects ------------------------------
    line("--- Pattern: Flat sealed FSM (pure step → LightEffect, boundary runs them) ---")
    val pureStep = step(LightState.Red, LightEvent.Tick)
    line("pure step effects (no I/O): ${pureStep.effects}")
    val flatLog = TransitionLog()
    val light = TrafficLight(log = flatLog)
    line("initial=${light.current.label()}")
    light.handle(LightEvent.Tick)
    light.handle(LightEvent.Tick)
    light.handle(LightEvent.Emergency)
    line("final=${light.current.label()}")
    line(flatLog.formatAll())
    line()

    // --- 2. GoF + hierarchical + domain heartbeat + audit --------------------
    line("--- Pattern: GoF State + hierarchical + ConnectionEffect (audit + IncHeartbeat) ---")
    val connLog = TransitionLog()
    val conn = ConnectionContext(log = connLog)
    line("initial=${conn.current.name}")
    conn.handle(ConnEvent.Connect)          // Offline → Idle (enter Online, Idle)
    conn.handle(ConnEvent.Heartbeat)        // IncHeartbeat + LogTransition via boundary
    conn.handle(ConnEvent.StartTransfer)    // Idle → Transferring
    conn.handle(ConnEvent.FinishTransfer)   // Transferring → Idle
    conn.handle(ConnEvent.Disconnect)       // Idle → Offline (via Online)
    // Illegal at Offline: refuse
    conn.handle(ConnEvent.StartTransfer)
    line("final=${conn.current.name} heartbeats=${conn.heartbeatCount}")
    line(connLog.formatAll())
    line()

    // --- 3. Orthogonal components --------------------------------------------
    line("--- Pattern: Orthogonal Component (Latch ∥ Swing) ---")
    val door = DoorSystem()
    line("initial=${door.configuration()}")
    door.handle(DoorEvent.Open)    // refuse: locked
    door.handle(DoorEvent.Unlock)
    door.handle(DoorEvent.Open)
    door.handle(DoorEvent.Lock)    // refuse: still open
    door.handle(DoorEvent.Close)
    door.handle(DoorEvent.Lock)
    line("final=${door.configuration()}")
    line(door.log.formatAll())
    line()

    // --- 4. CSP-style process + FileEffect -----------------------------------
    line("--- Pattern: CSP process (alphabet, menu, FileEffect accept/refuse) ---")
    val cspLog = TransitionLog()
    val file = FileProtocol(log = cspLog)
    val refusedStep = file.stepOffer(FileState.Closed, FileEvent.Read)
    line("pure refuse step: $refusedStep")
    line(
        "pure refuse step effects: ${refusedStep.effects.filterIsInstance<FileEffect.Audit>()}",
    )
    line("initial=${file.current.label()} menu=${file.menu().map { it.label() }}")
    file.offer(FileEvent.Read)   // refuse: not in menu while Closed
    file.offer(FileEvent.Open)
    file.offer(FileEvent.Read)
    file.offer(FileEvent.Close)
    line("final=${file.current.label()} menu=${file.menu().map { it.label() }}")
    line(cspLog.formatAll())
    line()

    line("=== Demo complete: flat, hierarchical/GoF, orthogonal, CSP (per-behavior effects) ===")
}
