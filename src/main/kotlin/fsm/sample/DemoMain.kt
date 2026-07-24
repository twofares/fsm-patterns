package fsm.sample

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.flat.LightEvent
import fsm.sample.flat.TrafficLight
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
 * 1. Flat sealed FSM + pure transitions + logging (TrafficLight)
 * 2. GoF State + hierarchical delegation + entry/exit (Connection)
 * 3. Orthogonal Component composition (DoorSystem)
 * 4. CSP-style alphabet + menu + refusal (FileProtocol)
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

    // --- 1. Flat pure FSM ----------------------------------------------------
    line("--- Pattern: Flat sealed FSM (pure State+Event→State) ---")
    val flatLog = TransitionLog()
    val light = TrafficLight(log = flatLog)
    line("initial=${light.current.label()}")
    light.handle(LightEvent.Tick)
    light.handle(LightEvent.Tick)
    light.handle(LightEvent.Emergency)
    line("final=${light.current.label()}")
    line(flatLog.formatAll())
    line()

    // --- 2. GoF + hierarchical + entry/exit ----------------------------------
    line("--- Pattern: GoF State + hierarchical delegation + entry/exit ---")
    val connLog = TransitionLog()
    val conn = ConnectionContext(log = connLog)
    line("initial=${conn.current.name}")
    conn.handle(ConnEvent.Connect)          // Offline → Idle (enter Online, Idle)
    conn.handle(ConnEvent.Heartbeat)        // handled by Online superstate
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

    // --- 4. CSP-style process ------------------------------------------------
    line("--- Pattern: CSP process (alphabet, menu, refusal) ---")
    val cspLog = TransitionLog()
    val file = FileProtocol(log = cspLog)
    line("initial=${file.current.label()} menu=${file.menu().map { it.label() }}")
    file.offer(FileEvent.Read)   // refuse: not in menu while Closed
    file.offer(FileEvent.Open)
    file.offer(FileEvent.Read)
    file.offer(FileEvent.Close)
    line("final=${file.current.label()} menu=${file.menu().map { it.label() }}")
    line(cspLog.formatAll())
    line()

    line("=== Demo complete: flat, hierarchical/GoF, orthogonal, CSP ===")
}
