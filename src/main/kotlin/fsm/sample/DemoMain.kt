package fsm.sample

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.exportLabels
import fsm.sample.effect.exportLabels
import fsm.sample.flat.DWELL_GREEN_MS
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.TrafficLight
import fsm.sample.flat.exportLabel
import fsm.sample.flat.step
import fsm.sample.gof.CONNECTION_WATCHDOG_MS
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnectionContext
import fsm.sample.interproc.PlayerEvent
import fsm.sample.interproc.StatelessPlayerEngagement
import fsm.sample.interproc.TRACK_DURATION_MS
import fsm.sample.orthogonal.DoorEvent
import fsm.sample.orthogonal.DoorSystem

/**
 * PURPOSE: Demo of **generalized cross-cutting** (audit + metrics + trace) vs **domain**.
 *
 * Cross-cutting families share [CrossCuttingEnv] sinks:
 * - Audit → TransitionLog
 * - Metric → MetricsPort
 * - Trace → TracePort
 *
 * Domain stays on machine ports (lamp, file ops, session, door actuators).
 *
 * In-process vignettes use live contexts + [fsm.sample.machine] apply helpers.
 * A short **inter-proc** section shows load→step→store→thin-player deliver
 * (`interproc/`); see `docs/in-process-vs-interproc.md`.
 */
fun main() {
    fun line(s: String = "") {
        println(s)
    }

    line("=== Cross-cutting (multi-family) vs domain-specific effects ===")
    line()

    line("--- TrafficLight: Audit + Metric(+Trace on Emergency) + DriveLamp + dwell Timer + Flash ε ---")
    val pureStep = step(LightState.Red, LightEvent.Tick)
    line("pure step labels: ${pureStep.exportLabels { it.exportLabel() }}")
    val light = TrafficLight()
    light.handle(LightEvent.Tick)
    line("after Tick: state=${light.current.label()} pending=${light.timers.pending()}")
    // Deterministic dwell fire — VirtualClock.advance + fireDueTimeouts (never Thread.sleep)
    light.clock.advance(DWELL_GREEN_MS)
    val fired = light.fireDueTimeouts()
    line("after advance+fireDue: fired=$fired state=${light.current.label()} pending=${light.timers.pending()}")
    // Emergency → Flash (Cancel dwell) then drainAutomatic ε → Red (re-arm Red dwell)
    light.handle(LightEvent.Emergency)
    line("after Emergency+ε drain: state=${light.current.label()} pending=${light.timers.pending()}")
    line("CROSS-CUTTING audit log:")
    line(light.crossCutting.log.formatAll())
    line("CROSS-CUTTING ${light.crossCutting.snapshot()}")
    line("DOMAIN port: ${light.port.snapshot()}")
    line()

    line("--- Connection: multi-family CC + Online watchdog Timer + domain session/heartbeat ---")
    val conn = ConnectionContext()
    conn.handle(ConnEvent.Connect)
    line("after Connect: state=${conn.current.name} pending=${conn.timers.pending()}")
    conn.handle(ConnEvent.Heartbeat)
    // Deterministic watchdog fire — VirtualClock.advance + fireDueTimeouts
    conn.clock.advance(CONNECTION_WATCHDOG_MS)
    val connFired = conn.fireDueTimeouts()
    line("after advance+fireDue: fired=$connFired state=${conn.current.name} pending=${conn.timers.pending()}")
    // Re-connect then Disconnect to show cancel path (previous fire already Offline)
    conn.handle(ConnEvent.Connect)
    conn.handle(ConnEvent.Disconnect)
    line("final=${conn.current.name} heartbeats=${conn.port.heartbeats} pending=${conn.timers.pending()}")
    line("CROSS-CUTTING audit log:")
    line(conn.crossCutting.log.formatAll())
    line("CROSS-CUTTING ${conn.crossCutting.snapshot()}")
    line("DOMAIN port: ${conn.port.snapshot()}")
    line()

    line("--- Door: refuse → Audit+Metric+Trace (no domain); unlock/open → actuators ---")
    val door = DoorSystem()
    door.handle(DoorEvent.Open)
    line("after refused Open: domain ${door.port.snapshot()}; CC ${door.crossCutting.snapshot()}")
    door.handle(DoorEvent.Unlock)
    door.handle(DoorEvent.Open)
    line("final=${door.configuration()}")
    line("CROSS-CUTTING audit log:")
    line(door.crossCutting.log.formatAll())
    line("CROSS-CUTTING ${door.crossCutting.snapshot()}")
    line("DOMAIN port: ${door.port.snapshot()}")
    line()

    line("--- FileProtocol: accept metrics; refuse Metric+Trace (no domain I/O) ---")
    val file = FileProtocol()
    val refused = file.stepOffer(FileState.Closed, FileEvent.Read)
    line("pure refuse labels: ${refused.exportLabels()}")
    file.offer(FileEvent.Read)
    line("after refuse: domain ${file.port.snapshot()}; CC ${file.crossCutting.snapshot()}")
    file.offer(FileEvent.Open)
    file.offer(FileEvent.Read)
    file.offer(FileEvent.Close)
    line("final=${file.current.label()}")
    line("CROSS-CUTTING audit log:")
    line(file.crossCutting.log.formatAll())
    line("CROSS-CUTTING ${file.crossCutting.snapshot()}")
    line("DOMAIN port: ${file.port.snapshot()}")
    line()

    line("--- Inter-proc PlayerControl: load→step→applyOffer(store→CC→domain) + timer ---")
    val player = StatelessPlayerEngagement(sessionId = "demo-session")
    player.engage(PlayerEvent.Play)
    line("after Play: state=${player.state()?.label()} pending=${player.timers.pending()}")
    line("thin player: ${player.player.snapshot()}")
    // Separate engagement instance so refuse demo does not share store/port with Play path above
    val refuseOnly = StatelessPlayerEngagement(sessionId = "r")
    val refuseResult = refuseOnly.engage(PlayerEvent.Pause)
    line(
        "refuse Pause@Idle: accepted=${refuseResult.accepted} delivered=${refuseResult.delivered} " +
            "reason=${refuseResult.reason} state=${refuseOnly.state()?.label()} " +
            "storePresent=${refuseOnly.store.isPresent()}",
    )
    // Multi-session = N engagement instances (independent store / player / timers / CC)
    val sessA = StatelessPlayerEngagement(sessionId = "a")
    val sessB = StatelessPlayerEngagement(sessionId = "b")
    sessA.engage(PlayerEvent.Play)
    sessB.engage(PlayerEvent.Play)
    sessA.engage(PlayerEvent.Pause)
    line(
        "multi-session instances: a=${sessA.state()?.label()} pendingA=${sessA.timers.pending().size} " +
            "b=${sessB.state()?.label()} pendingB=${sessB.timers.pending().size}",
    )
    player.clock.advance(TRACK_DURATION_MS)
    val trackFired = player.fireDueTimeouts()
    line(
        "after track-end fireDue: events=${trackFired.map { it.event }} " +
            "accepted=${trackFired.map { it.result.accepted }} " +
            "state=${player.state()?.label()} player=${player.player.snapshot()}",
    )
    line("CROSS-CUTTING audit log:")
    line(player.crossCutting.log.formatAll())
    line("CROSS-CUTTING ${player.crossCutting.snapshot()}")
    line("DOMAIN thin player: ${player.player.snapshot()}")
    line()

    line("=== Demo complete: multi-family cross-cutting + domain ports (+ inter-proc) ===")
}
