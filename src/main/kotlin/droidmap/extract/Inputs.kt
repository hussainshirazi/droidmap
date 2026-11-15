package droidmap.extract

import droidmap.Call
import droidmap.Code
import droidmap.Field
import droidmap.Graph
import droidmap.Type
import droidmap.bool
import droidmap.classRefs
import droidmap.str
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/** A call that sends values to [type]; [creates] when it makes the value there, so its keys can be followed. */
class Target(val type: Type, val creates: Boolean)

/** How one kind of value carries keys to the class it is sent to. */
interface Channel {
    /** The meta list its findings are recorded in. */
    val meta: String
    fun target(c: Call): Target?
    /** Whether a call on the value returns the value itself, so that a chain goes on (a builder method). */
    fun keeps(c: Call): Boolean
    /** The keys a call on the value puts: none for a call that puts none, null when they are not known. */
    fun puts(c: Call): Set<String>?
    /** Whether the value, passed to [c], arrives as it is (`startActivity(intent)`). */
    fun sends(c: Call): Boolean
    /** The key argument of [c] when it reads a value sent to the class it is in. */
    fun readKey(c: Call): PsiElement?
    /** Whether only this project's code sends to [t] (for a component: no intent filter, not exported). */
    fun onlyFromApp(t: Type): Boolean
}

/**
 * Values sent to a class by key that the class reads under a key none of its senders put, so it is always null (or the
 * default): an Intent's extras for an activity, service or receiver, and a work request's input for a worker. A class is
 * checked only when droidmap sees every place that sends to it and the keys each one puts; a key a test puts is left out.
 */
class Inputs(private val code: Code, private val g: Graph) {
    private val values = Values(code)
    private val app = code.sources.filter { !it.isTest }

    fun run() {
        for (ch in listOf(IntentExtras(code, g, values), WorkerInput(code, values))) check(ch)
    }

    private fun check(ch: Channel) {
        val sent = LinkedHashMap<Type, MutableList<Set<String>?>>()
        val senders = HashMap<Type, MutableList<Call>>()
        val tested = HashMap<Type, MutableSet<String>>()
        for (s in code.sources) for (c in s.calls) {
            val target = ch.target(c) ?: continue
            val keys = if (target.creates) keys(ch, c) else null
            if (s.isTest) tested.getOrPut(target.type) { HashSet() }.addAll(keys.orEmpty())
            else { sent.getOrPut(target.type) { ArrayList() }.add(keys); senders.getOrPut(target.type) { ArrayList() }.add(c) }
        }
        for ((t, keys) in sent) {
            if (t.src.isTest || keys.any { it == null } || !ch.onlyFromApp(t) || sentElsewhere(t, senders.getValue(t))) continue
            val put = keys.flatMap { it!! }.toSortedSet()
            val reads = LinkedHashMap<String, Int>()
            for (c in t.src.calls) if (c.isInside(t.psi)) values.constant(ch.readKey(c))?.let { reads.putIfAbsent(it, c.line) }
            for ((key, line) in reads) {
                if (key in put || key in tested[t].orEmpty()) continue
                g.addMeta(ch.meta, linkedMapOf("class" to t.fq, "key" to key, "puts" to put.toList(), "senders" to keys.size,
                    "file" to t.src.path, "line" to line))
            }
        }
    }

    /** The keys the value [created] makes carries, or null when they are not known. */
    private fun keys(ch: Channel, created: Call): Set<String>? {
        val uses = values.follow(created, ch::keeps)
        if (uses.escapes || uses.passedTo.any { !ch.sends(it) }) return null
        val out = HashSet<String>()
        for (c in uses.calls) out += ch.puts(c) ?: return null
        return out
    }

    /**
     * Where app code hands each project class to other code: a class literal passed as an argument (`helper(X::class.java)`)
     * or a call's type argument (`startActivity<X>()`). Any of those that is not a known sender may send to it too.
     */
    private val handedOver: Map<Type, List<PsiElement>> by lazy {
        val out = HashMap<Type, MutableList<PsiElement>>()
        for (s in app) {
            for ((name, at) in classRefs(s.psi)) {
                if (!isArgument(at)) continue
                code.index.resolveType(name, s, at)?.let { out.getOrPut(it) { ArrayList() }.add(at) }
            }
            for (c in s.calls) for (ref in c.typeArgs) code.index.resolveType(ref.name, s, c.psi)?.let { out.getOrPut(it) { ArrayList() }.add(c.psi) }
        }
        out
    }

    /** `X::class.java` or `X.class` passed to a call, not used for its name (`X::class.java.simpleName`). */
    private fun isArgument(literal: PsiElement): Boolean {
        var e = literal
        val p = e.parent
        if (p is KtDotQualifiedExpression && p.receiverExpression == e && p.selectorExpression?.text in setOf("java", "javaObjectType")) e = p
        return e.parent is KtValueArgument || e.parent is PsiExpressionList
    }

    /** Whether something else may send to [t]: a navigation destination that shows it (its arguments arrive as extras), or other code it is handed to. */
    private fun sentElsewhere(t: Type, senders: List<Call>): Boolean {
        if (g.inEdges(classId(t.fq)).any { it.kind == "shows" }) return true
        return handedOver[t].orEmpty().any { el -> senders.none { it.psi.containingFile == el.containingFile && it.psi.textRange.contains(el.textRange) } }
    }
}

/** Intent methods that return the Intent itself. */
private val INTENT_BUILDERS = setOf("putExtra", "putExtras", "putStringArrayListExtra", "putIntegerArrayListExtra", "putParcelableArrayListExtra",
    "putCharSequenceArrayListExtra", "removeExtra", "addFlags", "setFlags", "removeFlags", "setAction", "setData", "setDataAndType", "setDataAndNormalize",
    "setType", "setTypeAndNormalize", "setPackage", "setClass", "setClassName", "setComponent", "addCategory", "removeCategory", "setIdentifier",
    "setSelector", "setClipData", "setSourceBounds")
/** Calls that start, or wrap in a PendingIntent, the Intent they are given as it is. */
private val INTENT_SENDS = setOf("startActivity", "startActivityForResult", "startActivities", "startActivityIfNeeded", "startService",
    "startForegroundService", "bindService", "sendBroadcast", "sendOrderedBroadcast", "launch", "getActivity", "getActivities", "getService",
    "getForegroundService", "getBroadcast", "addNextIntent", "addNextIntentWithParentStack", "navigateUpTo")
private val EXTRA_READ = Regex("get\\w*Extra|hasExtra")
/** Functions whose `intent` parameter is the Intent that started the component. */
private val INTENT_CALLBACKS = setOf("onNewIntent", "onStartCommand", "onBind", "onUnbind", "onRebind", "onHandleIntent", "onReceive",
    "onTaskRemoved", "onHandleWork")
private val INTENT_REFS = setOf("intent", "getIntent()")
private val EXTRAS_REFS = setOf("intent.extras", "intent.getExtras()", "getIntent().getExtras()", "getIntent().extras")

/** Receiver text without spaces, `this.`, `?` and `!!`: `this.intent?.extras` is `intent.extras`. */
private fun plain(text: String?): String? = text?.filterNot { it.isWhitespace() }?.replace("!!", "")?.replace("?", "")?.removePrefix("this.")

/** An Intent's extras, sent to an activity, service or receiver with an explicit Intent. */
private class IntentExtras(private val code: Code, private val g: Graph, private val values: Values) : Channel {
    override val meta = "intent_extra_missing"
    private val declarations by lazy { g.edgesOf("declares") }

    override fun target(c: Call): Target? = explicitComponent(c, code.index)?.let { Target(it, creates = c.name == "Intent") }

    override fun keeps(c: Call) = c.name in INTENT_BUILDERS

    override fun puts(c: Call): Set<String>? = when {
        c.name == "putExtras" -> values.callOf(c.args.firstOrNull()?.expr)?.takeIf { it.name == "bundleOf" }?.let { values.pairKeys(it) }
        c.name.startsWith("put") && c.name.endsWith("Extra") -> values.constant(c.args.firstOrNull()?.expr)?.let { setOf(it) }
        c.name in INTENT_BUILDERS || c.name in INTENT_SENDS || c.name.startsWith("get") || c.name.startsWith("has") -> emptySet()
        else -> null
    }

    override fun sends(c: Call) = c.name in INTENT_SENDS

    /** `intent.getStringExtra(KEY)`, `intent.extras?.getString(KEY)` or `IntentCompat.getParcelableExtra(intent, KEY, X::class.java)`. */
    override fun readKey(c: Call): PsiElement? {
        val r = plain(c.receiver)
        val arg = when {
            r in INTENT_REFS && EXTRA_READ.matches(c.name) -> c.args.getOrNull(0)
            r in EXTRAS_REFS && (c.name.startsWith("get") || c.name == "containsKey") -> c.args.getOrNull(0)
            r == "IntentCompat" && plain(c.args.getOrNull(0)?.text) in INTENT_REFS -> c.args.getOrNull(1)
            else -> null
        } ?: return null
        // `intent` must be the Intent that started the component, not another function's parameter or a local of that name.
        val fn = c.fn
        if (fn != null && ((fn.params.any { it.name == "intent" } && fn.name !in INTENT_CALLBACKS) || fn.locals.any { it.name == "intent" })) return null
        return arg.expr
    }

    override fun onlyFromApp(t: Type): Boolean {
        val decls = declarations.filter { it.target == classId(t.fq) || it.attrs.str("target_activity") == t.fq }
        return decls.isNotEmpty() && decls.none { it.attrs.bool("intent_filter") || it.attrs.str("exported") == "true" || it.attrs.str("target_activity") == t.fq }
    }
}

private val REQUEST_BUILDERS = setOf("OneTimeWorkRequestBuilder", "PeriodicWorkRequestBuilder")
private val REQUESTS = setOf("OneTimeWorkRequest", "PeriodicWorkRequest")
private val DATA_READ = Regex("get(String|Int|Long|Boolean|Float|Double|Byte)(Array)?|hasKeyWithValueOfType")
private val DATA_PUT = Regex("put(String|Int|Long|Boolean|Float|Double|Byte)(Array)?")

/** A work request's input data, read by its worker. */
private class WorkerInput(private val code: Code, private val values: Values) : Channel {
    override val meta = "worker_input_missing"

    /** `OneTimeWorkRequestBuilder<W>()`, `PeriodicWorkRequestBuilder<W>(...)`, `OneTimeWorkRequest.Builder(W::class.java)`, `OneTimeWorkRequest.from(W::class.java)`. */
    override fun target(c: Call): Target? {
        val name = when {
            c.name in REQUEST_BUILDERS -> c.typeArgs.firstOrNull()?.name
            (c.name == "Builder" || c.name == "from") && c.receiver?.substringAfterLast('.') in REQUESTS -> classRefs(c.args.firstOrNull()?.psi).firstOrNull()?.first
            else -> null
        } ?: return null
        val t = code.index.resolveType(name, c.src, c.psi) ?: return null
        return if (isWorker(code.index, t)) Target(t, creates = true) else null
    }

    override fun keeps(c: Call) = c.name != "build"

    override fun puts(c: Call): Set<String>? = when {
        c.name == "setInputData" -> data(c.args.firstOrNull()?.expr, 0)
        c.name.startsWith("set") || c.name.startsWith("add") || c.name == "keepResultsForAtLeast" || c.name == "build" -> emptySet()
        else -> null
    }

    /** A builder passed to other code may get more input there. */
    override fun sends(c: Call) = false

    /** `inputData.getString(KEY)`, `getInputData().getInt(KEY, 0)`. */
    override fun readKey(c: Call): PsiElement? {
        val r = plain(c.receiver) ?: return null
        if (!(r.endsWith("inputData") || r.endsWith("getInputData()")) || !DATA_READ.matches(c.name)) return null
        return c.args.getOrNull(0)?.expr
    }

    override fun onlyFromApp(t: Type) = true

    /** The keys of a Data: `workDataOf("a" to 1)`, `Data.Builder().putString("a", x).build()`, or a val that holds one; null when not known. */
    private fun data(e: PsiElement?, depth: Int): Set<String>? {
        if (e == null || depth > 3) return null
        if (e.text.filterNot { it.isWhitespace() } == "Data.EMPTY") return emptySet()
        val call = values.callOf(e)
        if (call?.name == "workDataOf") return values.pairKeys(call)
        if (call?.name == "build") {
            val builder = call.src.calls.firstOrNull { it.name == "Builder" && it.receiver?.substringAfterLast('.') == "Data" && it.isInside(e) } ?: return null
            val out = HashSet<String>()
            for (c in values.follow(builder) { it.name != "build" }.calls) {
                when {
                    DATA_PUT.matches(c.name) -> out.add(values.constant(c.args.firstOrNull()?.expr) ?: return null)
                    c.name != "build" -> return null // putAll(...) or something else
                }
            }
            return out
        }
        val name = (e as? KtNameReferenceExpression)?.getReferencedName() ?: (e as? PsiReferenceExpression)?.takeIf { it.qualifierExpression == null }?.referenceName
            ?: return null
        val src = code.index.sourceOf(e) ?: return null
        val held = code.index.local(name, e) ?: code.index.resolveMember(name, src, e) as? Field
        return held?.takeIf { it.isConstant }?.let { data(it.initializer, depth + 1) }
    }
}
