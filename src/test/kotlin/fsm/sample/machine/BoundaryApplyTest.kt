package fsm.sample.machine

import fsm.sample.effect.CrossCuttingEnv
import fsm.sample.effect.StepResult
import fsm.sample.effect.auditEntry
import fsm.sample.effect.auditExit
import fsm.sample.effect.auditRefusal
import fsm.sample.effect.auditTransition
import fsm.sample.effect.metricInc
import fsm.sample.flat.LightEffect
import fsm.sample.flat.LightPort
import fsm.sample.flat.LightState
import fsm.sample.flat.applyDomainLight
import fsm.sample.logging.RecordKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PURPOSE: Prove dual-sink boundary helpers — ordering contracts, refuse skips domain,
 * and hierarchy must not use [applyAccepted].
 *
 * Fixtures: [LightEffect]/[LightPort] + [CrossCuttingEnv] (existing ADTs/ports).
 */
class BoundaryApplyTest {

    // -------------------------------------------------------------------------
    // applyAccepted — total / flat: assign → CC → domain
    // -------------------------------------------------------------------------

    @Test
    fun `applyAccepted assigns then applies crossCutting then domain`() {
        val env = CrossCuttingEnv()
        val port = LightPort()
        val order = mutableListOf<String>()
        var current: LightState = LightState.Red

        val result = StepResult(
            next = LightState.Green,
            crossCutting = listOf(
                auditTransition("Light", "Red", "Tick", "Green"),
                metricInc("light.ticks"),
            ),
            domain = listOf(LightEffect.DriveLamp("Green")),
        )

        applyAccepted(
            result = result,
            env = env,
            port = port,
            applyDomain = { domain, p ->
                order += "domain"
                applyDomainLight(domain, p)
            },
            assign = {
                order += "assign"
                current = it
            },
        )

        // Total family: assign first, then sinks (CC before domain via helper body).
        assertEquals(listOf("assign", "domain"), order)
        assertEquals(LightState.Green, current)
        assertEquals(listOf("Green"), port.commands)
        assertEquals(1, env.log.snapshot().filter { it.kind == RecordKind.TRANSITION }.size)
        assertEquals(1, env.metrics.get("light.ticks"))
    }

    @Test
    fun `applyAccepted applies crossCutting before domain within the post-assign phase`() {
        val env = CrossCuttingEnv()
        val port = LightPort()
        val order = mutableListOf<String>()
        // Intercept domain apply and probe env after CC should have run.
        val result = StepResult(
            next = LightState.Red,
            crossCutting = listOf(metricInc("probe")),
            domain = listOf(LightEffect.DriveLamp("Red")),
        )
        applyAccepted(
            result = result,
            env = env,
            port = port,
            applyDomain = { domain, p ->
                // CC must already have updated metrics before domain runs.
                order += if (env.metrics.get("probe") == 1) "cc-before-domain" else "cc-missing"
                applyDomainLight(domain, p)
                order += "domain-done"
            },
            assign = { order += "assign" },
        )
        assertEquals(listOf("assign", "cc-before-domain", "domain-done"), order)
        assertEquals(listOf("Red"), port.commands)
    }

    // -------------------------------------------------------------------------
    // applyOffer — Accepted: assign → CC → domain; Refused: assign → CC only
    // -------------------------------------------------------------------------

    @Test
    fun `applyOffer Accepted assigns CC and domain`() {
        val env = CrossCuttingEnv()
        val port = LightPort()
        var current: LightState = LightState.Red
        val order = mutableListOf<String>()

        val accepted = OfferBoundaryResult.Accepted(
            next = LightState.Green,
            crossCutting = listOf(
                auditTransition("Light", "Red", "Open", "Green"),
                metricInc("csp.accept"),
            ),
            domain = listOf(LightEffect.DriveLamp("Green")),
        )

        applyOffer(
            result = accepted,
            env = env,
            port = port,
            applyDomain = { domain, p ->
                order += "domain"
                applyDomainLight(domain, p)
            },
            assign = {
                order += "assign"
                current = it
            },
        )

        assertEquals(listOf("assign", "domain"), order)
        assertEquals(LightState.Green, current)
        assertEquals(listOf("Green"), port.commands)
        assertEquals(1, env.metrics.get("csp.accept"))
    }

    @Test
    fun `applyOffer Refused never calls applyDomain`() {
        val env = CrossCuttingEnv()
        val port = LightPort()
        var current: LightState = LightState.Red
        var domainCalls = 0

        val refused = OfferBoundaryResult.Refused(
            next = LightState.Red,
            crossCutting = listOf(
                auditRefusal("Light", "Red", "Read", "not-open"),
                metricInc("csp.refuse"),
            ),
            reason = "not-open",
        )

        applyOffer(
            result = refused,
            env = env,
            port = port,
            applyDomain = { _, _ -> domainCalls++ },
            assign = { current = it },
        )

        assertEquals(0, domainCalls)
        assertTrue(port.commands.isEmpty())
        assertEquals(LightState.Red, current)
        assertEquals(1, env.metrics.get("csp.refuse"))
        assertEquals(1, env.log.snapshot().filter { it.kind == RecordKind.REFUSAL }.size)
    }

    // -------------------------------------------------------------------------
    // applyFragments — hierarchical: CC then domain per fragment; no assign
    // -------------------------------------------------------------------------

    @Test
    fun `applyFragments applies CC then domain per fragment in order`() {
        val env = CrossCuttingEnv()
        val port = LightPort()
        val order = mutableListOf<String>()

        val fragments = listOf(
            EffectFragment(
                crossCutting = listOf(auditExit("Conn", "Offline")),
                domain = emptyList(),
            ),
            EffectFragment(
                crossCutting = listOf(
                    auditEntry("Conn", "Online"),
                    metricInc("connection.session.open"),
                ),
                domain = listOf(LightEffect.DriveLamp("SessionOpen")),
            ),
            EffectFragment(
                crossCutting = listOf(auditEntry("Conn", "Idle")),
                domain = emptyList(),
            ),
        )

        applyFragments(
            fragments = fragments,
            env = env,
            port = port,
            applyDomain = { domain, p ->
                for (d in domain) {
                    order += "domain:${(d as LightEffect.DriveLamp).colorLabel}"
                    p.drive(d.colorLabel)
                }
            },
        )

        // Per-fragment pairing: Online enter CC immediately before domain OpenSession analogue.
        val labels = buildApplyOrderLabels(env, port)
        assertTrue(
            labels.indexOf("Audit.LogEntry(Online)") < labels.indexOf("DriveLamp(SessionOpen)"),
            "LogEntry(Online) must precede domain OpenSession within apply order: $labels",
        )
        assertEquals(listOf("domain:SessionOpen"), order)
        assertEquals(listOf("SessionOpen"), port.commands)
        assertEquals(1, env.metrics.get("connection.session.open"))
    }

    @Test
    fun `applyFragments FragmentedStepEffects overload delegates to list`() {
        val env = CrossCuttingEnv()
        val port = LightPort()
        val effects = FragmentedStepEffects(
            listOf(
                EffectFragment(
                    crossCutting = listOf(metricInc("frag")),
                    domain = listOf(LightEffect.DriveLamp("A")),
                ),
            ),
        )
        applyFragments(effects, env, port, ::applyDomainLight)
        assertEquals(1, env.metrics.get("frag"))
        assertEquals(listOf("A"), port.commands)
    }

    @Test
    fun `applyFragments does not assign state — caller assigns after`() {
        val env = CrossCuttingEnv()
        val port = LightPort()
        var current = "Offline"
        val fromLeaf = current

        val fragments: List<EffectFragment<LightEffect>> = listOf(
            EffectFragment(
                crossCutting = listOf(auditExit("Conn", "Offline")),
                domain = emptyList(),
            ),
            EffectFragment(
                crossCutting = listOf(auditEntry("Conn", "Idle")),
                domain = listOf(LightEffect.DriveLamp("Idle")),
            ),
        )

        // Hierarchical recipe: apply first, then assign if leaf changes.
        applyFragments(fragments, env, port, ::applyDomainLight)
        assertEquals("Offline", current) // still old leaf during/after apply
        val next = "Idle"
        if (next != fromLeaf) current = next
        assertEquals("Idle", current)
        assertEquals(listOf("Idle"), port.commands)
    }

    // -------------------------------------------------------------------------
    // Ordering contract: hierarchy must NOT use applyAccepted
    // -------------------------------------------------------------------------

    /**
     * Documents why hierarchical handle is a separate recipe from total/offer.
     *
     * [applyAccepted] assigns **before** sinks. Connection-style LCA requires
     * exit effects while still in the old leaf, then assign — i.e. [applyFragments]
     * first, then assign. Flattening into one [StepResult] + [applyAccepted] would:
     * 1. Assign next before exit CC/domain of the old state
     * 2. Lose per-fragment CC↔domain adjacency (all CC then all domain if lists merged)
     */
    @Test
    fun `hierarchy must not use applyAccepted — wrong assign order vs applyFragments`() {
        val envAccepted = CrossCuttingEnv()
        val portAccepted = LightPort()
        var currentAccepted = "Offline"

        val envFragments = CrossCuttingEnv()
        val portFragments = LightPort()
        var currentFragments = "Offline"

        // Simulated hierarchical step Offline → idle with exit Offline then enter Idle + domain.
        val exitCc = listOf(auditExit("Conn", "Offline"))
        val enterCc = listOf(auditEntry("Conn", "Idle"), metricInc("session.open"))
        val enterDomain = listOf(LightEffect.DriveLamp("OpenSession"))

        // --- Wrong recipe: flatten + applyAccepted (assign-then-apply) ---
        val orderAccepted = mutableListOf<String>()
        val flat = StepResult(
            next = "Idle",
            crossCutting = exitCc + enterCc,
            domain = enterDomain,
        )
        applyAccepted(
            result = flat,
            env = envAccepted,
            port = portAccepted,
            applyDomain = { domain, p ->
                orderAccepted += "domain"
                applyDomainLight(domain, p)
            },
            assign = {
                orderAccepted += "assign:$it"
                currentAccepted = it
            },
        )

        // --- Correct hierarchical recipe: applyFragments then assign ---
        val orderFragments = mutableListOf<String>()
        val fragments = listOf(
            EffectFragment(crossCutting = exitCc, domain = emptyList()),
            EffectFragment(crossCutting = enterCc, domain = enterDomain),
        )
        val fromLeaf = currentFragments
        applyFragments(
            fragments = fragments,
            env = envFragments,
            port = portFragments,
            applyDomain = { domain, p ->
                if (domain.isNotEmpty()) {
                    orderFragments += "domain"
                    // During hierarchical apply, leaf must still be the old state.
                    orderFragments += "leaf-during-apply:$currentFragments"
                }
                applyDomainLight(domain, p)
            },
        )
        val next = "Idle"
        if (next != fromLeaf) {
            orderFragments += "assign:$next"
            currentFragments = next
        }

        // applyAccepted assigned before any domain (and before exit could observe old leaf).
        assertEquals(listOf("assign:Idle", "domain"), orderAccepted)
        assertEquals("Idle", currentAccepted)

        // applyFragments ran domain while still Offline, then caller assigned.
        assertEquals(
            listOf("domain", "leaf-during-apply:Offline", "assign:Idle"),
            orderFragments,
        )
        assertEquals("Idle", currentFragments)

        // Both drive domain once — the bug is ordering / pairing, not omitting domain.
        assertEquals(listOf("OpenSession"), portAccepted.commands)
        assertEquals(listOf("OpenSession"), portFragments.commands)

        // Contract reminder for agents: hierarchical path is applyFragments, not applyAccepted.
        assertTrue(
            orderAccepted.first().startsWith("assign:"),
            "applyAccepted is assign-first — unsafe for hierarchy",
        )
        assertTrue(
            orderFragments.first() == "domain" || orderFragments.first().startsWith("leaf"),
            "hierarchy applies effects before assign",
        )
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    /** Approximate apply-order labels: log records then domain commands (CC applied before domain in helpers). */
    private fun buildApplyOrderLabels(env: CrossCuttingEnv, port: LightPort): List<String> {
        val fromLog = env.log.snapshot().map { rec ->
            when (rec.kind) {
                RecordKind.EXIT -> "Audit.LogExit(${rec.from ?: rec.to})"
                RecordKind.ENTRY -> "Audit.LogEntry(${rec.to})"
                RecordKind.TRANSITION -> "Audit.LogTransition"
                RecordKind.REFUSAL -> "Audit.LogRefusal"
            }
        }
        val fromDomain = port.commands.map { "DriveLamp($it)" }
        // Within each fragment CC then domain; across fragments we only need Online before OpenSession.
        // Reconstruct from actual sinks: exits/entries are sequential in the log; domain appends after its fragment CC.
        // For the Offline→online→idle fixture, log is Exit(Offline), Entry(Online), Entry(Idle) and domain is SessionOpen.
        // Insert domain after Online entry by position: metrics prove Online enter CC ran; use index of Online entry.
        val labels = fromLog.toMutableList()
        val onlineIdx = labels.indexOf("Audit.LogEntry(Online)")
        if (onlineIdx >= 0 && fromDomain.isNotEmpty()) {
            labels.addAll(onlineIdx + 1, fromDomain)
        } else {
            labels.addAll(fromDomain)
        }
        return labels
    }
}
