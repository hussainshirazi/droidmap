package droidmap.extract

import droidmap.Arg
import droidmap.Call
import droidmap.Code
import droidmap.Fn
import droidmap.Graph
import droidmap.Project
import droidmap.Source
import droidmap.WILD
import droidmap.arg
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

private val DEST_CALLS = setOf("composable", "dialog", "navigation", "bottomSheet", "activity", "entry")
private val START_ARGS = setOf("startDestination", "startKey", "startRoute")
private val BACKSTACK_STARTS = setOf("rememberNavBackStack", "NavBackStack")
/** XML elements with an android:id that are not destinations. */
private val NOT_DESTINATIONS = setOf("action", "argument", "deepLink", "include")
private val R_ID = Regex("""^(?:[\w.]+\.)?R\.id\.(\w+)$""")
const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
const val APP_NS = "http://schemas.android.com/apk/res-auto"
const val TOOLS_NS = "http://schemas.android.com/tools"

fun destId(key: String) = "nav.dest::$key"

/** (path segments with "*" for arguments, query keys). */
fun routeSig(route: String): Pair<List<String>, Set<String>> {
    val path = route.substringBefore('?')
    val query = route.substringAfter('?', "")
    val segs = path.split('/').map { if ('{' in it || WILD in it) "*" else it }
    val keys = query.split('&').map { it.substringBefore('=') }.filter { it.isNotEmpty() && WILD !in it }.toSet()
    return segs to keys
}

fun routeMatches(dest: String, nav: String): Boolean {
    val (ds, dk) = routeSig(dest)
    val (ns, nk) = routeSig(nav)
    if (ds.size != ns.size) return false
    if (ds.zip(ns).any { (a, b) -> a != "*" && b != "*" && a != b }) return false
    return dk.containsAll(nk)
}

fun display(route: String) = route.replace(WILD, '*')

/** Parses an XML file, or records it as unresolved and returns null. */
fun readXml(p: Project, g: Graph, rel: String, kind: String): Element? = try {
    secureXml(p.read(rel))
} catch (e: Exception) {
    g.addUnresolved(kind, rel, rel, null, "skipped: ${e.message?.take(80) ?: "invalid XML"}")
    null
}

/** XML parsing with DOCTYPE (and so entity expansion) refused. */
fun secureXml(text: String): Element? {
    val f = DocumentBuilderFactory.newInstance()
    f.isNamespaceAware = true
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    f.setFeature("http://xml.org/sax/features/external-general-entities", false)
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    f.isXIncludeAware = false
    f.isExpandEntityReferences = false
    val b = f.newDocumentBuilder()
    b.setErrorHandler(null)
    return b.parse(InputSource(StringReader(text))).documentElement
}

fun Element.childElements(): List<Element> {
    val out = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { out.add(it) }
    return out
}

fun Element.attr(ns: String, name: String): String? = getAttributeNS(ns, name).takeIf { it.isNotEmpty() }

/** Navigation: Compose Navigation (string and type-safe routes), Navigation 3 and XML graphs, and navigate() calls in Kotlin and Java. */
class NavScan(private val p: Project, private val code: Code, private val g: Graph) {
    private val index = code.index
    private val stringDests = LinkedHashMap<String, String>() // route pattern -> node id
    private val typeDests = LinkedHashMap<String, String>() // fq -> node id
    private val xmlIds = HashMap<String, String>()
    private val xmlActions = HashMap<String, String>()
    private val pendingStarts = ArrayList<Pair<String, Call>>()
    private val files = code.sources.filter { !it.isTest }

    fun run() {
        for (s in files) {
            if (!s.isKotlin || !s.importsFrom("androidx.navigation")) continue
            for (call in s.calls) if (call.name in DEST_CALLS && !call.isNew) destination(s, call)
        }
        xmlGraphs()
        menuItems()
        for (s in files) {
            navigateCalls(s)
            starts(s)
        }
        helperCalls()
        routeReferences()
        resourceIdUses()
    }

    /** XML destinations that a menu item opens by its id (bottom navigation or a drawer set up with setupWithNavController). */
    private fun menuItems() {
        if (xmlIds.isEmpty()) return
        for (rel in p.menuXmlPaths) {
            val items = readXml(p, g, rel, "menu_xml")?.getElementsByTagName("item") ?: continue
            for (i in 0 until items.length) {
                val key = (items.item(i) as Element).attr(ANDROID_NS, "id")?.substringAfter('/') ?: continue
                xmlIds[key]?.let { g.nodes.getValue(it).attrs["menu_item"] = true }
            }
        }
    }

    /** R.id.x in app code reaches an XML destination in ways other than navigate(), such as AppBarConfiguration(setOf(R.id.x)). */
    private fun resourceIdUses() {
        if (xmlIds.isEmpty()) return
        val counts = HashMap<String, Int>()
        for (s in files) for (key in s.resourceIds) counts.merge(key, 1, Int::plus)
        for ((key, nid) in xmlIds) counts[key]?.let { g.nodes.getValue(nid).attrs["references"] = it }
    }

    private fun fnNode(fn: Fn?): String? = fn?.let { nodeForFun(g, it, "function") }

    private fun destination(s: Source, call: Call) {
        val name = call.name
        val api = if (name == "entry") "nav3" else "compose"
        val typeArg = call.typeArgs.firstOrNull()
        if (name == "entry" && typeArg == null) return
        val nid: String
        val common = mapOf("api" to api, "registered" to true, "graph" to flag(name == "navigation"), "dialog" to flag(name == "dialog"),
            "deep_link" to flag(call.args.arg("deepLinks") != null))
        if (typeArg != null) {
            val d = index.resolveType(typeArg.name, s, call.psi)
            val fq = d?.fq ?: typeArg.name
            nid = destId(fq)
            g.addNode(nid, "nav_destination", fq.substringAfterLast('.'), d?.src?.path ?: s.path, d?.line ?: call.line,
                d?.src?.module ?: s.module, mapOf("route" to fq, "style" to "type", "resolved" to flag(d != null), "end_line" to (d?.endLine ?: call.endLine)) + common)
            typeDests[fq] = nid
            if (d != null) nodeForClass(g, d, "nav_route")
        } else {
            // navigation(startDestination, route) takes the route second.
            val arg = call.args.arg("route", if (name == "navigation") 1 else 0) ?: return
            val v = code.eval.eval(arg.expr)
            if (v !is String) {
                if (name in setOf("composable", "dialog", "navigation")) {
                    g.addUnresolved("nav_route", arg.text.take(120), s.path, call.line, "route is not a constant")
                }
                return
            }
            nid = destId(display(v))
            // A route held in a constant (composable(SPENDING)) can be reached through other uses
            // of that constant, such as a list of bottom-bar tabs.
            val constant = when (val e = arg.expr) {
                is KtNameReferenceExpression -> e.getReferencedName()
                is KtDotQualifiedExpression -> (e.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                else -> null
            }
            g.addNode(nid, "nav_destination", display(v), s.path, call.line, s.module,
                mapOf("route" to display(v), "style" to "string", "route_constant" to constant, "end_line" to call.endLine) + common)
            stringDests[v] = nid
        }
        fnNode(call.fn)?.let { g.addEdge(it, nid, "registers", s.path, call.line) }
        if (name == "navigation") pendingStarts.add(nid to call)
        call.lambda?.let { screen(s, it, nid) }
    }

    /** The screen a destination shows: the first project composable its content lambda calls. */
    private fun screen(s: Source, lambda: PsiElement, nid: String) {
        for (c in s.calls) {
            if (!c.isInside(lambda) || c.receiver != null || !c.name.first().isUpperCase()) continue
            val f = index.resolveMember(c.name, s, c.psi) as? Fn ?: continue
            val sid = nodeForFun(g, f, "screen") ?: continue
            g.addEdge(nid, sid, "shows", s.path, c.line)
            return
        }
    }

    // ----- XML graphs -----
    /** An action or start destination in an XML graph: [from] (a destination id) to the XML id [target]. */
    private class XmlLink(val rel: String, val from: String?, val action: String?, val target: String)

    private fun xmlGraphs() {
        val actions = ArrayList<XmlLink>()
        val starts = ArrayList<XmlLink>()
        for (rel in p.navXmlPaths) {
            val root = readXml(p, g, rel, "nav_xml") ?: continue
            if (root.localName != "navigation" && root.tagName != "navigation") continue
            xmlElement(root, rel, p.moduleOf(rel), actions, starts)
        }
        // Linked once every file is read: an action can point into another graph file (<include>).
        for (a in actions) {
            val tid = xmlIds[a.target] ?: destId("@id/${a.target}").also {
                g.addNode(it, "nav_destination", "@id/${a.target}", a.rel, null, null,
                    mapOf("route" to "@id/${a.target}", "style" to "xml", "api" to "xml", "registered" to false))
            }
            if (!a.action.isNullOrEmpty()) xmlActions[a.action] = tid
            if (a.from != null) g.addEdge(a.from, tid, "navigates_to", a.rel, null, mapOf("action" to a.action?.ifEmpty { null }))
        }
        // A root graph without an id has no node: its start destination is marked instead.
        for (s in starts) xmlIds[s.target]?.let { if (s.from != null) g.addEdge(s.from, it, "starts_at", s.rel) else g.nodes.getValue(it).attrs["start"] = true }
    }

    private fun xmlElement(el: Element, rel: String, module: String?, actions: MutableList<XmlLink>, starts: MutableList<XmlLink>) {
        val raw = el.attr(ANDROID_NS, "id")?.takeIf { el.localName !in NOT_DESTINATIONS }
        var nid: String? = null
        if (raw != null) {
            val key = raw.substringAfter('/')
            nid = destId("@id/$key")
            val name = el.attr(ANDROID_NS, "name")
            g.addNode(nid, "nav_destination", "@id/$key", rel, null, module,
                mapOf("route" to "@id/$key", "style" to "xml", "api" to "xml", "registered" to true,
                    "graph" to flag(el.localName == "navigation"), "dialog" to flag(el.localName == "dialog"),
                    "deep_link" to flag(el.childElements().any { it.localName == "deepLink" }), "class_name" to name))
            xmlIds[key] = nid
            if (name != null && name in index.types) g.addEdge(nid, classId(name), "shows", rel)
        }
        val start = el.attr(APP_NS, "startDestination")
        if (start != null) starts.add(XmlLink(rel, nid, null, start.substringAfter('/')))
        for (child in el.childElements()) {
            if (child.localName == "action") {
                val target = child.attr(APP_NS, "destination") ?: continue
                actions.add(XmlLink(rel, nid, child.attr(ANDROID_NS, "id")?.substringAfter('/'), target.substringAfter('/')))
            } else {
                xmlElement(child, rel, module, actions, starts)
            }
        }
    }

    // ----- navigate calls -----
    private fun typeChain(e: KtExpression): String? = when (e) {
        is KtNameReferenceExpression -> e.getReferencedName()
        is KtCallExpression -> if ((e.parent as? KtQualifiedExpression)?.selectorExpression == e) null
            else (e.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        is KtClassLiteralExpression -> e.receiverExpression?.text?.filterNot { it.isWhitespace() }
        is KtDotQualifiedExpression -> {
            val r = e.receiverExpression
            val sel = e.selectorExpression
            val left = if (r is KtNameReferenceExpression || r is KtDotQualifiedExpression) typeChain(r) else null
            val right = when (sel) {
                is KtNameReferenceExpression -> sel.getReferencedName()
                is KtCallExpression -> (sel.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                else -> null
            }
            if (left != null && right != null) "$left.$right" else null
        }
        else -> null
    }

    /** A navigation argument to a destination node id, or (null, why not). */
    private fun target(s: Source, e: PsiElement?): Pair<String?, String?> {
        if (e == null) return null to "empty"
        R_ID.find(e.text.filterNot { it.isWhitespace() })?.let { m ->
            val key = m.groupValues[1]
            val nid = xmlActions[key] ?: xmlIds[key]
            return if (nid != null) nid to null else null to "R.id.$key matches no navigation XML id"
        }
        val chain = (e as? KtExpression)?.let { typeChain(it) }
        if (chain != null && chain.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
            val d = index.resolveType(chain, s, e)
            if (d != null) {
                val nid = typeDests[d.fq] ?: destId(d.fq).also { id ->
                    g.addNode(id, "nav_destination", d.name, d.src.path, d.line, d.src.module,
                        mapOf("route" to d.fq, "style" to "type", "registered" to false))
                    typeDests[d.fq] = id
                }
                return nid to null
            }
        }
        val v = code.eval.eval(e)
        if (v is String) {
            val (segs, _) = routeSig(v)
            if (segs.all { it == "*" }) return null to "route is dynamic"
            stringDests[v]?.let { return it to null }
            stringDests.entries.firstOrNull { routeMatches(it.key, v) }?.let { return it.value to null }
            val nid = destId(display(v))
            g.addNode(nid, "nav_destination", display(v), s.path, s.line(e), s.module,
                mapOf("route" to display(v), "style" to "string", "registered" to false))
            return nid to null
        }
        return null to "route is dynamic"
    }

    /** Functions that navigate to one of their own parameters: fun go(route: String) = navigate(route). */
    private class Helper(val fn: Fn, val param: String, val index: Int)
    private val helpers = ArrayList<Helper>()

    private fun navigateCalls(s: Source) {
        val navFile = s.importsFrom("androidx.navigation")
        for (call in s.calls) {
            if (call.isNew || (call.name != "navigate" && call.name != "add")) continue
            val receiver = call.receiverName
            if (call.name == "add" && !(receiver != null && "backstack" in receiver.lowercase())) continue
            val arg = call.args.arg("route", 0) ?: call.args.arg("key") ?: call.args.arg("resId") ?: continue
            val (nid, reason) = target(s, arg.expr)
            if (nid == null) {
                val fn = call.fn
                val pname = plainName(arg.expr)
                val pindex = fn?.params?.indexOfFirst { it.name == pname } ?: -1
                if (fn != null && pname != null && pindex >= 0 && index.local(pname, call.psi) == null) {
                    helpers.add(Helper(fn, pname, pindex))
                } else if (navFile || (receiver != null && "nav" in receiver.lowercase())) {
                    g.addUnresolved("nav_target", arg.text.take(120), s.path, call.line, reason ?: "unknown")
                }
                continue
            }
            fnNode(call.fn)?.let { g.addEdge(it, nid, "navigates_to", s.path, call.line) }
        }
    }

    /** `route` in navigate(route): an unqualified name. */
    private fun plainName(e: PsiElement?): String? = when (e) {
        is KtNameReferenceExpression -> e.getReferencedName()
        is PsiReferenceExpression -> if (e.qualifierExpression == null) e.referenceName else null
        else -> null
    }

    /** Calls to navigation helpers, resolved through the argument passed for the route parameter. */
    private fun helperCalls() {
        if (helpers.isEmpty()) return
        val byName = helpers.groupBy { it.fn.name }
        for (s in files) for (call in s.calls) {
            val hs = byName[call.name] ?: continue
            if (call.isNew) continue
            val callee = index.resolveMember(call.name, s, call.psi) as? Fn
            val h = hs.firstOrNull { it.fn === callee } ?: hs.singleOrNull() ?: continue
            if (call.fn === h.fn) continue
            val arg = call.args.arg(h.param, h.index) ?: continue
            val (nid, reason) = target(s, arg.expr)
            if (nid == null) {
                g.addUnresolved("nav_target", "${call.name}(${arg.text.take(100)})", s.path, call.line, reason ?: "unknown")
                continue
            }
            fnNode(call.fn)?.let { g.addEdge(it, nid, "navigates_to", s.path, call.line, mapOf("via" to h.fn.name)) }
        }
    }

    private fun starts(s: Source) {
        class Start(val arg: Arg, val call: Call, val at: PsiElement)
        val found = ArrayList<Start>()
        for (call in s.calls) {
            // NavHost(startDestination = ...), rememberNavBackStack(Home) and NavHost(navController, "home");
            // a graph's own start is linked with the graph below.
            if (call.name != "navigation") for (a in call.args) if (a.name in START_ARGS) found.add(Start(a, call, a.psi))
            if (call.name in BACKSTACK_STARTS) call.args.arg(null, 0)?.let { found.add(Start(it, call, call.psi)) }
            if (call.name == "NavHost") call.args.arg("startDestination", 1)?.takeIf { it.name == null }?.let { found.add(Start(it, call, call.psi)) }
        }
        for (st in found) {
            val (nid, _) = target(s, st.arg.expr)
            val src = fnNode(st.call.fn)
            if (nid != null && src != null) g.addEdge(src, nid, "starts_at", s.path, s.line(st.at))
        }
        for ((nid, call) in pendingStarts) {
            if (call.src !== s) continue
            val a = call.args.arg("startDestination", 0) ?: continue
            val (tid, _) = target(s, a.expr)
            if (tid != null) g.addEdge(nid, tid, "starts_at", s.path, call.line)
        }
    }

    /** Uses of each type route (or route constant) outside its declaration and its registration. */
    private fun routeReferences() {
        val constants = stringDests.values.mapNotNull { nid -> g.nodes[nid]?.attrs?.get("route_constant")?.let { nid to it as String } }
        if (typeDests.isEmpty() && constants.isEmpty()) return
        val counts = code.references(includeTests = false)
        fun refs(nid: String, name: String) {
            val regs = g.inEdges(nid).count { it.kind == "registers" }
            g.nodes[nid]!!.attrs["references"] = maxOf(0, (counts[name] ?: 0) - regs)
        }
        for ((fq, nid) in typeDests) refs(nid, fq.substringAfterLast('.'))
        for ((nid, name) in constants) refs(nid, name)
    }
}
