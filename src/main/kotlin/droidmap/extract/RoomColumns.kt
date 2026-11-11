package droidmap.extract

import droidmap.Code
import droidmap.Type
import droidmap.arg
import droidmap.find
import droidmap.has

/** A table column as Room derives it from an entity field. [affinity] is null when a type converter may decide it. */
class Column(val name: String, val affinity: String?, val notNull: Boolean)

/** Types whose SQLite affinity Room fixes without a type converter (Java types are read with these Kotlin names). */
private val AFFINITY = mapOf(
    "Int" to "INTEGER", "Long" to "INTEGER", "Short" to "INTEGER", "Byte" to "INTEGER", "Boolean" to "INTEGER",
    "String" to "TEXT", "Double" to "REAL", "Float" to "REAL", "ByteArray" to "BLOB",
)

/**
 * Reads the columns of a Room entity from source: its stored fields (Kotlin properties with a backing
 * field, Java instance fields) minus @Ignore, @Relation and @Transient (and Java's transient), named by
 * @ColumnInfo(name), with @Embedded types expanded (with their prefix) and superclass fields included.
 */
class RoomColumns(private val code: Code) {
    /** The entity's columns, or null when part of it cannot be followed (a superclass or embedded type outside the project). */
    fun of(t: Type): List<Column>? {
        val out = ArrayList<Column>()
        return if (collect(t, "", true, out, HashSet())) out else null
    }

    private fun collect(t: Type, prefix: String, nonNull: Boolean, out: MutableList<Column>, path: MutableSet<Type>): Boolean {
        if (!path.add(t)) return false // a type that embeds itself
        try {
            for (sup in t.supers) {
                if (sup.args == null || sup.type.simple == "Any" || sup.type.simple == "Object") continue // an interface: no fields
                val base = code.index.resolveType(sup.type.name, t.src, t.psi) ?: return false
                if (!collect(base, prefix, nonNull, out, path)) return false
            }
            for (f in t.fields) {
                if (!f.stored || f.anns.has("Ignore", "Relation", "Transient")) continue
                val type = f.type ?: return false // no declared type: cannot tell
                val fieldNonNull = nonNull && !type.nullable
                val embedded = f.anns.find("Embedded")
                if (embedded != null) {
                    val pre = code.eval.eval(embedded.args.arg("prefix", 0)?.expr) as? String
                    val d = code.index.resolveType(type.name, t.src, t.psi) ?: return false
                    if (!collect(d, prefix + (pre ?: ""), fieldNonNull, out, path)) return false
                } else {
                    val named = code.eval.eval(f.anns.find("ColumnInfo")?.args?.arg("name", 0)?.expr) as? String
                    val name = named?.takeIf { it != "[field-name]" } ?: f.name ?: return false
                    val plain = '.' !in type.name || type.name.startsWith("kotlin.")
                    out.add(Column(prefix + name, AFFINITY[type.simple]?.takeIf { plain && type.args.isEmpty() }, fieldNonNull))
                }
            }
            return true
        } finally {
            path.remove(t)
        }
    }
}
