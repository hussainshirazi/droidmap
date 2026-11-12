package droidmap

import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiParenthesizedExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiPolyadicExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiPrefixExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReturnStatement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.util.IdentityHashMap

/** Marks an unknown part of an evaluated string (a template or call we could not resolve). */
const val WILD = ''

/**
 * Name resolution over the project's own declarations, by Kotlin's and Java's rules: imports (star imports
 * too), the package, enclosing classes and the classes nested in their supertypes. Without type inference.
 */
class Index(sources: List<Source>) {
    val types = HashMap<String, Type>()
    private val members = HashMap<String, MutableList<Decl>>()
    private val sourceByFile = IdentityHashMap<PsiFile, Source>()
    private val typeByPsi = IdentityHashMap<PsiElement, Type>()
    private val fnByPsi = IdentityHashMap<PsiElement, Fn>()

    init {
        for (s in sources) {
            sourceByFile[s.psi] = s
            for (t in s.types) {
                typeByPsi[t.psi] = t
                val prev = types[t.fq]
                if (prev == null || (prev.isExpect && !t.isExpect)) types[t.fq] = t // the first one, or an actual over its expect
            }
            for (f in s.fns) {
                fnByPsi[f.psi] = f
                if (f.fq != null) addMember(f.fq, f, f.owner)
            }
            for (f in s.fields) if (f.fq != null && !f.isLocal && !f.isParam) addMember(f.fq, f, f.owner)
        }
    }

    private fun addMember(fq: String, d: Decl, owner: Type?) {
        members.getOrPut(fq) { ArrayList() }.add(d)
        if (owner?.kind == TypeKind.COMPANION) owner.outer?.let { members.getOrPut("${it.fq}.${d.name}") { ArrayList() }.add(d) }
    }

    private fun pick(ds: List<Decl>): Decl = ds.firstOrNull { !(it is Fn && it.isExpect) && !(it is Field && it.isExpect) } ?: ds[0]

    fun sourceOf(el: PsiElement): Source? = sourceByFile[el.containingFile]

    /** The types around [el], innermost first (starting with [el] itself when it is one). */
    fun typesAround(el: PsiElement?): List<Type> {
        val out = ArrayList<Type>()
        var p = el
        while (p != null && p !is PsiFile) {
            typeByPsi[p]?.let { out.add(it) }
            p = p.parent
        }
        return out
    }

    fun resolveType(name: String, src: Source, scope: PsiElement?): Type? {
        if (name.isEmpty()) return null
        types[name]?.let { return it }
        val first = name.substringBefore('.')
        val rest = name.substringAfter('.', "")
        val suffix = if (rest.isEmpty()) "" else ".$rest"
        val cands = ArrayList<String>()
        src.imports[first]?.let { cands.add(it) }
        val around = typesAround(scope)
        for (t in around) cands.add("${t.fq}.$first")
        cands.add(if (src.pkg.isEmpty()) first else "${src.pkg}.$first")
        src.starImports.forEach { cands.add("$it.$first") }
        for (c in cands) types[c + suffix]?.let { return it }
        if (first in src.imports) return null // imported from outside the project
        // A class nested in a supertype of an enclosing class (class Sub : Base() { val x: Inner }).
        for (t in around) for (sup in t.supers) {
            val st = resolveType(sup.type.name, t.src, null) ?: continue
            types["${st.fq}.$first$suffix"]?.let { return it }
        }
        // Anything else is not a project type: Kotlin and Java need an import for a class from another
        // package, so a name without one is the standard library's (Result, Pair) or a library's.
        return null
    }

    fun resolveMember(chain: String, src: Source, scope: PsiElement?): Decl? {
        members[chain]?.let { return pick(it) }
        val head = chain.substringBeforeLast('.', "")
        val last = chain.substringAfterLast('.')
        if (head.isNotEmpty()) {
            val t = resolveType(head, src, scope)?.fq ?: return null
            return (members["$t.$last"] ?: members["$t.Companion.$last"])?.let { pick(it) }
        }
        val cands = ArrayList<String>()
        src.imports[last]?.let { cands.add(it) }
        for (t in typesAround(scope)) { cands.add("${t.fq}.$last"); cands.add("${t.fq}.Companion.$last") }
        cands.add(if (src.pkg.isEmpty()) last else "${src.pkg}.$last")
        src.starImports.forEach { cands.add("$it.$last") }
        for (c in cands) members[c]?.let { return pick(it) }
        return null
    }

    /** The declarations named [fq] exactly ("com.x.Repo.load"): functions (overloads) and properties. */
    fun membersOf(fq: String): List<Decl> = members[fq] ?: emptyList()

    /** The named functions around [el], innermost first. */
    fun fnsAround(el: PsiElement?): List<Fn> {
        val out = ArrayList<Fn>()
        var p = el
        while (p != null && p !is PsiFile) {
            fnByPsi[p]?.let { out.add(it) }
            p = p.parent
        }
        return out
    }

    /** The nearest local val or var named [name] declared before [at] in a function around it. */
    fun local(name: String, at: PsiElement): Field? {
        var p: PsiElement? = at.parent
        while (p != null && p !is PsiFile) {
            fnByPsi[p]?.locals?.lastOrNull { it.name == name && it.psi.textOffset < at.textOffset }?.let { return it }
            p = p.parent
        }
        return null
    }
}

fun parseIntLiteral(text: String): Long? {
    val t = text.replace("_", "").trimEnd('l', 'L', 'u', 'U')
    return try {
        when {
            t.startsWith("0x", true) -> t.substring(2).toLong(16)
            t.startsWith("0b", true) -> t.substring(2).toLong(2)
            else -> t.toLong()
        }
    } catch (e: NumberFormatException) {
        null
    }
}

/**
 * Evaluates constant expressions in Kotlin and Java: literals, string templates, `+`, references to
 * vals (Kotlin) and final fields or effectively final locals (Java), and calls to functions whose body
 * is one expression or one return. Unknown parts of a string become WILD; an expression that is entirely
 * unknown is null. Numbers are Long.
 */
class Evaluator(private val index: Index) {
    private val memo = HashMap<PsiElement, Any?>()
    private val active = HashSet<PsiElement>()

    fun eval(e: PsiElement?, depth: Int = 0): Any? {
        if (e == null || depth > MAX_DEPTH) return null
        return when (e) {
            is KtExpression -> kotlin(e, depth)
            is PsiExpression -> java(e, depth)
            else -> null
        }
    }

    fun evalInt(e: PsiElement?): Int? = (eval(e) as? Long)?.toInt()

    private fun kotlin(e: KtExpression, depth: Int): Any? = when (e) {
        is KtStringTemplateExpression -> buildString {
            for (entry in e.entries) when (entry) {
                is KtLiteralStringTemplateEntry -> append(entry.text)
                is KtEscapeStringTemplateEntry -> append(entry.unescapedValue)
                is KtStringTemplateEntryWithExpression -> append(part(eval(entry.expression, depth + 1)))
                else -> append(WILD)
            }
        }
        is KtConstantExpression -> when (e.text) {
            "true" -> true
            "false" -> false
            else -> parseIntLiteral(e.text)
        }
        is KtParenthesizedExpression -> eval(e.expression, depth + 1)
        is KtPrefixExpression -> if (e.operationToken == KtTokens.MINUS) (eval(e.baseExpression, depth + 1) as? Long)?.let { -it } else null
        is KtBinaryExpression -> if (e.operationToken == KtTokens.PLUS) plus(eval(e.left, depth + 1), eval(e.right, depth + 1)) else null
        is KtNameReferenceExpression -> valueOf(e.getReferencedName(), e, depth)
        is KtDotQualifiedExpression -> kotlinChain(e)?.let { chain ->
            if (e.selectorExpression is KtCallExpression) call(chain, e, depth) else valueOf(chain, e, depth)
        }
        is KtCallExpression -> (e.calleeExpression as? KtNameReferenceExpression)?.let { call(it.getReferencedName(), e, depth) }
        else -> null
    }

    private fun java(e: PsiExpression, depth: Int): Any? = when (e) {
        is PsiLiteralExpression -> when (val v = e.value) {
            is String, is Boolean, is Long -> v
            is Int -> v.toLong()
            else -> null
        }
        is PsiParenthesizedExpression -> eval(e.expression, depth + 1)
        is PsiPrefixExpression -> if (e.operationTokenType == JavaTokenType.MINUS) (eval(e.operand, depth + 1) as? Long)?.let { -it } else null
        is PsiPolyadicExpression -> if (e.operationTokenType == JavaTokenType.PLUS) e.operands.map { eval(it, depth + 1) }.reduce(::plus) else null
        is PsiReferenceExpression -> javaChain(e)?.let { valueOf(it, e, depth) }
        is PsiMethodCallExpression -> javaChain(e.methodExpression)?.let { call(it, e, depth) }
        else -> null
    }

    private fun plus(a: Any?, b: Any?): Any? = when {
        a is Long && b is Long -> a + b
        a is String || b is String -> part(a) + part(b)
        else -> null
    }

    private fun part(v: Any?): String = if (v is String || v is Long) v.toString() else WILD.toString()

    /** "a.b.C" for a.b.C or a.b.C(...); null when a part is not a plain name. */
    private fun kotlinChain(e: KtExpression?): String? = when (e) {
        is KtNameReferenceExpression -> e.getReferencedName()
        is KtCallExpression -> (e.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        is KtDotQualifiedExpression -> {
            val r = kotlinChain(e.receiverExpression)
            val s = kotlinChain(e.selectorExpression)
            if (r != null && s != null) "$r.$s" else null
        }
        else -> null
    }

    private fun javaChain(e: PsiReferenceExpression): String? {
        val name = e.referenceName ?: return null
        val q = e.qualifierExpression ?: return name
        return (q as? PsiReferenceExpression)?.let { javaChain(it) }?.let { "$it.$name" }
    }

    private fun valueOf(chain: String, at: PsiElement, depth: Int): Any? {
        val src = index.sourceOf(at) ?: return null
        val d = (if ('.' !in chain) index.local(chain, at) else null) ?: index.resolveMember(chain, src, at)
        val f = d as? Field ?: return null
        if (!f.isConstant) return null // may be reassigned: not a constant
        val init = f.initializer ?: return null
        return memoized(f.psi, depth) { eval(init, depth + 1) }
    }

    private fun call(chain: String, at: PsiElement, depth: Int): Any? {
        val src = index.sourceOf(at) ?: return null
        val f = index.resolveMember(chain, src, at) as? Fn ?: return null
        return memoized(f.psi, depth) { eval(returned(f.psi), depth + 1) }
    }

    /** The expression a function returns: its expression body, or its only return. */
    private fun returned(f: PsiElement): PsiElement? = when (f) {
        is KtNamedFunction -> if (f.hasBlockBody()) {
            PsiTreeUtil.findChildrenOfType(f.bodyBlockExpression, KtReturnExpression::class.java).singleOrNull()?.returnedExpression
        } else f.bodyExpression
        is PsiMethod -> PsiTreeUtil.findChildrenOfType(f.body, PsiReturnStatement::class.java).singleOrNull()?.returnValue
        else -> null
    }

    private fun memoized(key: PsiElement, depth: Int, compute: () -> Any?): Any? {
        if (memo.containsKey(key)) return memo[key]
        if (!active.add(key) || depth > MAX_DEPTH) return null
        try {
            val v = compute()
            memo[key] = v
            return v
        } finally {
            active.remove(key)
        }
    }

    private companion object {
        const val MAX_DEPTH = 12
    }
}
