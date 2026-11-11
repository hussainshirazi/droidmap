package droidmap.extract

import droidmap.Git
import droidmap.Graph
import droidmap.Project
import droidmap.Source
import droidmap.TypeRef
import droidmap.ann
import droidmap.arg
import droidmap.calleeName
import droidmap.compactJson
import droidmap.fq
import droidmap.hasAnn
import droidmap.receiverText
import droidmap.int
import droidmap.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

private val SQL_TABLE = Regex("""\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\s+[`"\[]?(\w+)""", RegexOption.IGNORE_CASE)
private val WRITE_ANNS = listOf("Insert", "Update", "Delete", "Upsert")
private val FALLBACKS = setOf("fallbackToDestructiveMigration", "fallbackToDestructiveMigrationFrom", "fallbackToDestructiveMigrationOnDowngrade")
private val COLLECTIONS = setOf("List", "Collection", "Set", "Iterable", "Array")
private val REPLACE = Regex("\\bREPLACE\\b")
private val HELPER_CALLS = setOf("createDatabase", "runMigrationsAndValidate")

fun migrationId(db: String?, from: Int, to: Int, name: String) = "room.migration::${db ?: "?"}:$from->$to:$name"
fun schemaId(db: String, version: Int) = "room.schema::$db@$version"

/** Room: databases, entities, DAOs, exported schema files, migrations and migration tests. */
class RoomScan(private val p: Project, private val g: Graph, private val base: String? = null) {
    private val dbs = LinkedHashMap<String, Pair<KtClassOrObject, Source>>()
    private val entities = HashMap<String, KtClassOrObject>()
    private val tables = HashMap<String, String>() // lower table name -> entity fq
    private val daos = LinkedHashMap<String, Pair<KtClassOrObject, Source>>()
    private class Manual(val id: String, val name: String, val inTests: Boolean)
    private val manual = ArrayList<Manual>()
    /** Databases whose @Database(entities = [...]) lists a class droidmap could not read. */
    private val partialEntityLists = HashSet<String>()
    /** (database, version) -> table -> column -> what its schema JSON says. */
    private val schemaTables = HashMap<Pair<String, Int>, Map<String, Map<String, Column>>>()

    fun run() {
        val files = p.sources.filter { it.importsFrom("androidx.room") }
        for (s in files) for (c in s.file.collectDescendantsOfType<KtClassOrObject>()) {
            val fq = c.fq() ?: continue
            if (c is KtObjectDeclaration && c.isObjectLiteral()) continue
            if (c.hasAnn("Entity")) entity(s, c, fq)
            if (c.hasAnn("Dao")) { daos[fq] = c to s; nodeForClass(g, c, s, "room_dao") }
            if (c.hasAnn("Database")) dbs[fq] = c to s
        }
        for ((fq, cs) in dbs) database(fq, cs.first, cs.second)
        for ((fq, cs) in daos) dao(fq, cs.first, cs.second)
        for (s in files) {
            manualMigrations(s)
            for (call in s.file.collectDescendantsOfType<KtCallExpression> { it.calleeName() in FALLBACKS }) {
                @Suppress("UNCHECKED_CAST")
                val list = g.meta.getOrPut("room_destructive_fallback") { ArrayList<Map<String, Any?>>() } as MutableList<Map<String, Any?>>
                list.add(linkedMapOf("file" to s.path, "line" to s.line(call), "call" to call.calleeName(), "module" to s.module))
            }
        }
        countReferences()
        schemas()
        drift()
        tests()
    }

    // ----- entities, databases, DAOs -----
    private fun entity(s: Source, c: KtClassOrObject, fq: String) {
        var table = c.name ?: fq
        c.ann("Entity")?.valueArguments?.firstOrNull { it.getArgumentName()?.asName?.asString() == "tableName" }?.let { a ->
            (p.eval.eval(a.getArgumentExpression()) as? String)?.let { table = it }
        }
        entities[fq] = c
        tables[table.lowercase()] = fq
        nodeForClass(g, c, s, "room_entity", mapOf("table" to table, "fts" to (if (c.hasAnn("Fts4", "Fts3")) true else null)))
    }

    private fun database(fq: String, c: KtClassOrObject, s: Source) {
        val ann = c.ann("Database")
        val args = ann?.valueArguments?.filterIsInstance<KtValueArgument>() ?: emptyList()
        val version = p.eval.evalInt(args.arg("version")?.getArgumentExpression())
        val export = (p.eval.eval(args.arg("exportSchema")?.getArgumentExpression()) as? Boolean) ?: true
        val dbid = nodeForClass(g, c, s, "room_database", mapOf("version" to version, "export_schema" to export))
        if (version == null) g.addUnresolved("room_version", fq, s.path, s.line(c), "@Database version is not a constant")
        for ((name, lit) in classRefs(args.arg("entities"))) {
            val e = p.index.resolveType(name, s, c)?.fq()
            if (e == null || e !in entities) partialEntityLists.add(fq)
            if (e == null) g.addUnresolved("room_entity", name, s.path, s.line(lit), "entity class not found in the project")
            else g.addEdge(dbid, classId(e), "contains_entity", s.path, s.line(lit))
        }
        val autos = args.arg("autoMigrations")?.collectDescendantsOfType<KtCallExpression> { it.calleeName() == "AutoMigration" } ?: emptyList()
        for (call in autos) {
            val a = call.valueArguments
            val from = p.eval.evalInt(a.arg("from", 0)?.getArgumentExpression())
            val to = p.eval.evalInt(a.arg("to", 1)?.getArgumentExpression())
            val line = s.line(call)
            if (from == null || to == null) {
                g.addUnresolved("room_migration", "AutoMigration", s.path, line, "from/to are not constants")
                continue
            }
            val spec = classRefs(a.arg("spec")).firstOrNull()?.first
            val mid = migrationId(fq, from, to, "auto")
            g.addNode(mid, "room_migration", "AutoMigration $from->$to", s.path, line, s.module,
                mapOf("db" to fq, "from_version" to from, "to_version" to to, "style" to "auto", "spec" to spec))
            g.addEdge(mid, dbid, "migrates", s.path, line)
        }
        for (d in c.declarations) {
            val ty = when (d) {
                is KtNamedFunction -> d.typeReference
                is KtProperty -> d.typeReference
                else -> null
            } ?: continue
            val target = TypeRef.of(ty)?.let { p.index.resolveType(it.name, s, c) }?.fq() ?: continue
            if (target in daos) g.addEdge(dbid, classId(target), "contains_dao", s.path, s.line(d))
        }
    }

    /**
     * A DAO function, with where its types resolve. An inherited one (`ItemDao : BaseDao<Item>`)
     * comes with the base's type parameters bound to the DAO's arguments, as class names.
     */
    private class DaoFun(val fn: KtNamedFunction, val src: Source, val scope: KtClassOrObject, val typeArgs: Map<String, String>)

    private fun daoFunctions(c: KtClassOrObject, s: Source, typeArgs: Map<String, String>, seen: MutableSet<KtClassOrObject>): List<DaoFun> {
        if (!seen.add(c)) return emptyList()
        val out = c.declarations.filterIsInstance<KtNamedFunction>().mapTo(ArrayList()) { DaoFun(it, s, c, typeArgs) }
        for (entry in c.superTypeListEntries) {
            val t = TypeRef.of(entry.typeReference) ?: continue
            val base = p.index.resolveType(t.name, s, c) ?: continue
            val bs = p.index.sourceOf(base) ?: continue
            val bound = base.typeParameters.mapNotNull { it.name }.zip(t.args).mapNotNull { (param, arg) ->
                (typeArgs[arg.name] ?: p.index.resolveType(arg.name, s, c)?.fq())?.let { param to it }
            }.toMap()
            out += daoFunctions(base, bs, bound, seen)
        }
        return out
    }

    private fun dao(fq: String, c: KtClassOrObject, s: Source) {
        class Agg(val line: Int) { val functions = sortedSetOf<String>(); val replace = ArrayList<Map<String, Any?>>() }
        val agg = sortedMapOf<String, Agg>()
        for (df in daoFunctions(c, s, emptyMap(), HashSet())) {
            val fn = df.fn
            // Edges live in the DAO's file: an inherited function points at the DAO's own line.
            val line = if (df.src == s) s.line(fn) else s.line(c)
            fn.ann("Query")?.let { q ->
                val arg = q.valueArguments.filterIsInstance<KtValueArgument>().let { it.arg("value") ?: it.arg(null, 0) }
                val sql = p.eval.eval(arg?.getArgumentExpression()) as? String
                if (sql == null) {
                    g.addUnresolved("room_query", "${c.name}.${fn.name}", df.src.path, df.src.line(fn), "SQL is not a constant")
                } else {
                    for (m in SQL_TABLE.findAll(sql)) {
                        val efq = tables[m.groupValues[1].lowercase()] ?: continue
                        agg.getOrPut("queries\u0000$efq") { Agg(line) }.functions.add(fn.name ?: "?")
                    }
                }
            }
            for (wa in WRITE_ANNS) {
                val ann = fn.ann(wa) ?: continue
                val replace = ann.valueArgumentList?.text?.let { REPLACE.containsMatchIn(it) } == true
                for (prm in fn.valueParameters) {
                    var ty = TypeRef.of(prm.typeReference)
                    while (ty != null && ty.simple in COLLECTIONS && ty.args.isNotEmpty()) ty = ty.args[0]
                    val e = ty?.let { df.typeArgs[it.name] ?: p.index.resolveType(it.name, df.src, df.scope)?.fq() } ?: continue
                    if (e !in entities) continue
                    val a = agg.getOrPut("writes\u0000$e") { Agg(line) }
                    a.functions.add(fn.name ?: "?")
                    if (replace) a.replace.add(linkedMapOf("function" to fn.name, "file" to df.src.path, "line" to df.src.line(ann)))
                }
            }
        }
        for ((key, a) in agg) {
            val (kind, efq) = key.split('\u0000')
            g.addEdge(classId(fq), classId(efq), kind, s.path, a.line,
                mapOf("functions" to a.functions.toList(), "replace" to a.replace.ifEmpty { null }))
        }
    }

    // ----- migrations -----
    private fun dbFor(s: Source): String? {
        val same = dbs.filter { it.value.second.module == s.module }.keys
        if (same.size == 1) return same.first()
        return if (dbs.size == 1) dbs.keys.first() else null
    }

    private fun manualMigrations(s: Source) {
        class Found(val args: List<KtValueArgument>, val at: PsiElement, val owner: KtClassOrObject?)
        val found = ArrayList<Found>()
        for (c in s.file.collectDescendantsOfType<KtClassOrObject>()) {
            for (entry in c.superTypeListEntries) {
                if (entry is KtSuperTypeCallEntry && TypeRef.of(entry.typeReference)?.simple == "Migration") {
                    found.add(Found(entry.valueArguments.filterIsInstance<KtValueArgument>(), entry, c))
                }
            }
        }
        for (call in s.file.collectDescendantsOfType<KtCallExpression> { it.calleeName() == "Migration" }) {
            val r = call.receiverText()
            if (r != null && r != "migration") continue
            found.add(Found(call.valueArguments, call, null))
        }
        for (f in found) {
            val from = p.eval.evalInt(f.args.arg("startVersion", 0)?.getArgumentExpression())
            val to = p.eval.evalInt(f.args.arg("endVersion", 1)?.getArgumentExpression())
            val named = namedDeclaration(f.at, f.owner)
            val name = named?.name ?: "<anonymous>"
            val owner = named?.fqName?.asString() ?: "${s.pkg}.<anonymous>"
            val line = s.line(f.at)
            if (from == null || to == null) {
                g.addUnresolved("room_migration", name, s.path, line, "versions are not constants")
                continue
            }
            val db = dbFor(s)
            val mid = migrationId(db, from, to, name)
            g.addNode(mid, "room_migration", "$name $from->$to", s.path, line, s.module,
                mapOf("db" to db, "from_version" to from, "to_version" to to, "style" to "manual", "name" to name, "owner" to owner))
            if (named != null) manual.add(Manual(mid, name, s.isTest)) // one passed inline is used where it is written
            if (db != null) g.addEdge(mid, classId(db), "migrates", s.path, line)
        }
    }

    /**
     * The class, object or property that names a migration: `object M : Migration(1, 2)` or
     * `val M = Migration(1, 2) {...}`. Null when it is written inline, as in addMigrations(object : Migration(1, 2) {...}).
     */
    private fun namedDeclaration(at: PsiElement, owner: KtClassOrObject?): KtNamedDeclaration? {
        if (owner != null && !(owner is KtObjectDeclaration && owner.isObjectLiteral())) return owner
        val expr = owner?.parent ?: at // the object literal expression, or the Migration(...) call
        val prop = expr.getStrictParentOfType<KtProperty>() ?: return null
        val init = prop.initializer
        return prop.takeIf { init == expr || (init as? KtQualifiedExpression)?.selectorExpression == expr }
    }

    /**
     * How often app code uses each manual migration's name (0 = never passed to addMigrations).
     * A test that runs a migration does not count: that is exactly the one written, tested and never registered.
     */
    private fun countReferences() {
        if (manual.isEmpty()) return
        val app = ReferenceCounts(p, includeTests = false)
        val all by lazy { ReferenceCounts(p, includeTests = true) }
        for (m in manual) g.nodes[m.id]!!.attrs["references"] = (if (m.inTests) all else app)[m.name]
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
        val reader = RoomColumns(p)
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
                if (c.hasAnn("Fts3", "Fts4")) continue // FTS tables have hidden columns; not compared
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
    private fun versionArg(args: List<KtValueArgument>): KtValueArgument? {
        args.arg("version")?.let { return it }
        val first = args.arg(null, 0)
        return if (p.eval.eval(first?.getArgumentExpression()) is Long) first else args.arg(null, 1)
    }

    private fun tests() {
        val migByDb = g.byKind("room_migration").groupBy { it.attrs["db"] as String? }
        val migNames = manual.associate { it.name to it.id }
        for (s in p.sources) {
            if (!s.isTest || "runMigrationsAndValidate" !in s.file.text) continue
            var helperDb: String? = null
            for (call in s.file.collectDescendantsOfType<KtCallExpression> { it.calleeName() == "MigrationTestHelper" }) {
                for ((name, _) in classRefs(call.valueArgumentList)) {
                    p.index.resolveType(name, s, call)?.fq()?.takeIf { it in dbs }?.let { helperDb = it }
                }
            }
            val db = helperDb ?: (if (dbs.size == 1) dbs.keys.first() else null)
            for (fn in s.file.collectDescendantsOfType<KtNamedFunction>()) {
                val body = fn.bodyExpression ?: continue
                val created = sortedSetOf<Int>()
                val validated = sortedSetOf<Int>()
                val explicit = HashSet<String>()
                var generic = false
                val calls = body.collectDescendantsOfType<KtCallExpression> {
                    it.calleeName() in HELPER_CALLS && it.getStrictParentOfType<KtNamedFunction>() == fn
                }
                for (call in calls) {
                    val v = p.eval.evalInt(versionArg(call.valueArguments)?.getArgumentExpression())
                    when {
                        v == null -> generic = true
                        call.calleeName() == "createDatabase" -> created.add(v)
                        else -> validated.add(v)
                    }
                    if (call.calleeName() == "runMigrationsAndValidate") {
                        for (a in call.valueArguments) for (r in a.collectDescendantsOfType<KtNameReferenceExpression>()) {
                            migNames[r.getReferencedName()]?.let { explicit.add(it) }
                        }
                    }
                }
                if (created.isEmpty() && validated.isEmpty() && !generic) continue
                val fq = fn.fqName?.asString() ?: continue
                val tid = "test::$fq"
                val cls = (fn.parent?.parent as? KtClassOrObject)?.name
                g.addNode(tid, "test", if (cls != null) "$cls.${fn.name}" else fn.name, s.path, s.line(fn), s.module,
                    mapOf("source_set" to s.sourceSet, "db" to db, "created" to created.toList(), "validated" to validated.toList(),
                        "generic" to (if (generic) true else null)))
                for (m in migByDb[db] ?: emptyList()) {
                    val from = (m.attrs["from_version"] as Number).toInt()
                    val to = (m.attrs["to_version"] as Number).toInt()
                    val covered = generic || m.id in explicit || created.any { c -> c <= from && validated.any { v -> to <= v } }
                    if (covered) g.addEdge(tid, m.id, "tests", s.path, s.line(fn),
                        mapOf("generic" to (if (generic && m.id !in explicit) true else null)))
                }
            }
        }
    }
}
