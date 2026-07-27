package fsm.sample.gof

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.TimerIntent
import fsm.sample.logging.RecordKind
import fsm.sample.machine.TimerPort
import fsm.sample.machine.VirtualClock
import fsm.sample.machine.applyFragments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Drive shipped [ConnectionContext] for GoF state replacement,
 * hierarchical parent delegation, entry/exit ordering, Online watchdog, and refusal.
 */
class ConnectionMachineTest {

    @Test
    fun `connect enters Online then Idle with entry records`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        // Initial Offline was entered in init
        assertEquals(Offline, ctx.current)
        assertTrue(ctx.handle(ConnEvent.Connect))
        assertEquals(Idle, ctx.current)

        val entries = env.log.snapshot().filter { it.kind == RecordKind.ENTRY }
        // Offline (init) + Online + Idle on connect
        assertTrue(entries.any { it.to == "Offline" })
        assertTrue(entries.any { it.to == "Online" })
        assertTrue(entries.any { it.to == "Idle" })
    }

    /**
     * Fragment pairing gate: Connect Offline→Idle keeps `Audit.LogEntry(Online)` paired with
     * domain `OpenSession` then `Timer.Arm` in **both** pure [exportLabels] order and boundary apply.
     * Online-session metric/trace may sit between LogEntry and OpenSession; Idle entry must not.
     */
    @Test
    fun `Connect Offline to Idle keeps LogEntry Online immediately before OpenSession in labels and apply`() {
        // --- Pure export labels / fragment pairing ---
        val from: ConnState = Offline
        val result = dispatchConnection(from, ConnEvent.Connect)
        assertTrue(result is HandleResult.Handled)
        val effects = declaredEffects(Offline, ConnEvent.Connect, result)
        val labels = effects.exportLabels()
        val labelOnline = labels.indexOfFirst { it == "Audit.LogEntry(Online)" }
        val labelOpen = labels.indexOfFirst { it == "OpenSession" }
        val labelIdle = labels.indexOfFirst { it == "Audit.LogEntry(Idle)" }
        assertTrue(labelOnline >= 0, "missing Audit.LogEntry(Online) in $labels")
        assertTrue(labelOpen >= 0, "missing OpenSession in $labels")
        assertTrue(labelOpen > labelOnline, "OpenSession must follow LogEntry(Online); labels=$labels")
        val between = labels.subList(labelOnline + 1, labelOpen)
        assertTrue(
            between.none { it.startsWith("Audit.LogEntry") || it.startsWith("Audit.LogExit") },
            "foreign audit between LogEntry(Online) and OpenSession: $between in $labels",
        )
        assertTrue(labelIdle > labelOpen, "LogEntry(Idle) must follow OpenSession; labels=$labels")
        val onlineFrag = effects.fragments.single { ConnectionEffect.OpenSession in it.domain }
        assertTrue(onlineFrag.crossCutting.any { it.exportLabel() == "Audit.LogEntry(Online)" })
        assertEquals(
            listOf(
                ConnectionEffect.OpenSession,
                ConnectionEffect.Timer(
                    TimerIntent.Arm(
                        id = CONNECTION_WATCHDOG_ID,
                        delayMs = CONNECTION_WATCHDOG_MS,
                        eventKey = ConnEvent.WatchdogTimeout.label(),
                    ),
                ),
            ),
            onlineFrag.domain,
        )
        assertTrue(labels.any { it.startsWith("Timer.Arm($CONNECTION_WATCHDOG_ID,") })

        // --- Boundary apply order: Online entry audit before OpenSession domain ---
        val env = CrossCuttingEnv()
        val port = ConnectionPort()
        val timers = TimerPort(VirtualClock())
        val applyOrder = mutableListOf<String>()
        applyFragments(
            effects = effects,
            env = env,
            port = port,
            applyDomain = { domain, p ->
                val onlineEntered = env.log.snapshot().any {
                    it.kind == RecordKind.ENTRY && it.to == "Online"
                }
                val idleEntered = env.log.snapshot().any {
                    it.kind == RecordKind.ENTRY && it.to == "Idle"
                }
                for (d in domain) {
                    when (d) {
                        ConnectionEffect.OpenSession -> {
                            applyOrder += when {
                                !onlineEntered -> "OpenSession-before-online-cc"
                                idleEntered -> "OpenSession-after-idle-entry"
                                else -> "cc-online-then-OpenSession"
                            }
                            p.record(SessionOp.Opened)
                        }
                        else -> applyDomainConnection(listOf(d), p, timers)
                    }
                }
            },
        )
        assertEquals(
            listOf("cc-online-then-OpenSession"),
            applyOrder,
            "applyFragments must run Online enter CC before OpenSession and before Idle entry",
        )
        assertEquals(listOf(SessionOp.Opened), port.sessions)
        assertEquals(1, timers.pending().size)
        assertEquals(CONNECTION_WATCHDOG_ID, timers.pending().single().id)

        // Live handle path must match the same pairing (applyFragments, not applyAccepted).
        val live = ConnectionContext()
        live.handle(ConnEvent.Connect)
        assertEquals(listOf(SessionOp.Opened), live.port.sessions)
        assertEquals(1, live.timers.pending().size)
        val liveLog = live.crossCutting.log.snapshot()
        val liveOnline = liveLog.indexOfFirst { it.kind == RecordKind.ENTRY && it.to == "Online" }
        assertTrue(liveOnline >= 0)
        assertEquals(Idle, live.current)
    }

    @Test
    fun `Online exit domain is Cancel then CloseSession`() {
        val effects = Online.exitEffects("Connection")
        assertEquals(
            listOf(
                ConnectionEffect.Timer(TimerIntent.Cancel(CONNECTION_WATCHDOG_ID)),
                ConnectionEffect.CloseSession,
            ),
            effects.domain,
        )
        val labels = effects.exportLabels()
        val cancelIdx = labels.indexOfFirst { it == "Timer.Cancel($CONNECTION_WATCHDOG_ID)" }
        val closeIdx = labels.indexOfFirst { it == "CloseSession" }
        assertTrue(cancelIdx >= 0, "missing Timer.Cancel in $labels")
        assertTrue(closeIdx > cancelIdx, "CloseSession must follow Cancel; labels=$labels")
    }

    @Test
    fun `Connect arms watchdog and Disconnect leaves pending empty`() {
        val ctx = ConnectionContext()
        assertTrue(ctx.timers.pending().isEmpty(), "no watchdog before Online enter")
        ctx.handle(ConnEvent.Connect)
        assertEquals(1, ctx.timers.pending().size)
        assertEquals(CONNECTION_WATCHDOG_ID, ctx.timers.pending().single().id)
        assertEquals(ConnEvent.WatchdogTimeout.label(), ctx.timers.pending().single().eventKey)
        assertEquals(CONNECTION_WATCHDOG_MS, ctx.timers.pending().single().dueAtMs - ctx.clock.nowMs())

        assertTrue(ctx.handle(ConnEvent.Disconnect))
        assertEquals(Offline, ctx.current)
        assertTrue(ctx.timers.pending().isEmpty(), "Online exit must cancel watchdog")
        assertEquals(listOf(SessionOp.Opened, SessionOp.Closed), ctx.port.sessions)
    }

    @Test
    fun `Idle Transferring round-trip keeps watchdog armed under Online LCA`() {
        val ctx = ConnectionContext()
        ctx.handle(ConnEvent.Connect)
        val pendingAfterConnect = ctx.timers.pending()
        assertEquals(1, pendingAfterConnect.size)

        assertTrue(ctx.handle(ConnEvent.StartTransfer))
        assertEquals(Transferring, ctx.current)
        assertEquals(pendingAfterConnect, ctx.timers.pending(), "Idle→Transferring must not re-arm or cancel")

        assertTrue(ctx.handle(ConnEvent.FinishTransfer))
        assertEquals(Idle, ctx.current)
        assertEquals(pendingAfterConnect, ctx.timers.pending(), "Transferring→Idle must not re-arm or cancel")
        assertEquals(listOf(SessionOp.Opened), ctx.port.sessions)
    }

    @Test
    fun `Heartbeat does not re-arm watchdog`() {
        val ctx = ConnectionContext()
        ctx.handle(ConnEvent.Connect)
        val dueAtFromEnter = ctx.timers.pending().single().dueAtMs
        // Advance so a mistaken re-Arm(CONNECTION_WATCHDOG_MS) would shift dueAtMs.
        ctx.clock.advance(1_000L)
        assertTrue(ctx.handle(ConnEvent.Heartbeat))
        assertEquals(Idle, ctx.current)
        assertEquals(1, ctx.timers.pending().size)
        assertEquals(
            dueAtFromEnter,
            ctx.timers.pending().single().dueAtMs,
            "absolute deadline from Online enter; re-arm would yield now+${CONNECTION_WATCHDOG_MS}=${ctx.clock.nowMs() + CONNECTION_WATCHDOG_MS}",
        )
        assertEquals(1, ctx.port.heartbeats)

        when (val handled = Online.onEvent(ConnEvent.Heartbeat, Idle)) {
            is HandleResult.Handled -> {
                assertEquals(listOf(ConnectionEffect.IncHeartbeat), handled.domain)
                assertTrue(handled.domain.none { it is ConnectionEffect.Timer })
            }
            is HandleResult.Unhandled -> error("expected Heartbeat handled by Online")
        }
    }

    @Test
    fun `WatchdogTimeout goes Offline and closes session with empty pending`() {
        val ctx = ConnectionContext()
        ctx.handle(ConnEvent.Connect)
        assertEquals(Idle, ctx.current)

        assertTrue(ctx.handle(ConnEvent.WatchdogTimeout))
        assertEquals(Offline, ctx.current)
        assertTrue(ctx.timers.pending().isEmpty())
        assertEquals(listOf(SessionOp.Opened, SessionOp.Closed), ctx.port.sessions)
    }

    @Test
    fun `fireDueTimeouts maps WatchdogTimeout and goes Offline`() {
        val ctx = ConnectionContext()
        ctx.handle(ConnEvent.Connect)
        ctx.clock.advance(CONNECTION_WATCHDOG_MS)
        val fired = ctx.fireDueTimeouts()
        assertEquals(listOf(ConnEvent.WatchdogTimeout), fired)
        assertEquals(Offline, ctx.current)
        assertTrue(ctx.timers.pending().isEmpty())
        assertEquals(listOf(SessionOp.Opened, SessionOp.Closed), ctx.port.sessions)
    }

    @Test
    fun `connEventFromTimerKey maps WatchdogTimeout label only`() {
        assertEquals(ConnEvent.WatchdogTimeout, connEventFromTimerKey(ConnEvent.WatchdogTimeout.label()))
        assertEquals(null, connEventFromTimerKey("DwellElapsed"))
        assertEquals(null, connEventFromTimerKey(""))
    }

    @Test
    fun `heartbeat is handled by Online superstate without leaving Idle`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        ctx.handle(ConnEvent.Connect)
        assertEquals(Idle, ctx.current)
        assertEquals(0, ctx.port.heartbeats)

        assertTrue(ctx.handle(ConnEvent.Heartbeat))
        assertEquals(Idle, ctx.current)
        assertEquals(1, ctx.port.heartbeats)

        val transitions = env.log.snapshot().filter {
            it.kind == RecordKind.TRANSITION && it.event == "Heartbeat"
        }
        assertEquals(1, transitions.size)
        assertEquals("Idle", transitions[0].from)
        assertEquals("Idle", transitions[0].to)
    }

    @Test
    fun `disconnect from Transferring uses hierarchical Online handler`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        ctx.handle(ConnEvent.Connect)
        ctx.handle(ConnEvent.StartTransfer)
        assertEquals(Transferring, ctx.current)

        assertTrue(ctx.handle(ConnEvent.Disconnect))
        assertEquals(Offline, ctx.current)

        val exits = env.log.snapshot().filter { it.kind == RecordKind.EXIT }.map { it.from }
        // Leave Transferring then Online before Offline entry path
        assertTrue(exits.contains("Transferring"))
        assertTrue(exits.contains("Online"))
        assertTrue(ctx.timers.pending().isEmpty())
    }

    @Test
    fun `entry exit order is outer to inner on enter and reverse on exit`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        ctx.handle(ConnEvent.Connect) // Offline exit, Online enter, Idle enter

        val afterConnect = env.log.snapshot()
        val offlineExitIdx = afterConnect.indexOfFirst {
            it.kind == RecordKind.EXIT && it.from == "Offline"
        }
        val onlineEntryIdx = afterConnect.indexOfFirst {
            it.kind == RecordKind.ENTRY && it.to == "Online" &&
                afterConnect.indexOf(it) > offlineExitIdx
        }
        val idleEntryIdx = afterConnect.indexOfFirst {
            it.kind == RecordKind.ENTRY && it.to == "Idle" &&
                afterConnect.indexOf(it) > onlineEntryIdx
        }
        assertTrue(offlineExitIdx >= 0)
        assertTrue(onlineEntryIdx > offlineExitIdx)
        assertTrue(idleEntryIdx > onlineEntryIdx)

        // StartTransfer: stay under Online — only Idle exit, Transferring enter
        env.log.clear()
        // Re-seed: need a fresh context for clean exit order check on disconnect
        val env2 = CrossCuttingEnv()
        val ctx2 = ConnectionContext(crossCutting = env2)
        ctx2.handle(ConnEvent.Connect)
        ctx2.handle(ConnEvent.StartTransfer)
        env2.log.clear()
        ctx2.handle(ConnEvent.Disconnect)

        val snap = env2.log.snapshot()
        val exitTransferring = snap.indexOfFirst { it.kind == RecordKind.EXIT && it.from == "Transferring" }
        val exitOnline = snap.indexOfFirst { it.kind == RecordKind.EXIT && it.from == "Online" }
        val enterOffline = snap.indexOfFirst { it.kind == RecordKind.ENTRY && it.to == "Offline" }
        assertTrue(exitTransferring >= 0)
        assertTrue(exitOnline > exitTransferring) // leaf exits before superstate
        assertTrue(enterOffline > exitOnline)
    }

    @Test
    fun `illegal event at Offline is refused and state unchanged`() {
        val env = CrossCuttingEnv()
        val ctx = ConnectionContext(crossCutting = env)
        assertFalse(ctx.handle(ConnEvent.StartTransfer))
        assertEquals(Offline, ctx.current)
        assertTrue(env.log.snapshot().any { it.kind == RecordKind.REFUSAL })
    }
}
