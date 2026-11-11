package droidmap

import droidmap.extract.GradleScan
import droidmap.extract.HiltScan
import droidmap.extract.ManifestScan
import droidmap.extract.NavScan
import droidmap.extract.RoomScan
import java.nio.file.Path

const val VERSION = "0.1.0"

/** Runs every extractor over a project; with [base] (a git commit), Room schema files are also compared with that commit. */
fun buildGraph(root: Path, config: Config? = null, base: String? = null): Pair<Graph, Project> {
    val p = Project(root, config)
    val g = Graph()
    if (base != null) g.meta["base"] = base
    GradleScan(p, g).run()
    p.parseKotlin()
    RoomScan(p, g, base).run()
    NavScan(p, g).run()
    HiltScan(p, g).run()
    ManifestScan(p, g).run()
    g.meta["tool"] = "droidmap"
    g.meta["version"] = VERSION
    g.meta["files"] = linkedMapOf(
        "kotlin" to p.ktPaths.size, "gradle" to p.gradlePaths.size, "manifests" to p.manifestPaths.size,
        "navigation_xml" to p.navXmlPaths.size, "schema_json" to p.jsonPaths.size,
    )
    g.meta["skipped_large_files"] = p.skipped.sorted()
    return g to p
}

fun stats(g: Graph): Map<String, Any?> = linkedMapOf(
    "nodes" to g.nodes.size, "edges" to g.edges.size, "unresolved" to g.unresolved.size,
    "node_kinds" to g.nodes.values.groupingBy { it.kind }.eachCount().toSortedMap(),
    "edge_kinds" to g.edges.groupingBy { it.kind }.eachCount().toSortedMap(),
)
