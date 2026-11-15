package droidmap.extract

import droidmap.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Room migrations replayed on the schema files. The SQL a manual migration runs (its execSQL calls, in order)
 * is applied to the tables of the version it starts from (<from>.json), and the result is compared with the
 * tables of the version it ends at (<to>.json) the way Room compares them once a migration is done: columns
 * (affinity, NOT NULL, primary key position, default value), foreign keys and indices, then FTS tables and views.
 * A statement SQLite refuses (a NOT NULL column without a default, a table that is not there) is reported as
 * the crash it causes. As on Android, only the first statement of each execSQL runs.
 */

/** A column as PRAGMA table_info reports it, which is what Room compares. */
internal data class SqlColumn(val name: String, val type: String?, val notNull: Boolean, val pk: Int, val default: String?) {
    val affinity: String get() = affinity(type)
}

internal data class SqlForeignKey(val columns: List<String>, val table: String, val refColumns: List<String>, val onDelete: String, val onUpdate: String) {
    override fun toString() = "(${columns.joinToString(", ")}) REFERENCES $table(${refColumns.joinToString(", ")})" +
        (if (onDelete != "NO ACTION") " ON DELETE $onDelete" else "") + (if (onUpdate != "NO ACTION") " ON UPDATE $onUpdate" else "")
}

internal class SqlTable(var name: String, val columns: LinkedHashMap<String, SqlColumn>, val foreignKeys: MutableList<SqlForeignKey>, val virtual: Boolean) {
    fun copy() = SqlTable(name, LinkedHashMap(columns), ArrayList(foreignKeys), virtual)
}

internal data class SqlIndex(val name: String, val table: String, val unique: Boolean, val columns: List<String>, val orders: List<String>)

/** Tables, indices and views by lower-case name. */
internal class SqlSchema(
    val tables: LinkedHashMap<String, SqlTable> = LinkedHashMap(),
    val indices: LinkedHashMap<String, SqlIndex> = LinkedHashMap(),
    val views: LinkedHashMap<String, String> = LinkedHashMap(),
) {
    fun copy() = SqlSchema(LinkedHashMap(tables.mapValues { it.value.copy() }), LinkedHashMap(indices), LinkedHashMap(views))
    fun table(name: String): SqlTable? = tables[name.lowercase()]
    private fun taken(name: String) = name.lowercase().let { it in tables || it in indices || it in views }
    fun takenBy(name: String): Boolean = taken(name)
}

/** SQLite would refuse the statement: the migration throws. */
internal class SqlFailure(message: String) : Exception(message)

/** SQL droidmap does not follow (a generated column, CREATE TABLE ... AS SELECT): the migration is not checked. */
internal class SqlUnknown(message: String) : Exception(message)

/** One execSQL call of a migration: its SQL and its line. */
class SqlCall(val sql: String, val line: Int)

/**
 * What replaying a migration found: [crash], the statement SQLite refuses (null when none does); [problems], how the
 * result differs from the schema Room expects; [notes], statements that never run or fail on older phones; [unknown],
 * why the migration could not be followed (then nothing else is set).
 */
class Replayed(val crash: String?, val problems: List<String>, val notes: List<String>, val unknown: String?)

/** SQLite's type affinity rules, which Room's own schema check follows. */
internal fun affinity(type: String?): String {
    val t = (type ?: "").uppercase()
    return when {
        "INT" in t -> "INTEGER"
        "CHAR" in t || "CLOB" in t || "TEXT" in t -> "TEXT"
        t.isBlank() || "BLOB" in t -> "BLOB"
        "REAL" in t || "FLOA" in t || "DOUB" in t -> "REAL"
        else -> "NUMERIC"
    }
}

/** ALTER TABLE statements newer than the SQLite of older Android versions: (statement, SQLite version, Android version, API level). */
private val NEWER_SQL = mapOf(
    "RENAME COLUMN" to Triple("3.25", "Android 11", 30),
    "DROP COLUMN" to Triple("3.35", "Android 14", 34),
)

object RoomSql {
    /** The tables, indices and views of a schema file's "database" object; SqlUnknown when its SQL cannot be read. */
    internal fun schemaOf(database: JsonObject): SqlSchema {
        val s = SqlSchema()
        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
        fun JsonObject.list(key: String): List<JsonObject> = (get(key) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
        for (e in database.list("entities")) {
            val table = e.text("tableName") ?: continue
            val create = e.text("createSql") ?: throw SqlUnknown("table $table has no createSql")
            apply(s, create.replace("\${TABLE_NAME}", table))
            for (ix in e.list("indices")) ix.text("createSql")?.let { apply(s, it.replace("\${TABLE_NAME}", table)) }
        }
        for (v in database.list("views")) {
            val name = v.text("viewName") ?: continue
            v.text("createSql")?.let { apply(s, it.replace("\${VIEW_NAME}", name)) }
        }
        return s
    }

    /** Runs every statement of [sql] (as a schema file's SQL, which is one statement each). */
    private fun apply(s: SqlSchema, sql: String) {
        for (st in sqlStatements(sqlLex(sql))) run(s, st, ArrayList())
    }

    /**
     * Replays [calls] on [before] and compares the result with [after], the schema of [afterFile]. [minSdk] (the
     * lowest minSdk and where it is set) turns on the check for statements older Android versions do not have.
     */
    internal fun replay(before: SqlSchema, after: SqlSchema, calls: List<SqlCall>, afterFile: String, minSdk: Pair<Int, String>?): Replayed {
        val s = before.copy()
        val notes = ArrayList<String>()
        for (c in calls) {
            val stmts = try {
                sqlStatements(sqlLex(c.sql))
            } catch (e: SqlUnknown) {
                return Replayed(null, emptyList(), emptyList(), "line ${c.line}: ${e.message}")
            }
            if (stmts.isEmpty()) continue
            if (stmts.size > 1) {
                val more = stmts.size - 2
                notes.add("line ${c.line} passes ${stmts.size} statements to one execSQL, and Android runs only the first, so ${quote(stmts[1])}" +
                    (if (more > 0) " and $more more never run" else " never runs"))
            }
            val ops = ArrayList<String>()
            try {
                run(s, stmts[0], ops)
            } catch (e: SqlFailure) {
                return Replayed("line ${c.line}: ${quote(stmts[0])}, which SQLite refuses (\"${e.message}\")", emptyList(), notes, null)
            } catch (e: SqlUnknown) {
                return Replayed(null, emptyList(), emptyList(), "line ${c.line}: ${e.message}")
            }
            if (minSdk != null) for (op in ops) {
                val (sqlite, android, api) = NEWER_SQL[op] ?: continue
                if (minSdk.first < api) notes.add("line ${c.line} uses ALTER TABLE ... $op, which needs SQLite $sqlite ($android, API $api), but minSdk is " +
                    "${minSdk.first} (${minSdk.second}): on older phones the statement fails (\"syntax error\")")
            }
        }
        return Replayed(null, compare(s, after, afterFile), notes, null)
    }

    private fun quote(st: List<SqlTok>): String {
        val t = sqlText(st)
        return "`" + (if (t.length > 90) t.take(87) + "..." else t) + "`"
    }

    // ----- comparing, as Room's TableInfo, FtsTableInfo and ViewInfo do -----

    private fun stripParens(v: String): String {
        val x = v.trim()
        if (!x.startsWith("(") || !x.endsWith(")")) return x
        var depth = 0
        for ((k, ch) in x.withIndex()) {
            if (ch == '(') depth++
            if (ch == ')') depth--
            if (depth == 0 && k < x.length - 1) return x // "(a) + (b)": not one pair around everything
        }
        return x.substring(1, x.length - 1).trim()
    }

    /** Room's index equality: a name starting with index_ (Room's own names) only has to start with index_ too. */
    private fun sameIndex(x: SqlIndex, y: SqlIndex): Boolean {
        val names = if (x.name.startsWith("index_")) y.name.startsWith("index_") else x.name.equals(y.name, ignoreCase = true)
        return names && x.unique == y.unique && x.columns == y.columns && x.orders == y.orders
    }

    private fun fkKey(f: SqlForeignKey) = f.copy(columns = f.columns.map { it.lowercase() }, table = f.table.lowercase(),
        refColumns = f.refColumns.map { it.lowercase() })

    private fun compare(actual: SqlSchema, expected: SqlSchema, file: String): List<String> {
        val out = ArrayList<String>()
        for (e in expected.tables.values) {
            val a = actual.table(e.name)
            if (a == null) { out.add("table ${e.name} is missing"); continue }
            if (e.virtual || a.virtual) {
                if (e.virtual != a.virtual) out.add("${e.name} is ${if (a.virtual) "a virtual table" else "a plain table"}, unlike in $file")
                else if (e.columns.keys != a.columns.keys) out.add("FTS table ${e.name} has the columns ${a.columns.values.joinToString(", ") { it.name }} " +
                    "instead of ${e.columns.values.joinToString(", ") { it.name }}")
                continue
            }
            for ((k, ec) in e.columns) {
                val ac = a.columns[k]
                if (ac == null) { out.add("${e.name}.${ec.name} is missing"); continue }
                val diffs = ArrayList<String>()
                if (ac.affinity != ec.affinity) diffs.add("${ac.affinity} instead of ${ec.affinity}")
                if (ac.notNull != ec.notNull) diffs.add(if (ec.notNull) "nullable instead of NOT NULL" else "NOT NULL instead of nullable")
                if (ac.pk != ec.pk) diffs.add(when {
                    ec.pk == 0 -> "in the primary key, which it should not be"
                    ac.pk == 0 -> "not in the primary key"
                    else -> "at position ${ac.pk} of the primary key instead of ${ec.pk}"
                })
                val ed = ec.default
                if (ed != null && (ac.default == null || stripParens(ac.default) != stripParens(ed))) {
                    diffs.add(if (ac.default == null) "without DEFAULT $ed" else "DEFAULT ${ac.default} instead of DEFAULT $ed")
                }
                if (diffs.isNotEmpty()) out.add("${e.name}.${ec.name} is ${diffs.joinToString(", ")}")
            }
            for ((k, ac) in a.columns) if (k !in e.columns) out.add("${e.name}.${ac.name} is still there (it is not in $file)")
            val ef = e.foreignKeys.map { fkKey(it) }.toSet()
            val af = a.foreignKeys.map { fkKey(it) }.toSet()
            for (x in ef - af) out.add("${e.name} lacks the foreign key $x")
            for (x in af - ef) out.add("${e.name} has the foreign key $x, which $file does not")
            val unmatched = actual.indices.values.filter { it.table == a.name.lowercase() }.toMutableList()
            for (x in expected.indices.values.filter { it.table == e.name.lowercase() }) {
                val m = unmatched.firstOrNull { sameIndex(x, it) }
                if (m != null) unmatched.remove(m)
                else out.add("${if (x.unique) "unique index" else "index"} ${x.name} on ${e.name}(${x.columns.joinToString(", ")}) is missing")
            }
            for (y in unmatched) out.add("index ${y.name} on ${e.name}(${y.columns.joinToString(", ")}) is not in $file")
        }
        for ((k, v) in expected.views) if (k !in actual.views) out.add("view $v is missing")
        return out
    }

    // ----- statements -----

    private fun fail(message: String): Nothing = throw SqlFailure(message)

    /** Runs one statement on [s], adding to [ops] the ALTER TABLE forms it uses. */
    private fun run(s: SqlSchema, st: List<SqlTok>, ops: MutableList<String>) {
        val p = SqlParser(st)
        when {
            p.eat("CREATE") -> create(p, s)
            p.eat("DROP") -> drop(p, s)
            p.eat("ALTER", "TABLE") -> alter(p, s, ops)
            p.at("INSERT") || p.at("REPLACE") -> insert(p, s)
            p.eat("UPDATE") -> {
                if (p.eat("OR")) p.skip()
                val name = p.name()
                if (s.table(name) == null) fail("no such table: $name")
            }
            p.eat("DELETE", "FROM") -> p.name().let { if (s.table(it) == null) fail("no such table: $it") }
            QUIET.any { p.at(it) } -> {}
            else -> throw SqlUnknown("droidmap does not follow ${st.firstOrNull()?.text ?: "an empty"} statements")
        }
    }

    private val QUIET = listOf("SELECT", "WITH", "PRAGMA", "BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE", "ANALYZE", "VACUUM", "REINDEX")

    private fun create(p: SqlParser, s: SqlSchema) {
        val temp = p.eat("TEMP") || p.eat("TEMPORARY")
        when {
            p.eat("TABLE") -> {
                val ifNot = p.eat("IF", "NOT", "EXISTS")
                val name = p.name()
                if (p.at("AS")) throw SqlUnknown("CREATE TABLE ... AS SELECT")
                val t = table(name, p)
                if (temp) return
                if (s.takenBy(name)) { if (ifNot) return else fail("table $name already exists") }
                s.tables[name.lowercase()] = t
            }
            p.eat("VIRTUAL", "TABLE") -> {
                val ifNot = p.eat("IF", "NOT", "EXISTS")
                val name = p.name()
                if (s.takenBy(name)) { if (ifNot) return else fail("table $name already exists") }
                p.need("USING")
                p.name()
                // FTS columns, without the options (tokenize=..., content=...).
                val cols = if (p.isPunct("(")) sqlItems(p.group()).filter { it.none { t -> t.kind == 'p' && t.text == "=" } }.map { SqlParser(it).name() } else emptyList()
                s.tables[name.lowercase()] = SqlTable(name, LinkedHashMap(cols.associate { it.lowercase() to SqlColumn(it, null, false, 0, null) }), ArrayList(), virtual = true)
            }
            p.eat("UNIQUE", "INDEX") -> index(p, s, unique = true)
            p.eat("INDEX") -> index(p, s, unique = false)
            p.eat("VIEW") -> {
                val ifNot = p.eat("IF", "NOT", "EXISTS")
                val name = p.name()
                if (temp) return
                if (s.takenBy(name)) { if (ifNot) return else fail("view $name already exists") }
                s.views[name.lowercase()] = name
            }
            p.eat("TRIGGER") -> {}
            else -> throw SqlUnknown("droidmap does not follow CREATE ${p.peek()?.text}")
        }
    }

    private val TABLE_CONSTRAINTS = setOf("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN")

    private fun table(name: String, p: SqlParser): SqlTable {
        val t = SqlTable(name, LinkedHashMap(), ArrayList(), virtual = false)
        for (item in sqlItems(p.group())) {
            val first = item.firstOrNull() ?: continue
            if (first.kind == 'w' && first.text.uppercase() in TABLE_CONSTRAINTS) {
                val ip = SqlParser(item)
                tableConstraint(ip, t)
                if (!ip.done) throw SqlUnknown("droidmap does not follow the constraint ${sqlText(item)}")
                continue
            }
            val def = columnDef(item)
            if (def.generated) throw SqlUnknown("a generated column")
            t.columns[def.column.name.lowercase()] = def.column
            def.fk?.let { t.foreignKeys.add(it) }
        }
        return t
    }

    private fun tableConstraint(p: SqlParser, t: SqlTable) {
        if (p.eat("CONSTRAINT")) p.name()
        when {
            p.eat("PRIMARY", "KEY") -> {
                indexed(p.group()).forEachIndexed { k, (c, _) ->
                    val col = t.columns[c] ?: throw SqlUnknown("PRIMARY KEY names $c, which is not a column")
                    t.columns[c] = col.copy(pk = k + 1)
                }
                conflict(p)
            }
            p.eat("UNIQUE") -> { p.group(); conflict(p) }
            p.eat("CHECK") -> p.group()
            p.eat("FOREIGN", "KEY") -> t.foreignKeys.add(references(p, sqlItems(p.group()).map { SqlParser(it).name() }))
            else -> throw SqlUnknown("droidmap does not follow the constraint ${p.peek()?.text}")
        }
    }

    private class ColumnDef(val column: SqlColumn, val unique: Boolean, val fk: SqlForeignKey?, val generated: Boolean)

    private val COLUMN_CONSTRAINTS = setOf("CONSTRAINT", "PRIMARY", "NOT", "NULL", "UNIQUE", "CHECK", "DEFAULT", "COLLATE", "REFERENCES", "GENERATED", "AS")

    private fun columnDef(ts: List<SqlTok>): ColumnDef {
        val p = SqlParser(ts)
        val name = p.name()
        val type = StringBuilder()
        while (!p.done) {
            val tok = p.peek()!!
            if (tok.kind == 'w' && tok.text.uppercase() !in COLUMN_CONSTRAINTS) {
                if (type.isNotEmpty()) type.append(' ')
                type.append(tok.text)
                p.skip()
            } else if (tok.kind == 'p' && tok.text == "(" && type.isNotEmpty()) {
                type.append('(').append(sqlText(p.group())).append(')')
            } else break
        }
        var notNull = false
        var pk = 0
        var default: String? = null
        var unique = false
        var fk: SqlForeignKey? = null
        var generated = false
        while (!p.done) {
            when {
                p.eat("CONSTRAINT") -> p.name()
                p.eat("PRIMARY", "KEY") -> {
                    pk = 1
                    if (!p.eat("ASC")) p.eat("DESC")
                    conflict(p)
                    p.eat("AUTOINCREMENT")
                }
                p.eat("NOT", "NULL") -> { notNull = true; conflict(p) }
                p.eat("NULL") -> {}
                p.eat("UNIQUE") -> { unique = true; conflict(p) }
                p.eat("CHECK") -> p.group()
                p.eat("DEFAULT") -> default = defaultValue(p)
                p.eat("COLLATE") -> p.name()
                p.at("REFERENCES") -> fk = references(p, listOf(name))
                p.eat("GENERATED", "ALWAYS", "AS") || p.eat("AS") -> {
                    p.group()
                    generated = true
                    if (!p.eat("STORED")) p.eat("VIRTUAL")
                }
                else -> throw SqlUnknown("droidmap does not follow the column $name (${p.peek()!!.text})")
            }
        }
        return ColumnDef(SqlColumn(name, type.toString().ifEmpty { null }, notNull, pk, default), unique, fk, generated)
    }

    private fun conflict(p: SqlParser) {
        if (p.eat("ON", "CONFLICT")) p.skip()
    }

    private fun defaultValue(p: SqlParser): String {
        if (p.isPunct("(")) return "(" + sqlText(p.group()) + ")"
        val tok = p.peek() ?: throw SqlUnknown("DEFAULT without a value")
        p.skip()
        if (tok.kind == 'p' && (tok.text == "-" || tok.text == "+")) {
            val n = p.peek() ?: throw SqlUnknown("DEFAULT without a value")
            p.skip()
            return tok.text + n.text
        }
        return tok.sql()
    }

    private fun references(p: SqlParser, columns: List<String>): SqlForeignKey {
        p.need("REFERENCES")
        val table = p.name()
        val ref = if (p.isPunct("(")) sqlItems(p.group()).map { SqlParser(it).name() } else emptyList()
        var onDelete = "NO ACTION"
        var onUpdate = "NO ACTION"
        while (!p.done) {
            when {
                p.eat("ON", "DELETE") -> onDelete = action(p)
                p.eat("ON", "UPDATE") -> onUpdate = action(p)
                p.eat("MATCH") -> p.name()
                p.eat("NOT", "DEFERRABLE") || p.eat("DEFERRABLE") -> if (p.eat("INITIALLY")) { if (!p.eat("DEFERRED")) p.eat("IMMEDIATE") }
                else -> break
            }
        }
        return SqlForeignKey(columns, table, ref, onDelete, onUpdate)
    }

    private fun action(p: SqlParser): String = when {
        p.eat("SET", "NULL") -> "SET NULL"
        p.eat("SET", "DEFAULT") -> "SET DEFAULT"
        p.eat("CASCADE") -> "CASCADE"
        p.eat("RESTRICT") -> "RESTRICT"
        p.eat("NO", "ACTION") -> "NO ACTION"
        else -> throw SqlUnknown("droidmap does not follow the foreign key action ${p.peek()?.text}")
    }

    /** Index or primary key columns: (lower-case name, ASC or DESC). */
    private fun indexed(ts: List<SqlTok>): List<Pair<String, String>> = sqlItems(ts).map { item ->
        val p = SqlParser(item)
        val name = p.name().lowercase()
        if (p.eat("COLLATE")) p.name()
        val order = if (p.eat("DESC")) "DESC" else { p.eat("ASC"); "ASC" }
        if (!p.done) throw SqlUnknown("an index on an expression")
        name to order
    }

    private fun index(p: SqlParser, s: SqlSchema, unique: Boolean) {
        val ifNot = p.eat("IF", "NOT", "EXISTS")
        val name = p.name()
        p.need("ON")
        val tname = p.name()
        val cols = indexed(p.group())
        if (s.takenBy(name)) { if (ifNot) return else fail("index $name already exists") }
        val t = s.table(tname) ?: fail("no such table: $tname")
        for ((c, _) in cols) if (c !in t.columns) fail("no such column: $c")
        s.indices[name.lowercase()] = SqlIndex(name, t.name.lowercase(), unique, cols.map { it.first }, cols.map { it.second })
    }

    private fun drop(p: SqlParser, s: SqlSchema) {
        when {
            p.eat("TABLE") -> {
                val ifExists = p.eat("IF", "EXISTS")
                val name = p.name()
                if (s.table(name) == null) { if (ifExists) return else fail("no such table: $name") }
                s.tables.remove(name.lowercase())
                s.indices.values.removeIf { it.table == name.lowercase() }
            }
            p.eat("INDEX") -> {
                val ifExists = p.eat("IF", "EXISTS")
                val name = p.name()
                if (s.indices.remove(name.lowercase()) == null && !ifExists) fail("no such index: $name")
            }
            p.eat("VIEW") -> {
                val ifExists = p.eat("IF", "EXISTS")
                val name = p.name()
                if (s.views.remove(name.lowercase()) == null && !ifExists) fail("no such view: $name")
            }
            p.eat("TRIGGER") -> {}
            else -> throw SqlUnknown("droidmap does not follow DROP ${p.peek()?.text}")
        }
    }

    private fun alter(p: SqlParser, s: SqlSchema, ops: MutableList<String>) {
        val name = p.name()
        val t = s.table(name) ?: fail("no such table: $name")
        val low = t.name.lowercase()
        when {
            p.eat("RENAME", "TO") -> {
                val to = p.name()
                if (!to.equals(name, ignoreCase = true) && s.takenBy(to)) fail("there is already another table or index with this name: $to")
                s.tables.remove(low)
                t.name = to
                s.tables[to.lowercase()] = t
                for ((k, ix) in s.indices) if (ix.table == low) s.indices[k] = ix.copy(table = to.lowercase())
                for (o in s.tables.values) o.foreignKeys.replaceAll { if (it.table.equals(low, ignoreCase = true)) it.copy(table = to) else it }
            }
            p.eat("RENAME") -> {
                p.eat("COLUMN")
                val old = p.name()
                p.need("TO")
                val new = p.name()
                ops.add("RENAME COLUMN")
                if (t.virtual) fail("virtual tables may not be altered")
                val c = t.columns[old.lowercase()] ?: fail("no such column: \"$old\"")
                if (!new.equals(old, ignoreCase = true) && new.lowercase() in t.columns) fail("duplicate column name: $new")
                val rebuilt = LinkedHashMap<String, SqlColumn>()
                for ((k, v) in t.columns) if (k == old.lowercase()) rebuilt[new.lowercase()] = c.copy(name = new) else rebuilt[k] = v
                t.columns.clear()
                t.columns.putAll(rebuilt)
                fun renamed(cs: List<String>) = cs.map { if (it.equals(old, ignoreCase = true)) new else it }
                for ((k, ix) in s.indices) if (ix.table == low) s.indices[k] = ix.copy(columns = ix.columns.map { if (it == old.lowercase()) new.lowercase() else it })
                t.foreignKeys.replaceAll { it.copy(columns = renamed(it.columns)) }
                for (o in s.tables.values) o.foreignKeys.replaceAll { if (it.table.equals(low, ignoreCase = true)) it.copy(refColumns = renamed(it.refColumns)) else it }
            }
            p.eat("ADD") -> {
                p.eat("COLUMN")
                if (t.virtual) fail("virtual tables may not be altered")
                val def = columnDef(p.rest())
                val c = def.column
                if (c.name.lowercase() in t.columns) fail("duplicate column name: ${c.name}")
                if (c.pk > 0) fail("Cannot add a PRIMARY KEY column")
                if (def.unique) fail("Cannot add a UNIQUE column")
                if (c.notNull && (c.default == null || c.default.equals("NULL", ignoreCase = true))) fail("Cannot add a NOT NULL column with default value NULL")
                if (def.generated) throw SqlUnknown("a generated column")
                t.columns[c.name.lowercase()] = c
                def.fk?.let { t.foreignKeys.add(it) }
            }
            p.eat("DROP") -> {
                p.eat("COLUMN")
                val col = p.name()
                ops.add("DROP COLUMN")
                if (t.virtual) fail("virtual tables may not be altered")
                val c = t.columns[col.lowercase()] ?: fail("no such column: \"$col\"")
                if (c.pk > 0) fail("cannot drop PRIMARY KEY column: \"$col\"")
                s.indices.values.firstOrNull { it.table == low && col.lowercase() in it.columns }?.let {
                    fail("error in index ${it.name} after drop column: no such column: $col")
                }
                if (t.foreignKeys.any { f -> f.columns.any { it.equals(col, ignoreCase = true) } }) fail("cannot drop column \"$col\": it is used in a foreign key")
                t.columns.remove(col.lowercase())
            }
            else -> throw SqlUnknown("droidmap does not follow ALTER TABLE ... ${p.peek()?.text}")
        }
    }

    /** INSERT INTO: the table and the columns named, and, for INSERT ... SELECT from one table, the columns read and their number. */
    private fun insert(p: SqlParser, s: SqlSchema) {
        if (!p.eat("REPLACE")) {
            p.need("INSERT")
            if (p.eat("OR")) p.skip()
        }
        p.need("INTO")
        val name = p.name()
        val t = s.table(name) ?: fail("no such table: $name")
        if (p.eat("AS")) p.name()
        val cols = if (p.isPunct("(")) sqlItems(p.group()).map { SqlParser(it).name() } else null
        if (t.virtual) return
        if (cols != null) for (c in cols) if (c.lowercase() !in t.columns) fail("table ${t.name} has no column named $c")
        if (!p.eat("SELECT")) return
        if (!p.eat("DISTINCT")) p.eat("ALL")
        val list = ArrayList<SqlTok>()
        var depth = 0
        while (!p.done) {
            val tok = p.peek()!!
            if (depth == 0 && tok.isWord("FROM")) break
            if (tok.kind == 'p' && tok.text == "(") depth++
            if (tok.kind == 'p' && tok.text == ")") depth--
            list.add(tok)
            p.skip()
        }
        val values = sqlItems(list)
        val star = values.any { v -> v.lastOrNull()?.let { it.kind == 'p' && it.text == "*" } == true }
        val want = cols?.size ?: t.columns.size
        if (!star && values.size != want) {
            fail(if (cols != null) "${values.size} values for ${cols.size} columns" else "table ${t.name} has ${t.columns.size} columns but ${values.size} values were supplied")
        }
        if (!p.eat("FROM")) return
        val src = p.name()
        val from = s.table(src)
        if (from == null) { if (src.lowercase() in s.views) return else fail("no such table: $src") }
        if (from.virtual) return
        // The columns read are checked only when they come from that one table.
        var alias: String? = null
        if (p.eat("AS")) alias = p.name() else if (p.peek()?.kind == 'w' && !p.peek()!!.text.uppercase().let { it in CLAUSES }) alias = p.name()
        val tail = p.rest()
        var d = 0
        for (tok in tail) {
            if (tok.kind == 'p' && tok.text == "(") d++
            if (tok.kind == 'p' && tok.text == ")") d--
            if (d == 0 && (tok.isWord("JOIN") || (tok.kind == 'p' && tok.text == ","))) return
            if (d == 0 && tok.kind == 'w' && tok.text.uppercase() in CLAUSES) break
        }
        for (v in values) {
            val col = when {
                v.size == 1 && v[0].kind in "wq" && v[0].text.uppercase() !in LITERAL_WORDS -> v[0].text
                v.size == 3 && v[1].kind == 'p' && v[1].text == "." && v[2].kind in "wq" &&
                    (v[0].text.equals(src, ignoreCase = true) || v[0].text.equals(alias, ignoreCase = true)) -> v[2].text
                else -> null
            } ?: continue
            if (col.lowercase() !in from.columns) fail("no such column: $col")
        }
    }

    private val CLAUSES = setOf("WHERE", "GROUP", "ORDER", "LIMIT", "UNION", "EXCEPT", "INTERSECT", "WINDOW", "HAVING", "ON", "JOIN", "INNER", "LEFT", "CROSS", "NATURAL")
    private val LITERAL_WORDS = setOf("NULL", "TRUE", "FALSE", "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME")
}

// ----- tokens -----

/** A token: [kind] is w (a word), q (a quoted name), s (a string), n (a number) or p (punctuation). */
private class SqlTok(val text: String, val kind: Char) {
    fun isWord(w: String) = kind == 'w' && text.equals(w, ignoreCase = true)

    /** The token as SQL text again. */
    fun sql(): String = when (kind) {
        's' -> "'" + text.replace("'", "''") + "'"
        'q' -> "`$text`"
        else -> text
    }
}

private val OPS2 = setOf("||", "<=", ">=", "!=", "<>", "==", "<<", ">>")

private fun sqlLex(sql: String): List<SqlTok> {
    val out = ArrayList<SqlTok>()
    var i = 0
    val n = sql.length
    while (i < n) {
        val c = sql[i]
        when {
            c.isWhitespace() -> i++
            sql.startsWith("--", i) -> { val e = sql.indexOf('\n', i); i = if (e < 0) n else e + 1 }
            sql.startsWith("/*", i) -> { val e = sql.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
            c == '\'' || c == '"' || c == '`' || c == '[' -> {
                val close = if (c == '[') ']' else c
                val sb = StringBuilder()
                var j = i + 1
                while (true) {
                    if (j >= n) throw SqlUnknown("a quote that is never closed")
                    val d = sql[j]
                    if (d == close) {
                        if (close != ']' && j + 1 < n && sql[j + 1] == close) { sb.append(d); j += 2; continue }
                        break
                    }
                    sb.append(d)
                    j++
                }
                out.add(SqlTok(sb.toString(), if (c == '\'') 's' else 'q'))
                i = j + 1
            }
            c.isLetter() || c == '_' -> {
                var j = i + 1
                while (j < n && (sql[j].isLetterOrDigit() || sql[j] == '_' || sql[j] == '$')) j++
                out.add(SqlTok(sql.substring(i, j), 'w'))
                i = j
            }
            c.isDigit() || (c == '.' && i + 1 < n && sql[i + 1].isDigit()) -> {
                var j = i + 1
                while (j < n && (sql[j].isLetterOrDigit() || sql[j] == '.' || ((sql[j] == '+' || sql[j] == '-') && sql[j - 1] in "eE"))) j++
                out.add(SqlTok(sql.substring(i, j), 'n'))
                i = j
            }
            else -> {
                val two = if (i + 1 < n) sql.substring(i, i + 2) else ""
                if (two in OPS2) { out.add(SqlTok(two, 'p')); i += 2 } else { out.add(SqlTok(c.toString(), 'p')); i++ }
            }
        }
    }
    return out
}

/** The statements in [tokens], split at semicolons (except inside a trigger's BEGIN ... END). */
private fun sqlStatements(tokens: List<SqlTok>): List<List<SqlTok>> {
    val out = ArrayList<List<SqlTok>>()
    var cur = ArrayList<SqlTok>()
    var trigger = false
    var body = false
    for (t in tokens) {
        if (t.kind == 'p' && t.text == ";" && !body) {
            if (cur.isNotEmpty()) out.add(cur)
            cur = ArrayList()
            trigger = false
            continue
        }
        cur.add(t)
        if (cur.size <= 3 && t.isWord("TRIGGER") && cur[0].isWord("CREATE")) trigger = true
        if (trigger && t.isWord("BEGIN")) body = true
        if (body && t.isWord("END")) body = false
    }
    if (cur.isNotEmpty()) out.add(cur)
    return out
}

private fun sqlText(ts: List<SqlTok>): String = buildString {
    for ((k, t) in ts.withIndex()) {
        val prev = ts.getOrNull(k - 1)
        val tight = t.kind == 'p' && t.text in setOf(",", ")", ".") || prev?.kind == 'p' && prev.text in setOf("(", ".")
        if (k > 0 && !tight) append(' ')
        append(t.sql())
    }
}

/** [tokens] split at the commas outside parentheses. */
private fun sqlItems(tokens: List<SqlTok>): List<List<SqlTok>> {
    val out = ArrayList<List<SqlTok>>()
    var cur = ArrayList<SqlTok>()
    var depth = 0
    for (t in tokens) {
        if (t.kind == 'p' && t.text == "(") depth++
        if (t.kind == 'p' && t.text == ")") depth--
        if (depth == 0 && t.kind == 'p' && t.text == ",") { out.add(cur); cur = ArrayList(); continue }
        cur.add(t)
    }
    if (cur.isNotEmpty()) out.add(cur)
    return out
}

private class SqlParser(private val t: List<SqlTok>) {
    private var i = 0
    val done get() = i >= t.size
    fun peek(k: Int = 0): SqlTok? = t.getOrNull(i + k)
    fun skip() { i++ }
    fun at(vararg ws: String) = ws.withIndex().all { (k, w) -> peek(k)?.isWord(w) == true }
    fun eat(vararg ws: String): Boolean = at(*ws).also { if (it) i += ws.size }
    fun need(vararg ws: String) { if (!eat(*ws)) throw SqlUnknown("expected ${ws.joinToString(" ")} at ${peek()?.text ?: "the end"}") }
    fun isPunct(p: String) = peek()?.let { it.kind == 'p' && it.text == p } == true

    /** A table, column or index name; a schema-qualified one (main.x) loses its schema. */
    fun name(): String {
        val tok = peek() ?: throw SqlUnknown("a name is missing")
        if (tok.kind !in "wqs") throw SqlUnknown("expected a name at ${tok.text}")
        i++
        if (isPunct(".") && peek(1)?.kind?.let { it in "wqs" } == true) { i++; return name() }
        return tok.text
    }

    /** The tokens inside the parentheses that start here (consumed). */
    fun group(): List<SqlTok> {
        if (!isPunct("(")) throw SqlUnknown("expected ( at ${peek()?.text ?: "the end"}")
        i++
        val start = i
        var depth = 1
        while (i < t.size) {
            val tok = t[i]
            if (tok.kind == 'p' && tok.text == "(") depth++
            if (tok.kind == 'p' && tok.text == ")") {
                depth--
                if (depth == 0) { i++; return t.subList(start, i - 1) }
            }
            i++
        }
        throw SqlUnknown("parentheses that are never closed")
    }

    fun rest(): List<SqlTok> = t.subList(i, t.size).also { i = t.size }
}

/**
 * The lowest minSdk the project sets, and where: in a build file, a version catalog or a convention plugin
 * (build-logic, buildSrc). Null when none is found.
 */
fun minSdk(p: Project): Pair<Int, String>? {
    val gradle = Regex("""\bminSdk(?:Version)?\s*(?:=|\(|\s)\s*(\d{1,2})\b""")
    val catalog = Regex("""(?im)^\s*"?[\w.-]*min-?sdk[\w.-]*"?\s*=\s*"?(\d{1,2})"?\s*$""")
    val files = p.gradlePaths.map { it to gradle } + p.tomlPaths.map { it to catalog } +
        p.ktPaths.filter { "build-logic/" in it || "buildSrc/" in it || it.startsWith("buildSrc/") }.map { it to gradle }
    var best: Pair<Int, String>? = null
    for ((rel, re) in files) {
        val text = runCatching { p.read(rel) }.getOrNull() ?: continue
        for (m in re.findAll(text)) {
            val v = m.groupValues[1].toInt()
            if (v in 1..40 && (best == null || v < best.first)) best = v to rel
        }
    }
    return best
}
