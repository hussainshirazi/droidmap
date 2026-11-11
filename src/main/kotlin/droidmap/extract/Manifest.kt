package droidmap.extract

import droidmap.Code
import droidmap.Graph
import droidmap.Project
import droidmap.sourceSetOf
import org.w3c.dom.Element
import java.util.TreeMap
import java.util.TreeSet

private val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider")
private val USES_PERMISSION = setOf("uses-permission", "uses-permission-sdk-23")
private val NAMESPACE = Regex("""\bnamespace\s*=?\s*["']([\w.]+)["']""")
private const val MERGED_DIR = "/build/intermediates/merged_manifests/"

fun permId(name: String) = "perm::$name"

/**
 * AndroidManifest.xml: permissions and components from the source manifests, and the permissions of
 * the merged manifest when a build has produced one (with the libraries that added them).
 */
class ManifestScan(private val p: Project, private val code: Code, private val g: Graph) {
    /** The module's android { namespace = "..." }, from its build file. */
    private fun namespace(module: String): String? =
        g.nodes[moduleId(module)]?.file?.let { NAMESPACE.find(p.read(it))?.groupValues?.get(1) }

    private fun permissions(root: Element): List<Element> = root.childElements().filter { it.localName in USES_PERMISSION }

    fun run() {
        for (rel in p.manifestPaths) {
            val root = readXml(p, g, rel, "manifest") ?: continue
            val module = p.moduleOf(rel) ?: continue
            if (module.startsWith("[")) continue
            val ss = sourceSetOf(rel)
            val mid = moduleId(module)
            val pkg = root.getAttribute("package").ifEmpty { null } ?: namespace(module)
            for (el in permissions(root)) {
                val name = el.attr(ANDROID_NS, "name") ?: continue
                g.addNode(permId(name), "permission", name.substringAfterLast('.'))
                g.addEdge(mid, permId(name), "uses_permission", rel, null, mapOf(
                    "source_set" to ss, "max_sdk" to el.attr(ANDROID_NS, "maxSdkVersion"), "removed" to flag(el.attr(TOOLS_NS, "node") == "remove")))
            }
            val app = root.childElements().firstOrNull { it.localName == "application" } ?: continue
            app.attr(ANDROID_NS, "name")?.let { n ->
                val fq = qualify(n, pkg)
                g.addNode(classId(fq), "component", fq.substringAfterLast('.'), module = module, attrs = mapOf("component" to "application"))
                g.addEdge(mid, classId(fq), "declares", rel, null, mapOf("source_set" to ss, "type" to "application"))
            }
            for (el in app.childElements()) {
                if (el.localName !in COMPONENTS) continue
                val name = el.attr(ANDROID_NS, "name") ?: continue
                if (el.attr(TOOLS_NS, "node") == "remove") continue
                val fq = qualify(name, pkg)
                val actions = el.childElements().filter { it.localName == "intent-filter" }
                    .flatMap { f -> f.childElements().filter { it.localName == "action" }.mapNotNull { it.attr(ANDROID_NS, "name") } }
                    .toSortedSet().toList()
                val decl = code.index.types[fq]
                g.addNode(classId(fq), "component", fq.substringAfterLast('.'), decl?.src?.path, decl?.line, module,
                    mapOf("component" to el.localName))
                g.addEdge(mid, classId(fq), "declares", rel, null, mapOf(
                    "source_set" to ss, "type" to el.localName, "exported" to el.attr(ANDROID_NS, "exported"),
                    "permission" to el.attr(ANDROID_NS, "permission"), "actions" to actions.ifEmpty { null }))
            }
        }
        merged()
    }

    /**
     * Permissions that only the merged manifest has: added by a library (or a library project's
     * manifest), which is how an app ends up with a permission nobody wrote. A permission the app itself
     * defines (`<permission>`, such as androidx's DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) is not one a user grants.
     */
    private fun merged() {
        val inSources = g.edgesOf("uses_permission").map { it.target }.toSet()
        val newestInput = (p.gradlePaths + p.tomlPaths + p.manifestPaths).mapNotNull { p.mtime(it) }.maxOrNull() ?: 0
        for ((module, files) in p.mergedManifests.groupBy { p.moduleOf(it) }) {
            if (module == null || module.startsWith("[")) continue
            val variants = TreeMap<String, TreeSet<String>>() // permission -> variants
            val addedBy = HashMap<String, Set<String>>()
            var stale = false
            for (rel in files) {
                val variant = "/$rel".substringAfter(MERGED_DIR).substringBefore('/')
                if ("test" in variant.lowercase()) continue
                val root = readXml(p, g, rel, "merged_manifest") ?: continue
                val own = root.childElements().filter { it.localName == "permission" }.mapNotNull { it.attr(ANDROID_NS, "name") }.toSet()
                for (el in permissions(root)) {
                    val name = el.attr(ANDROID_NS, "name")?.takeIf { it !in own } ?: continue
                    variants.getOrPut(name) { TreeSet() }.add(variant)
                }
                stale = stale || (p.mtime(rel) ?: 0) < newestInput
                addedBy.putAll(report("/$rel".substringBefore(MERGED_DIR).removePrefix("/"), variant))
            }
            val mid = moduleId(module)
            for ((name, vs) in variants) {
                if (permId(name) in inSources) continue // declared in a source manifest: reported there
                val by = addedBy[name] ?: emptySet()
                if ("" in by) continue // from a project module's manifest
                g.addNode(permId(name), "permission", name.substringAfterLast('.'))
                g.addEdge(mid, permId(name), "uses_permission", g.nodes[mid]?.file, null, mapOf(
                    "merged" to true, "variants" to vs.toList(), "added_by" to by.sorted().ifEmpty { null }, "stale" to flag(stale)))
            }
        }
    }

    /**
     * Who added each permission, from the manifest merger's report (build/outputs/logs/manifest-merger-<variant>-report.txt):
     * library coordinates, or "" for a manifest in the project.
     */
    private fun report(moduleDir: String, variant: String): Map<String, Set<String>> {
        val rel = reportFile(moduleDir, variant) ?: return emptyMap()
        val out = HashMap<String, MutableSet<String>>()
        var current: String? = null
        for (line in p.read(rel).lineSequence()) {
            if (line.isEmpty() || line[0].isWhitespace()) continue
            val from = line.substringAfter(" from ", "")
            when {
                USES_PERMISSION.any { line.startsWith("$it#") } -> current = line.substringAfter('#').trim()
                line.startsWith("ADDED from ") || line.startsWith("MERGED from ") -> current?.let { perm ->
                    out.getOrPut(perm) { LinkedHashSet() }.add(if (from.startsWith("[")) from.substring(1, from.indexOf(']').coerceAtLeast(1)) else "")
                }
                line.startsWith("REJECTED from ") || line.startsWith("INJECTED from ") -> {}
                else -> current = null
            }
        }
        return out
    }

    /** manifest-merger-debug-report.txt for debug, manifest-merger-demo-debug-report.txt for demoDebug. */
    private fun reportFile(moduleDir: String, variant: String): String? = p.mergerReports.firstOrNull {
        "/$it".startsWith("/$moduleDir/build/outputs/logs/".replace("//", "/")) &&
            it.substringAfterLast('/').removePrefix("manifest-merger-").removeSuffix("-report.txt").replace("-", "").equals(variant, ignoreCase = true)
    }

    private fun qualify(name: String, pkg: String?): String = when {
        name.startsWith(".") && pkg != null -> pkg + name
        '.' !in name && pkg != null -> "$pkg.$name"
        else -> name
    }
}
