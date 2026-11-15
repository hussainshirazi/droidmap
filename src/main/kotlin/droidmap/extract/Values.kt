package droidmap.extract

import droidmap.Call
import droidmap.Code
import droidmap.KotlinReader
import droidmap.Source
import droidmap.WILD
import droidmap.arg
import org.jetbrains.kotlin.com.intellij.psi.PsiAssignmentExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiParenthesizedExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCastExpression
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import java.util.IdentityHashMap

/** Scope functions that return the value itself, so a chain goes on after them, and all of those followed into. */
private val KEEPING_SCOPES = setOf("apply", "also")
private val SCOPES = KEEPING_SCOPES + setOf("let", "run")

/**
 * Follows a value through the function that makes it: the calls made on it (in a chain, in `apply`/`also`/`let`/`run`,
 * or on a local variable that holds it) and the calls it is passed to. When it goes anywhere else (returned, stored in a
 * property or field, assigned to again), it escapes: other code may change it, so what it carries is not known.
 */
class Values(private val code: Code) {
    class Uses(val calls: List<Call>, val passedTo: List<Call>, val escapes: Boolean)

    private val byPsi = IdentityHashMap<Source, IdentityHashMap<PsiElement, Call>>()

    private fun callAt(s: Source, el: PsiElement?): Call? =
        if (el == null) null else byPsi.getOrPut(s) { IdentityHashMap<PsiElement, Call>().apply { s.calls.forEach { put(it.psi, it) } } }[el]

    /** The calls on the value [created] makes and where it goes. [keeps]: whether a call returns the value itself (a builder method). */
    fun follow(created: Call, keeps: (Call) -> Boolean): Uses = Walk(created.src, keeps).run {
        value(result(created))
        Uses(on, passed, escapes)
    }

    /** The call an expression is: `f(...)`, `a.f(...)` or Java's `a.f(...)` and `new F(...)`; null for anything else. */
    fun callOf(e: PsiElement?): Call? {
        val s = e?.let { code.index.sourceOf(it) } ?: return null
        return callAt(s, (e as? KtQualifiedExpression)?.selectorExpression ?: e)
    }

    /** A constant string, or null when [e] is not one. */
    fun constant(e: PsiElement?): String? = (code.eval.eval(e) as? String)?.takeIf { WILD !in it }

    /**
     * The constant keys of `bundleOf("a" to 1)`, `workDataOf(...)` or `mapOf(...)`: each `key to value` or `Pair(key, value)`
     * argument. Null when one of them is not a constant pair (a spread array, a variable key).
     */
    fun pairKeys(c: Call): Set<String>? {
        val out = LinkedHashSet<String>()
        for (a in c.args) {
            val e = a.expr
            val key = when {
                e is KtBinaryExpression && e.operationReference.getReferencedName() == "to" -> constant(e.left)
                e is KtCallExpression && (e.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "Pair" ->
                    constant(KotlinReader.args(e.valueArgumentList).arg(null, 0)?.expr)
                else -> null
            } ?: return null
            out.add(key)
        }
        return out
    }

    /** The expression whose value is [c]'s result: `a.f()` for a call on a receiver, the call itself otherwise. */
    private fun result(c: Call): PsiElement {
        val p = c.psi
        if (p is KtCallExpression) (p.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == p }?.let { return it }
        return p
    }

    private inner class Walk(private val s: Source, private val keeps: (Call) -> Boolean) {
        val on = ArrayList<Call>()
        val passed = ArrayList<Call>()
        var escapes = false
        private val seen = HashSet<PsiElement>()

        /** [e] is an expression that holds the value. */
        fun value(e: PsiElement) {
            if (!seen.add(e)) return
            var cur = e
            while (true) {
                val c = next(cur) ?: break
                on.add(c)
                if (c.name in SCOPES) scope(c)
                val goesOn = if (c.name in SCOPES) c.name in KEEPING_SCOPES else keeps(c)
                if (!goesOn) return // the chain holds another value from here on
                cur = result(c)
            }
            consumer(cur)
        }

        /** The call made on the value of [e] (`e.f()`), if any. */
        private fun next(e: PsiElement): Call? = when (e) {
            is KtExpression -> (e.parent as? KtQualifiedExpression)?.takeIf { it.receiverExpression == e }
                ?.let { callAt(s, it.selectorExpression as? KtCallExpression) }
            else -> (e.parent as? PsiReferenceExpression)?.takeIf { it.qualifierExpression == e }
                ?.let { callAt(s, it.parent as? PsiMethodCallExpression) }
        }

        /** What the outermost expression holding the value does with it. */
        private fun consumer(e: PsiElement) {
            var x = e
            var p = x.parent
            while (p is KtParenthesizedExpression || p is PsiParenthesizedExpression || p is KtBinaryExpressionWithTypeRHS ||
                p is PsiTypeCastExpression || (p is KtPostfixExpression && p.operationToken == KtTokens.EXCLEXCL)) {
                x = p
                p = p.parent
            }
            when {
                p is KtValueArgument -> callAt(s, p.parent?.parent)?.let { passed.add(it) } ?: run { escapes = true }
                p is PsiExpressionList -> callAt(s, p.parent)?.let { passed.add(it) } ?: run { escapes = true }
                p is KtProperty && p.isLocal && p.initializer == x -> local(p.name, p)
                p is PsiLocalVariable && p.initializer == x -> local(p.name, p)
                p is KtBlockExpression -> if (p.parent is KtFunctionLiteral && p.statements.lastOrNull() == x) escapes = true // a lambda's result
                p is PsiExpressionStatement -> {} // a statement: the value goes no further
                else -> escapes = true
            }
        }

        /** The uses of a local variable that holds the value, after it is declared. */
        private fun local(name: String?, decl: PsiElement) {
            val body = PsiTreeUtil.getParentOfType(decl, KtDeclarationWithBody::class.java, PsiMethod::class.java, PsiLambdaExpression::class.java)
            if (name == null || body == null) { escapes = true; return }
            for (r in references(body, name)) {
                if (r.textOffset <= decl.textOffset) continue
                if (assigned(r)) escapes = true else value(r)
            }
        }

        /** Inside `apply { putExtra(...) }` the value is the receiver of each statement's call; inside `also { it... }`, the parameter. */
        private fun scope(c: Call) {
            val lambda = c.lambda as? KtLambdaExpression ?: return
            val body = lambda.bodyExpression ?: return
            if (c.name == "apply" || c.name == "run") {
                for (x in s.calls) if (x.receiver == null && x.isInside(body) && result(x).parent == body) on.add(x)
                PsiTreeUtil.findChildrenOfType(body, KtThisExpression::class.java).filter { it.getLabelName() == null }.forEach { value(it) }
            } else {
                references(body, lambda.valueParameters.singleOrNull()?.name ?: "it").forEach { value(it) }
            }
        }

        /** References to the name [name] under [el]: plain names, not members (`x.name`) or calls (`name()`). */
        private fun references(el: PsiElement, name: String): List<PsiElement> =
            PsiTreeUtil.findChildrenOfAnyType(el, false, KtNameReferenceExpression::class.java, PsiReferenceExpression::class.java).filter { r ->
                when (r) {
                    is KtNameReferenceExpression -> r.getReferencedName() == name && (r.parent as? KtQualifiedExpression)?.selectorExpression != r &&
                        (r.parent as? KtCallExpression)?.calleeExpression != r
                    is PsiReferenceExpression -> r.referenceName == name && r.qualifierExpression == null && r.parent !is PsiMethodCallExpression
                    else -> false
                }
            }

        private fun assigned(r: PsiElement): Boolean {
            val p = r.parent
            return (p is KtBinaryExpression && p.operationToken == KtTokens.EQ && p.left == r) || (p is PsiAssignmentExpression && p.lExpression == r)
        }
    }
}
