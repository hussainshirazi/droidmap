package droidmap.extract

import droidmap.Ann
import droidmap.Call
import droidmap.Code
import droidmap.Field
import droidmap.Fn
import droidmap.Graph
import droidmap.Source
import droidmap.Type
import droidmap.TypeKind
import droidmap.TypeRef
import droidmap.classRefs
import droidmap.has
import org.jetbrains.kotlin.com.intellij.psi.PsiElement

private val SCOPES = setOf("Singleton", "ActivityScoped", "ViewModelScoped", "ActivityRetainedScoped", "FragmentScoped",
    "ServiceScoped", "ViewScoped", "Reusable")
private val MULTIBINDING = setOf("IntoSet", "IntoMap", "ElementsIntoSet")
private val COMPONENT = Regex("\\b(\\w+Component)\\b")

/** Hilt / Dagger: ViewModels, @Inject, modules with @Provides/@Binds, qualifiers, entry points. */
class HiltScan(private val code: Code, private val g: Graph) {
    private val index = code.index
    private val qualifiers = mutableSetOf("ApplicationContext", "ActivityContext")
    private val modules = HashMap<String, Type>()

    private fun isDiFile(s: Source) = s.importsFrom("dagger") || s.importsFrom("javax.inject") || s.importsFrom("jakarta.inject")

    fun run() {
        // Dagger (and so Hilt) is in use when app code imports it; leftovers in tests alone do not count.
        if (code.sources.none { !it.isTest && it.importsFrom("dagger") }) return
        val files = code.sources.filter(::isDiFile)
        for (s in files) for (t in s.types) {
            if (t.kind == TypeKind.ANNOTATION && t.anns.has("Qualifier")) t.name?.let { qualifiers.add(it) }
        }
        for (s in files) for (t in s.types) klass(t)
        for (s in files) for (f in s.fns) provider(f)
    }

    // ----- helpers -----
    private fun qualifier(anns: List<Ann>): String? {
        for (a in anns) {
            if (a.name in qualifiers) {
                val args = a.text?.filterNot { it.isWhitespace() }
                return if (args != null) "${a.name}$args" else a.name
            }
            if (a.name == "Named") {
                val v = code.eval.eval(a.args.firstOrNull()?.expr)
                return if (v is String) "Named($v)" else "Named(?)"
            }
        }
        return null
    }

    private fun typeDecl(ref: TypeRef?, src: Source, scope: PsiElement?): Type? =
        if (ref == null || ref.fn) null else index.resolveType(ref.unwrapped().name, src, scope)

    private fun scopeOf(anns: List<Ann>) = anns.firstOrNull { it.name in SCOPES }?.name

    // ----- classes -----
    private fun klass(t: Type) {
        val roles = ArrayList<String>()
        if (t.anns.has("HiltViewModel")) roles.add("viewmodel")
        val ctor = t.ctors.firstOrNull { it.anns.has("Inject", "AssistedInject") }
        if (ctor != null) roles.add("injectable")
        val entryPoint = t.anns.has("AndroidEntryPoint", "HiltAndroidApp")
        if (entryPoint) roles.add("component")
        if (t.anns.has("EntryPoint")) roles.add("entry_point")
        if (t.anns.has("AssistedFactory")) roles.add("injectable")
        if (t.anns.has("Module")) { roles.add("hilt_module"); modules[t.fq] = t }
        val injected = t.fields.filter { !it.isParam && it.anns.has("Inject") }
        if (roles.isEmpty()) {
            if (injected.isEmpty()) return
            roles.add("class")
        }
        val cid = classId(t.fq)
        for (r in roles) nodeForClass(g, t, r, mapOf(
            "scope" to scopeOf(t.anns),
            "assisted" to flag(ctor?.anns?.has("AssistedInject") == true),
            "assisted_factory" to flag(t.anns.has("AssistedFactory")),
            "android_entry_point" to flag(entryPoint)))
        if (t.anns.has("Module")) {
            val comps = t.anns.filter { it.name == "InstallIn" || it.name == "TestInstallIn" }
                .flatMap { a -> COMPONENT.findAll(a.text ?: "").map { it.groupValues[1] } }
            g.nodes[cid]!!.attrs.putIfAbsent("install_in", comps.toSortedSet().toList())
            if (t.anns.has("TestInstallIn")) g.nodes[cid]!!.attrs["test_install_in"] = true
        }
        if (ctor != null) for (prm in ctor.params) {
            if (prm.anns.has("Assisted")) continue
            injectEdge(cid, prm.type, prm.anns, t, prm.line)
        }
        for (f in injected) injectEdge(cid, f.type, f.anns, t, f.line)
        if (t.anns.has("EntryPoint")) for (m in t.fns) if (m.returns != null) injectEdge(cid, m.returns, m.anns, t, m.line)
    }

    private fun injectEdge(src: String, ref: TypeRef?, anns: List<Ann>, scope: Type, line: Int) {
        val target = typeDecl(ref, scope.src, scope.psi) ?: return
        g.addEdge(src, nodeForClass(g, target, "class"), "injects", scope.src.path, line, mapOf("qualifier" to qualifier(anns)))
    }

    // ----- @Provides / @Binds -----
    /** The @Module class a function is declared in (directly, or in its companion object). */
    private fun moduleOf(f: Fn): Type? {
        var t = f.owner
        while (t != null) {
            if (t.fq in modules) return t
            if (t.kind == TypeKind.COMPANION && t.outer?.fq in modules) return t.outer
            t = t.outer
        }
        return null
    }

    private fun provider(f: Fn) {
        val style = when {
            f.anns.has("Provides") -> "provides"
            f.anns.has("Binds") -> "binds"
            else -> return
        }
        val fq = f.fq ?: return
        val s = f.src
        val module = moduleOf(f)
        val q = qualifier(f.anns)
        val multi = f.anns.firstOrNull { it.name in MULTIBINDING }?.name
        val returns = f.returns?.unwrapped()
        val target = typeDecl(f.returns, s, f.psi)
        val pid = funId(fq)
        g.addNode(pid, "hilt_provider", if (module != null) "${module.name}.${f.name}" else f.name, s.path, f.line, s.module,
            mapOf("source_set" to s.sourceSet, "style" to style, "returns" to (target?.fq ?: returns?.copy(nullable = false)?.toString()),
                "qualifier" to q, "scope" to scopeOf(f.anns), "multibinding" to multi,
                "hilt_module" to module?.fq, "test_install_in" to flag(module?.anns?.has("TestInstallIn") == true)))
        if (module != null) g.addEdge(pid, classId(module.fq), "declared_in", s.path, f.line)
        if (target != null) g.addEdge(nodeForClass(g, target, "class"), pid, "bound_by", s.path, f.line, mapOf("qualifier" to q, "multibinding" to multi))
        for (prm in f.params) {
            val dep = typeDecl(prm.type, s, f.psi) ?: continue
            g.addEdge(pid, nodeForClass(g, dep, "class"), "requires", s.path, prm.line, mapOf("qualifier" to qualifier(prm.anns)))
        }
    }
}

private val VM_DELEGATES = setOf("viewModels", "activityViewModels", "hiltNavGraphViewModels", "navGraphViewModels", "viewModel",
    "hiltViewModel", "activityViewModel", "sharedViewModel")
private val VM_CALLS = setOf("hiltViewModel", "viewModel", "hiltActivityViewModel", "koinViewModel", "koinActivityViewModel",
    "getViewModel", "koinNavViewModel")

/**
 * Who uses each ViewModel (whatever DI made it one): composables that get it with hiltViewModel() or
 * koinViewModel() or take it as a parameter, classes with a `by viewModels()` property, and Java's
 * `new ViewModelProvider(this).get(X.class)`. Runs after the DI scans, which mark the ViewModels.
 */
class ViewModelUses(private val code: Code, private val g: Graph) {
    private val viewmodels = g.byKind("viewmodel").map { it.id }.toHashSet()

    private fun vm(ref: TypeRef?, src: Source, scope: PsiElement?): String? {
        if (ref == null || ref.fn) return null
        return code.index.resolveType(ref.unwrapped().name, src, scope)?.let { classId(it.fq) }?.takeIf { it in viewmodels }
    }

    fun run() {
        if (viewmodels.isEmpty()) return
        for (s in code.sources) {
            if (s.isTest) continue
            for (f in s.fns) for (prm in f.params) {
                val vm = vm(prm.type, s, f.psi) ?: continue
                nodeForFun(g, f, "function")?.let { g.addEdge(it, vm, "uses_viewmodel", s.path, prm.line) }
            }
            for (call in s.calls) {
                val assigned = call.assignedTo
                when {
                    call.name in VM_DELEGATES && assigned?.delegate != null && assigned.owner != null -> delegate(call, assigned, assigned.owner)
                    call.name in VM_CALLS && call.fn != null -> {
                        val vm = vm(call.typeArgs.firstOrNull() ?: assigned?.type, s, call.fn.psi) ?: continue
                        nodeForFun(g, call.fn, "function")?.let { g.addEdge(it, vm, "uses_viewmodel", s.path, call.line) }
                    }
                    call.name == "get" && call.receiver?.contains("ViewModelProvider") == true -> provider(call)
                }
            }
        }
    }

    /** The class's node; one that exists keeps its roles. */
    private fun classNode(t: Type) = classId(t.fq).takeIf { it in g.nodes } ?: nodeForClass(g, t, "class")

    /** `private val vm: HomeViewModel by viewModels()` or `by viewModels<HomeViewModel>()` in a class. */
    private fun delegate(call: Call, prop: Field, owner: Type) {
        val vm = vm(prop.type, owner.src, owner.psi) ?: vm(call.typeArgs.firstOrNull(), owner.src, owner.psi) ?: return
        g.addEdge(classNode(owner), vm, "uses_viewmodel", owner.src.path, prop.line)
    }

    /** `new ViewModelProvider(this).get(HomeViewModel.class)` (or ::class.java) in a class. */
    private fun provider(call: Call) {
        val owner = call.fn?.owner ?: return
        val name = classRefs(call.args.firstOrNull()?.psi).firstOrNull()?.first ?: return
        val vm = vm(TypeRef(name), call.src, call.psi) ?: return
        g.addEdge(classNode(owner), vm, "uses_viewmodel", call.src.path, call.line)
    }
}
