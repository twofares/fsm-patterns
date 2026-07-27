package fsm.sample.machine

import fsm.sample.effect.CrossCuttingEffect
import fsm.sample.effect.acceptTransitionCrossCutting

/**
 * PURPOSE: Shared hierarchical structure helpers — path/LCA assembly and parent-bubble
 * dispatch via type parameters + lambdas (no [HierarchicalState] interface).
 *
 * **One hierarchy API:** call these functions directly from vignettes (e.g. Connection).
 * Do not re-export thin free-function wrappers that only bind lambdas.
 *
 * Vignette-local policy stays outside: Connection keeps [declaredEffects], onEvent,
 * enter/exit domain payloads, and Heartbeat/same_leaf metrics.
 *
 * Boundary is **apply-then-assign** via [applyFragments] — never [applyAccepted].
 * See README "Boundary families" and `docs/authoring-guide.md` hierarchical recipe.
 */

/**
 * PURPOSE: Build root → leaf path for hierarchical entry/exit ordering.
 * Pure; no context or interpreter.
 */
fun <S> pathFromRoot(leaf: S, parentOf: (S) -> S?): List<S> {
    val chain = mutableListOf<S>()
    var n: S? = leaf
    while (n != null) {
        chain.add(0, n)
        n = parentOf(n)
    }
    return chain
}

/**
 * PURPOSE: Length of shared superstate prefix (LCA index into root→leaf paths).
 * Defaults to identity equality (`===`) so sealed state objects compare by reference.
 */
fun <S> commonPrefixLength(
    a: List<S>,
    b: List<S>,
    eq: (S, S) -> Boolean = { x, y -> x === y },
): Int {
    val n = minOf(a.size, b.size)
    var i = 0
    while (i < n && eq(a[i], b[i])) i++
    return i
}

/**
 * PURPOSE: Pure assembly of hierarchical transition effects (LCA discipline).
 *
 * Fragment order: exit (innermost→outermost, exclusive of LCA) →
 * transition audit (via [acceptTransitionCrossCutting] + optional [transitionExtras]) →
 * [handlerDomain] → enter (outermost→innermost, exclusive of LCA).
 *
 * [transitionExtras] are first-class cross-cutting effects on the **leaf-change**
 * transition fragment (metrics/trace that belong with the transition audit).
 * Same-leaf reactions (e.g. Heartbeat) stay vignette-local in `declaredEffects`
 * and do not use this parameter.
 *
 * Does **not** encode machine-specific same-leaf / heartbeat / refuse policy —
 * callers compose those in vignette-local `declaredEffects`.
 */
fun <S, D> planTransition(
    from: S,
    to: S,
    eventLabel: String,
    handlerDomain: List<D> = emptyList(),
    machineName: String,
    nameOf: (S) -> String,
    parentOf: (S) -> S?,
    enterEffects: (S, String) -> FragmentedStepEffects<D>,
    exitEffects: (S, String) -> FragmentedStepEffects<D>,
    transitionExtras: List<CrossCuttingEffect> = emptyList(),
): FragmentedStepEffects<D> {
    val fromPath = pathFromRoot(from, parentOf)
    val toPath = pathFromRoot(to, parentOf)
    val lcaIndex = commonPrefixLength(fromPath, toPath)
    var assembled = FragmentedStepEffects<D>()

    // Exit states exclusive of LCA, from leaf toward root.
    for (i in fromPath.lastIndex downTo lcaIndex) {
        assembled += exitEffects(fromPath[i], machineName)
    }

    assembled += FragmentedStepEffects.of(
        crossCutting = acceptTransitionCrossCutting(
            machine = machineName,
            from = nameOf(from),
            event = eventLabel,
            to = nameOf(to),
            extras = transitionExtras,
        ),
    )

    // Handler / transition-action domain effects between exit and enter.
    assembled += FragmentedStepEffects.of(domain = handlerDomain)

    // Enter states exclusive of LCA, from root toward leaf.
    for (i in lcaIndex until toPath.size) {
        assembled += enterEffects(toPath[i], machineName)
    }

    return assembled
}

/**
 * PURPOSE: Hierarchical event dispatch — walk [start] → parent until handled.
 *
 * Pure control flow; no effect application. [R] is the vignette handle-result type
 * (e.g. Connection's [fsm.sample.gof.HandleResult]). Callers supply [onEvent],
 * [parentOf], [isUnhandled], and the [unhandled] sentinel.
 *
 * [currentLeaf] is the leaf active at dispatch start (defaults to [start]).
 */
fun <S, E, R> dispatchWithDelegation(
    start: S,
    event: E,
    currentLeaf: S = start,
    parentOf: (S) -> S?,
    onEvent: (node: S, event: E, currentLeaf: S) -> R,
    isUnhandled: (R) -> Boolean,
    unhandled: R,
): R {
    var node: S? = start
    while (node != null) {
        val r = onEvent(node, event, currentLeaf)
        if (!isUnhandled(r)) return r
        node = parentOf(node)
    }
    return unhandled
}
