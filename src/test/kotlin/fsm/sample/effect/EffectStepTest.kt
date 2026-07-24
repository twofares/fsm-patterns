package fsm.sample.effect

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileEffect
import fsm.sample.csp.FileOp
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.OfferResult
import fsm.sample.csp.OfferStep
import fsm.sample.csp.interpretFile
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightEffect
import fsm.sample.flat.LightPort
import fsm.sample.flat.LightState
import fsm.sample.flat.TrafficLight
import fsm.sample.flat.interpretLight
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
import fsm.sample.logging.RecordKind
import fsm.sample.logging.TransitionLog
import fsm.sample.orthogonal.DoorActuator
import fsm.sample.orthogonal.DoorEvent
import fsm.sample.orthogonal.DoorEffect
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
 * PURPOSE: Prove dual sinks — cross-cutting audit vs domain ports — via shipped APIs.
 */
class EffectStepTest {

    @Test
    fun `TrafficLight pure step declares audit and DriveLamp domain effect`() {
        val result = step(LightState.Red, LightEvent.Tick)
        assertEquals(LightState.Green, result.next)
        assertEquals(transition(LightState.Red, LightEvent.Tick), result.next)
        assertEquals(2, result.effects.size)
        assertTrue(result.effects[0] is LightEffect.Audit)
        assertEquals(LightEffect.DriveLamp("Green"), result.effects[1])
    }

    @Test
    fun `TrafficLight interpret splits audit log and domain port`() {
        val log = TransitionLog()
        val port = LightPort()
        val result = step(LightState.Yellow, LightEvent.Emergency)
        assertTrue(log.snapshot().isEmpty())
        assertTrue(port.commands.isEmpty())
        interpretLight(result.effects, log, port)
        assertEquals(1, log.snapshot().size)
        assertEquals(RecordKind.TRANSITION, log.snapshot()[0].kind)
        assertEquals(listOf("Red"), port.commands)
    }

    @Test
    fun `TrafficLight handle drives domain port`() {
        val log = TransitionLog()
        val light = TrafficLight(log = log)
        light.handle(LightEvent.Tick)
        assertEquals(listOf("Green"), light.port.commands)
        assertEquals(1, log.snapshot().filter { it.kind == RecordKind.TRANSITION }.size)
    }

    @Test
    fun `TrafficLight effects never include Connection domain types`() {
        val all = listOf(
            step(LightState.Red, LightEvent.Tick),
            step(LightState.Green, LightEvent.Tick),
        )
        for (r in all) {
            assertTrue(r.effects.any { it is LightEffect.DriveLamp })
            assertTrue(r.effects.any { it is LightEffect.Audit })
            // Compile-time: List<LightEffect> cannot hold ConnectionEffect.IncHeartbeat.
        }
    }

    @Test
    fun `File refuse is audit-only with empty FilePort after interpret`() {
        val p = FileProtocol()
        val result = p.stepOffer(FileState.Closed, FileEvent.Read)
        assertTrue(result is OfferStep.Refused)
        assertEquals(1, result.effects.size)
        assertTrue(result.effects.single() is FileEffect.Audit)
        val log = TransitionLog()
        interpretFile(result.effects, log, p.port)
        assertTrue(p.port.ops.isEmpty())
        assertEquals(RecordKind.REFUSAL, log.snapshot().single().kind)
    }

    @Test
    fun `File accept declares domain OpenHandle plus audit`() {
        val p = FileProtocol()
        val result = p.stepOffer(FileState.Closed, FileEvent.Open)
        assertTrue(result is OfferStep.Accepted)
        assertTrue(result.effects.any { it is FileEffect.Audit })
        assertTrue(result.effects.contains(FileEffect.OpenHandle))
    }

    @Test
    fun `File offer boundary records domain ops only on accept path`() {
        val log = TransitionLog()
        val p = FileProtocol(log = log)
        assertTrue(p.offer(FileEvent.Read) is OfferResult.Refused)
        assertTrue(p.port.ops.isEmpty())
        assertTrue(p.offer(FileEvent.Open) is OfferResult.Accepted)
        assertEquals(listOf(FileOp.Open), p.port.ops)
        p.offer(FileEvent.Read)
        p.offer(FileEvent.Close)
        assertEquals(listOf(FileOp.Open, FileOp.Read, FileOp.Close), p.port.ops)
    }

    @Test
    fun `Online onEvent declares IncHeartbeat without mutating heartbeatCount`() {
        val ctx = ConnectionContext(log = null)
        ctx.handle(ConnEvent.Connect)
        assertEquals(0, ctx.heartbeatCount)
        val handled = Online.onEvent(ctx, ConnEvent.Heartbeat)
        val h = handled as HandleResult.Handled
        assertEquals(listOf(ConnectionEffect.IncHeartbeat), h.effects)
        assertEquals(0, ctx.heartbeatCount)
        assertTrue(ctx.handle(ConnEvent.Heartbeat))
        assertEquals(1, ctx.heartbeatCount)
    }

    @Test
    fun `Connection Connect opens domain session and Disconnect closes it`() {
        val ctx = ConnectionContext()
        assertTrue(ctx.port.sessions.isEmpty()) // Offline init: no OpenSession
        ctx.handle(ConnEvent.Connect)
        assertEquals(listOf(SessionOp.Opened), ctx.port.sessions)
        ctx.handle(ConnEvent.StartTransfer) // stay under Online — no new OpenSession
        assertEquals(listOf(SessionOp.Opened), ctx.port.sessions)
        ctx.handle(ConnEvent.Disconnect)
        assertEquals(listOf(SessionOp.Opened, SessionOp.Closed), ctx.port.sessions)
    }

    @Test
    fun `planTransition Offline to Idle includes OpenSession domain effect`() {
        val planned = fsm.sample.gof.planTransition(Offline, Idle, ConnEvent.Connect)
        assertTrue(planned.any { it is ConnectionEffect.Audit })
        assertTrue(planned.contains(ConnectionEffect.OpenSession))
        assertFalse(planned.contains(ConnectionEffect.CloseSession))
        // exit Offline, transition, enter Online (audit+OpenSession), enter Idle
        assertTrue(planned.size >= 5)
    }

    @Test
    fun `Connection entry exit still appear via audit boundary`() {
        val log = TransitionLog()
        val ctx = ConnectionContext(log = log)
        assertTrue(log.snapshot().any { it.kind == RecordKind.ENTRY && it.to == "Offline" })
        ctx.handle(ConnEvent.Connect)
        val entries = log.snapshot().filter { it.kind == RecordKind.ENTRY }.map { it.to }
        assertTrue(entries.contains("Online"))
        assertTrue(entries.contains("Idle"))
    }

    @Test
    fun `cross-cutting audit consistent across machines with domain ports separate`() {
        val shared = TransitionLog()
        val lightPort = LightPort()
        interpretLight(step(LightState.Red, LightEvent.Tick).effects, shared, lightPort)
        val file = FileProtocol()
        interpretFile(file.stepOffer(FileState.Closed, FileEvent.Open).effects, shared, file.port)
        assertEquals(listOf(RecordKind.TRANSITION, RecordKind.TRANSITION), shared.snapshot().map { it.kind })
        assertEquals(listOf("Green"), lightPort.commands)
        assertEquals(listOf(FileOp.Open), file.port.ops)
    }

    @Test
    fun `Latch pure step declares EnergizeStrike domain with audit`() {
        val latch = LatchMachine()
        val result = latch.step(LatchState.Locked, LatchEvent.Unlock)
        assertTrue(result.effects.any { it is LatchEffect.Audit })
        assertTrue(result.effects.contains(LatchEffect.EnergizeStrike))
    }

    @Test
    fun `Door refused Open is audit-only without domain actuators`() {
        val door = DoorSystem()
        assertFalse(door.handle(DoorEvent.Open))
        assertTrue(door.port.actuators.isEmpty())
        assertTrue(door.log.snapshot().any { it.kind == RecordKind.REFUSAL })
    }

    @Test
    fun `Door unlock and open accumulate domain actuators`() {
        val door = DoorSystem()
        door.handle(DoorEvent.Unlock)
        assertEquals(listOf(DoorActuator.EnergizeStrike), door.port.actuators)
        door.handle(DoorEvent.Open)
        assertEquals(
            listOf(DoorActuator.EnergizeStrike, DoorActuator.SoundChime),
            door.port.actuators,
        )
        door.handle(DoorEvent.Close)
        door.handle(DoorEvent.Lock)
        assertTrue(door.port.actuators.contains(DoorActuator.EngageDeadbolt))
    }

    @Test
    fun `DoorSystem pure guard declares DoorEffect refusal without mutating log or port`() {
        val door = DoorSystem()
        val effects = door.guardRefusalEffects(
            DoorEvent.Open,
            latchState = LatchState.Locked,
            swingState = SwingState.Closed,
        )
        assertEquals(1, effects.size)
        assertTrue(effects.single() is DoorEffect.Audit)
        assertTrue(door.log.snapshot().isEmpty())
        assertTrue(door.port.actuators.isEmpty())
    }
}
