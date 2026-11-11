package droidmap.extract

import droidmap.Arg
import droidmap.Code
import droidmap.Decl
import droidmap.Field
import droidmap.Fn
import droidmap.Git
import droidmap.Graph
import droidmap.Project
import droidmap.Source
import droidmap.Type
import droidmap.arg
import droidmap.classRefs
import droidmap.compactJson
import droidmap.find
import droidmap.has
import droidmap.int
import droidmap.namesIn
import droidmap.nestedCalls
import droidmap.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.com.intellij.psi.PsiElement

private val SQL_TABLE = Regex("""\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\s+[`"\[]?(\w+)""", RegexOption.IGNORE_CASE)
private val WRITE_ANNS = listOf("Insert", "Update", "Delete", "Upsert")
private val FALLBACKS = setOf("fallbackToDestructiveMigration", "fallbackToDestructiveMigrationFrom", "fallbackToDestructiveMigrationOnDowngrade")
private val COLLECTIONS = setOf("List", "Collection", "Set", "Iterable", "Array")
private val REPLACE = Regex("\\bREPLACE\\b")
private val HELPER_CALLS = setOf("createDatabase", "runMigrationsAndValidate")

fun migrationId(db: String?, from: Int, to: Int, name: String) = "room.migration::${db ?: "?"}:$from->$to:$name"
fun schemaId(db: String, version: Int) = "room.schema::$db@$version"

/** Room: databases, entities, DAOs, exported schema files, migrations and migration tests. */
class RoomScan(private val p: Project, private val code: Code, private val g: Graph, private val base: String? = null) {
    private val index = code.index
    private val eval = code.eval
    private val dbs = LinkedHashMap<String, Type>()
    private val entities = HashMap<String, Type>()
    private val tables = HashMap<String, String>() // lower table name -> entity fq
    private val daos = LinkedHashMap<String, Type>()
    private class Manual(val id: String, val name: String, val inTests: Boolean)
    private val manual = ArrayList<Manual>()
    /** Databases whose @Database(entities = [...]) lists a class droidmap could not read. */
    private val partialEntityLists = HashSet<String>()
    /** (database, version) -> table -> column -> what its schema JSON says. */
    private val schemaTables = HashMap<Pair<String, Int>, Map<String, Map<String, Column>>>()

    fun run() {
        val files = code.sources.filter { it.importsFrom("androidx.room") }
        for (s in files) for (t in s.types) {
            if (t.anns.has("Entity")) entity(t)
            if (t.anns.has("Dao")) { daos[t.fq] = t; nodeForClass(g, t, "room_dao") }
            if (t.anns.has("Database")) dbs[t.fq] = t
        }
        for (t in dbs.values) database(t)
        for (t in daos.values) dao(t)
        for (s in files) {
            manualMigrations(s)
            for (call in s.calls) {
                if (call.name !in FALLBACKS) continue
                @Suppress("UNCHECKED_CAST")
                val list = g.meta.getOrPut("room_destructive_fallback") { ArrayList<Map<String, Any?>>() } as MutableList<Map<String, Any?>>
                list.add(linkedMapOf("file" to s.path, "line" to call.line, "call" to call.name, "module" to s.module))
            }
        }
        countReferences()
        schemas()
        drift()
        tests()
    }

    // ----- entities, databases, DAOs -----
    private fun entity(t: Type) {
        val table = (eval.eval(t.anns.find("Entity")?.args?.arg("tableName")?.expr) as? String) ?: t.name ?: t.fq
        entities[t.fq] = t
        tables[table.lowercase()] = t.fq
        nodeForClass(g, t, "room_entity", mapOf("table" to table, "fts" to flag(t.anns.has("Fts4", "Fts3"))))
    }

    private fun database(t: Type) {
        val args = t.anns.find("Database")?.args ?: emptyList()
        val version = eval.evalInt(args.arg("version")?.expr)
        val export = (eval.eval(args.arg("exportSchema")?.expr) as? Boolean) ?: true
        val dbid = nodeForClass(g, t, "room_database", mapOf("version" to version, "export_schema" to export))
        val s = t.src
        if (version == null) g.addUnresolved("room_version", t.fq, s.path, t.line, "@Database version is not a constant")
        for ((name, lit) in classRefs(args.arg("entities")?.psi)) {
            val e = index.resolveType(name, s, t.psi)?.fq
            if (e == null || e !in entities) partialEntityLists.add(t.fq)
            if (e == null) g.addUnresolved("room_entity", name, s.path, s.line(lit), "entity class not found in the project")
            else g.addEdge(dbid, classId(e), "contains_entity", s.path, s.line(lit))
        }
        for ((a, call) in nestedCalls(args.arg("autoMigrations")?.psi, "AutoMigration")) {
            val from = eval.evalInt(a.arg("from", 0)?.expr)
            val to = eval.evalInt(a.arg("to", 1)?.expr)
            val line = s.line(call)
            if (from == null || to == null) {
                g.addUnresolved("room_migration", "AutoMigration", s.path, line, "from/to are not constants")
                continue
            }
            val mid = migrationId(t.fq, from, to, "auto")
            g.addNode(mid, "room_migration", "AutoMigration $from->$to", s.path, line, s.module,
                mapOf("db" to t.fq, "from_version" to from, "to_version" to to, "style" to "auto", "spec" to classRefs(a.arg("spec")?.psi).firstOrNull()?.first))
            g.addEdge(mid, dbid, "migrates", s.path, line)
        }
        // DAO getters: abstract functions or properties whose type is a DAO.
        val members: List<Decl> = (t.fns + t.fields.filter { !it.isParam }).sortedBy { it.psi.textOffset }
        for (m in members) {
            val type = (m as? Fn)?.returns ?: (m as? Field)?.type ?: continue
            val target = index.resolveType(type.name, s, t.psi)?.fq ?: continue
            if (target in daos) g.addEdge(dbid, classId(target), "contains_dao", s.path, m.line)
        }
    }

    /**
     * A DAO function. An inherited one (`ItemDao : BaseDao<Item>`) comes with the base's type
     * parameters bound to the DAO's arguments, as class names.
     */
    private class DaoFun(val fn: Fn, val typeArgs: Map<String, String>)

    private fun daoFunctions(t: Type, typeArgs: Map<String, String>, seen: MutableSet<Type>): List<DaoFun> {
        if (!seen.add(t)) return emptyList()
        val out = t.fns.mapTo(ArrayList()) { DaoFun(it, typeArgs) }
        for (sup in t.supers) {
            val base = index.resolveType(sup.type.name, t.src, t.psi) ?: continue
            val bound = base.typeParams.zip(sup.type.args).mapNotNull { (param, arg) ->
                (typeArgs[arg.name] ?: index.resolveType(arg.name, t.src, t.psi)?.fq)?.let { param to it }
            }.toMap()
            out += daoFunctions(base, bound, seen)
        }
        return out
    }

    private fun dao(t: Type) {
        class Agg(val line: Int) { val functions = sortedSetOf<String>(); val replace = ArrayList<Map<String, Any?>>() }
        val agg = sortedMapOf<String, Agg>()
        for (df in daoFunctions(t, emptyMap(), HashSet())) {
            val fn = df.fn
            // Edges live in the DAO's file: an inherited function points at the DAO's own line.
            val line = if (fn.src == t.src) fn.line else t.line
            fn.anns.find("Query")?.let { q ->
                val sql = eval.eval((q.args.arg("value") ?: q.args.arg(null, 0))?.expr) as? String
                if (sql == null) {
                    g.addUnresolved("room_query", "${t.name}.${fn.name}", fn.src.path, fn.line, "SQL is not a constant")
                } else {
                    for (m in SQL_TABLE.findAll(sql)) {
                        val efq = tables[m.groupValues[1].lowercase()] ?: continue
                        agg.getOrPut("queries\u0000$efq") { Agg(line) }.functions.add(fn.name ?: "?")
                    }
                }
            }
            for (wa in WRITE_ANNS) {
                val ann = fn.anns.find(wa) ?: continue
                val replace = ann.text?.let { REPLACE.containsMatchIn(it) } == true
                for (prm in fn.params) {
                    var ty = prm.type
                    while (ty != null && ty.simple in COLLECTIONS && ty.args.isNotEmpty()) ty = ty.args[0]
                    val e = ty?.let { df.typeArgs[it.name] ?: index.resolveType(it.name, fn.src, fn.owner?.psi ?: fn.psi)?.fq } ?: continue
                    if (e !in entities) continue
                    val a = agg.getOrPut("writes\u0000$e") { Agg(line) }
                    a.functions.add(fn.name ?: "?")
                    if (replace) a.replace.add(linkedMapOf("function" to fn.name, "file" to fn.src.path, "line" to fn.src.line(ann.psi)))
                }
            }
        }
        for ((key, a) in agg) {
            val (kind, efq) = key.split('\u0000')
            g.addEdge(classId(t.fq), classId(efq), kind, t.src.path, a.line,
                mapOf("functions" to a.functions.toList(), "replace" to a.replace.ifEmpty { null }))
        }
    }

    // ----- migrations -----
    private fun dbFor(s: Source): String? {
        val same = dbs.filter { it.value.src.module == s.module }.keys
        if (same.size == 1) return same.first()
        return if (dbs.size == 1) dbs.keys.first() else null
    }

    /**
     * Manual migrations: `object M : Migration(1, 2)`, `val M = Migration(1, 2) { }`, an anonymous
     * `object : Migration(1, 2) { }`, or Java's `new Migration(1, 2) { }`. [Found.named] is the class,
     * object or property that names it; null when it is written inline, as in addMigrations(object : Migration(1, 2) {...}).
     */
    private class Found(val args: List<Arg>, val at: PsiElement, val named: Decl?)

    private fun manualMigrations(s: Source) {
        val found = ArrayList<Found>()
        for (t in s.types) for (sup in t.supers) if (sup.args != null && sup.type.simple == "Migration") found.add(Found(sup.args, sup.psi, t))
        for (call in s.calls) {
            if (call.name != "Migration" || (call.receiverName != null && call.receiverName != "migration")) continue
            found.add(Found(call.args, call.psi, call.assignedTo))
        }
        for (f in found) {
            val from = eval.evalInt(f.args.arg("startVersion", 0)?.expr)
            val to = eval.evalInt(f.args.arg("endVersion", 1)?.expr)
            val name = f.named?.name ?: "<anonymous>"
            val owner = (f.named as? Type)?.fq ?: (f.named as? Field)?.fq ?: "${s.pkg}.<anonymous>"
            val line = s.line(f.at)
            if (from == null || to == null) {
                g.addUnresolved("room_migration", name, s.path, line, "versions are not constants")
                continue
            }
            val db = dbFor(s)
            val mid = migrationId(db, from, to, name)
            g.addNode(mid, "room_migration", "$name $from->$to", s.path, line, s.module,
                mapOf("db" to db, "from_version" to from, "to_version" to to, "style" to "manual", "name" to name, "owner" to owner,
                    "end_line" to (f.named?.endLine ?: s.lineAt(f.at.textRange.endOffset))))
            if (f.named != null) manual.add(Manual(mid, name, s.isTest)) // one passed inline is used where it is written
            if (db != null) g.addEdge(mid, classId(db), "migrates", s.path, line)
        }
    }

    /**
     * How often app code uses each manual migration's name (0 = never passed to addMigrations).
     * A test that runs a migration does not count: that is exactly the one written, tested and never registered.
     */
    private fun countReferences() {
        for (m in manual) g.nodes[m.id]!!.attrs["references"] = code.references(includeTests = m.inTests)[m.name] ?: 0
    }

    // ----- schema files -----
    /** The "database" object of a Room schema JSON file. */
    private fun schemaDatabase(text: String): JsonObject? =
        (compactJson.parseToJsonElement(text) as? JsonObject)?.get("database") as? JsonObject

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun schemas() {
        for (rel in p.jsonPaths) {
            val parts = rel.split('/')
            if (parts.size < 2 || parts[parts.size - 2] !in dbs) continue
            val db = parts[parts.size - 2]
            val version = parts.last().dropLast(5).toIntOrNull() ?: continue
            var tablesInFile = emptyList<String>()
            var hash: String? = null
            try {
                val database = schemaDatabase(p.read(rel))
                val jv = database?.get("version")?.jsonPrimitive?.intOrNull
                if (jv != null && jv != version) g.addUnresolved("room_schema", rel, rel, null, "file name says $version, JSON says $jv")
                hash = database?.text("identityHash")
                val ts = (database?.get("entities") as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
                tablesInFile = ts.mapNotNull { it.text("tableName") }.sorted()
                schemaTables[db to version] = ts.mapNotNull { t ->
                    val fields = (t["fields"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return@mapNotNull null
                    val cols = fields.mapNotNull { f ->
                        f.text("columnName")?.let { Column(it, f.text("affinity"), (f["notNull"] as? JsonPrimitive)?.booleanOrNull == true) }
                    }
                    t.text("tableName")?.let { it to cols.associateBy { c -> c.name } }
                }.toMap()
            } catch (e: Exception) {
                g.addUnresolved("room_schema", rel, rel, null, "not valid Room schema JSON")
            }
            // With --base: the same file's identity hash there (null when the file is new since then).
            val baseHash = base?.let { b -> Git.show(p.root, b, rel)?.let { runCatching { schemaDatabase(it)?.text("identityHash") }.getOrNull() } }
            val sid = schemaId(db, version)
            g.addNode(sid, "room_schema", "${db.substringAfterLast('.')} v$version", rel, null, p.moduleOf(rel),
                mapOf("db" to db, "version" to version, "tables" to tablesInFile, "identity_hash" to hash, "base_identity_hash" to baseHash))
            g.addEdge(classId(db), sid, "has_schema", rel)
        }
    }

    /**
     * Compares each database's entities with the schema JSON of its current version. A difference
     * means the version was not bumped after an entity changed (users on it crash on update), or
     * the JSON was not regenerated. Recorded as the database's "schema_drift" attribute.
     */
    private fun drift() {
        val reader = RoomColumns(code)
        for (fq in dbs.keys) {
            val node = g.nodes.getValue(classId(fq))
            val version = node.attrs.int("version") ?: continue
            val json = schemaTables[fq to version] ?: continue
            val jsonByLower = json.mapKeys { it.key.lowercase() }
            val problems = ArrayList<String>()
            val declared = HashSet<String>()
            for (e in g.outEdges(classId(fq)).filter { it.kind == "contains_entity" }) {
                val entity = g.nodes[e.target] ?: continue
                val c = entities[e.target.substringAfter("::")] ?: continue
                val table = entity.attrs.str("table") ?: continue
                declared.add(table.lowercase())
                if (c.anns.has("Fts3", "Fts4")) continue // FTS tables have hidden columns; not compared
                val have = jsonByLower[table.lowercase()]
                if (have == null) { problems.add("table $table is new"); continue }
                val mine = reader.of(c)?.associateBy { it.name } ?: continue
                for (col in (mine.keys - have.keys).sorted()) problems.add("$table.$col is new")
                for (col in (have.keys - mine.keys).sorted()) problems.add("$table.$col is gone")
                for (col in (mine.keys intersect have.keys).sorted()) {
                    val a = mine.getValue(col)
                    val b = have.getValue(col)
                    if (a.notNull != b.notNull) problems.add("$table.$col is ${if (a.notNull) "now NOT NULL" else "now nullable"}")
                    if (a.affinity != null && b.affinity != null && a.affinity != b.affinity) problems.add("$table.$col is now ${a.affinity} (was ${b.affinity})")
                }
            }
            if (fq !in partialEntityLists) for (t in json.keys.filter { it.lowercase() !in declared }.sorted()) problems.add("table $t is gone")
            if (problems.isNotEmpty()) node.attrs["schema_drift"] = problems
        }
    }

    // ----- tests -----
    /**
     * The version argument of createDatabase / runMigrationsAndValidate: second in the classic
     * MigrationTestHelper (name, version, ...), first in the driver one of Room 2.7+ (version, ...).
     */
    private fun versionArg(args: List<Arg>): Arg? {
        args.arg("version")?.let { return it }
        val first = args.arg(null, 0)
        return if (eval.eval(first?.expr) is Long) first else args.arg(null, 1)
    }

    private fun tests() {
        val migByDb = g.byKind("room_migration").groupBy { it.attrs["db"] as String? }
        val migNames = manual.associate { it.name to it.id }
        for (s in code.sources) {
            if (!s.isTest || "runMigrationsAndValidate" !in s.text) continue
            var helperDb: String? = null
            for (call in s.calls) if (call.name == "MigrationTestHelper") for (a in call.args) for ((name, _) in classRefs(a.psi)) {
                index.resolveType(name, s, call.psi)?.fq?.takeIf { it in dbs }?.let { helperDb = it }
            }
            val db = helperDb ?: (if (dbs.size == 1) dbs.keys.first() else null)
            for ((fn, calls) in s.calls.filter { it.name in HELPER_CALLS && it.fn != null }.groupBy { it.fn!! }) {
                val created = sortedSetOf<Int>()
                val validated = sortedSetOf<Int>()
                val explicit = HashSet<String>()
                var generic = false
                for (call in calls) {
                    val v = eval.evalInt(versionArg(call.args)?.expr)
                    when {
                        v == null -> generic = true
                        call.name == "createDatabase" -> created.add(v)
                        else -> validated.add(v)
                    }
                    if (call.name == "runMigrationsAndValidate") for (a in call.args) for (r in namesIn(a.psi)) migNames[r]?.let { explicit.add(it) }
                }
                val fq = fn.fq ?: continue
                val tid = "test::$fq"
                val cls = fn.owner?.name
                g.addNode(tid, "test", if (cls != null) "$cls.${fn.name}" else fn.name, s.path, fn.line, s.module,
                    mapOf("source_set" to s.sourceSet, "end_line" to fn.endLine, "db" to db, "created" to created.toList(), "validated" to validated.toList(),
                        "generic" to flag(generic)))
                for (m in migByDb[db] ?: emptyList()) {
                    val from = (m.attrs["from_version"] as Number).toInt()
                    val to = (m.attrs["to_version"] as Number).toInt()
                    val covered = generic || m.id in explicit || created.any { c -> c <= from && validated.any { v -> to <= v } }
                    if (covered) g.addEdge(tid, m.id, "tests", s.path, fn.line, mapOf("generic" to flag(generic && m.id !in explicit)))
                }
            }
        }
    }
}
