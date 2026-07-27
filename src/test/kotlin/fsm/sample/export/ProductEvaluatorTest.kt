package fsm.sample.export

import fsm.sample.csp.FileEvent
import fsm.sample.csp.FileProtocol
import fsm.sample.csp.FileState
import fsm.sample.csp.exportLabels
import fsm.sample.effect.OfferOutcome
import fsm.sample.effect.exportLabels
import fsm.sample.flat.LightEvent
import fsm.sample.flat.LightState
import fsm.sample.flat.exportLabel
import fsm.sample.flat.step
import fsm.sample.flat.transition
import fsm.sample.effect.HierarchyStep
import fsm.sample.gof.ConnEvent
import fsm.sample.gof.ConnectionContext
import fsm.sample.gof.Idle
import fsm.sample.gof.Offline
import fsm.sample.gof.Transferring
import fsm.sample.gof.connectionStep
import fsm.sample.gof.exportLabels
import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.orthogonal.LatchEvent
import fsm.sample.orthogonal.LatchMachine
import fsm.sample.orthogonal.LatchState
import fsm.sample.orthogonal.SwingEvent
import fsm.sample.orthogonal.SwingMachine
import fsm.sample.orthogonal.SwingState
import fsm.sample.orthogonal.exportLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped product-evaluation APIs and assert rows match
 * evaluating the same pure step/offer/plan functions the generator calls.
 * Also asserts export does not mutate shared logs/counters via interpreters.
 */
class ProductEvaluatorTest {

    @Test
    fun `traffic light product rows match shipped transition`() {
        val table = evaluateTrafficLight()
        val states = listOf(LightState.Red, LightState.Green, LightState.Yellow)
        val events = listOf(LightEvent.Tick, LightEvent.Emergency, LightEvent.DwellElapsed)
        assertEquals(states.size * events.size, table.rows.size)

        for (s in states) {
            for (e in events) {
                val expectedTo = transition(s, e).label()
                val row = table.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(expectedTo, row.to)
                assertEquals(TransitionKind.ACCEPTED, row.kind)
            }
        }
    }

    @Test
    fun `traffic light product effects match pure step`() {
        val table = evaluateTrafficLight()
        for (s in listOf(LightState.Red, LightState.Green, LightState.Yellow)) {
            for (e in listOf(LightEvent.Tick, LightEvent.Emergency, LightEvent.DwellElapsed)) {
                val pure = step(s, e)
                val row = table.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(pure.next.label(), row.to)
                assertEquals(pure.exportLabels { it.exportLabel() }, row.effects)
                assertTrue(row.effects.any { it.contains("LogTransition") }, "row=$row")
                assertTrue(row.effects.any { it.startsWith("DriveLamp") }, "row=$row")
                assertTrue(row.effects.any { it.startsWith("Metric") }, "row=$row")
                assertTrue(row.effects.any { it.startsWith("Timer.Cancel") }, "row=$row")
                assertTrue(row.effects.any { it.startsWith("Timer.Arm") }, "row=$row")
            }
        }
    }

    @Test
    fun `file protocol non-menu events are refused self-successors`() {
        val table = evaluateFileProtocol()
        val protocol = FileProtocol()

        for (s in listOf(FileState.Closed, FileState.Opened)) {
            val menu = protocol.menu(s)
            for (e in listOf(FileEvent.Open, FileEvent.Read, FileEvent.Close)) {
                val row = table.rows.single { it.from == s.label() && it.event == e.label() }
                if (e in menu) {
                    assertEquals(TransitionKind.ACCEPTED, row.kind)
                    assertEquals(protocol.pureWhenAccepted(s, e).label(), row.to)
                } else {
                    assertEquals(TransitionKind.REFUSED, row.kind)
                    assertEquals(s.label(), row.to) // self-successor
                    assertTrue(row.note?.contains("menu") == true)
                }
            }
        }
    }

    @Test
    fun `file protocol product effects match pure stepOffer`() {
        val table = evaluateFileProtocol()
        val protocol = FileProtocol()
        for (s in listOf(FileState.Closed, FileState.Opened)) {
            for (e in listOf(FileEvent.Open, FileEvent.Read, FileEvent.Close)) {
                val pure = protocol.stepOffer(s, e)
                val row = table.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(pure.next.label(), row.to)
                assertEquals(pure.exportLabels(), row.effects)
                when (pure) {
                    is OfferOutcome.Accepted -> {
                        assertEquals(TransitionKind.ACCEPTED, row.kind)
                        assertTrue(row.effects.any { it.contains("LogTransition") })
                        assertTrue(row.effects.any { it.contains("csp.accept") || it.contains("Metric") })
                        assertTrue(
                            row.effects.any {
                                it == "OpenHandle" || it == "ReadChunk" || it == "CloseHandle"
                            },
                            "accepted row should declare domain I/O: $row",
                        )
                    }
                    is OfferOutcome.Refused -> {
                        assertEquals(TransitionKind.REFUSED, row.kind)
                        assertTrue(row.effects.any { it.contains("LogRefusal") })
                        assertTrue(row.effects.any { it.contains("csp.refuse") || it.contains("Trace") })
                    }
                }
            }
        }
    }

    @Test
    fun `latch and swing region products match pureTransition`() {
        val latch = LatchMachine()
        val swing = SwingMachine()
        val latchTable = evaluateLatchRegion()
        val swingTable = evaluateSwingRegion()

        for (s in listOf(LatchState.Locked, LatchState.Unlocked)) {
            for (e in listOf(LatchEvent.Lock, LatchEvent.Unlock)) {
                val row = latchTable.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(latch.pureTransition(s, e).label(), row.to)
            }
        }
        for (s in listOf(SwingState.Closed, SwingState.Open)) {
            for (e in listOf(SwingEvent.OpenDoor, SwingEvent.CloseDoor)) {
                val row = swingTable.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(swing.pureTransition(s, e).label(), row.to)
            }
        }
    }

    @Test
    fun `latch and swing product effects match pure step`() {
        val latch = LatchMachine()
        val swing = SwingMachine()
        val latchTable = evaluateLatchRegion()
        val swingTable = evaluateSwingRegion()

        for (s in listOf(LatchState.Locked, LatchState.Unlocked)) {
            for (e in listOf(LatchEvent.Lock, LatchEvent.Unlock)) {
                val pure = latch.step(s, e)
                val row = latchTable.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(pure.exportLabels { it.exportLabel() }, row.effects)
                assertTrue(row.effects.any { it.contains("LogTransition") })
                assertTrue(
                    row.effects.contains("EnergizeStrike") || row.effects.contains("EngageDeadbolt"),
                    "latch domain: $row",
                )
            }
        }
        for (s in listOf(SwingState.Closed, SwingState.Open)) {
            for (e in listOf(SwingEvent.OpenDoor, SwingEvent.CloseDoor)) {
                val pure = swing.step(s, e)
                val row = swingTable.rows.single { it.from == s.label() && it.event == e.label() }
                assertEquals(pure.exportLabels { it.exportLabel() }, row.effects)
                if (e is SwingEvent.OpenDoor) {
                    assertTrue(row.effects.contains("SoundChime"), "row=$row")
                }
            }
        }
    }

    @Test
    fun `connection probe matches hierarchical handle outcomes for key edges`() {
        val table = evaluateConnectionHierarchy()
        // Connect from Offline → Idle (shipped offline handler)
        val connect = table.rows.single { it.from == Offline.name && it.event == ConnEvent.Connect.label() }
        assertEquals(Idle.name, connect.to)
        assertEquals(TransitionKind.ACCEPTED, connect.kind)

        // StartTransfer from Idle → Transferring
        val start = table.rows.single { it.from == Idle.name && it.event == ConnEvent.StartTransfer.label() }
        assertEquals(Transferring.name, start.to)

        // Disconnect from Transferring via Online superstate → Offline
        val disc = table.rows.single {
            it.from == Transferring.name && it.event == ConnEvent.Disconnect.label()
        }
        assertEquals(Offline.name, disc.to)

        // Illegal at Offline
        val refuse = table.rows.single {
            it.from == Offline.name && it.event == ConnEvent.StartTransfer.label()
        }
        assertEquals(TransitionKind.REFUSED, refuse.kind)
        assertEquals(Offline.name, refuse.to)
    }

    @Test
    fun `connection product effects match connectionStep oracle including multi-family CC`() {
        val table = evaluateConnectionHierarchy()
        val leaves = listOf(Offline, Idle, Transferring)
        val events = listOf(
            ConnEvent.Connect,
            ConnEvent.Disconnect,
            ConnEvent.StartTransfer,
            ConnEvent.FinishTransfer,
            ConnEvent.Heartbeat,
        )
        for (leaf in leaves) {
            for (event in events) {
                val pure = connectionStep(leaf, event)
                val expected = when (pure) {
                    is HierarchyStep.Handled -> pure.effects
                    is HierarchyStep.Unhandled -> pure.effects
                }
                val row = table.rows.single { it.from == leaf.name && it.event == event.label() }
                assertEquals(
                    expected.exportLabels(),
                    row.effects,
                    "leaf=${leaf.name} event=${event.label()}",
                )
            }
        }

        val connect = table.rows.single { it.from == Offline.name && it.event == ConnEvent.Connect.label() }
        assertTrue(connect.effects.contains("OpenSession"))
        assertTrue(connect.effects.any { it.startsWith("Audit.LogEntry") && it.contains("Online") })
        assertTrue(connect.effects.any { it.contains("connection.session.open") || it.contains("Metric") })

        val start = table.rows.single {
            it.from == Idle.name && it.event == ConnEvent.StartTransfer.label()
        }
        assertFalse(start.effects.contains("OpenSession"))
        assertTrue(start.effects.any { it.startsWith("Audit.LogTransition") || it == "Audit.LogTransition" })

        val hb = table.rows.single { it.from == Idle.name && it.event == ConnEvent.Heartbeat.label() }
        assertTrue(hb.effects.contains("IncHeartbeat"))
        assertTrue(hb.effects.any { it.contains("heartbeat") || it.contains("Metric") })

        val refuse = table.rows.single {
            it.from == Offline.name && it.event == ConnEvent.StartTransfer.label()
        }
        assertTrue(refuse.effects.any { it.contains("LogRefusal") })
        assertTrue(refuse.effects.any { it.contains("Trace") || it.contains("refuse") })
    }

    @Test
    fun `export product evaluation does not mutate shared logs or live heartbeat counters`() {
        val env = CrossCuttingEnv()
        val live = ConnectionContext(crossCutting = env)
        val logSizeAfterInit = env.log.snapshot().size
        val metricsAfterInit = env.metrics.snapshot()
        assertTrue(logSizeAfterInit > 0)
        assertEquals(0, live.port.heartbeats)

        evaluateTrafficLight()
        evaluateFileProtocol()
        evaluateLatchRegion()
        evaluateSwingRegion()
        val connTable = evaluateConnectionHierarchy()

        assertEquals(logSizeAfterInit, env.log.snapshot().size, "export must not write shared TransitionLog")
        assertEquals(metricsAfterInit, env.metrics.snapshot(), "export must not apply metrics")
        assertEquals(0, live.port.heartbeats, "export must not apply IncHeartbeat on a live context")

        val hb = connTable.rows.single { it.from == Idle.name && it.event == ConnEvent.Heartbeat.label() }
        assertTrue(hb.effects.contains("IncHeartbeat"))

        val pure = connectionStep(Idle, ConnEvent.Heartbeat)
        assertTrue(pure is HierarchyStep.Handled)
        assertEquals(0, live.port.heartbeats)
    }

    @Test
    fun `markdown table includes Effects column and every product row`() {
        val table = evaluateTrafficLight()
        val md = formatMarkdownTable(table)
        assertTrue(md.contains("| From | Event | To | Kind | Effects | Note |"))
        assertTrue(md.contains("TrafficLight"))
        assertTrue(md.contains("Audit.LogTransition") || md.contains("LogTransition"))
        assertTrue(md.contains("Metric") || md.contains("DriveLamp"))
        for (row in table.rows) {
            assertTrue(md.contains(row.from) && md.contains(row.event) && md.contains(row.to))
        }
    }

    @Test
    fun `exportAllDocument Effects column appears for multiple machines`() {
        val doc = exportAllDocument()
        assertTrue(doc.contains("| From | Event | To | Kind | Effects | Note |"))
        assertTrue(doc.contains("Audit.LogTransition") || doc.contains("LogTransition"))
        assertTrue(doc.contains("Metric") || doc.contains("IncHeartbeat") || doc.contains("Trace"))
        // TrafficLight always logs transitions; FileProtocol refusals use LogRefusal
        assertTrue(doc.contains("TrafficLight"))
        assertTrue(doc.contains("FileProtocol"))
        assertTrue(doc.contains("Connection"))
    }
}
