package droidmap.extract

import droidmap.Call
import droidmap.Code
import droidmap.Edge
import droidmap.Graph
import droidmap.Index
import droidmap.Project
import droidmap.Type
import droidmap.classRefs
import droidmap.str

/** The kind of Android component a class is, by its supertypes: "activity", "service" or "receiver"; null for anything else. */
fun componentKind(index: Index, t: Type): String? {
    val supers = superNames(index, t)
    return when {
        supers.any { it.endsWith("Activity") } -> "activity"
        supers.any { it.endsWith("Service") } -> "service"
        supers.any { it.endsWith("Receiver") || it == "AppWidgetProvider" } -> "receiver"
        else -> null
    }
}

private val NAMING_CALLS = setOf("Intent", "setClass", "ComponentName")

/**
 * The project component an Intent is sent to by its class: `Intent(context, X::class.java)` (Java's `new Intent(this, X.class)`),
 * `intent.setClass(context, X::class.java)` or `ComponentName(context, X::class.java)`. Null for anything else.
 */
fun explicitComponent(c: Call, index: Index): Type? {
    if (c.name !in NAMING_CALLS || (c.name != "setClass" && c.receiver != null && !c.isNew)) return null
    val (name, at) = c.args.flatMap { classRefs(it.psi) }.singleOrNull() ?: return null
    return index.resolveType(name, c.src, at)?.takeIf { componentKind(index, it) != null }
}

/**
 * Activities, services and receivers that app code sends an explicit Intent to, but that no AndroidManifest.xml in the
 * project declares: Android cannot find them (ActivityNotFoundException for an activity; a service or a receiver silently
 * does not run). Recorded as meta component_not_declared; runs after ManifestScan.
 */
class UndeclaredComponents(private val p: Project, private val code: Code, private val g: Graph) {
    fun run() {
        if (p.manifestPaths.isEmpty()) return
        val declared = g.edgesOf("declares")
        val sites = LinkedHashMap<Type, MutableList<Call>>()
        for (s in code.sources) {
            if (s.isTest) continue
            for (c in s.calls) explicitComponent(c, code.index)?.takeIf { !it.src.isTest }?.let { sites.getOrPut(it) { ArrayList() }.add(c) }
        }
        for ((t, calls) in sites) {
            if (declared.any { names(it, t) }) continue
            val first = calls.minWith(compareBy({ it.src.path }, { it.line }))
            g.addMeta("component_not_declared", linkedMapOf("class" to t.fq, "kind" to componentKind(code.index, t), "uses" to calls.size,
                "file" to first.src.path, "line" to first.line))
        }
    }

    /** Whether a manifest entry names [t]: by its full name, or by a relative one (`.ui.DetailActivity`) whose package was not known. */
    private fun names(e: Edge, t: Type): Boolean {
        if (e.target == classId(t.fq)) return true
        val name = e.attrs.str("name") ?: return false
        return (name.startsWith(".") && t.fq.endsWith(name)) || ('.' !in name && t.name == name)
    }
}
