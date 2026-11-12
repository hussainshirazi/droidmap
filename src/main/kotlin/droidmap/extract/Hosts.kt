package droidmap.extract

import droidmap.Call
import droidmap.Code
import droidmap.Graph
import droidmap.Project
import droidmap.Type
import droidmap.has
import org.w3c.dom.Element

private val R_LAYOUT = Regex("""\bR\.layout\.(\w+)""")
private val R_NAVIGATION = Regex("""\bR\.navigation\.(\w+)""")
private val BINDING = Regex("""\b([A-Z]\w*)Binding\s*\.\s*inflate\b""")
private val TYPE_NAME = Regex("""\b[A-Z]\w*\b""")
private const val NAV_HOST = "androidx.navigation.fragment.NavHostFragment"
private val FRAGMENT_TAGS = setOf("fragment", "androidx.fragment.app.FragmentContainerView")

/**
 * Which activities host which fragments: the navigation graph of a NavHostFragment in the activity's layout
 * (or included layouts) or set in its code (`R.navigation.x`), a `<fragment>` or FragmentContainerView in the
 * layout, and fragment transactions (`add`, `replace`) in its code. Records, as meta `hilt_fragment_hosts`, each
 * @AndroidEntryPoint fragment hosted by an activity that is not one: Hilt throws when that fragment is attached.
 */
class FragmentHosts(private val p: Project, private val code: Code, private val g: Graph) {
    private val index = code.index

    private class Layout(val rel: String, val graphs: List<String>, val fragments: List<String>, val includes: List<String>)
    private class NavGraph(val fragments: List<String>, val includes: List<String>)

    fun run() {
        val types = code.sources.filter { !it.isTest }.flatMap { it.types }
        if (types.none { it.anns.has("AndroidEntryPoint") }) return
        val layouts = readLayouts()
        val graphs = readGraphs()
        for (a in types) {
            if (!superNames(index, a).any { "Activity" in it } || entryPoint(a)) continue
            val text = a.psi.text
            val hosted = LinkedHashMap<String, String>() // fragment fq -> how the activity hosts it
            val graphVia = LinkedHashMap<String, String>()
            val layoutNames = (R_LAYOUT.findAll(text).map { it.groupValues[1] } + BINDING.findAll(text).map { snake(it.groupValues[1]) }).toSet()
            for (lay in layoutNames.flatMap { closure(it, layouts) }) {
                val file = lay.rel.substringAfterLast('/')
                lay.graphs.forEach { graphVia.putIfAbsent(it, "the navigation graph $it in $file") }
                lay.fragments.forEach { hosted.putIfAbsent(it, file) }
            }
            R_NAVIGATION.findAll(text).forEach { graphVia.putIfAbsent(it.groupValues[1], "the navigation graph ${it.groupValues[1]}") }
            for ((graph, via) in graphVia) for (f in fragments(graph, graphs)) hosted.putIfAbsent(f, via)
            for (c in a.src.calls) {
                if (c.name != "add" && c.name != "replace" || !c.isInside(a.psi)) continue
                for (f in transactionFragments(c)) hosted.putIfAbsent(f.fq, "a fragment transaction (line ${c.line})")
            }
            for ((fq, via) in hosted) {
                val f = index.types[fq] ?: continue
                if (!entryPoint(f) || superNames(index, f).none { "Fragment" in it }) continue
                g.addMeta("hilt_fragment_hosts", mapOf("activity" to a.fq, "fragment" to f.fq, "via" to via, "file" to a.src.path, "line" to a.line))
            }
        }
    }

    /** @AndroidEntryPoint on the class or a project superclass (a subclass of a Hilt activity is one). */
    private fun entryPoint(t: Type) = lineage(index, t).any { it.anns.has("AndroidEntryPoint") }

    /** Project fragment classes named in a transaction's arguments: `XFragment()`, `XFragment.newInstance()`, `XFragment::class.java`, `replace<XFragment>()`. */
    private fun transactionFragments(c: Call): List<Type> {
        val names = c.args.flatMap { a -> TYPE_NAME.findAll(a.text).map { it.value }.toList() } + c.typeArgs.map { it.name }
        return names.distinct().mapNotNull { index.resolveType(it, c.src, c.psi) }.filter { t -> superNames(index, t).any { "Fragment" in it } }
    }

    private fun readLayouts(): Map<String, List<Layout>> {
        val out = HashMap<String, MutableList<Layout>>()
        for (rel in p.layoutXmlPaths) {
            val root = readXml(p, g, rel, "layout_xml") ?: continue
            val graphs = ArrayList<String>()
            val fragments = ArrayList<String>()
            val includes = ArrayList<String>()
            for (el in elements(root)) {
                val tag = el.tagName
                if (tag == "include") el.getAttribute("layout").takeIf { it.startsWith("@layout/") }?.let { includes.add(it.substringAfter('/')) }
                if (tag !in FRAGMENT_TAGS) continue
                val cls = el.attr(ANDROID_NS, "name") ?: el.getAttribute("class").ifEmpty { null } ?: continue
                val graph = el.attr(APP_NS, "navGraph")?.substringAfter('/')
                if (cls == NAV_HOST || graph != null) graph?.let { graphs.add(it) } else if (!cls.startsWith(".")) fragments.add(cls)
            }
            out.getOrPut(navGraphName(rel)) { ArrayList() }.add(Layout(rel, graphs, fragments, includes))
        }
        return out
    }

    private fun readGraphs(): Map<String, NavGraph> {
        val out = HashMap<String, NavGraph>()
        for (rel in p.navXmlPaths) {
            val root = readXml(p, g, rel, "nav_xml") ?: continue
            val fragments = ArrayList<String>()
            val includes = ArrayList<String>()
            for (el in elements(root)) {
                when (el.localName ?: el.tagName) {
                    "fragment", "dialog" -> el.attr(ANDROID_NS, "name")?.let { fragments.add(it) }
                    "include" -> el.attr(APP_NS, "graph")?.let { includes.add(it.substringAfter('/')) }
                }
            }
            out[navGraphName(rel)] = NavGraph(fragments, includes)
        }
        return out
    }

    /** A layout and the layouts it includes, transitively. */
    private fun closure(name: String, layouts: Map<String, List<Layout>>): List<Layout> {
        val seen = LinkedHashSet<String>()
        val todo = ArrayDeque(listOf(name))
        val out = ArrayList<Layout>()
        while (todo.isNotEmpty()) {
            val n = todo.removeFirst()
            if (!seen.add(n)) continue
            for (l in layouts[n] ?: emptyList()) { out.add(l); todo.addAll(l.includes) }
        }
        return out
    }

    /** The fragment destinations of a navigation graph and the graphs it includes. */
    private fun fragments(name: String, graphs: Map<String, NavGraph>): List<String> {
        val seen = LinkedHashSet<String>()
        val todo = ArrayDeque(listOf(name))
        val out = ArrayList<String>()
        while (todo.isNotEmpty()) {
            val n = todo.removeFirst()
            if (!seen.add(n)) continue
            graphs[n]?.let { out.addAll(it.fragments); todo.addAll(it.includes) }
        }
        return out
    }

    private fun elements(root: Element): List<Element> {
        val out = ArrayList<Element>()
        val todo = ArrayDeque(listOf(root))
        while (todo.isNotEmpty()) {
            val e = todo.removeFirst()
            out.add(e)
            todo.addAll(e.childElements())
        }
        return out
    }

    /** ActivityMain -> activity_main: the layout a view binding class is generated from. */
    private fun snake(binding: String) = binding.replace(Regex("(?<=[a-z0-9])([A-Z])"), "_$1").lowercase()
}
