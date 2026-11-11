package droidmap.extract

import droidmap.Graph
import droidmap.Project
import droidmap.Source
import droidmap.TypeRef
import droidmap.WILD
import droidmap.arg
import droidmap.calleeName
import droidmap.fq
import droidmap.name
import droidmap.receiverText
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

private val DEST_CALLS = setOf("composable", "dialog", "navigation", "bottomSheet", "activity", "entry")
private val START_ARGS = setOf("startDestination", "startKey", "startRoute")
private val BACKSTACK_STARTS = setOf("rememberNavBackStack", "NavBackStack")
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

/** Navigation: Compose Navigation (string and type-safe routes), Navigation 3 and XML graphs. */
class NavScan(private val p: Project, private val g: Graph) {
    private val stringDests = LinkedHashMap<String, String>() // route pattern -> node id
    private val typeDests = LinkedHashMap<String, String>() // fq -> node id
    private val xmlIds = HashMap<String, String>()
    private val xmlActions = HashMap<String, String>()
    private val pendingStarts = ArrayList<Pair<String, KtCallExpression>>()

    private fun files() = p.sources.filter { !it.isTest }

    fun run() {
        for (s in files().filter { it.importsFrom("androidx.navigation") }) {
            for (call in s.file.collectDescendantsOfType<KtCallExpression> { it.calleeName() in DEST_CALLS }) destination(s, call)
        }
        xmlGraphs()
        for (s in files()) {
            navigateCalls(s)
            starts(s)
        }
        helperCalls()
        routeReferences()
    }

    private fun fnNode(s: Source, at: PsiElement): String? {
        val fn = (at as? KtNamedFunction) ?: at.getStrictParentOfType<KtNamedFunction>() ?: return null
        return nodeForFun(g, fn, s, "function")
    }

    private fun destination(s: Source, call: KtCallExpression) {
        val name = call.calleeName() ?: return
        val api = if (name == "entry") "nav3" else "compose"
        val typeArg = call.typeArguments.firstOrNull()?.typeReference?.let { TypeRef.of(it) }
        if (name == "entry" && typeArg == null) return
        val nid: String
        if (typeArg != null) {
            val d = p.index.resolveType(typeArg.name, s, call)
            val fq = d?.fq() ?: typeArg.name
            val ds = d?.let { p.index.sourceOf(it) }
            nid = destId(fq)
            g.addNode(nid, "nav_destination", fq.substringAfterLast('.'), ds?.path ?: s.path, ds?.line(d) ?: s.line(call),
                ds?.module ?: s.module, mapOf("route" to fq, "style" to "type", "api" to api, "registered" to true,
                    "graph" to (if (name == "navigation") true else null), "dialog" to (if (name == "dialog") true else null),
                    "resolved" to (if (d != null) true else null)))
            typeDests[fq] = nid
            if (d != null && ds != null) nodeForClass(g, d, ds, "nav_route")
        } else {
            val arg = call.valueArguments.arg("route", 0) ?: return
            val v = p.eval.eval(arg.getArgumentExpression())
            if (v !is String) {
                if (name in setOf("composable", "dialog", "navigation")) {
                    g.addUnresolved("nav_route", arg.text.take(120), s.path, s.line(call), "route is not a constant")
                }
                return
            }
            nid = destId(display(v))
            // A route held in a constant (composable(SPENDING)) can be reached through other uses
            // of that constant, such as a list of bottom-bar tabs.
            val constant = when (val e = arg.getArgumentExpression()) {
                is KtNameReferenceExpression -> e.getReferencedName()
                is KtDotQualifiedExpression -> (e.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                else -> null
            }
            g.addNode(nid, "nav_destination", display(v), s.path, s.line(call), s.module,
                mapOf("route" to display(v), "style" to "string", "api" to api, "registered" to true,
                    "graph" to (if (name == "navigation") true else null), "dialog" to (if (name == "dialog") true else null),
                    "route_constant" to constant))
            stringDests[v] = nid
        }
        fnNode(s, call)?.let { g.addEdge(it, nid, "registers", s.path, s.line(call)) }
        if (name == "navigation") pendingStarts.add(nid to call)
        call.lambdaArguments.lastOrNull()?.getLambdaExpression()?.let { screen(s, it, nid) }
    }

    private fun screen(s: Source, lambda: PsiElement, nid: String) {
        for (c in lambda.collectDescendantsOfType<KtCallExpression>()) {
            val n = c.calleeName() ?: continue
            if (!n.first().isUpperCase() || c.receiverText() != null) continue
            val f = p.index.resolveMember(n, s, c) as? KtNamedFunction ?: continue
            val fs = p.index.sourceOf(f) ?: continue
            val sid = nodeForFun(g, f, fs, "screen") ?: continue
            g.addEdge(nid, sid, "shows", s.path, s.line(c))
            return
        }
    }

    // ----- XML graphs -----
    private fun xmlGraphs() {
        for (rel in p.navXmlPaths) {
            val root = try {
                secureXml(p.read(rel))
            } catch (e: Exception) {
                g.addUnresolved("nav_xml", rel, rel, null, "skipped: ${e.message?.take(80) ?: "invalid XML"}")
                null
            } ?: continue
            if (root.localName != "navigation" && root.tagName != "navigation") continue
            val actions = ArrayList<List<String?>>()
            val starts = ArrayList<Pair<String, String>>()
            xmlElement(root, rel, p.moduleOf(rel), actions, starts)
            for ((src, aid, target) in actions) {
                val tid = xmlIds[target] ?: destId("@id/$target").also {
                    g.addNode(it, "nav_destination", "@id/$target", rel, null, null,
                        mapOf("route" to "@id/$target", "style" to "xml", "api" to "xml", "registered" to false))
                }
                if (!aid.isNullOrEmpty()) xmlActions[aid] = tid
                if (src != null) g.addEdge(src, tid, "navigates_to", rel, null, mapOf("action" to aid?.ifEmpty { null }))
            }
            for ((gid, target) in starts) xmlIds[target]?.let { g.addEdge(gid, it, "starts_at", rel) }
        }
    }

    private fun xmlElement(el: Element, rel: String, module: String?, actions: MutableList<List<String?>>, starts: MutableList<Pair<String, String>>) {
        val raw = el.attr(ANDROID_NS, "id")
        var nid: String? = null
        if (raw != null) {
            val key = raw.substringAfter('/')
            nid = destId("@id/$key")
            val name = el.attr(ANDROID_NS, "name")
            g.addNode(nid, "nav_destination", "@id/$key", rel, null, module,
                mapOf("route" to "@id/$key", "style" to "xml", "api" to "xml", "registered" to true,
                    "graph" to (if (el.localName == "navigation") true else null),
                    "dialog" to (if (el.localName == "dialog") true else null), "class_name" to name))
            xmlIds[key] = nid
            if (name != null && name in p.index.types) g.addEdge(nid, classId(name), "shows", rel)
        }
        val start = el.attr(APP_NS, "startDestination")
        if (start != null && nid != null) starts.add(nid to start.substringAfter('/'))
        for (child in el.childElements()) {
            if (child.localName == "action") {
                val target = child.attr(APP_NS, "destination") ?: continue
                actions.add(listOf(nid, child.attr(ANDROID_NS, "id")?.substringAfter('/'), target.substringAfter('/')))
            } else {
                xmlElement(child, rel, module, actions, starts)
            }
        }
    }

    // ----- navigate calls -----
    private fun typeChain(e: KtExpression): String? = when (e) {
        is KtNameReferenceExpression -> e.getReferencedName()
        is KtCallExpression -> if (e.receiverText() == null) e.calleeName() else null
        is KtClassLiteralExpression -> e.receiverExpression?.text?.filterNot { it.isWhitespace() }
        is KtDotQualifiedExpression -> {
            val r = e.receiverExpression
            val sel = e.selectorExpression
            val left = if (r is KtNameReferenceExpression || r is KtDotQualifiedExpression) typeChain(r) else null
            val right = when (sel) {
                is KtNameReferenceExpression -> sel.getReferencedName()
                is KtCallExpression -> sel.calleeName()
                else -> null
            }
            if (left != null && right != null) "$left.$right" else null
        }
        else -> null
    }

    /** A navigation argument to a destination node id, or (null, why not). */
    private fun target(s: Source, e: KtExpression?): Pair<String?, String?> {
        if (e == null) return null to "empty"
        val text = e.text.filterNot { it.isWhitespace() }
        Regex("""^R\.id\.(\w+)$""").find(text)?.let { m ->
            val key = m.groupValues[1]
            val nid = xmlActions[key] ?: xmlIds[key]
            return if (nid != null) nid to null else null to "R.id.$key matches no navigation XML id"
        }
        val chain = typeChain(e)
        if (chain != null && chain.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
            val d = p.index.resolveType(chain, s, e)
            val fq = d?.fq()
            if (d != null && fq != null) {
                val nid = typeDests[fq] ?: destId(fq).also { id ->
                    val ds = p.index.sourceOf(d)
                    g.addNode(id, "nav_destination", d.name, ds?.path, ds?.line(d), ds?.module,
                        mapOf("route" to fq, "style" to "type", "registered" to false))
                    typeDests[fq] = id
                }
                return nid to null
            }
        }
        val v = p.eval.eval(e)
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
    private class Helper(val fn: KtNamedFunction, val param: String, val index: Int)
    private val helpers = ArrayList<Helper>()

    private fun navigateCalls(s: Source) {
        val navFile = s.importsFrom("androidx.navigation")
        for (call in s.file.collectDescendantsOfType<KtCallExpression> { it.calleeName() == "navigate" || it.calleeName() == "add" }) {
            val receiver = call.receiverText()
            if (call.calleeName() == "add" && !(receiver != null && "backstack" in receiver.lowercase())) continue
            val a = call.valueArguments
            val arg = a.arg("route", 0) ?: a.arg("key") ?: a.arg("resId") ?: continue
            val expr = arg.getArgumentExpression()
            val (nid, reason) = target(s, expr)
            if (nid == null) {
                val fn = call.getStrictParentOfType<KtNamedFunction>()
                val pname = (expr as? KtNameReferenceExpression)?.getReferencedName()
                val pindex = fn?.valueParameters?.indexOfFirst { it.name == pname } ?: -1
                if (fn != null && pname != null && pindex >= 0 && p.index.local(pname, call) == null) {
                    helpers.add(Helper(fn, pname, pindex))
                } else if (navFile || (receiver != null && "nav" in receiver.lowercase())) {
                    g.addUnresolved("nav_target", arg.text.take(120), s.path, s.line(call), reason ?: "unknown")
                }
                continue
            }
            fnNode(s, call)?.let { g.addEdge(it, nid, "navigates_to", s.path, s.line(call)) }
        }
    }

    /** Calls to navigation helpers, resolved through the argument passed for the route parameter. */
    private fun helperCalls() {
        if (helpers.isEmpty()) return
        val byName = helpers.groupBy { it.fn.name }
        for (s in files()) for (call in s.file.collectDescendantsOfType<KtCallExpression> { it.calleeName() in byName }) {
            val callee = p.index.resolveMember(call.calleeName()!!, s, call) as? KtNamedFunction
            val h = byName[call.calleeName()]!!.firstOrNull { it.fn == callee } ?: byName[call.calleeName()]!!.singleOrNull() ?: continue
            if (call.getStrictParentOfType<KtNamedFunction>() == h.fn) continue
            val arg = call.valueArguments.arg(h.param, h.index) ?: continue
            val (nid, reason) = target(s, arg.getArgumentExpression())
            if (nid == null) {
                g.addUnresolved("nav_target", "${call.calleeName()}(${arg.text.take(100)})", s.path, s.line(call), reason ?: "unknown")
                continue
            }
            fnNode(s, call)?.let { g.addEdge(it, nid, "navigates_to", s.path, s.line(call), mapOf("via" to h.fn.name)) }
        }
    }

    private fun starts(s: Source) {
        val args = ArrayList<Pair<KtValueArgument, PsiElement>>()
        for (a in s.file.collectDescendantsOfType<KtValueArgument> { it.name() in START_ARGS }) {
            val call = a.parent?.parent as? KtCallExpression
            if (call != null && call.calleeName() == "navigation") continue // a graph's own start, below
            args.add(a to a)
        }
        for (c in s.file.collectDescendantsOfType<KtCallExpression> { it.calleeName() in BACKSTACK_STARTS }) {
            c.valueArguments.arg(null, 0)?.let { args.add(it to c) }
        }
        for ((a, at) in args) {
            val (nid, _) = target(s, a.getArgumentExpression())
            val src = fnNode(s, at)
            if (nid != null && src != null) g.addEdge(src, nid, "starts_at", s.path, s.line(at))
        }
        for ((nid, call) in pendingStarts) {
            if (call.containingFile != s.file) continue
            val a = call.valueArguments.arg("startDestination") ?: continue
            val (tid, _) = target(s, a.getArgumentExpression())
            if (tid != null) g.addEdge(nid, tid, "starts_at", s.path, s.line(call))
        }
    }

    /** Uses of each type route (or route constant) outside its declaration and its registration. */
    private fun routeReferences() {
        val constants = stringDests.values.mapNotNull { nid -> g.nodes[nid]?.attrs?.get("route_constant")?.let { nid to it as String } }
        if (typeDests.isEmpty() && constants.isEmpty()) return
        val counts = ReferenceCounts(p, includeTests = false)
        for ((fq, nid) in typeDests) {
            val regs = g.inEdges(nid).count { it.kind == "registers" }
            g.nodes[nid]!!.attrs["references"] = maxOf(0, counts[fq.substringAfterLast('.')] - regs)
        }
        for ((nid, name) in constants) {
            val regs = g.inEdges(nid).count { it.kind == "registers" }
            g.nodes[nid]!!.attrs["references"] = maxOf(0, counts[name] - regs)
        }
    }
}
