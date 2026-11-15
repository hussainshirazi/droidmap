package droidmap.extract

import droidmap.Call
import droidmap.Code
import droidmap.Graph
import droidmap.Type
import droidmap.TypeKind
import droidmap.classRefs
import droidmap.has
import droidmap.str

/*
 * What users of the last release already have saved on their phones, and a change that no longer reads it. Both compare the
 * code with the code at the base commit (see codeAt), so they run only with a base.
 */

private val CONVERTER_PARSES = listOf("valueOf(", "enumValueOf", ".first {", ".single {")

/**
 * Enum constants the change removed (or renamed) while Room stores the enum by name in a column: rows saved with one fail to
 * read (Room's own converter throws IllegalArgumentException, and so does a @TypeConverter that calls valueOf). A migration
 * that names the constant in its SQL ('PAUSED') is taken to update those rows. Recorded as meta room_enum_constant_removed.
 */
class RemovedEnumConstants(private val code: Code, private val base: Code, private val g: Graph) {
    fun run() {
        val columns = RoomColumns(code)
        val migrations = g.byKind("room_migration").mapNotNull { it.file }.toSet().mapNotNull { f -> code.sources.firstOrNull { it.path == f }?.text }
        val stored = LinkedHashMap<Type, MutableList<String>>() // enum -> table.column that hold it
        for (e in g.byKind("room_entity")) {
            val entity = code.index.types[e.id.removePrefix("class::")] ?: continue
            for (col in columns.of(entity) ?: continue) {
                val f = col.field ?: continue
                val enum = f.type?.let { code.index.resolveType(it.name, f.src, f.psi) }?.takeIf { it.kind == TypeKind.ENUM } ?: continue
                stored.getOrPut(enum) { ArrayList() }.add("${e.attrs.str("table")}.${col.name}")
            }
        }
        for ((enum, cols) in stored) {
            val before = base.index.types[enum.fq]?.let(::enumConstants) ?: continue
            val now = enumConstants(enum).toSet()
            val removed = before.filter { it !in now && migrations.none { m -> "'$it'" in m } }
            if (removed.isEmpty() || !readsByName(enum)) continue
            g.addMeta("room_enum_constant_removed", linkedMapOf("enum" to enum.label, "removed" to removed, "columns" to cols.distinct(),
                "file" to enum.src.path, "line" to enum.line))
        }
    }

    /** Whether rows are read back by the constant's name: Room's own converter, or a @TypeConverter that parses without a fallback. */
    private fun readsByName(enum: Type): Boolean {
        val converters = code.sources.flatMap { it.fns }.filter { f ->
            f.anns.has("TypeConverter") && f.returns?.let { code.index.resolveType(it.name, f.src, f.psi) } === enum
        }
        return converters.isEmpty() || converters.any { f -> CONVERTER_PARSES.any { it in f.psi.text } }
    }
}

/**
 * Worker classes the change renamed, moved or deleted while the app enqueued them as periodic work: WorkManager keeps that work
 * in its database under the old class name, so on users' phones it fails each time it runs, and enqueueing again under the same
 * unique name with KEEP (or UPDATE, which cannot change the class) leaves it there. Not reported when the code now cancels that
 * work, or enqueues it with CANCEL_AND_REENQUEUE (REPLACE), or still names the old class. Recorded as meta work_worker_renamed.
 */
class RenamedWorkers(private val code: Code, private val base: Code, private val g: Graph) {
    private class Enqueued(val name: String?, val policy: String?)
    private val now = Values(code)
    private val then = Values(base)

    fun run() {
        for (s in base.sources) for (t in s.types) {
            if (s.isTest || code.index.types[t.fq] != null || !isWorker(base.index, t)) continue
            val enqueued = periodic(t)
            if (enqueued.isEmpty() || enqueued.all { it.name != null && released(it.name) }) continue
            if (code.sources.any { "\"${t.fq}\"" in it.text }) continue // a WorkerFactory still knows the old name
            val now = code.sources.flatMap { it.types }.firstOrNull { it.name == t.name && isWorker(code.index, it) }
            g.addMeta("work_worker_renamed", linkedMapOf("old" to t.fq, "new" to now?.fq, "names" to enqueued.mapNotNull { it.name }.distinct(),
                "policies" to enqueued.mapNotNull { it.policy }.distinct(), "file" to (now?.src?.path ?: s.path), "line" to (now?.line ?: t.line)))
        }
    }

    /** Where the base code made periodic work for [t], and the unique name and policy it was enqueued with (null for a plain enqueue). */
    private fun periodic(t: Type): List<Enqueued> = base.sources.filter { !it.isTest }.flatMap { s ->
        s.calls.filter { makesPeriodic(it, t) }.map { made ->
            val unique = s.calls.firstOrNull { it.name == "enqueueUniquePeriodicWork" && it.fn === made.fn }
            Enqueued(unique?.let { then.constant(it.args.firstOrNull()?.expr) }, unique?.args?.getOrNull(1)?.text?.substringAfterLast('.'))
        }
    }

    private fun makesPeriodic(c: Call, t: Type): Boolean {
        val name = when {
            c.name == "PeriodicWorkRequestBuilder" -> c.typeArgs.firstOrNull()?.name
            c.name == "Builder" && c.receiver?.substringAfterLast('.') == "PeriodicWorkRequest" -> classRefs(c.args.firstOrNull()?.psi).firstOrNull()?.first
            else -> null
        } ?: return false
        return base.index.resolveType(name, c.src, c.psi) === t
    }

    /** Whether the code now cancels the unique work [name], or enqueues it with a policy that replaces the saved work. */
    private fun released(name: String): Boolean = code.sources.any { s ->
        s.calls.any { c ->
            now.constant(c.args.firstOrNull()?.expr) == name && (c.name == "cancelUniqueWork" ||
                (c.name == "enqueueUniquePeriodicWork" && c.args.getOrNull(1)?.text?.let { "CANCEL_AND_REENQUEUE" in it || "REPLACE" in it } == true))
        }
    }
}
