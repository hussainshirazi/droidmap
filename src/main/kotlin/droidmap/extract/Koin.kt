package droidmap.extract

import droidmap.Call
import droidmap.Code
import droidmap.Fn
import droidmap.Graph
import droidmap.Source
import droidmap.Type
import droidmap.TypeRef
import droidmap.classRefs
import droidmap.find
import droidmap.has
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import java.util.IdentityHashMap

private val MODULE_CALLS = setOf("module", "lazyModule")
private val DEFINITIONS = setOf("single", "factory", "scoped", "viewModel", "worker", "singleOf", "factoryOf", "scopedOf", "viewModelOf", "workerOf")
/** Koin Annotations, and the JSR-330 ones Koin reads too (@Singleton, an @Inject constructor). */
private val ANNOTATED = setOf("Single", "Factory", "Scoped", "KoinViewModel", "KoinWorker", "Singleton")
private val NOT_INJECTED = setOf("InjectedParam", "Property", "Provided")
private val USES = setOf("get", "inject", "koinInject")

fun koinModuleId(key: String) = "koin.module::$key"

/**
 * Koin: modules and their definitions (the DSL, `single { Repo(get()) }` and `singleOf(::Repo)`, and the
 * annotations, `@Single class Repo`), what each one needs through get(), and koinInject / inject / get uses.
 * Mapped onto the same edges as Hilt: a provided type is `bound_by` its definition, a class `injects` what
 * its constructor gets, with a `koin` attribute so the Koin rule can tell them apart.
 */
class KoinScan(private val code: Code, private val g: Graph) {
    private val index = code.index

    fun run() {
        // With Koin Annotations, annotated classes anywhere in the project are definitions (found by @ComponentScan).
        val annotations = code.sources.any { it.importsFrom("org.koin.core.annotation") }
        for (s in code.sources) {
            if (annotations) {
                for (t in s.types) annotatedClass(t)
                for (f in s.fns) annotatedFunction(f)
            }
            if (!s.importsFrom("org.koin")) continue
            val calls = IdentityHashMap<PsiElement, Call>().apply { s.calls.forEach { put(it.psi, it) } }
            val modules = s.calls.filter { it.name in MODULE_CALLS && it.receiver == null && it.lambda != null }
            val definitions = s.calls.filter { it.name in DEFINITIONS && it.receiver == null && (it.lambda != null || it.name.endsWith("Of")) }
            for (m in modules) moduleNode(m)
            for (d in definitions) definition(s, d, modules.firstOrNull { d.isInside(it.lambda!!) }, calls)
            for (c in s.calls) if (c.name in USES && c.receiver == null && definitions.none { d -> d.lambda?.let { c.isInside(it) } == true }) use(c)
        }
    }

    private fun moduleKey(m: Call) = m.assignedTo?.fq ?: "${m.src.path}:${m.line}"

    private fun moduleNode(m: Call): String = g.addNode(koinModuleId(moduleKey(m)), "koin_module", m.assignedTo?.name ?: "module",
        m.src.path, m.line, m.src.module)

    private fun type(ref: TypeRef?, src: Source, at: PsiElement): Type? = ref?.let { index.resolveType(it.unwrapped().name, src, at) }

    private fun classNode(t: Type) = classId(t.fq).takeIf { it in g.nodes } ?: nodeForClass(g, t, "class")

    /** `X(...)` where X is a project class: the class it constructs. */
    private fun constructed(c: Call): Type? =
        if (c.receiver == null && c.name.first().isUpperCase()) index.resolveType(c.name, c.src, c.psi) else null

    private fun definition(s: Source, d: Call, module: Call?, calls: Map<PsiElement, Call>) {
        val style = d.name.removeSuffix("Of")
        val lambda = d.lambda
        val inside = if (lambda != null) s.calls.filter { it.isInside(lambda) } else emptyList()
        // singleOf(::Repo) constructs Repo; single { Repo(get()) } constructs the first project class it calls.
        val ctor: Call?
        val made: Type?
        if (d.name.endsWith("Of")) {
            ctor = null
            made = (d.args.firstOrNull()?.expr as? KtCallableReferenceExpression)?.callableReference?.getReferencedName()
                ?.let { index.resolveType(it, s, d.psi) }
        } else {
            ctor = inside.firstOrNull { constructed(it) != null }
            made = ctor?.let { constructed(it) }
        }
        // single<Store> { ... } provides Store; single { Repo(...) } provides Repo; and either may bind more types.
        val provides = LinkedHashSet<Type>()
        (type(d.typeArgs.firstOrNull(), s, d.psi) ?: made ?: lambda?.let { produced(s, it, calls) })?.let { provides.add(it) }
        provides += bound(s, d, inside)
        if (provides.isEmpty()) {
            g.addUnresolved("koin_definition", d.psi.text.take(80), s.path, d.line, "the type it provides is not known")
            return
        }
        val first = provides.first()
        val did = g.addNode("koin.def::${s.path}:${d.line}", "koin_definition", "${module?.assignedTo?.name ?: "module"}.$style ${first.label}",
            s.path, d.line, s.module, mapOf("style" to style, "provides" to provides.map { it.fq }, "koin_module" to module?.let { moduleKey(it) },
                "source_set" to s.sourceSet))
        module?.let { g.addEdge(did, moduleNode(it), "declared_in", s.path, d.line) }
        for (t in provides) g.addEdge(classNode(t), did, "bound_by", s.path, d.line)
        if (made != null) {
            if (style == "viewModel") nodeForClass(g, made, "viewmodel")
            if (made !in provides) g.addEdge(did, classNode(made), "requires", s.path, d.line, mapOf("constructs" to true))
        }
        // What it needs: each get() passed to the constructor (typed by the constructor's parameter), every other get<T>(),
        // and for singleOf(::Repo) every constructor parameter.
        val params = made?.ctors?.firstOrNull()?.params ?: emptyList()
        if (d.name.endsWith("Of") && made != null) {
            for (prm in params) if (!prm.anns.has(*NOT_INJECTED.toTypedArray())) type(prm.type, made.src, made.psi)?.let { dep ->
                g.addEdge(classId(made.fq), classNode(dep), "injects", s.path, d.line, mapOf("koin" to true))
            }
        }
        val ctorArgs = ctor?.args ?: emptyList()
        for (c in inside) {
            if (c.name != "get" || c.receiver != null) continue
            val at = ctorArgs.indexOfFirst { it.expr === c.psi }
            val paramType = if (at >= 0) (ctorArgs[at].name?.let { n -> params.firstOrNull { it.name == n } } ?: params.getOrNull(at))?.type else null
            val dep = type(c.typeArgs.firstOrNull() ?: c.assignedTo?.type ?: paramType, s, c.psi) ?: continue
            if (at >= 0 && made != null) g.addEdge(classId(made.fq), classNode(dep), "injects", s.path, c.line, mapOf("koin" to true))
            else g.addEdge(did, classNode(dep), "requires", s.path, c.line, mapOf("koin" to true))
        }
    }

    /**
     * The type a definition's lambda returns when it calls no constructor: a function with a declared
     * project return type (`single { createApi() }`, `single { get<AppDatabase>().itemDao() }`), or the class
     * named in it (`single { retrofit.create(Api::class.java) }`).
     */
    private fun produced(s: Source, lambda: PsiElement, calls: Map<PsiElement, Call>): Type? {
        val last = ((lambda as? KtLambdaExpression)?.bodyExpression as? KtBlockExpression)?.statements?.lastOrNull() ?: return null
        val qualified = last as? KtDotQualifiedExpression
        val callee = calls[qualified?.selectorExpression ?: last]
        if (callee != null) {
            // get<AppDatabase>().itemDao(): the function is looked up on the type get() returns.
            val on = qualified?.receiverExpression?.let { calls[it] }?.takeIf { it.name == "get" }?.typeArgs?.firstOrNull()?.let { type(it, s, last) }
            val fn = (if (on != null) index.resolveMember("${on.fq}.${callee.name}", s, last) else index.resolveMember(callee.name, s, last)) as? Fn
            type(fn?.returns, s, last)?.let { return it }
        }
        return classRefs(last).firstNotNullOfOrNull { (name, at) -> index.resolveType(name, s, at) }
    }

    /** Extra types a definition is bound to: `bind<X>()` in its options lambda, or the infix `bind X::class` after it. */
    private fun bound(s: Source, d: Call, inside: List<Call>): List<Type> {
        val out = ArrayList<Type>()
        for (c in inside) if (c.name == "bind" || c.name == "binds") {
            c.typeArgs.firstOrNull()?.let { t -> type(t, s, c.psi)?.let { out.add(it) } }
            for ((name, at) in c.args.flatMap { classRefs(it.psi) }) index.resolveType(name, s, at)?.let { out.add(it) }
        }
        var e: PsiElement = d.psi
        while (true) {
            val b = e.parent as? KtBinaryExpression ?: break
            if (b.left != e || b.operationReference.getReferencedName() !in setOf("bind", "binds")) break
            for ((name, at) in classRefs(b.right)) index.resolveType(name, s, at)?.let { out.add(it) }
            e = b
        }
        return out
    }

    /** `@Single class Repo(dao: Dao) : Store` (or an @Inject constructor): an injectable bound to its supertypes and to `binds = [...]`. */
    private fun annotatedClass(t: Type) {
        val ann = t.anns.firstOrNull { it.name in ANNOTATED } ?: t.ctors.firstOrNull { it.anns.has("Inject") }?.anns?.find("Inject") ?: return
        val cid = nodeForClass(g, t, "injectable", mapOf("koin" to true))
        if (ann.name == "KoinViewModel") nodeForClass(g, t, "viewmodel")
        val supers = t.supers.mapNotNull { type(it.type, t.src, t.psi) } + ann.args.flatMap { classRefs(it.psi) }.mapNotNull { (n, at) -> index.resolveType(n, t.src, at) }
        for (sup in supers.distinct()) g.addEdge(classNode(sup), cid, "bound_by", t.src.path, t.line)
        for (prm in t.ctors.firstOrNull()?.params ?: emptyList()) {
            if (prm.anns.has(*NOT_INJECTED.toTypedArray())) continue
            type(prm.type, t.src, t.psi)?.let { g.addEdge(cid, classNode(it), "injects", t.src.path, prm.line, mapOf("koin" to true)) }
        }
    }

    /** `@Single fun provideApi(client: Client): Api` in a Koin @Module class. */
    private fun annotatedFunction(f: Fn) {
        if (f.owner?.anns?.has("Module") != true || f.anns.none { it.name in ANNOTATED }) return
        val fq = f.fq ?: return
        val provided = type(f.returns, f.src, f.psi) ?: return
        val did = g.addNode(funId(fq), "koin_definition", "${f.owner.name}.${f.name}", f.src.path, f.line, f.src.module,
            mapOf("style" to f.anns.first { it.name in ANNOTATED }.name.lowercase(), "provides" to listOf(provided.fq), "source_set" to f.src.sourceSet))
        g.addEdge(classNode(provided), did, "bound_by", f.src.path, f.line)
        for (prm in f.params) {
            if (prm.anns.has(*NOT_INJECTED.toTypedArray())) continue
            type(prm.type, f.src, f.psi)?.let { g.addEdge(did, classNode(it), "requires", f.src.path, prm.line, mapOf("koin" to true)) }
        }
    }

    /** `koinInject<Repo>()`, `val repo: Repo by inject()` or `get<Repo>()` outside a definition. */
    private fun use(c: Call) {
        val dep = type(c.typeArgs.firstOrNull() ?: c.assignedTo?.type, c.src, c.psi) ?: return
        val from = c.fn?.let { nodeForFun(g, it, "function") } ?: c.assignedTo?.owner?.let { classNode(it) } ?: return
        g.addEdge(from, classNode(dep), "injects", c.src.path, c.line, mapOf("koin" to true))
    }
}
