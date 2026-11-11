package droidmap

fun findNodes(g: Graph, text: String, limit: Int = 20): List<Node> {
    g.nodes[text]?.let { return listOf(it) }
    val low = text.lowercase()
    val tiers = List(4) { ArrayList<Node>() }
    for (n in g.nodes.values) {
        if (n.kind == "ref") continue
        val id = n.id.lowercase()
        val label = n.label.lowercase()
        when {
            label == low -> tiers[0].add(n)
            id.endsWith(".$low") || id.endsWith("::$low") || id.endsWith(":$low") -> tiers[1].add(n)
            label.startsWith(low) -> tiers[2].add(n)
            low in id || low in label -> tiers[3].add(n)
        }
    }
    return tiers.firstOrNull { it.isNotEmpty() }?.sortedWith(compareBy({ it.kind }, { it.id }))?.take(limit) ?: emptyList()
}

/** One node for [text], or null with the candidates. */
fun resolveOne(g: Graph, text: String): Pair<Node?, List<Node>> {
    val hits = findNodes(g, text)
    if (hits.size == 1) return hits[0] to hits
    val exact = hits.filter { it.label == text }
    return (if (exact.size == 1) exact[0] else null) to hits
}

class EdgeView(val kind: String, val node: String, val label: String, val nodeKind: String?, val file: String?, val line: Int?, val attrs: Map<String, Any?>) {
    fun toMap() = linkedMapOf("kind" to kind, "node" to node, "label" to label, "node_kind" to nodeKind, "file" to file, "line" to line, "attrs" to attrs)
}

class Shown(val node: Node, val dependsOn: List<EdgeView>, val usedBy: List<EdgeView>) {
    fun toMap() = linkedMapOf("node" to Graph.nodeMap(node), "depends_on" to dependsOn.map { it.toMap() }, "used_by" to usedBy.map { it.toMap() })
}

fun show(g: Graph, id: String): Shown {
    fun view(e: Edge, other: String) = g.nodes[other].let { EdgeView(e.kind, other, it?.label ?: other, it?.kind, e.file, e.line, e.attrs) }
    return Shown(g.nodes.getValue(id), g.outEdges(id).map { view(it, it.target) }, g.inEdges(id).map { view(it, it.source) })
}

/**
 * A database "contains" every entity and DAO: it is affected by each of them, but what
 * depends on the database is not affected by one entity, so impact stops there.
 */
private val NON_TRANSITIVE = setOf("contains_entity", "contains_dao")

/** Structural links that are not "must change too" dependencies. */
private val NOT_IMPACT = setOf("migrates", "has_schema")

/** Hilt indirection (interface -> @Binds/@Provides function -> implementation) is one dependency in the code. */
private val FREE_HOPS = setOf("bound_by", "requires")

class Dependent(val id: String, val label: String, val kind: String, val depth: Int, val via: String, val through: String,
                val file: String?, val line: Int?, val module: String?) {
    fun toMap() = linkedMapOf("id" to id, "label" to label, "kind" to kind, "depth" to depth, "via" to via, "through" to through,
        "file" to file, "line" to line, "module" to module)
}

class Impact(val node: Node, val depth: Int, val dependents: List<Dependent>, val modules: List<String>,
             val moduleDependents: List<String>, val tests: List<Dependent>) {
    fun toMap() = linkedMapOf(
        "node" to linkedMapOf("id" to node.id, "label" to node.label, "kind" to node.kind, "file" to node.file, "line" to node.line, "module" to node.module),
        "depth" to depth, "dependents" to dependents.map { it.toMap() }, "modules" to modules,
        "module_dependents" to moduleDependents, "tests" to tests.map { it.toMap() })
}

/** Everything that (transitively) depends on [id], nearest first. */
fun impact(g: Graph, id: String, depth: Int = 4): Impact {
    val seen = HashMap<String, Pair<Int, Edge?>>()
    seen[id] = 0 to null
    val queue = ArrayDeque(listOf(id to 0))
    while (queue.isNotEmpty()) {
        val (x, d) = queue.removeFirst()
        if (d > seen[x]!!.first) continue
        for (e in g.inEdges(x)) {
            if (e.kind in NOT_IMPACT) continue
            val nd = d + if (e.kind in FREE_HOPS) 0 else 1
            if (nd > depth) continue
            val s = e.source
            val prev = seen[s]
            if (prev != null && prev.first <= nd) continue
            seen[s] = nd to e
            if (e.kind in NON_TRANSITIVE) continue
            if (nd == d) queue.addFirst(s to nd) else queue.addLast(s to nd)
        }
    }
    val dependents = seen.mapNotNull { (nid, v) ->
        val e = v.second ?: return@mapNotNull null
        val n = g.nodes.getValue(nid)
        Dependent(nid, n.label, n.kind, v.first, e.kind, e.target, n.file, n.line, n.module)
    }.sortedWith(compareBy({ it.depth }, { it.kind }, { it.id }))
    val root = g.nodes.getValue(id)
    val modules = (dependents.mapNotNull { it.module } + listOfNotNull(root.module)).toSortedSet().toList()
    val moduleDependents = ArrayList<String>()
    root.module?.let { m ->
        val start = "module::$m"
        val seenM = hashSetOf(start)
        val todo = ArrayDeque(listOf(start))
        while (todo.isNotEmpty()) for (e in g.inEdges(todo.removeLast())) {
            if (e.kind == "depends_on" && seenM.add(e.source)) todo.addLast(e.source)
        }
        moduleDependents.addAll(seenM.filter { it != start }.map { g.label(it) }.sorted())
    }
    return Impact(root, depth, dependents, modules, moduleDependents, dependents.filter { it.kind == "test" })
}

private fun loc(file: String?, line: Int?) = if (file == null) "no file" else if (line != null) "$file:$line" else file

fun formatImpact(r: Impact, limit: Int = 200): String {
    val n = r.node
    val lines = arrayListOf("Impact of ${n.label} [${n.kind}] (${loc(n.file, n.line)})", "")
    if (r.dependents.isEmpty()) {
        lines.add("Nothing in the graph depends on it.")
    } else {
        lines.add("${r.dependents.size} dependents (depth <= ${r.depth}):")
        val viaText = mapOf("bound_by" to "provided by a binding that needs it", "requires" to "a binding that needs it")
        for (x in r.dependents.take(limit)) {
            val indent = "  ".repeat(maxOf(0, x.depth - 1))
            lines.add("  $indent${x.label} [${x.kind}] ${viaText[x.via] ?: "via ${x.via}"}  ${loc(x.file, x.line)}")
        }
        if (r.dependents.size > limit) lines.add("  ... ${r.dependents.size - limit} more")
    }
    if (r.tests.isNotEmpty()) lines += listOf("", "Tests that cover it: " + r.tests.joinToString(", ") { it.label })
    if (r.modules.isNotEmpty()) lines += listOf("", "Modules with impacted code: " + r.modules.joinToString(", "))
    if (r.moduleDependents.isNotEmpty()) lines.add("Modules that depend on ${n.module}: " + r.moduleDependents.joinToString(", "))
    return lines.joinToString("\n")
}

private fun pyRepr(v: Any?): String = when (v) {
    is String -> v
    is List<*> -> v.joinToString(", ", "[", "]") { if (it is String) "'$it'" else pyRepr(it) }
    is Boolean -> if (v) "True" else "False"
    else -> v.toString()
}

fun formatShow(s: Shown): String {
    val n = s.node
    val lines = arrayListOf("${n.label} [${n.kind}]  ${n.id}", "  at ${loc(n.file, n.line)}")
    val attrs = n.attrs.filter { (k, v) -> k != "roles" && v != null && v != emptyList<Any>() && v != emptyMap<String, Any>() }.toSortedMap()
    if (n.roles.size > 1) attrs["roles"] = n.roles
    for ((k, v) in attrs) lines.add("  $k: ${pyRepr(v)}")
    for ((title, list) in listOf("Depends on" to s.dependsOn, "Used by" to s.usedBy)) {
        if (list.isEmpty()) continue
        lines.add("$title:")
        for (x in list.sortedWith(compareBy({ it.kind }, { it.label }))) {
            val extra = x.attrs.toSortedMap().filter { (_, v) -> v != null && v != "" && v != emptyList<Any>() }
                .entries.joinToString("") { " ${it.key}=${pyRepr(it.value)}" }
            lines.add("  ${x.kind.padEnd(16)} ${x.label} [${x.nodeKind}]$extra  ${loc(x.file, x.line)}")
        }
    }
    return lines.joinToString("\n")
}
