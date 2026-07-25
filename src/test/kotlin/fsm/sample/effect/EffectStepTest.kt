package fsm.sample.effect

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileEffect
import fsm.sample.csp.FileOp
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.OfferResult
import fsm.sample.csp.OfferStep
import fsm.sample.csp.applyDomainFile
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightEffect
import fsm.sample.flat.LightPort
import fsm.sample.flat.LightState
import fsm.sample.flat.TrafficLight
import fsm.sample.flat.applyDomainLight
import fsm.sample.flat.step
import fsm.sample.flat.transition
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnectionContext
import fsm.sample.gof.ConnectionEffect
import fsm.sample.gof.HandleResult
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Online
import fsm.sample.gof.SessionOp
import fsm.sample.gof.exportLabels
import fsm.sample.gof.planTransition
import fsm.sample.logging.RecordKind
import fsm.sample.orthogonal.DoorActuator
import fsm.sample.orthogonal.DoorEvent
import fsm.sample.orthogonal.DoorSystem
import fsm.sample.orthogonal.LatchEffect
import fsm.sample.orthogonal.LatchEvent
import fsm.sample.orthogonal.LatchMachine
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Prove multi-family cross-cutting + domain ports via shipped APIs.
 */
class EffectStepTest {

    @Test
    fun `TrafficLight pure step declares multi-family crossCutting and DriveLamp`() {
        val result = step(LightState.Red, LightEvent.Tick)
        assertEquals(LightState.Green, result.next)
        assertEquals(transition(LightState.Red, LightEvent.Tick), result.next)
        assertTrue(result.crossCutting.any { it is CrossCuttingEffect.Audit.LogTransition })
        assertTrue(result.crossCutting.any { it is CrossCuttingEffect.Metric.Inc && it.name == "fsm.transitions" })
        assertTrue(result.crossCutting.any { it is CrossCuttingEffect.Metric.Inc && it.name == "light.ticks" })
        assertEquals(listOf(LightEffect.DriveLamp("Green")), result.domain)
    }

    @Test
    fun `TrafficLight Emergency adds Trace family distinct from Metric`() {
        val result = step(LightState.Green, LightEvent.Emergency)
        assertTrue(result.crossCutting.any { it is CrossCuttingEffect.Trace.Span })
        assertTrue(result.crossCutting.none { it is CrossCuttingEffect.Metric.Inc && it.name == "light.ticks" })
    }

    @Test
    fun `TrafficLight boundary apply splits CrossCuttingEnv and domain port`() {
        val env = CrossCuttingEnv()
        val port = LightPort()
        val result = step(LightState.Yellow, LightEvent.Emergency)
        CrossCuttingInterpreter.apply(result.crossCutting, env)
        applyDomainLight(result.domain, port)
        assertEquals(1, env.log.snapshot().filter { it.kind == RecordKind.TRANSITION }.size)
        assertTrue(env.metrics.get("fsm.transitions") >= 1)
        assertTrue(env.traces.spans.any { it.startsWith("light.emergency") })
        assertEquals(listOf("Red"), port.commands)
    }

    @Test
    fun `TrafficLight handle drives domain port and shared metrics`() {
        val light = TrafficLight()
        light.handle(LightEvent.Tick)
        assertEquals(listOf("Green"), light.port.commands)
        assertEquals(1, light.crossCutting.metrics.get("light.ticks"))
        assertEquals(1, light.crossCutting.metrics.get("fsm.transitions"))
    }

    @Test
    fun `File refuse is cross-cutting multi-family without domain IO`() {
        val p = FileProtocol()
        val refused = p.stepOffer(FileState.Closed, FileEvent.Read)
        assertTrue(refused is OfferStep.Refused)
        assertTrue(refused.crossCutting.any { it is CrossCuttingEffect.Audit.LogRefusal })
        assertTrue(refused.crossCutting.any { it is CrossCuttingEffect.Metric.Inc && it.name == "csp.refuse" })
        assertTrue(refused.crossCutting.any { it is CrossCuttingEffect.Trace.Span })
        val env = CrossCuttingEnv()
        CrossCuttingInterpreter.apply(refused.crossCutting, env)
        assertTrue(p.port.ops.isEmpty())
        assertEquals(1, env.metrics.get("csp.refuse"))
        assertTrue(env.traces.spans.isNotEmpty())
    }

    @Test
    fun `File accept declares csp accept metric and domain OpenHandle`() {
        val p = FileProtocol()
        val accepted = p.stepOffer(FileState.Closed, FileEvent.Open)
        assertTrue(accepted is OfferStep.Accepted)
        assertTrue(accepted.crossCutting.any { it is CrossCuttingEffect.Metric.Inc && it.name == "csp.accept" })
        assertEquals(listOf(FileEffect.OpenHandle), (accepted as OfferStep.Accepted).domain)
    }

    @Test
    fun `File offer boundary records domain only on accept`() {
        val p = FileProtocol()
        assertTrue(p.offer(FileEvent.Read) is OfferResult.Refused)
        assertTrue(p.port.ops.isEmpty())
        assertEquals(1, p.crossCutting.metrics.get("csp.refuse"))
        assertTrue(p.offer(FileEvent.Open) is OfferResult.Accepted)
        assertEquals(listOf(FileOp.Open), p.port.ops)
        assertEquals(1, p.crossCutting.metrics.get("csp.accept"))
    }

    @Test
    fun `Online onEvent declares IncHeartbeat without mutating port heartbeats`() {
        val ctx = ConnectionContext()
        ctx.handle(ConnEvent.Connect)
        assertEquals(0, ctx.port.heartbeats)
        val handled = Online.onEvent(ConnEvent.Heartbeat, Idle)
        val h = handled as HandleResult.Handled
        assertEquals(listOf(ConnectionEffect.IncHeartbeat), h.domain)
        assertEquals(0, ctx.port.heartbeats)
        assertTrue(ctx.handle(ConnEvent.Heartbeat))
        assertEquals(1, ctx.port.heartbeats)
    }

    @Test
    fun `Connection Connect opens domain session and emits multi-family crossCutting`() {
        val ctx = ConnectionContext()
        ctx.handle(ConnEvent.Connect)
        assertEquals(listOf(SessionOp.Opened), ctx.port.sessions)
        assertEquals(1, ctx.crossCutting.metrics.get("connection.session.open"))
        assertTrue(ctx.crossCutting.traces.spans.any { it.contains("open") })
        ctx.handle(ConnEvent.Disconnect)
        assertEquals(listOf(SessionOp.Opened, SessionOp.Closed), ctx.port.sessions)
        assertEquals(1, ctx.crossCutting.metrics.get("connection.session.close"))
    }

    @Test
    fun `planTransition Offline to Idle includes OpenSession and session metrics`() {
        val planned = planTransition(Offline, Idle, ConnEvent.Connect)
        assertTrue(planned.domain.contains(ConnectionEffect.OpenSession))
        assertTrue(planned.crossCutting.any { it is CrossCuttingEffect.Metric.Inc && it.name == "connection.session.open" })
        assertTrue(planned.exportLabels().contains("OpenSession"))
        assertTrue(planned.exportLabels().any { it.startsWith("Audit.LogEntry") })
    }

    /**
     * PR 6 gate: Offline→Idle keeps Online enter paired with OpenSession in export labels.
     * Within the Online enter fragment, LogEntry(Online) precedes OpenSession; only
     * Online-session metric/trace may intervene (not Idle entry or flattened reordering).
     */
    @Test
    fun `Connect Offline to Idle exportLabels keeps LogEntry Online immediately before OpenSession`() {
        val planned = planTransition(Offline, Idle, ConnEvent.Connect)
        val labels = planned.exportLabels()
        val onlineEntry = labels.indexOfFirst { it == "Audit.LogEntry(Online)" }
        val openSession = labels.indexOfFirst { it == "OpenSession" }
        val idleEntry = labels.indexOfFirst { it == "Audit.LogEntry(Idle)" }
        assertTrue(onlineEntry >= 0, "missing Audit.LogEntry(Online) in $labels")
        assertTrue(openSession >= 0, "missing OpenSession in $labels")
        assertTrue(openSession > onlineEntry, "OpenSession must follow LogEntry(Online); labels=$labels")
        // No other state entry/exit between Online enter and its domain OpenSession.
        val between = labels.subList(onlineEntry + 1, openSession)
        assertTrue(
            between.none { it.startsWith("Audit.LogEntry") || it.startsWith("Audit.LogExit") },
            "foreign audit between LogEntry(Online) and OpenSession: $between in $labels",
        )
        // Idle enter must come after OpenSession (fragment pairing, not flatten-all-CC).
        assertTrue(idleEntry > openSession, "LogEntry(Idle) must follow OpenSession; labels=$labels")
        // Same fragment: OpenSession is first domain after Online's CC block.
        val onlineFrag = planned.fragments.single { ConnectionEffect.OpenSession in it.domain }
        assertTrue(onlineFrag.crossCutting.any { it.exportLabel() == "Audit.LogEntry(Online)" })
        assertEquals(listOf(ConnectionEffect.OpenSession), onlineFrag.domain)
    }

    @Test
    fun `shared CrossCuttingEnv accumulates metrics across machines`() {
        val shared = CrossCuttingEnv()
        val light = step(LightState.Red, LightEvent.Tick)
        CrossCuttingInterpreter.apply(light.crossCutting, shared)
        applyDomainLight(light.domain, LightPort())
        val file = FileProtocol().stepOffer(FileState.Closed, FileEvent.Open)
        CrossCuttingInterpreter.apply(file.crossCutting, shared)
        applyDomainFile((file as OfferStep.Accepted).domain, fsm.sample.csp.FilePort())
        assertEquals(2, shared.metrics.get("fsm.transitions"))
        assertEquals(1, shared.metrics.get("light.ticks"))
        assertEquals(1, shared.metrics.get("csp.accept"))
    }

    @Test
    fun `Door refuse is multi-family cross-cutting without domain actuators`() {
        val door = DoorSystem()
        assertFalse(door.handle(DoorEvent.Open))
        assertTrue(door.port.actuators.isEmpty())
        assertEquals(1, door.crossCutting.metrics.get("door.refuse"))
        assertTrue(door.crossCutting.traces.spans.any { it.contains("locked") })
        assertTrue(door.crossCutting.log.snapshot().any { it.kind == RecordKind.REFUSAL })
    }

    @Test
    fun `Door unlock and open accumulate domain actuators and latch metrics`() {
        val door = DoorSystem()
        door.handle(DoorEvent.Unlock)
        assertEquals(listOf(DoorActuator.EnergizeStrike), door.port.actuators)
        assertEquals(1, door.crossCutting.metrics.get("door.latch.unlock"))
        door.handle(DoorEvent.Open)
        assertTrue(door.port.actuators.contains(DoorActuator.SoundChime))
        assertEquals(1, door.crossCutting.metrics.get("door.swing.open"))
    }

    @Test
    fun `DoorSystem pure guard returns Metric and Trace not only Audit`() {
        val door = DoorSystem()
        val effects = door.guardRefusalEffects(
            DoorEvent.Open,
            latchState = LatchState.Locked,
            swingState = SwingState.Closed,
        )
        assertTrue(effects.any { it is CrossCuttingEffect.Audit.LogRefusal })
        assertTrue(effects.any { it is CrossCuttingEffect.Metric.Inc })
        assertTrue(effects.any { it is CrossCuttingEffect.Trace.Span })
        assertTrue(door.crossCutting.log.snapshot().isEmpty())
        assertTrue(door.port.actuators.isEmpty())
    }

    @Test
    fun `Latch pure step declares domain EnergizeStrike and crossCutting metrics`() {
        val latch = LatchMachine()
        val result = latch.step(LatchState.Locked, LatchEvent.Unlock)
        assertTrue(result.domain.contains(LatchEffect.EnergizeStrike))
        assertTrue(result.crossCutting.any { it is CrossCuttingEffect.Metric.Inc && it.name == "door.latch.unlock" })
    }
}
