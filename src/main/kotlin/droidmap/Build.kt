package droidmap

import droidmap.extract.GradleScan
import droidmap.extract.HiltScan
import droidmap.extract.KoinScan
import droidmap.extract.ManifestScan
import droidmap.extract.NavScan
import droidmap.extract.RoomScan
import droidmap.extract.ViewModelUses
import droidmap.extract.WorkScan
import java.nio.file.Path

/** A graph with the project and the parsed code it was built from. */
class Built(val graph: Graph, val project: Project, val code: Code)

/**
 * Runs every extractor over a project. With [base] (a git commit), Room schema files are also compared
 * with that commit. Files unchanged since [previous] are not parsed again.
 */
fun buildGraph(p: Project, base: String? = null, previous: Code? = null): Built {
    val g = Graph()
    if (base != null) g.meta["base"] = base
    GradleScan(p, g).run() // first: the modules, which every file is assigned to
    val code = Code.read(p, previous)
    RoomScan(p, code, g, base).run()
    NavScan(p, code, g).run()
    HiltScan(code, g).run()
    KoinScan(code, g).run()
    WorkScan(code, g).run()
    ViewModelUses(code, g).run() // after the DI scans, which mark the ViewModels
    ManifestScan(p, code, g).run()
    g.meta["tool"] = "droidmap"
    g.meta["version"] = VERSION
    g.meta["files"] = linkedMapOf(
        "kotlin" to p.ktPaths.size, "java" to p.javaPaths.size, "gradle" to p.gradlePaths.size, "manifests" to p.manifestPaths.size,
        "navigation_xml" to p.navXmlPaths.size, "schema_json" to p.jsonPaths.size,
    )
    g.meta["skipped_large_files"] = p.skipped.sorted()
    requireKnownKinds(g)
    return Built(g, p, code)
}

fun buildGraph(root: Path, config: Config? = null, base: String? = null): Graph = buildGraph(Project(root, config), base).graph

fun stats(g: Graph): Map<String, Any?> = linkedMapOf(
    "nodes" to g.nodes.size, "edges" to g.edges.size, "unresolved" to g.unresolved.size,
    "node_kinds" to g.nodes.values.groupingBy { it.kind }.eachCount().toSortedMap(),
    "edge_kinds" to g.edges.groupingBy { it.kind }.eachCount().toSortedMap(),
)
