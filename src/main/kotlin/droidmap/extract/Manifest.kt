package droidmap.extract

import droidmap.Code
import droidmap.Graph
import droidmap.Project
import droidmap.sourceSetOf

private val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider")
private val NAMESPACE = Regex("""\bnamespace\s*=?\s*["']([\w.]+)["']""")

fun permId(name: String) = "perm::$name"

/** AndroidManifest.xml (source manifests, not the merged one): permissions and components. */
class ManifestScan(private val p: Project, private val code: Code, private val g: Graph) {
    /** The module's android { namespace = "..." }, from its build file. */
    private fun namespace(module: String): String? =
        g.nodes[moduleId(module)]?.file?.let { NAMESPACE.find(p.read(it))?.groupValues?.get(1) }

    fun run() {
        for (rel in p.manifestPaths) {
            val root = readXml(p, g, rel, "manifest") ?: continue
            val module = p.moduleOf(rel) ?: continue
            if (module.startsWith("[")) continue
            val ss = sourceSetOf(rel)
            val mid = moduleId(module)
            val pkg = root.getAttribute("package").ifEmpty { null } ?: namespace(module)
            for (el in root.childElements()) {
                if (el.localName !in setOf("uses-permission", "uses-permission-sdk-23")) continue
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
    }

    private fun qualify(name: String, pkg: String?): String = when {
        name.startsWith(".") && pkg != null -> pkg + name
        '.' !in name && pkg != null -> "$pkg.$name"
        else -> name
    }
}
