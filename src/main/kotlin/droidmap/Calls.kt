package droidmap

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiParenthesizedExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiThisExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCastExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import java.util.IdentityHashMap

/** A test class that reaches changed code through calls. [instrumented]: an Android device test (androidTest). */
class TestRef(val fq: String, val name: String, val module: String?, val file: String, val instrumented: Boolean) {
    fun toMap() = linkedMapOf("class" to fq, "name" to name, "module" to module, "file" to file, "instrumented" to instrumented)
}

/**
 * Who calls what in the project's own code. Each call is resolved to a project function or constructor, its
 * receiver typed from what the code declares: a property, parameter or local with a type, a constructor call,
 * a function's declared return type, or a type argument (`by viewModels<HomeViewModel>()`). Nothing is
 * inferred beyond that. A call on an interface or a base class also reaches the overrides in its subtypes.
 * Keys are graph ids: `fun::<fq>` for functions, `class::<fq>` for classes.
 */
class Calls(private val code: Code) {
    private val index = code.index
    private val callers = HashMap<String, MutableSet<String>>()
    private val decls = HashMap<String, Decl>()
    private val callByPsi = IdentityHashMap<PsiElement, Call>()
    private val fieldTypes = IdentityHashMap<Field, Type?>()
    private val resolving = HashSet<Field>()
    private val supertypes = IdentityHashMap<Type, List<Type>>()

    init {
        for (s in code.sources) for (c in s.calls) callByPsi[c.psi] = c
        for (s in code.sources) for (c in s.calls) {
            val from = callerOf(c) ?: continue
            val to = callee(c, 0) ?: continue
            link(from, to)
        }
        // A call to Base.f (or an interface's f) can run any override of f.
        for (s in code.sources) for (t in s.types) for (f in t.fns) {
            if (f.fq == null) continue
            for (a in ancestors(t)) index.membersOf("${a.fq}.${f.name}").filterIsInstance<Fn>().firstOrNull()?.let { link(it, f) }
        }
    }

    /** Callers so far: [b] is reached from [a]. */
    private fun link(a: Decl, b: Decl) {
        val ka = key(a) ?: return
        val kb = key(b) ?: return
        if (ka == kb) return
        decls[ka] = a
        decls[kb] = b
        callers.getOrPut(kb) { HashSet() }.add(ka)
    }

    /** How many calls were resolved to a project declaration (for tests and statistics). */
    val size: Int get() = callers.values.sumOf { it.size }

    /** The keys that call [key] directly. */
    fun callersOf(key: String): Set<String> = callers[key] ?: emptySet()

    /**
     * The test classes whose code reaches [keys] through calls, at most [maxDepth] calls away: a class that has
     * `@Test` functions (or is named like a test) in a test source set.
     */
    fun testsReaching(keys: Collection<String>, maxDepth: Int = 12): List<TestRef> {
        val seen = HashSet(keys)
        var level = keys.toList()
        val tests = LinkedHashMap<String, TestRef>()
        var depth = 0
        while (level.isNotEmpty() && depth++ < maxDepth) {
            val next = ArrayList<String>()
            for (k in level) for (c in callersOf(k)) {
                if (!seen.add(c)) continue
                val d = decls[c] ?: continue
                if (d.src.isTest) testClass(d)?.let { t -> tests.putIfAbsent(t.fq, ref(t)) }
                next.add(c)
            }
            level = next
        }
        return tests.values.sortedWith(compareBy({ it.module ?: "" }, { it.fq }))
    }

    /** [key], and for a class the keys of its functions too: a changed class reaches what calls its members. */
    fun withMembers(key: String): Set<String> {
        val t = if (key.startsWith("class::")) index.types[key.removePrefix("class::")] else null
        return setOf(key) + (t?.fns?.mapNotNull { f -> f.fq?.let { "fun::$it" } } ?: emptyList())
    }

    /**
     * The declarations whose lines [changes] touch, innermost first (a function, not the class around it; a
     * class with its functions when the change is outside them), as keys.
     */
    fun changedKeys(changes: Changes): Set<String> {
        val byPath = code.sources.associateBy { it.path }
        val out = LinkedHashSet<String>()
        for (fc in changes.files) {
            val s = byPath[fc.path] ?: continue
            val decls: List<Decl> = s.fns.filter { it.fq != null } + s.types
            if (fc.whole) { decls.forEach { d -> key(d)?.let { out.add(it) } }; continue }
            for (r in fc.ranges) {
                val around = decls.filter { it.line <= r.last && r.first <= it.endLine }
                val innermost = around.filter { d -> around.none { o -> o !== d && d.psi.textRange.contains(o.psi.textRange) } }
                for (d in innermost) key(d)?.let { out += withMembers(it) }
            }
        }
        return out
    }

    /** The keys for a graph node: its own id for a class or function, or else the declaration at its file and line. */
    fun keysFor(node: Node): Set<String> {
        if (node.id.startsWith("class::") || node.id.startsWith("fun::")) return withMembers(node.id)
        val s = code.sources.firstOrNull { it.path == node.file } ?: return emptySet()
        val d = (s.types + s.fns).firstOrNull { it.line == node.line } ?: return emptySet()
        return key(d)?.let { withMembers(it) } ?: emptySet()
    }

    private fun ref(t: Type): TestRef = TestRef(t.fq, t.label, t.src.module, t.src.path, t.src.sourceSet?.startsWith("androidTest") == true)

    /** The top-level class [d] is in, when it is a test class. */
    private fun testClass(d: Decl): Type? {
        var t: Type? = when (d) {
            is Type -> d
            is Fn -> d.owner
            else -> null
        } ?: return null
        while (t?.outer != null) t = t.outer
        val top = t ?: return null
        val hasTests = generateSequence(listOf(top)) { ts -> ts.flatMap { o -> top.src.types.filter { it.outer === o } }.ifEmpty { null } }
            .flatten().any { c -> c.fns.any { it.anns.has("Test", "ParameterizedTest") } }
        return top.takeIf { hasTests || TEST_NAME.matches(it.label) }
    }

    private fun key(d: Decl): String? = when (d) {
        is Fn -> d.fq?.let { "fun::$it" }
        is Type -> "class::${d.fq}"
        else -> null
    }

    /** The function (or else the class) a call is made from. */
    private fun callerOf(c: Call): Decl? =
        index.fnsAround(c.psi).firstOrNull { it.fq != null } ?: index.typesAround(c.psi).firstOrNull()

    private fun type(ref: TypeRef?, src: Source, at: PsiElement?): Type? =
        ref?.let { index.resolveType(it.unwrapped().name, src, at) }

    private fun ancestors(t: Type): List<Type> {
        val out = ArrayList<Type>()
        val todo = ArrayDeque(supers(t))
        while (todo.isNotEmpty() && out.size < 32) {
            val a = todo.removeFirst()
            if (a === t || out.any { it === a }) continue
            out.add(a)
            todo.addAll(supers(a))
        }
        return out
    }

    private fun supers(t: Type): List<Type> = supertypes.getOrPut(t) { t.supers.mapNotNull { index.resolveType(it.type.name, t.src, null) } }

    /** [name] as a function of [t], its companion or a supertype. */
    private fun memberFn(t: Type, name: String): Fn? {
        for (c in listOf(t) + ancestors(t)) {
            (index.membersOf("${c.fq}.$name") + index.membersOf("${c.fq}.Companion.$name")).filterIsInstance<Fn>().firstOrNull()?.let { return it }
        }
        return null
    }

    /** [name] as a property of [t] or a supertype. */
    private fun memberField(t: Type, name: String): Field? {
        for (c in listOf(t) + ancestors(t)) {
            index.membersOf("${c.fq}.$name").filterIsInstance<Field>().firstOrNull()?.let { return it }
            c.fields.firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }

    private fun receiverOf(c: Call): PsiElement? = when (val p = c.psi) {
        is KtCallExpression -> (p.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == p }?.receiverExpression
        is PsiMethodCallExpression -> p.methodExpression.qualifierExpression
        else -> null
    }

    private fun isExtension(f: Fn) = (f.psi as? KtNamedFunction)?.receiverTypeReference != null

    /** The project function or class (for a constructor call) that [c] calls. */
    private fun callee(c: Call, depth: Int): Decl? {
        val src = c.src
        if (c.isNew) return index.resolveType(listOfNotNull(c.receiver, c.name).joinToString("."), src, c.psi)
        val recv = receiverOf(c)
        val ctor = c.name.first().isUpperCase()
        if (recv == null) {
            if (ctor) index.resolveType(c.name, src, c.psi)?.let { return it }
            for (t in index.typesAround(c.psi)) memberFn(t, c.name)?.let { return it }
            return index.resolveMember(c.name, src, c.psi) as? Fn
        }
        val rt = typeOf(recv, src, depth + 1)
        if (rt != null) {
            memberFn(rt, c.name)?.let { return it }
            if (ctor) index.types["${rt.fq}.${c.name}"]?.let { return it }
        }
        if (ctor) c.receiver?.let { r -> index.resolveType("$r.${c.name}", src, c.psi) }?.let { return it }
        return (index.resolveMember(c.name, src, c.psi) as? Fn)?.takeIf { isExtension(it) }
    }

    /** The project type an expression has, from what is declared; null when that is not known. */
    private fun typeOf(e: PsiElement?, src: Source, depth: Int): Type? {
        if (e == null || depth > MAX_DEPTH) return null
        return when (e) {
            is KtParenthesizedExpression -> typeOf(e.expression, src, depth + 1)
            is KtPostfixExpression -> typeOf(e.baseExpression, src, depth + 1) // x!!
            is KtBinaryExpressionWithTypeRHS -> type(KotlinReader.typeRef(e.right), src, e) // x as T
            is KtThisExpression -> index.typesAround(e).firstOrNull()
            is KtNameReferenceExpression -> nameType(e.getReferencedName(), src, e, depth)
            is KtCallExpression -> returned(e, depth)
            is KtQualifiedExpression -> when (val sel = e.selectorExpression) {
                is KtCallExpression -> returned(sel, depth)
                is KtNameReferenceExpression -> typeOf(e.receiverExpression, src, depth + 1)?.let { rt ->
                    memberField(rt, sel.getReferencedName())?.let { fieldType(it, depth + 1) } ?: index.types["${rt.fq}.${sel.getReferencedName()}"]
                } ?: index.resolveType(e.text.filterNot { it.isWhitespace() }, src, e)
                else -> null
            }
            is PsiParenthesizedExpression -> typeOf(e.expression, src, depth + 1)
            is PsiTypeCastExpression -> e.castType?.text?.let { index.resolveType(it.substringBefore('<'), src, e) }
            is PsiThisExpression -> index.typesAround(e).firstOrNull()
            is PsiMethodCallExpression -> returned(e, depth)
            is PsiNewExpression -> e.classReference?.text?.let { index.resolveType(it.substringBefore('<'), src, e) }
            is PsiReferenceExpression -> {
                val q = e.qualifierExpression
                val name = e.referenceName ?: return null
                if (q == null) nameType(name, src, e, depth)
                else typeOf(q, src, depth + 1)?.let { rt -> memberField(rt, name)?.let { fieldType(it, depth + 1) } }
                    ?: index.resolveType(e.text.filterNot { it.isWhitespace() }, src, e)
            }
            else -> null
        }
    }

    /** What a call expression returns: the class it constructs, or its function's declared return type. */
    private fun returned(e: PsiElement, depth: Int): Type? {
        val c = callByPsi[e] ?: return null
        return when (val d = callee(c, depth + 1)) {
            is Type -> d
            is Fn -> type(d.returns, d.src, d.psi)
            else -> null
        }
    }

    /** The type of a name: a local, a parameter, a property of a class around it (or its supertypes), a top-level property, or else a class (a static or companion call). */
    private fun nameType(name: String, src: Source, at: PsiElement, depth: Int): Type? {
        index.local(name, at)?.let { return fieldType(it, depth + 1) }
        for (f in index.fnsAround(at)) f.params.firstOrNull { it.name == name }?.let { return type(it.type, f.src, f.psi) }
        for (t in index.typesAround(at)) {
            memberField(t, name)?.let { return fieldType(it, depth + 1) }
            t.ctors.firstOrNull()?.params?.firstOrNull { it.name == name }?.let { return type(it.type, t.src, t.psi) }
        }
        (index.resolveMember(name, src, at) as? Field)?.let { return fieldType(it, depth + 1) }
        return if (name.first().isUpperCase()) index.resolveType(name, src, at) else null
    }

    /** A property's type: declared, or else from its initializer or delegate (`by viewModels<X>()`, `by lazy { X() }`). */
    private fun fieldType(f: Field, depth: Int): Type? {
        if (f in fieldTypes) return fieldTypes[f]
        if (!resolving.add(f) || depth > MAX_DEPTH) return null
        val t = try {
            type(f.type, f.src, f.psi)
                ?: typeOf(f.initializer, f.src, depth + 1)
                ?: f.delegate?.let { delegated(it, f.src, depth + 1) }
        } finally {
            resolving.remove(f)
        }
        fieldTypes[f] = t
        return t
    }

    private fun delegated(d: PsiElement, src: Source, depth: Int): Type? {
        val call = d as? KtCallExpression ?: return typeOf(d, src, depth)
        call.typeArguments.firstOrNull()?.let { return type(KotlinReader.typeRef(it.typeReference), src, call) }
        val lambda = call.lambdaArguments.lastOrNull()?.getLambdaExpression() as? KtLambdaExpression
        val last = (lambda?.bodyExpression as? KtBlockExpression)?.statements?.lastOrNull()
        return if (last != null) typeOf(last, src, depth + 1) else typeOf(call, src, depth)
    }

    private companion object {
        const val MAX_DEPTH = 10
        val TEST_NAME = Regex(".*(Test|Tests|Spec|IT)")
    }
}
