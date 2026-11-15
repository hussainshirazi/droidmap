package droidmap.extract

import droidmap.Code
import droidmap.Graph
import droidmap.Node
import droidmap.Type
import droidmap.WILD
import droidmap.arg
import droidmap.bool
import droidmap.strings
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression

/** SavedStateHandle calls that read a key, and those that put one there (then it is the ViewModel's own state, not an argument). */
private val READS = setOf("get", "getLiveData", "getStateFlow", "contains")
private val WRITES = setOf("set", "remove", "getMutableStateFlow", "setSavedStateProvider")

/**
 * Navigation arguments a ViewModel reads that its destinations do not have. With Navigation, a ViewModel's
 * SavedStateHandle holds the arguments of the destination it was made for, by name: `savedStateHandle["topicId"]`
 * is null when that destination calls its argument `id`. Checked for ViewModels whose every user is the screen of a
 * destination whose arguments droidmap knows (a string route, a type-safe route class or an XML graph), and that
 * nothing gets for an activity or a navigation graph instead. Recorded as meta nav_argument_missing.
 */
class NavArgs(private val code: Code, private val g: Graph) {
    fun run() {
        for (vm in g.byKind("viewmodel")) {
            val t = code.index.types[vm.id.removePrefix("class::")] ?: continue
            val uses = g.inEdges(vm.id).filter { it.kind == "uses_viewmodel" }
            if (uses.isEmpty() || uses.any { it.attrs.bool("shared") }) continue
            val hosts = hosts(uses.map { it.source }) ?: continue
            val (reads, writes) = keys(t)
            if (reads.isEmpty()) continue
            val declared = hosts.flatMap { it.attrs.strings("arguments") }.toSortedSet()
            for ((key, line) in reads) {
                if (key in declared || key in writes) continue
                g.addMeta("nav_argument_missing", linkedMapOf("viewmodel" to t.fq, "key" to key,
                    "destinations" to hosts.map { it.label }.distinct().sorted(), "arguments" to declared.toList(), "file" to t.src.path, "line" to line))
            }
        }
    }

    /** The destinations that show each user, when every user is shown by destinations with known arguments; null otherwise. */
    private fun hosts(users: List<String>): List<Node>? {
        val out = ArrayList<Node>()
        for (u in users.distinct()) {
            val dests = g.inEdges(u).filter { it.kind == "shows" }.mapNotNull { g.nodes[it.source] }.filter { it.kind == "nav_destination" }
            if (dests.isEmpty() || dests.any { it.attrs["arguments"] == null }) return null
            out += dests
        }
        return out
    }

    /** The constant keys [t] reads from its SavedStateHandle (with the line of the first read), and those it writes. */
    private fun keys(t: Type): Pair<Map<String, Int>, Set<String>> {
        val handles = (t.ctors.flatMap { it.params }.filter { it.type?.simple == "SavedStateHandle" }.mapNotNull { it.name } +
            t.fields.filter { it.type?.simple == "SavedStateHandle" }.mapNotNull { it.name }).toSet()
        if (handles.isEmpty()) return emptyMap<String, Int>() to emptySet()
        val reads = LinkedHashMap<String, Int>()
        val writes = HashSet<String>()
        fun key(e: org.jetbrains.kotlin.com.intellij.psi.PsiElement?): String? = (code.eval.eval(e) as? String)?.takeIf { WILD !in it }
        for (c in t.src.calls) {
            if (!c.isInside(t.psi) || c.receiverName?.removePrefix("this.") !in handles) continue
            val k = key((c.args.arg("key") ?: c.args.arg(null, 0))?.expr) ?: continue
            when (c.name) {
                in READS -> reads.putIfAbsent(k, c.line)
                in WRITES -> writes.add(k)
            }
        }
        // Kotlin's savedStateHandle["key"] (a read) and savedStateHandle["key"] = value (a write).
        for (a in PsiTreeUtil.findChildrenOfType(t.psi, KtArrayAccessExpression::class.java)) {
            if (a.arrayExpression?.text?.removePrefix("this.") !in handles) continue
            val k = key(a.indexExpressions.singleOrNull()) ?: continue
            val parent = a.parent as? KtBinaryExpression
            if (parent != null && parent.operationToken == KtTokens.EQ && parent.left == a) writes.add(k) else reads.putIfAbsent(k, t.src.line(a))
        }
        return reads to writes
    }
}
