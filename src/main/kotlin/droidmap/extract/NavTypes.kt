package droidmap.extract

import droidmap.Index
import droidmap.Type
import droidmap.TypeKind
import droidmap.TypeRef
import droidmap.has
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass

/** The types Navigation's type-safe routes have a NavType for (androidx.navigation.serialization.NavTypeConverter), besides enums. */
private val SCALARS = setOf("Int", "Long", "Float", "Double", "Boolean", "String")
private val ARRAYS = setOf("IntArray", "LongArray", "FloatArray", "DoubleArray", "BooleanArray")
private val LISTS = setOf("List", "MutableList", "ArrayList")
/** Standard types kotlinx.serialization can write, so a route with one compiles, but that Navigation has no NavType for. */
private val UNSUPPORTED = setOf("Char", "Short", "Byte", "UInt", "ULong", "UShort", "UByte", "Set", "MutableSet", "HashSet", "Map",
    "MutableMap", "HashMap", "Pair", "Triple", "Uuid", "Instant", "LocalDate", "LocalDateTime", "LocalTime", "Duration")

/**
 * The properties of a type-safe route class that Navigation has no NavType for, as "name: Type": each needs a custom NavType
 * in the destination's typeMap, or Navigation throws IllegalArgumentException ("Route ... could not find any NavType for
 * argument ..."). A type droidmap cannot tell (a type alias, a value class, a library class it does not know) is left out.
 */
fun unsupportedRouteProperties(index: Index, route: Type): List<String> =
    (route.ctors.firstOrNull()?.params ?: emptyList()).filter { !it.anns.has("Transient") }.mapNotNull { p ->
        p.type?.takeIf { missing(index, it, route) }?.let { "${p.name}: $it" }
    }

private fun missing(index: Index, ref: TypeRef, at: Type): Boolean {
    val name = ref.simple
    return when {
        name in SCALARS || name in ARRAYS -> false
        name == "Array" -> ref.args.singleOrNull()?.simple != "String"
        name in LISTS -> ref.args.singleOrNull()?.let { it.simple !in SCALARS && missing(index, it, at) } ?: false
        name in UNSUPPORTED -> true
        else -> index.resolveType(ref.name, at.src, at.psi)?.let { it.kind != TypeKind.ENUM && !isValueClass(it) } ?: false
    }
}

private fun isValueClass(t: Type): Boolean =
    t.anns.has("JvmInline") || (t.psi as? KtClass)?.let { it.hasModifier(KtTokens.VALUE_KEYWORD) || it.hasModifier(KtTokens.INLINE_KEYWORD) } == true
