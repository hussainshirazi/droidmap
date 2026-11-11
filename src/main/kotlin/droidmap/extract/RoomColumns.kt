package droidmap.extract

import droidmap.Project
import droidmap.TypeRef
import droidmap.ann
import droidmap.arg
import droidmap.hasAnn
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.ValueArgument

/** A table column as Room derives it from an entity field. [affinity] is null when a type converter may decide it. */
class Column(val name: String, val affinity: String?, val notNull: Boolean)

/** Kotlin types whose SQLite affinity Room fixes without a type converter. */
private val AFFINITY = mapOf(
    "Int" to "INTEGER", "Long" to "INTEGER", "Short" to "INTEGER", "Byte" to "INTEGER", "Boolean" to "INTEGER",
    "String" to "TEXT", "Double" to "REAL", "Float" to "REAL", "ByteArray" to "BLOB",
)

/**
 * Reads the columns of a Room entity from source: constructor and body properties with a backing field,
 * minus @Ignore, @Relation and @Transient, named by @ColumnInfo(name), with @Embedded types expanded
 * (with their prefix) and superclass fields included.
 */
class RoomColumns(private val p: Project) {
    /** The entity's columns, or null when part of it cannot be followed (a superclass or embedded type outside the project). */
    fun of(c: KtClassOrObject): List<Column>? {
        val out = ArrayList<Column>()
        return if (collect(c, "", true, out, HashSet())) out else null
    }

    private fun collect(c: KtClassOrObject, prefix: String, nonNull: Boolean, out: MutableList<Column>, path: MutableSet<KtClassOrObject>): Boolean {
        if (!path.add(c)) return false // a type that embeds itself
        try {
            val src = p.index.sourceOf(c) ?: return false
            for (entry in c.superTypeListEntries) {
                if (entry !is KtSuperTypeCallEntry) continue // an interface: no fields
                val t = TypeRef.of(entry.typeReference) ?: return false
                if (t.simple == "Any") continue
                val base = p.index.resolveType(t.name, src, c) ?: return false
                if (!collect(base, prefix, nonNull, out, path)) return false
            }
            val fields = ArrayList<Pair<KtNamedDeclaration, KtTypeReference?>>()
            for (prm in c.primaryConstructorParameters) if (prm.hasValOrVar()) fields.add(prm to prm.typeReference)
            for (prop in c.declarations.filterIsInstance<KtProperty>()) if (hasField(prop)) fields.add(prop to prop.typeReference)
            for ((f, ref) in fields) {
                val a = f as KtAnnotated
                if (a.hasAnn("Ignore", "Relation", "Transient")) continue
                val t = TypeRef.of(ref) ?: return false // no declared type: cannot tell
                val fieldNonNull = nonNull && !t.nullable
                val embedded = a.ann("Embedded")
                if (embedded != null) {
                    val pre = p.eval.eval(argOf(embedded.valueArguments, "prefix")?.getArgumentExpression()) as? String
                    val d = p.index.resolveType(t.name, src, c) ?: return false
                    if (!collect(d, prefix + (pre ?: ""), fieldNonNull, out, path)) return false
                } else {
                    val named = p.eval.eval(a.ann("ColumnInfo")?.let { argOf(it.valueArguments, "name") }?.getArgumentExpression()) as? String
                    val name = named?.takeIf { it != "[field-name]" } ?: f.name ?: return false
                    val plain = '.' !in t.name || t.name.startsWith("kotlin.")
                    out.add(Column(prefix + name, AFFINITY[t.simple]?.takeIf { plain && t.args.isEmpty() }, fieldNonNull))
                }
            }
            return true
        } finally {
            path.remove(c)
        }
    }

    /** An annotation argument by name, or else the first one. */
    private fun argOf(args: List<ValueArgument>, name: String) = args.filterIsInstance<KtValueArgument>().arg(name, 0)

    private fun hasField(prop: KtProperty): Boolean =
        !prop.hasDelegate() && !prop.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
            (prop.initializer != null || prop.hasModifier(KtTokens.LATEINIT_KEYWORD) || prop.getter == null)
}
