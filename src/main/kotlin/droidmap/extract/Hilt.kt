package droidmap.extract

import droidmap.Graph
import droidmap.Project
import droidmap.Source
import droidmap.TypeRef
import droidmap.calleeName
import droidmap.classChain
import droidmap.fq
import droidmap.hasAnn
import droidmap.simpleName
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

private val SCOPES = setOf("Singleton", "ActivityScoped", "ViewModelScoped", "ActivityRetainedScoped", "FragmentScoped",
    "ServiceScoped", "ViewScoped", "Reusable")
private val MULTIBINDING = setOf("IntoSet", "IntoMap", "ElementsIntoSet")
private val VM_DELEGATES = setOf("viewModels", "activityViewModels", "hiltNavGraphViewModels", "navGraphViewModels", "viewModel", "hiltViewModel")
private val VM_CALLS = setOf("hiltViewModel", "viewModel", "hiltActivityViewModel")

/** Hilt / Dagger: ViewModels, @Inject, modules with @Provides/@Binds, qualifiers, entry points. */
class HiltScan(private val p: Project, private val g: Graph) {
    private val qualifiers = mutableSetOf("ApplicationContext", "ActivityContext")
    private val viewmodels = HashSet<String>()
    private val modules = HashMap<String, KtClassOrObject>()

    private fun isDiFile(s: Source) = s.importsFrom("dagger") || s.importsFrom("javax.inject") || s.importsFrom("jakarta.inject")

    private fun classes(s: Source) = s.file.collectDescendantsOfType<KtClassOrObject>()
        .filter { it.fq() != null && !(it is KtObjectDeclaration && it.isObjectLiteral()) }

    fun run() {
        val files = p.sources.filter(::isDiFile)
        for (s in files) for (c in classes(s)) {
            if (c is KtClass && c.isAnnotation() && c.hasAnn("Qualifier")) c.name?.let { qualifiers.add(it) }
            if (c.hasAnn("HiltViewModel")) viewmodels.add(c.fq()!!)
        }
        for (s in files) for (c in classes(s)) klass(s, c)
        for (s in files) for (f in s.file.collectDescendantsOfType<KtNamedFunction>()) provider(s, f)
        for (s in p.sources) if (!s.isTest) viewModelUses(s)
    }

    // ----- helpers -----
    private fun qualifier(anns: List<KtAnnotationEntry>): String? {
        for (a in anns) {
            val n = a.simpleName()
            if (n in qualifiers) {
                val args = a.valueArgumentList?.text?.filterNot { it.isWhitespace() }
                return if (args != null) "$n$args" else n
            }
            if (n == "Named") {
                val v = p.eval.eval(a.valueArguments.firstOrNull()?.getArgumentExpression())
                return if (v is String) "Named($v)" else "Named(?)"
            }
        }
        return null
    }

    private fun typeDecl(s: Source, ref: KtTypeReference?, scope: org.jetbrains.kotlin.com.intellij.psi.PsiElement?): KtClassOrObject? {
        val t = TypeRef.of(ref) ?: return null
        if (t.fn) return null
        return p.index.resolveType(t.unwrapped().name, s, scope)
    }

    private fun typeNode(d: KtClassOrObject): String {
        val ds = p.index.sourceOf(d)!!
        return nodeForClass(g, d, ds, "class")
    }

    private fun scopeOf(anns: List<KtAnnotationEntry>) = anns.firstOrNull { it.simpleName() in SCOPES }?.simpleName()

    // ----- classes -----
    private fun klass(s: Source, c: KtClassOrObject) {
        val fq = c.fq()!!
        val roles = ArrayList<String>()
        if (fq in viewmodels) roles.add("viewmodel")
        val ctor = c.primaryConstructor?.takeIf { it.hasAnn("Inject", "AssistedInject") }
            ?: (c as? KtClass)?.secondaryConstructors?.firstOrNull { it.hasAnn("Inject", "AssistedInject") }
        if (ctor != null) roles.add("injectable")
        val entryPoint = c.hasAnn("AndroidEntryPoint", "HiltAndroidApp")
        if (entryPoint) roles.add("component")
        if (c.hasAnn("EntryPoint")) roles.add("entry_point")
        if (c.hasAnn("AssistedFactory")) roles.add("injectable")
        if (c.hasAnn("Module")) { roles.add("hilt_module"); modules[fq] = c }
        val members = c.declarations
        if (roles.isEmpty()) {
            if (members.none { it is KtProperty && it.hasAnn("Inject") }) return
            roles.add("class")
        }
        val cid = classId(fq)
        for (r in roles) nodeForClass(g, c, s, r, mapOf(
            "scope" to scopeOf(c.annotationEntries),
            "assisted" to (if (ctor?.hasAnn("AssistedInject") == true) true else null),
            "assisted_factory" to (if (c.hasAnn("AssistedFactory")) true else null),
            "android_entry_point" to (if (entryPoint) true else null)))
        if (c.hasAnn("Module")) {
            val comps = c.annotationEntries.filter { it.simpleName() in setOf("InstallIn", "TestInstallIn") }
                .flatMap { a -> Regex("\\b(\\w+Component)\\b").findAll(a.valueArgumentList?.text ?: "").map { it.groupValues[1] } }
            g.nodes[cid]!!.attrs.putIfAbsent("install_in", comps.toSortedSet().toList())
            if (c.hasAnn("TestInstallIn")) g.nodes[cid]!!.attrs["test_install_in"] = true
        }
        if (ctor != null) for (prm in ctor.valueParameters) {
            if (prm.hasAnn("Assisted")) continue
            injectEdge(s, cid, prm.typeReference, prm.annotationEntries, c, s.line(prm))
        }
        for (m in members) {
            if (m is KtProperty && m.hasAnn("Inject")) injectEdge(s, cid, m.typeReference, m.annotationEntries, c, s.line(m))
            if (m is KtProperty && m.delegateExpression != null) vmDelegate(s, cid, m, c)
            if (m is KtNamedFunction && c.hasAnn("EntryPoint") && m.typeReference != null) {
                injectEdge(s, cid, m.typeReference, m.annotationEntries, c, s.line(m))
            }
        }
    }

    private fun injectEdge(s: Source, src: String, ref: KtTypeReference?, anns: List<KtAnnotationEntry>, scope: KtClassOrObject, line: Int) {
        val target = typeDecl(s, ref, scope) ?: return
        g.addEdge(src, typeNode(target), "injects", s.path, line, mapOf("qualifier" to qualifier(anns)))
    }

    private fun vmDelegate(s: Source, src: String, prop: KtProperty, scope: KtClassOrObject) {
        val d = prop.delegateExpression
        val call = d as? KtCallExpression ?: (d as? KtDotQualifiedExpression)?.selectorExpression as? KtCallExpression ?: return
        if (call.calleeName() !in VM_DELEGATES) return
        val target = typeDecl(s, prop.typeReference, scope)
            ?: call.typeArguments.firstOrNull()?.typeReference?.let { typeDecl(s, it, scope) }
            ?: return
        if (target.fq() in viewmodels) g.addEdge(src, classId(target.fq()!!), "uses_viewmodel", s.path, s.line(prop))
    }

    // ----- @Provides / @Binds -----
    private fun moduleOf(f: KtNamedFunction): KtClassOrObject? {
        for (c in classChain(f)) {
            val fq = c.fq() ?: continue
            if (fq in modules) return c
            if (c is KtObjectDeclaration && c.isCompanion()) {
                val parent = c.getStrictParentOfType<KtClassOrObject>()
                if (parent?.fq() in modules) return parent
            }
        }
        return null
    }

    private fun provider(s: Source, f: KtNamedFunction) {
        val style = when {
            f.hasAnn("Provides") -> "provides"
            f.hasAnn("Binds") -> "binds"
            else -> return
        }
        val fq = f.fqName?.asString() ?: return
        val module = moduleOf(f)
        val q = qualifier(f.annotationEntries)
        val multi = f.annotationEntries.firstOrNull { it.simpleName() in MULTIBINDING }?.simpleName()
        val returns = TypeRef.of(f.typeReference)?.unwrapped()
        val target = typeDecl(s, f.typeReference, f)
        val pid = funId(fq)
        g.addNode(pid, "hilt_provider", if (module != null) "${module.name}.${f.name}" else f.name, s.path, s.line(f), s.module,
            mapOf("source_set" to s.sourceSet, "style" to style, "returns" to (target?.fq() ?: returns?.toString()),
                "qualifier" to q, "scope" to scopeOf(f.annotationEntries), "multibinding" to multi,
                "hilt_module" to module?.fq(), "test_install_in" to (if (module?.hasAnn("TestInstallIn") == true) true else null)))
        if (module != null) g.addEdge(pid, classId(module.fq()!!), "declared_in", s.path, s.line(f))
        if (target != null) g.addEdge(typeNode(target), pid, "bound_by", s.path, s.line(f), mapOf("qualifier" to q, "multibinding" to multi))
        for (prm in f.valueParameters) {
            val dep = typeDecl(s, prm.typeReference, f) ?: continue
            g.addEdge(pid, typeNode(dep), "requires", s.path, s.line(prm), mapOf("qualifier" to qualifier(prm.annotationEntries)))
        }
    }

    // ----- ViewModel users -----
    private fun viewModelUses(s: Source) {
        if (viewmodels.isEmpty()) return
        for (f in s.file.collectDescendantsOfType<KtNamedFunction>()) {
            for (prm in f.valueParameters) {
                val t = typeDecl(s, prm.typeReference, f) ?: continue
                if (t.fq() in viewmodels) nodeForFun(g, f, s, "function")?.let {
                    g.addEdge(it, classId(t.fq()!!), "uses_viewmodel", s.path, s.line(prm))
                }
            }
            val body = f.bodyExpression ?: continue
            for (call in body.collectDescendantsOfType<KtCallExpression> { it.calleeName() in VM_CALLS }) {
                if (call.getStrictParentOfType<KtNamedFunction>() != f) continue
                // hiltViewModel<HomeViewModel>(), or val vm: HomeViewModel = hiltViewModel()
                val ref = call.typeArguments.firstOrNull()?.typeReference
                    ?: (call.parent as? KtProperty)?.takeIf { it.initializer == call }?.typeReference ?: continue
                val t = typeDecl(s, ref, f) ?: continue
                if (t.fq() in viewmodels) nodeForFun(g, f, s, "function")?.let {
                    g.addEdge(it, classId(t.fq()!!), "uses_viewmodel", s.path, s.line(call))
                }
            }
        }
    }
}
