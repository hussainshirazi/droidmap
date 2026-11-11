package droidmap.extract

import droidmap.Graph
import droidmap.Project
import droidmap.sourceSetOf

private val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider")
private val NAMESPACE = Regex("""\bnamespace\s*=?\s*["']([\w.]+)["']""")

fun permId(name: String) = "perm::$name"

/** AndroidManifest.xml (source manifests, not the merged one): permissions and components. */
class ManifestScan(private val p: Project, private val g: Graph) {
    private fun namespace(module: String): String? {
        val dir = p.modules[module] ?: return null
        for (name in listOf("build.gradle.kts", "build.gradle")) {
            val rel = if (dir.isEmpty()) name else "$dir/$name"
            if (rel in p.gradlePaths) NAMESPACE.find(p.read(rel))?.let { return it.groupValues[1] }
        }
        return null
    }

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
                val removed = el.attr(TOOLS_NS, "node") == "remove"
                g.addNode(permId(name), "permission", name.substringAfterLast('.'))
                g.addEdge(mid, permId(name), "uses_permission", rel, null, mapOf(
                    "source_set" to ss, "max_sdk" to el.attr(ANDROID_NS, "maxSdkVersion"), "removed" to (if (removed) true else null)))
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
                val decl = p.index.types[fq]
                val ds = decl?.let { p.index.sourceOf(it) }
                g.addNode(classId(fq), "component", fq.substringAfterLast('.'), ds?.path, decl?.let { ds?.line(it) }, module,
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
