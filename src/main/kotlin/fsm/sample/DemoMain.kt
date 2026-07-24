package fsm.sample

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileEffect
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.exportLabel
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.TrafficLight
import fsm.sample.flat.exportLabel
import fsm.sample.flat.step
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnectionContext
import fsm.sample.logging.TransitionLog
import fsm.sample.orthogonal.DoorEvent
import fsm.sample.orthogonal.DoorSystem

/**
 * PURPOSE: Demo of **cross-cutting audit** vs **domain-specific** effects.
 *
 * For each vignette:
 * 1. Pure effect list (declare only)
 * 2. After boundary: TransitionLog (cross-cutting sink)
 * 3. Domain port snapshot (machine-local sink)
 *
 * Cross-cutting: AuditEffect → same log record format for all machines.
 * Domain: DriveLamp, OpenHandle/ReadChunk/CloseHandle, OpenSession/CloseSession/
 * IncHeartbeat, EnergizeStrike/EngageDeadbolt/SoundChime — never on foreign ADTs.
 */
fun main() {
    fun line(s: String = "") {
        println(s)
    }

    line("=== Domain-specific vs cross-cutting effects (no libraries/engines) ===")
    line()

    // --- 1. TrafficLight -----------------------------------------------------
    line("--- TrafficLight: Audit (cross-cutting) + DriveLamp (domain) ---")
    val pureStep = step(LightState.Red, LightEvent.Tick)
    line("pure step effects: ${pureStep.effects.map { it.exportLabel() }}")
    val flatLog = TransitionLog()
    val light = TrafficLight(log = flatLog)
    light.handle(LightEvent.Tick)
    light.handle(LightEvent.Tick)
    light.handle(LightEvent.Emergency)
    line("final=${light.current.label()}")
    line("AUDIT log:")
    line(flatLog.formatAll())
    line("DOMAIN port: ${light.port.snapshot()}")
    line()

    // --- 2. Connection -------------------------------------------------------
    line("--- Connection: Audit + OpenSession/CloseSession/IncHeartbeat (domain) ---")
    val connLog = TransitionLog()
    val conn = ConnectionContext(log = connLog)
    conn.handle(ConnEvent.Connect)
    conn.handle(ConnEvent.Heartbeat)
    conn.handle(ConnEvent.StartTransfer)
    conn.handle(ConnEvent.FinishTransfer)
    conn.handle(ConnEvent.Disconnect)
    conn.handle(ConnEvent.StartTransfer) // refuse
    line("final=${conn.current.name} heartbeats=${conn.heartbeatCount}")
    line("AUDIT log:")
    line(connLog.formatAll())
    line("DOMAIN port: ${conn.port.snapshot()}")
    line()

    // --- 3. Door (orthogonal) ------------------------------------------------
    line("--- Door: region actuators (domain); guard refusals audit-only ---")
    val door = DoorSystem()
    door.handle(DoorEvent.Open) // refuse locked — audit only
    line("after refused Open: domain ${door.port.snapshot()}")
    door.handle(DoorEvent.Unlock)
    door.handle(DoorEvent.Open)
    door.handle(DoorEvent.Lock) // refuse open
    door.handle(DoorEvent.Close)
    door.handle(DoorEvent.Lock)
    line("final=${door.configuration()}")
    line("AUDIT log:")
    line(door.log.formatAll())
    line("DOMAIN port: ${door.port.snapshot()}")
    line()

    // --- 4. FileProtocol (CSP) -----------------------------------------------
    line("--- FileProtocol: accept → OpenHandle/ReadChunk/CloseHandle; refuse → audit only ---")
    val cspLog = TransitionLog()
    val file = FileProtocol(log = cspLog)
    val refused = file.stepOffer(FileState.Closed, FileEvent.Read)
    line(
        "pure refuse effects (no domain I/O): ${refused.effects.map { it.exportLabel() }}",
    )
    file.offer(FileEvent.Read)
    line("after refuse offer: domain ${file.port.snapshot()}")
    file.offer(FileEvent.Open)
    file.offer(FileEvent.Read)
    file.offer(FileEvent.Close)
    line("final=${file.current.label()}")
    line("AUDIT log:")
    line(cspLog.formatAll())
    line("DOMAIN port: ${file.port.snapshot()}")
    line()

    line("=== Demo complete: dual sinks (audit log vs domain ports) ===")
}
