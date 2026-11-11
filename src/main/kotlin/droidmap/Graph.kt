package droidmap

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

const val SCHEMA = "droidmap/1"

/**
 * When one node plays several roles (a class that is a Room entity and a nav route), its
 * kind is the highest-ranked role; all roles stay in attrs["roles"].
 */
val KIND_RANK = mapOf(
    "ref" to 0, "class" to 1, "function" to 2, "injectable" to 3, "nav_route" to 4, "component" to 5,
    "hilt_provider" to 6, "koin_definition" to 6, "hilt_module" to 7, "koin_module" to 7, "entry_point" to 8, "screen" to 9, "viewmodel" to 10,
    "room_entity" to 11, "room_dao" to 12, "room_database" to 13,
)

/** Every node kind (the README lists them; "ref" is a placeholder for a node only an edge names). */
val NODE_KINDS = KIND_RANK.keys + setOf("module", "room_migration", "room_schema", "test", "nav_destination", "permission")

/** Every edge kind; an edge means "source depends on target". */
val EDGE_KINDS = setOf(
    "depends_on", "contains_entity", "contains_dao", "queries", "writes", "has_schema", "migrates", "tests", "registers", "shows",
    "navigates_to", "starts_at", "uses_viewmodel", "injects", "bound_by", "requires", "declared_in", "uses_permission", "declares",
)

/** Fails on a node or edge kind that is not in NODE_KINDS or EDGE_KINDS: a typo in an extractor. */
fun requireKnownKinds(g: Graph) {
    g.nodes.values.flatMap { it.roles + it.kind }.firstOrNull { it !in NODE_KINDS }?.let { error("unknown node kind $it") }
    g.edges.firstOrNull { it.kind !in EDGE_KINDS }?.let { error("unknown edge kind ${it.kind}") }
}

class Node(
    val id: String,
    var kind: String,
    var label: String,
    var file: String?,
    var line: Int?,
    var module: String?,
    val attrs: MutableMap<String, Any?>,
) {
    @Suppress("UNCHECKED_CAST")
    val roles: MutableList<String>
        get() = attrs.getOrPut("roles") { mutableListOf<String>() } as MutableList<String>

    fun hasRole(vararg kinds: String) = kind in kinds || roles.any { it in kinds }
}

class Edge(
    val source: String,
    val target: String,
    val kind: String,
    val file: String?,
    val line: Int?,
    val attrs: Map<String, Any?>,
)

class Unresolved(val kind: String, val text: String, val file: String?, val line: Int?, val reason: String)

/** Typed nodes and edges. An edge always means "source depends on target". */
class Graph {
    val nodes = LinkedHashMap<String, Node>()
    val edges = ArrayList<Edge>()
    val unresolved = ArrayList<Unresolved>()
    val meta = LinkedHashMap<String, Any?>()
    private val edgeKeys = HashSet<String>()
    private var inIdx: Map<String, List<Edge>>? = null
    private var outIdx: Map<String, List<Edge>>? = null

    fun addNode(
        id: String, kind: String, label: String? = null, file: String? = null, line: Int? = null,
        module: String? = null, attrs: Map<String, Any?> = emptyMap(),
    ): String {
        val n = nodes[id]
        if (n == null) {
            val node = Node(id, kind, label ?: id, file, line, module, linkedMapOf("roles" to mutableListOf(kind)))
            attrs.forEach { (k, v) -> if (v != null) node.attrs[k] = v }
            nodes[id] = node
        } else {
            if (n.kind == "ref" && kind != "ref") {
                n.roles.remove("ref")
                if (label != null) n.label = label
            }
            if (kind !in n.roles) {
                n.roles.add(kind)
                n.roles.sortByDescending { KIND_RANK[it] ?: 0 }
            }
            if ((KIND_RANK[kind] ?: 0) > (KIND_RANK[n.kind] ?: 0)) n.kind = kind
            if (n.kind == "ref" || n.file == null) {
                if (file != null) n.file = file
                if (line != null) n.line = line
            }
            if (n.module == null && module != null) n.module = module
            if (label != null && n.label == id) n.label = label
            attrs.forEach { (k, v) -> if (v != null && k !in n.attrs) n.attrs[k] = v }
        }
        inIdx = null; outIdx = null
        return id
    }

    fun addEdge(
        source: String, target: String, kind: String, file: String? = null, line: Int? = null,
        attrs: Map<String, Any?> = emptyMap(),
    ) {
        if (source == target) return
        val clean = LinkedHashMap<String, Any?>().apply { attrs.forEach { (k, v) -> if (v != null) put(k, v) } }
        val key = "$source\u0000$target\u0000$kind\u0000${canonical(clean)}"
        if (!edgeKeys.add(key)) return
        for (end in listOf(source, target)) if (end !in nodes) addNode(end, "ref", end.substringAfter("::"))
        edges.add(Edge(source, target, kind, file, line, clean))
        inIdx = null; outIdx = null
    }

    fun addUnresolved(kind: String, text: String, file: String?, line: Int?, reason: String) {
        unresolved.add(Unresolved(kind, text, file, line, reason))
    }

    private fun index() {
        inIdx = edges.groupBy { it.target }
        outIdx = edges.groupBy { it.source }
    }

    fun inEdges(id: String): List<Edge> {
        if (inIdx == null) index()
        return inIdx!![id] ?: emptyList()
    }

    fun outEdges(id: String): List<Edge> {
        if (outIdx == null) index()
        return outIdx!![id] ?: emptyList()
    }

    fun byKind(vararg kinds: String): List<Node> = nodes.values.filter { it.hasRole(*kinds) }
    fun edgesOf(kind: String): List<Edge> = edges.filter { it.kind == kind }
    fun label(id: String): String = nodes[id]?.label ?: id

    // ----- JSON -----
    private fun sortedEdges() =
        edges.sortedWith(compareBy<Edge>({ it.source }, { it.target }, { it.kind }, { canonical(it.attrs) }))

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "schema" to SCHEMA,
        "meta" to meta,
        "nodes" to nodes.values.sortedBy { it.id }.map { nodeMap(it) },
        "edges" to sortedEdges().map {
            linkedMapOf(
                "source" to it.source, "target" to it.target, "kind" to it.kind, "file" to it.file,
                "line" to it.line, "attrs" to it.attrs,
            )
        },
        "unresolved" to unresolved
            .sortedWith(compareBy<Unresolved>({ it.kind }, { it.file ?: "" }, { it.line ?: 0 }, { it.text }))
            .map { linkedMapOf("kind" to it.kind, "text" to it.text, "file" to it.file, "line" to it.line, "reason" to it.reason) },
    )

    fun save(path: Path) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(path, jsonText(toMap()) + "\n")
    }

    /** networkx node-link JSON (the shape graphify's graph.json uses). */
    fun toNodeLink(): Map<String, Any?> = linkedMapOf(
        "directed" to true, "multigraph" to false, "graph" to mapOf("generator" to "droidmap"),
        "nodes" to nodes.values.sortedBy { it.id }.map {
            linkedMapOf(
                "id" to it.id, "label" to it.label, "kind" to it.kind, "file_type" to "code",
                "source_file" to (it.file ?: ""), "source_location" to (it.line?.let { l -> "L$l" } ?: ""),
                "module" to (it.module ?: ""),
            )
        },
        "links" to sortedEdges().map {
            linkedMapOf(
                "source" to it.source, "target" to it.target, "relation" to it.kind,
                "source_file" to (it.file ?: ""), "source_location" to (it.line?.let { l -> "L$l" } ?: ""),
            )
        },
    )

    companion object {
        fun nodeMap(n: Node): Map<String, Any?> = linkedMapOf(
            "id" to n.id, "kind" to n.kind, "label" to n.label, "file" to n.file, "line" to n.line,
            "module" to n.module, "attrs" to n.attrs,
        )

        @Suppress("UNCHECKED_CAST")
        fun load(path: Path): Graph {
            val root = compactJson.parseToJsonElement(Files.readString(path)).jsonObject
            val schema = root["schema"]?.jsonPrimitive?.content
            require(schema == SCHEMA) { "not a $SCHEMA graph (schema=$schema)" }
            val g = Graph()
            (fromJson(root["meta"]) as? Map<String, Any?>)?.let { g.meta.putAll(it) }
            for (e in root["nodes"]?.jsonArray ?: emptyList()) {
                val m = fromJson(e) as Map<String, Any?>
                val attrs = LinkedHashMap((m["attrs"] as? Map<String, Any?>) ?: emptyMap())
                attrs["roles"] = (attrs["roles"] as? List<*>)?.map { it.toString() }?.toMutableList() ?: mutableListOf<String>()
                val id = m.str("id")!!
                g.nodes[id] = Node(id, m.str("kind")!!, m.str("label")!!, m.str("file"), m.int("line"), m.str("module"), attrs)
            }
            for (e in root["edges"]?.jsonArray ?: emptyList()) {
                val m = fromJson(e) as Map<String, Any?>
                g.addEdge(
                    m.str("source")!!, m.str("target")!!, m.str("kind")!!, m.str("file"), m.int("line"),
                    (m["attrs"] as? Map<String, Any?>) ?: emptyMap(),
                )
            }
            for (e in root["unresolved"]?.jsonArray ?: emptyList()) {
                val m = fromJson(e) as Map<String, Any?>
                g.addUnresolved(m.str("kind") ?: "", m.str("text") ?: "", m.str("file"), m.int("line"), m.str("reason") ?: "")
            }
            return g
        }
    }
}
