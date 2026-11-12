package droidmap

import droidmap.extract.moduleId

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

/** A node that depends on the impacted one: it [via]s (an edge kind) [through], which is nearer to it. */
class Dependent(val id: String, val label: String, val kind: String, val depth: Int, val via: String, val through: String,
                val throughLabel: String, val file: String?, val line: Int?, val module: String?) {
    fun toMap() = linkedMapOf("id" to id, "label" to label, "kind" to kind, "depth" to depth, "via" to via, "through" to through,
        "through_label" to throughLabel, "file" to file, "line" to line, "module" to module)
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
        Dependent(nid, n.label, n.kind, v.first, e.kind, e.target, g.label(e.target), n.file, n.line, n.module)
    }.sortedWith(compareBy({ it.depth }, { it.kind }, { it.id }))
    val root = g.nodes.getValue(id)
    val modules = (dependents.mapNotNull { it.module } + listOfNotNull(root.module)).toSortedSet().toList()
    val moduleDependents = ArrayList<String>()
    root.module?.let { m ->
        val start = moduleId(m)
        val seenM = hashSetOf(start)
        val todo = ArrayDeque(listOf(start))
        while (todo.isNotEmpty()) for (e in g.inEdges(todo.removeLast())) {
            if (e.kind == "depends_on" && seenM.add(e.source)) todo.addLast(e.source)
        }
        moduleDependents.addAll(seenM.filter { it != start }.map { g.label(it) }.sorted())
    }
    return Impact(root, depth, dependents, modules, moduleDependents, dependents.filter { it.kind == "test" })
}

/** "file:line", "file", or [none] when there is no file. */
fun location(file: String?, line: Int?, none: String = ""): String = if (file == null) none else if (line != null) "$file:$line" else file

private fun loc(file: String?, line: Int?) = location(file, line, none = "no file")

/** One line per node, as find prints them; the id is added only for a name that more than one node has. */
fun formatFind(hits: List<Node>, style: Style = Style.PLAIN): String {
    if (hits.isEmpty()) return ""
    val ambiguous = hits.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys
    val width = hits.maxOf { it.label.length }
    val kindWidth = hits.maxOf { it.kind.length }
    return hits.joinToString("\n") {
        val id = if (it.label in ambiguous) "  ${it.id}" else ""
        "${style.bold(it.label.padEnd(width))}  ${it.kind.padEnd(kindWidth)}  ${style.dim(location(it.file, it.line))}$id"
    }
}

fun formatImpact(r: Impact, limit: Int = 200, style: Style = Style.PLAIN): String {
    val n = r.node
    val lines = arrayListOf("${style.bold(n.label)} ${n.kind}  ${style.dim(loc(n.file, n.line))}", "")
    if (r.dependents.isEmpty()) {
        lines.add("Nothing in the graph depends on it.")
    } else {
        val shown = r.dependents.take(limit)
        lines.add("${r.dependents.size} ${plural(r.dependents.size, "dependent")}, nearest first (depth <= ${r.depth}):")
        val labelWidth = shown.maxOf { it.label.length }
        val kindWidth = shown.maxOf { it.kind.length }
        for (x in shown) {
            lines.add("  ${x.depth}  ${style.bold(x.label.padEnd(labelWidth))}  ${x.kind.padEnd(kindWidth)}  ${x.via} ${x.throughLabel}  " +
                style.dim(loc(x.file, x.line)))
        }
        if (r.dependents.size > limit) lines.add("  ... ${r.dependents.size - limit} more")
    }
    if (r.tests.isNotEmpty()) lines += listOf("", "Tests that cover it: " + r.tests.joinToString(", ") { it.label })
    if (r.modules.isNotEmpty()) lines += listOf("", "Modules with impacted code: " + r.modules.joinToString(", "))
    if (r.moduleDependents.isNotEmpty()) lines.add("Modules that depend on ${n.module}: " + r.moduleDependents.joinToString(", "))
    return lines.joinToString("\n")
}

/** An attribute value as text: a string as it is, a list as its items, anything else as compact JSON. */
private fun plain(v: Any?): String = when (v) {
    is String -> v
    is List<*> -> v.joinToString(", ") { plain(it) }
    else -> jsonText(v, pretty = false)
}

/** Attributes that only matter to the tools reading graph.json. */
private val INTERNAL_ATTRS = setOf("roles", "end_line", "source_set")

fun formatShow(s: Shown, style: Style = Style.PLAIN): String {
    val n = s.node
    val lines = arrayListOf("${style.bold(n.label)} ${n.kind}  ${style.dim(loc(n.file, n.line))}")
    val attrs = n.attrs.filter { (k, v) -> k !in INTERNAL_ATTRS && v != null && v != emptyList<Any>() && v != emptyMap<String, Any>() }.toSortedMap()
    val also = n.roles.filter { it != n.kind }
    if (also.isNotEmpty()) attrs["also"] = also
    for ((k, v) in attrs) lines.add("  $k: ${plain(v)}")
    for ((title, list) in listOf("Depends on" to s.dependsOn, "Used by" to s.usedBy)) {
        if (list.isEmpty()) continue
        lines.add("")
        lines.add("$title:")
        val sorted = list.sortedWith(compareBy({ it.kind }, { it.label }))
        val width = sorted.maxOf { it.label.length }
        for (x in sorted) {
            val extra = x.attrs.toSortedMap().filter { (_, v) -> v != null && v != "" && v != emptyList<Any>() }
                .entries.joinToString("") { " ${it.key}=${plain(it.value)}" }
            lines.add("  ${x.kind.padEnd(16)} ${style.bold(x.label.padEnd(width))}  ${x.nodeKind}$extra  ${style.dim(loc(x.file, x.line))}")
        }
    }
    return lines.joinToString("\n")
}
