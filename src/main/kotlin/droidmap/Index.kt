package droidmap

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.util.IdentityHashMap

/** Marks an unknown part of an evaluated string (a template or call we could not resolve). */
const val WILD = ''

data class TypeRef(val name: String, val args: List<TypeRef> = emptyList(), val nullable: Boolean = false, val fn: Boolean = false) {
    val simple get() = name.substringAfterLast('.')

    /** Provider<T> and Lazy<T> (javax/dagger) inject T. */
    fun unwrapped(): TypeRef {
        var t = this
        while (t.simple in setOf("Provider", "Lazy") && t.args.size == 1) t = t.args[0]
        return t
    }

    override fun toString(): String =
        name + (if (args.isNotEmpty()) args.joinToString(", ", "<", ">") else "") + (if (nullable) "?" else "")

    companion object {
        fun of(ref: KtTypeReference?): TypeRef? = ref?.typeElement?.let { of(it) }

        fun of(el: KtTypeElement): TypeRef? = when (el) {
            is KtNullableType -> el.innerType?.let { of(it) }?.copy(nullable = true)
            is KtFunctionType -> TypeRef("Function", fn = true)
            is KtUserType -> {
                val names = ArrayList<String>()
                var u: KtUserType? = el
                while (u != null) { names.add(u.referencedName ?: "?"); u = u.qualifier }
                TypeRef(names.reversed().joinToString("."), el.typeArgumentsAsTypes.mapNotNull { of(it) })
            }
            else -> null
        }
    }
}

// ----- small PSI helpers -----
fun KtAnnotationEntry.simpleName(): String = shortName?.asString() ?: ""
fun KtAnnotated.ann(simple: String): KtAnnotationEntry? = annotationEntries.firstOrNull { it.simpleName() == simple }
fun KtAnnotated.hasAnn(vararg simple: String): Boolean = annotationEntries.any { it.simpleName() in simple }
fun KtCallExpression.calleeName(): String? = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
fun KtValueArgument.name(): String? = getArgumentName()?.asName?.asString()

/** A named argument, or else the positional one at [pos] (counting unnamed, non-lambda arguments). */
fun List<KtValueArgument>.arg(name: String? = null, pos: Int? = null): KtValueArgument? {
    if (name != null) firstOrNull { it.name() == name }?.let { return it }
    if (pos != null) return filter { it.name() == null && it !is org.jetbrains.kotlin.psi.KtLambdaArgument }.getOrNull(pos)
    return null
}

fun KtCallExpression.receiverText(): String? {
    val p = parent
    if (p is org.jetbrains.kotlin.psi.KtQualifiedExpression && p.selectorExpression == this) {
        val r = p.receiverExpression
        return (r as? KtNameReferenceExpression)?.getReferencedName() ?: r.text.substringAfterLast('.')
    }
    return null
}

/** Enclosing classes and objects, innermost first (starting with [el] itself when it is one). */
fun classChain(el: PsiElement?): List<KtClassOrObject> {
    val out = ArrayList<KtClassOrObject>()
    var p: PsiElement? = el
    while (p != null && p !is KtFile) {
        if (p is KtClassOrObject && !(p is KtObjectDeclaration && p.isObjectLiteral())) out.add(p)
        p = p.parent
    }
    return out
}

fun KtClassOrObject.fq(): String? = fqName?.asString()

class Index(val sources: List<Source>) {
    val types = HashMap<String, KtClassOrObject>()
    val bySimple = HashMap<String, MutableList<KtClassOrObject>>()
    val members = HashMap<String, MutableList<KtCallableDeclaration>>()
    private val sourceByFile = IdentityHashMap<KtFile, Source>()

    init {
        for (s in sources) {
            sourceByFile[s.file] = s
            s.file.accept(object : KtTreeVisitorVoid() {
                override fun visitClassOrObject(c: KtClassOrObject) {
                    super.visitClassOrObject(c)
                    val fq = c.fq() ?: return
                    if (c is KtObjectDeclaration && c.isObjectLiteral()) return
                    types.putIfAbsent(fq, c)
                    if (!(c is KtObjectDeclaration && c.isCompanion())) c.name?.let { bySimple.getOrPut(it) { ArrayList() }.add(c) }
                }

                override fun visitNamedFunction(f: KtNamedFunction) {
                    super.visitNamedFunction(f)
                    if (!f.isLocal) addMember(f)
                }

                override fun visitProperty(p: KtProperty) {
                    super.visitProperty(p)
                    if (!p.isLocal) addMember(p)
                }
            })
        }
    }

    private fun addMember(d: KtCallableDeclaration) {
        val fq = d.fqName?.asString() ?: return
        members.getOrPut(fq) { ArrayList() }.add(d)
        val owner = d.containingClassOrObject
        if (owner is KtObjectDeclaration && owner.isCompanion()) {
            owner.containingClassOrObject?.fq()?.let { members.getOrPut("$it.${d.name}") { ArrayList() }.add(d) }
        }
    }

    fun sourceOf(el: PsiElement): Source? = sourceByFile[el.containingFile as? KtFile]

    fun resolveType(name: String, src: Source, scope: PsiElement?): KtClassOrObject? {
        if (name.isEmpty()) return null
        types[name]?.let { return it }
        val first = name.substringBefore('.')
        val rest = name.substringAfter('.', "")
        val suffix = if (rest.isEmpty()) "" else ".$rest"
        val cands = ArrayList<String>()
        src.imports[first]?.let { cands.add(it) }
        for (c in classChain(scope)) c.fq()?.let { cands.add("$it.$first") }
        cands.add(if (src.pkg.isEmpty()) first else "${src.pkg}.$first")
        src.starImports.forEach { cands.add("$it.$first") }
        for (c in cands) types[c + suffix]?.let { return it }
        if (first in src.imports) return null // imported from outside the project
        val same = bySimple[first] ?: return null
        if (same.size != 1) return null
        return if (rest.isEmpty()) same[0] else same[0].fq()?.let { types[it + suffix] }
    }

    fun resolveMember(chain: String, src: Source, scope: PsiElement?): KtCallableDeclaration? {
        members[chain]?.let { return it[0] }
        val head = chain.substringBeforeLast('.', "")
        val last = chain.substringAfterLast('.')
        if (head.isNotEmpty()) {
            val t = resolveType(head, src, scope)?.fq() ?: return null
            return (members["$t.$last"] ?: members["$t.Companion.$last"])?.get(0)
        }
        val cands = ArrayList<String>()
        src.imports[last]?.let { cands.add(it) }
        for (c in classChain(scope)) c.fq()?.let { cands.add("$it.$last"); cands.add("$it.Companion.$last") }
        cands.add(if (src.pkg.isEmpty()) last else "${src.pkg}.$last")
        src.starImports.forEach { cands.add("$it.$last") }
        for (c in cands) members[c]?.let { return it[0] }
        return null
    }

    /** The nearest local val/var with this name declared before [at] in a function that encloses it. */
    fun local(name: String, at: PsiElement): KtProperty? {
        var fn = at.getStrictParentOfType<KtNamedFunction>()
        while (fn != null) {
            fn.collectDescendantsOfType<KtProperty> { it.isLocal && it.name == name && it.textOffset < at.textOffset }
                .lastOrNull()?.let { return it }
            fn = fn.getStrictParentOfType()
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
 * Evaluates constant expressions: literals, string templates, `+`, const references and calls
 * to functions whose body is one expression or one return. Unknown parts of a string become
 * WILD; an expression that is entirely unknown is null. Numbers are Long.
 */
class Evaluator(private val index: Index) {
    private val memo = HashMap<PsiElement, Any?>()
    private val active = HashSet<PsiElement>()

    fun eval(e: KtExpression?, depth: Int = 0): Any? {
        if (e == null || depth > 12) return null
        return when (e) {
            is KtStringTemplateExpression -> buildString {
                for (entry in e.entries) when (entry) {
                    is KtLiteralStringTemplateEntry -> append(entry.text)
                    is KtEscapeStringTemplateEntry -> append(entry.unescapedValue)
                    is KtStringTemplateEntryWithExpression -> {
                        val v = eval(entry.expression, depth + 1)
                        append(if (v is String || v is Long) v.toString() else WILD.toString())
                    }
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
            is KtDotQualifiedExpression -> {
                val sel = e.selectorExpression
                val chain = chain(e) ?: return null
                if (sel is KtCallExpression) call(chain, e, depth) else valueOf(chain, e, depth)
            }
            is KtCallExpression -> e.calleeName()?.let { call(it, e, depth) }
            else -> null
        }
    }

    fun evalInt(e: KtExpression?): Int? = (eval(e) as? Long)?.toInt()

    private fun plus(a: Any?, b: Any?): Any? = when {
        a is Long && b is Long -> a + b
        a is String || b is String -> part(a) + part(b)
        else -> null
    }

    private fun part(v: Any?): String = if (v is String || v is Long) v.toString() else WILD.toString()

    /** "a.b.C" for a.b.C or a.b.C(...); null when a part is not a plain name. */
    private fun chain(e: KtExpression?): String? = when (e) {
        is KtNameReferenceExpression -> e.getReferencedName()
        is KtCallExpression -> e.calleeName()
        is KtDotQualifiedExpression -> {
            val r = chain(e.receiverExpression)
            val s = chain(e.selectorExpression)
            if (r != null && s != null) "$r.$s" else null
        }
        else -> null
    }

    private fun valueOf(chain: String, at: PsiElement, depth: Int): Any? {
        val src = index.sourceOf(at) ?: return null
        val d: KtCallableDeclaration? = (if ('.' !in chain) index.local(chain, at) else null) ?: index.resolveMember(chain, src, at)
        val p = d as? KtProperty ?: return null
        if (p.isVar) return null // may be reassigned: not a constant
        val init = p.initializer ?: return null
        return memoized(p, depth) { eval(init, depth + 1) }
    }

    private fun call(chain: String, at: PsiElement, depth: Int): Any? {
        val src = index.sourceOf(at) ?: return null
        val f = index.resolveMember(chain, src, at) as? KtNamedFunction ?: return null
        return memoized(f, depth) {
            if (f.hasBlockBody()) {
                val returns = f.bodyBlockExpression?.collectDescendantsOfType<KtReturnExpression>() ?: emptyList()
                if (returns.size == 1) eval(returns[0].returnedExpression, depth + 1) else null
            } else {
                eval(f.bodyExpression, depth + 1)
            }
        }
    }

    private fun memoized(key: PsiElement, depth: Int, compute: () -> Any?): Any? {
        if (memo.containsKey(key)) return memo[key]
        if (!active.add(key) || depth > 12) return null
        try {
            val v = compute()
            memo[key] = v
            return v
        } finally {
            active.remove(key)
        }
    }
}
