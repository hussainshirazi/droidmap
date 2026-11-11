package droidmap

import org.jetbrains.kotlin.com.intellij.psi.PsiClassObjectAccessExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/*
 * What the extractors read from a source file, whatever its language: its types, functions,
 * properties and calls, and the names it refers to. KotlinReader and JavaReader fill it in one
 * walk of each file, so every Android rule is written once for both languages.
 */

/** A type as written. Java types use their Kotlin names (int -> Int, Integer -> Int, byte[] -> ByteArray). */
data class TypeRef(val name: String, val args: List<TypeRef> = emptyList(), val nullable: Boolean = false, val fn: Boolean = false) {
    val simple get() = name.substringAfterLast('.')

    /** Provider<T> and Lazy<T> (javax/dagger) inject T. */
    fun unwrapped(): TypeRef {
        var t = this
        while (t.simple in WRAPPERS && t.args.size == 1) t = t.args[0]
        return t
    }

    override fun toString(): String =
        name + (if (args.isNotEmpty()) args.joinToString(", ", "<", ">") else "") + (if (nullable) "?" else "")

    private companion object {
        val WRAPPERS = setOf("Provider", "Lazy")
    }
}

/** An annotation: its simple name, its arguments and their text with the parentheses (null when there are none). */
class Ann(val name: String, val args: List<Arg>, val text: String?, val psi: PsiElement)

/** A call or annotation argument; [name] is null when it is positional. */
class Arg(val name: String?, val expr: PsiElement?, val psi: PsiElement) {
    val text: String get() = psi.text
}

/** A named argument, or else the positional one at [pos]. */
fun List<Arg>.arg(name: String? = null, pos: Int? = null): Arg? {
    if (name != null) firstOrNull { it.name == name }?.let { return it }
    if (pos != null) return filter { it.name == null }.getOrNull(pos)
    return null
}

fun List<Ann>.find(name: String): Ann? = firstOrNull { it.name == name }
fun List<Ann>.has(vararg names: String): Boolean = any { it.name in names }

/** A declaration: a type, function, property or parameter. */
sealed class Decl(val name: String?, val anns: List<Ann>, val src: Source, val psi: PsiElement) {
    val line: Int get() = src.line(psi)
    val endLine: Int get() = src.lineAt(psi.textRange.endOffset)
}

class Param(name: String?, val type: TypeRef?, anns: List<Ann>, src: Source, psi: PsiElement) : Decl(name, anns, src, psi)

/** A function, method or constructor. [fq] is null for local functions and constructors. */
class Fn(
    name: String?, val fq: String?, val returns: TypeRef?, val params: List<Param>, anns: List<Ann>, src: Source, psi: PsiElement,
    val owner: Type?, val isExpect: Boolean = false,
) : Decl(name, anns, src, psi) {
    /** Local vals and vars declared in its body, in order (not those of nested functions). */
    val locals = ArrayList<Field>()
}

/**
 * A property, field, local variable, or a Kotlin constructor parameter declared with val or var.
 * [stored]: it has a backing field (a Room column candidate); [isConstant]: it cannot be reassigned.
 */
class Field(
    name: String?, val fq: String?, val type: TypeRef?, anns: List<Ann>, src: Source, psi: PsiElement, val owner: Type?,
    val initializer: PsiElement?, val delegate: PsiElement?, var isConstant: Boolean, val stored: Boolean,
    val isStatic: Boolean = false, val isLocal: Boolean = false, val isParam: Boolean = false, val isExpect: Boolean = false,
) : Decl(name, anns, src, psi)

enum class TypeKind { CLASS, INTERFACE, OBJECT, COMPANION, ANNOTATION, ENUM }

/** A supertype; [args] are the superclass constructor's arguments, null for an interface. */
class Super(val type: TypeRef, val args: List<Arg>?, val psi: PsiElement)

/** A named class, interface, object or annotation class (not an anonymous or local one). */
class Type(
    name: String?, val fq: String, val kind: TypeKind, anns: List<Ann>, val supers: List<Super>, val typeParams: List<String>,
    src: Source, psi: PsiElement, val outer: Type?, val isExpect: Boolean = false,
) : Decl(name, anns, src, psi) {
    /** Constructors, the primary one first. */
    val ctors = ArrayList<Fn>()
    /** Fields and properties in declaration order, Kotlin constructor vals and vars first. */
    val fields = ArrayList<Field>()
    val fns = ArrayList<Fn>()
    val label: String get() = name ?: fq
}

/**
 * A call: `f(...)`, `a.f(...)`, a Java `new F(...)`, or the superclass call of an anonymous object
 * (`object : Migration(1, 2) { }`). [fn] is the innermost named function around it; [assignedTo] is
 * the property or variable it initializes or (Kotlin `by`) delegates.
 */
class Call(
    val name: String, val receiver: String?, val args: List<Arg>, val typeArgs: List<TypeRef>, val lambda: PsiElement?,
    val src: Source, val psi: PsiElement, val fn: Fn?, val assignedTo: Field?, val isNew: Boolean = false,
) {
    val line: Int get() = src.line(psi)

    /** The receiver's last part: `navController` for navController.navigate(...). */
    val receiverName: String? get() = receiver?.substringAfterLast('.')

    fun isInside(el: PsiElement): Boolean = el.textRange.contains(psi.textRange) && psi.containingFile == el.containingFile
}

/** One parsed source file, read into the model. */
class Source(
    val path: String, val module: String?, val sourceSet: String?, val psi: PsiFile, val text: String,
    val pkg: String, val imports: Map<String, String>, val starImports: List<String>,
) {
    val types = ArrayList<Type>()
    val fns = ArrayList<Fn>()
    val fields = ArrayList<Field>()
    val calls = ArrayList<Call>()
    /** How often each simple name is referred to, outside imports and the package line. */
    val names = HashMap<String, Int>()
    /** The x of each R.id.x. */
    val resourceIds = ArrayList<String>()
    /** The file's size and modification time when it was read (see Code.read). */
    var stamp: String? = null
    val isKotlin: Boolean get() = psi is KtFile
    private val lineStarts: IntArray = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    fun lineAt(offset: Int): Int {
        val k = lineStarts.binarySearch(offset)
        return if (k >= 0) k + 1 else -k - 1
    }

    fun line(el: PsiElement): Int = lineAt(el.textOffset)
    val isTest get() = isTestSet(sourceSet)
    fun importsFrom(prefix: String): Boolean = imports.values.any { it.startsWith(prefix) } || starImports.any { it.startsWith(prefix) }
}

/** Classes named as `Name::class` (Kotlin) or `Name.class` (Java) under [el]. */
fun classRefs(el: PsiElement?): List<Pair<String, PsiElement>> {
    if (el == null) return emptyList()
    val out = ArrayList<Pair<String, PsiElement>>()
    for (lit in PsiTreeUtil.findChildrenOfAnyType(el, false, KtClassLiteralExpression::class.java, PsiClassObjectAccessExpression::class.java)) {
        val name = when (lit) {
            is KtClassLiteralExpression -> lit.receiverExpression?.text
            is PsiClassObjectAccessExpression -> lit.operand.text
            else -> null
        }?.filterNot { it.isWhitespace() } ?: continue
        out.add(name to lit)
    }
    return out
}

/**
 * The arguments of each `Name(...)` nested under [el]: a Kotlin call, or a Java annotation, as in
 * `autoMigrations = [AutoMigration(from = 1, to = 2)]` and `autoMigrations = {@AutoMigration(from = 1, to = 2)}`.
 */
fun nestedCalls(el: PsiElement?, name: String): List<Pair<List<Arg>, PsiElement>> {
    if (el == null) return emptyList()
    return PsiTreeUtil.findChildrenOfAnyType(el, false, KtCallExpression::class.java, PsiAnnotation::class.java).mapNotNull {
        when {
            it is KtCallExpression && (it.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == name ->
                KotlinReader.args(it.valueArgumentList) to it
            it is PsiAnnotation && it.nameReferenceElement?.referenceName == name -> JavaReader.annotation(it).args to it
            else -> null
        }
    }
}

/** Simple names referred to under [el]. */
fun namesIn(el: PsiElement?): List<String> =
    if (el == null) emptyList()
    else PsiTreeUtil.findChildrenOfAnyType(el, false, KtNameReferenceExpression::class.java, PsiJavaCodeReferenceElement::class.java)
        .mapNotNull { (it as? KtNameReferenceExpression)?.getReferencedName() ?: (it as? PsiJavaCodeReferenceElement)?.referenceName }
