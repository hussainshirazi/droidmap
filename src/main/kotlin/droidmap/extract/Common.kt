package droidmap.extract

import droidmap.Graph
import droidmap.Project
import droidmap.Source
import droidmap.fq
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/** An attribute that is set only when true, so graphs stay small. */
fun flag(b: Boolean): Boolean? = if (b) true else null

fun classId(fq: String) = "class::$fq"
fun funId(fq: String) = "fun::$fq"

fun Source.importsFrom(prefix: String): Boolean =
    imports.values.any { it.startsWith(prefix) } || starImports.any { it.startsWith(prefix) }

/** Names used as `Name::class` (or `a.b.Name::class`) under [el]. */
fun classRefs(el: PsiElement?): List<Pair<String, KtClassLiteralExpression>> =
    el?.collectDescendantsOfType<KtClassLiteralExpression>()?.mapNotNull { lit ->
        lit.receiverExpression?.text?.filterNot { it.isWhitespace() }?.let { it to lit }
    } ?: emptyList()

fun nodeForClass(g: Graph, c: KtClassOrObject, src: Source, kind: String, attrs: Map<String, Any?> = emptyMap()): String =
    g.addNode(classId(c.fq()!!), kind, c.name ?: c.fq(), src.path, src.line(c), src.module, mapOf("source_set" to src.sourceSet) + attrs)

fun nodeForFun(g: Graph, f: KtNamedFunction, src: Source, kind: String, attrs: Map<String, Any?> = emptyMap()): String? {
    val fq = f.fqName?.asString() ?: return null
    return g.addNode(funId(fq), kind, f.name, src.path, src.line(f), src.module, attrs)
}

/** How often each simple name is referenced in code (imports and package lines excluded). */
class ReferenceCounts(p: Project, includeTests: Boolean) {
    private val counts = HashMap<String, Int>()

    init {
        for (s in p.sources) {
            if (!includeTests && s.isTest) continue
            for (r in s.file.collectDescendantsOfType<KtNameReferenceExpression>()) {
                if (r.getStrictParentOfType<KtImportDirective>() != null || r.getStrictParentOfType<KtPackageDirective>() != null) continue
                counts.merge(r.getReferencedName(), 1, Int::plus)
            }
        }
    }

    operator fun get(name: String): Int = counts[name] ?: 0
}
