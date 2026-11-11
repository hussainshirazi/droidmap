package droidmap

/**
 * Rules that run over a built graph. Each finding names its rule, so it can be configured in
 * .droidmap.toml ([rules] rule-id = "error" | "warning" | "info" | "off").
 */
val RULES: Map<String, Pair<String, String>> = sortedMapOf(
    "room-migration-path" to ("error" to "Every known schema version has a migration path to the current version"),
    "room-schema-missing" to ("warning" to "Schema JSON is exported for the current version and every version before it"),
    "room-migration-untested" to ("warning" to "Every migration step is run by a MigrationTestHelper test"),
    "room-migration-unused" to ("warning" to "Every manual Migration is used by app code (for example in addMigrations), not only by tests"),
    "room-destructive-fallback" to ("warning" to "fallbackToDestructiveMigration deletes user data when a migration is missing"),
    "room-insert-replace" to ("off" to "@Insert/@Upsert with OnConflictStrategy.REPLACE deletes and re-inserts rows"),
    "nav-unknown-target" to ("error" to "navigate() to a route or key that no destination registers"),
    "nav-unreachable" to ("warning" to "A destination that nothing navigates to, starts at, deep-links to, opens from a menu or references"),
    "hilt-missing-binding" to ("warning" to "An injected project type with no @Inject constructor, @Provides or @Binds"),
    "hilt-duplicate-binding" to ("error" to "The same type and qualifier is bound twice in one source set"),
    "gradle-unknown-project" to ("error" to "A project dependency on a module that settings does not include"),
    "gradle-forbidden-dependency" to ("error" to "A module dependency banned by a [[gradle.forbid]] rule"),
    "gradle-cycle" to ("error" to "Modules that depend on each other in a cycle"),
    "manifest-permission" to ("error" to "A permission that is not in [manifest] allowed_permissions"),
)
val SEVERITY_ORDER = mapOf("error" to 0, "warning" to 1, "info" to 2)

data class Finding(val rule: String, var severity: String, val message: String, val file: String?, val line: Int?, val node: String?) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "rule" to rule, "severity" to severity, "message" to message, "file" to file, "line" to line, "node" to node)
}

private fun finding(rule: String, message: String, file: String? = null, line: Int? = null, node: String? = null) =
    Finding(rule, RULES.getValue(rule).first, message, file, line, node)

fun runChecks(g: Graph, config: Config = Config()): List<Finding> {
    val all = room(g) + nav(g) + hilt(g) + gradle(g, config) + manifest(g, config)
    val out = ArrayList<Finding>()
    for (f in all) {
        val sev = config.severities[f.rule] ?: f.severity
        if (sev == "off") continue
        if (sev in SEVERITY_ORDER) f.severity = sev
        out.add(f)
    }
    return out.sortedWith(compareBy<Finding>({ SEVERITY_ORDER[it.severity] ?: 9 }, { it.rule }, { it.file ?: "" }, { it.line ?: 0 }))
}

private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

// ----- Room -----
private fun room(g: Graph): List<Finding> {
    val out = ArrayList<Finding>()
    val migrations = g.byKind("room_migration").groupBy { it.attrs.str("db") }
    val schemas = g.byKind("room_schema").groupBy({ it.attrs.str("db") }, { it.attrs.int("version")!! }).mapValues { it.value.toSet() }
    for (db in g.byKind("room_database")) {
        val fq = db.id.substringAfter("::")
        val version = db.attrs.int("version") ?: continue
        val migs = migrations[fq] ?: emptyList()
        val known = (schemas[fq] ?: emptySet()) + migs.map { it.attrs.int("from_version")!! }
        val adj = migs.groupBy({ it.attrs.int("from_version")!! }, { it.attrs.int("to_version")!! })
        val stuck = known.filter { it < version }.sorted().filter { v ->
            val seen = hashSetOf(v)
            val todo = ArrayDeque(listOf(v))
            while (todo.isNotEmpty()) for (y in adj[todo.removeLast()] ?: emptyList()) if (seen.add(y)) todo.addLast(y)
            version !in seen
        }
        if (stuck.isNotEmpty()) {
            out.add(finding("room-migration-path",
                "${db.label} is at version $version but there is no migration path from ${plural(stuck.size, "version")} " +
                    "${stuck.joinToString(", ")}: users on ${if (stuck.size > 1) "those versions" else "it"} hit " +
                    "IllegalStateException (or lose data with a destructive fallback)", db.file, db.line, db.id))
        }
        if (db.attrs["export_schema"] != false) {
            val have = schemas[fq] ?: emptySet()
            if (have.isEmpty()) {
                out.add(finding("room-schema-missing",
                    "${db.label} exports schemas but no <version>.json was found under a '$fq' folder: set " +
                        "room.schemaLocation (or the Room Gradle plugin's schemaDirectory) and commit the files", db.file, db.line, db.id))
            } else {
                val missing = ((have + version).min()..version).filter { it !in have }
                if (missing.isNotEmpty()) {
                    out.add(finding("room-schema-missing",
                        "${db.label}: no schema JSON for ${plural(missing.size, "version")} ${missing.joinToString(", ")}" +
                            (if (version in missing) " (including the current version: build and commit it)" else ""),
                        db.file, db.line, db.id))
                }
            }
        }
        val untested = migs.filter { m -> g.inEdges(m.id).none { it.kind == "tests" } }
        if (untested.isNotEmpty()) {
            val steps = untested.map { it.attrs.int("from_version")!! to it.attrs.int("to_version")!! }
                .sortedWith(compareBy({ it.first }, { it.second }))
            out.add(finding("room-migration-untested",
                "${db.label}: ${untested.size} of ${migs.size} migration steps have no MigrationTestHelper test " +
                    "(${steps.joinToString(", ") { "${it.first}->${it.second}" }})", db.file, db.line, db.id))
        }
    }
    for (n in g.byKind("room_migration")) {
        if (n.attrs.str("style") == "manual" && n.attrs.int("references") == 0) {
            out.add(finding("room-migration-unused",
                "${n.attrs.str("name")} (${n.attrs.int("from_version")}->${n.attrs.int("to_version")}) is never used by app code " +
                    "(tests aside): is it passed to addMigrations()?", n.file, n.line, n.id))
        }
    }
    for (fb in g.meta.maps("room_destructive_fallback")) {
        out.add(finding("room-destructive-fallback",
            "${fb.str("call")}() deletes the user's database whenever a migration is missing", fb.str("file"), fb.int("line")))
    }
    for (e in g.edgesOf("writes")) for (r in e.attrs.maps("replace")) {
        out.add(finding("room-insert-replace",
            "${r.str("function")} uses OnConflictStrategy.REPLACE, which deletes the old row (and cascades) instead of updating it",
            r.str("file") ?: e.file, r.int("line"), e.source))
    }
    return out
}

// ----- navigation -----
private fun nav(g: Graph): List<Finding> {
    val out = ArrayList<Finding>()
    val dynamic = g.unresolved.count { it.kind == "nav_target" }
    for (n in g.byKind("nav_destination")) {
        val incoming = g.inEdges(n.id)
        if (!n.attrs.bool("registered")) {
            for (e in incoming) if (e.kind == "navigates_to" || e.kind == "starts_at") {
                out.add(finding("nav-unknown-target",
                    "${g.label(e.source)} navigates to ${n.label}, but no destination registers it", e.file, e.line, n.id))
            }
            continue
        }
        val reached = incoming.any { it.kind == "navigates_to" || it.kind == "starts_at" }
        val entered = n.attrs.bool("deep_link") || n.attrs.bool("menu_item") || (n.attrs.int("references") ?: 0) > 0
        if (reached || entered || n.attrs.bool("graph")) continue
        val hint = if (dynamic > 0) " ($dynamic navigate calls could not be resolved)" else ""
        out.add(finding("nav-unreachable",
            "Nothing navigates to ${n.label}: it is not a start destination, deep link or menu item, and nothing else refers to it$hint",
            n.file, n.line, n.id))
    }
    return out
}

// ----- Hilt -----
private fun hilt(g: Graph): List<Finding> {
    val out = ArrayList<Finding>()
    for (e in g.edges) {
        if (e.kind != "injects" && e.kind != "requires") continue
        val t = g.nodes[e.target] ?: continue
        if (t.file == null) continue
        if (t.roles.any { it in setOf("injectable", "viewmodel", "hilt_module") } || t.attrs.bool("assisted_factory")) continue
        if (g.outEdges(t.id).any { it.kind == "bound_by" }) continue
        out.add(finding("hilt-missing-binding",
            "${g.label(e.source)} injects ${t.label}, but nothing binds it (no @Inject constructor, @Provides or @Binds in this project)",
            e.file, e.line, t.id))
    }
    val groups = LinkedHashMap<Pair<String, String?>, MutableList<Node>>()
    for (n in g.byKind("hilt_provider")) {
        val a = n.attrs
        if (a["multibinding"] != null || a.bool("test_install_in") || isTestSet(a.str("source_set"))) continue
        val returns = a.str("returns") ?: continue
        groups.getOrPut(returns to a.str("qualifier")) { ArrayList() }.add(n)
    }
    for ((key, ns) in groups) {
        if (ns.size < 2) continue
        val sets = ns.groupBy { it.attrs.str("source_set") ?: "main" }
        val clash = sets.values.filter { it.size > 1 }.toMutableList()
        if ("main" in sets && sets.size > 1) clash.add(ns)
        for (c in clash) {
            out.add(finding("hilt-duplicate-binding",
                "${key.first}${key.second?.let { " @$it" } ?: ""} is bound more than once: ${c.map { it.label }.sorted().joinToString(", ")}",
                c[0].file, c[0].line, c[0].id))
        }
    }
    return out
}

// ----- Gradle -----
private fun gradle(g: Graph, config: Config): List<Finding> {
    val out = ArrayList<Finding>()
    val deps = g.edgesOf("depends_on")
    for (e in deps) {
        val t = g.nodes[e.target] ?: continue
        if (t.attrs.bool("missing")) {
            out.add(finding("gradle-unknown-project", "${g.label(e.source)} depends on ${t.label}, which settings does not include",
                e.file, e.line, e.target))
        }
    }
    for (rule in config.forbid) {
        val from = rule["from"] as? String ?: continue
        val to = rule["to"] as? String ?: continue
        for (e in deps) {
            if (e.attrs.bool("test") && rule["tests"] != true) continue
            val s = g.label(e.source)
            val t = g.label(e.target)
            if (s == t || !fnmatch(s, from) || !fnmatch(t, to)) continue
            if (rule["allow_same_parent"] == true && s.substringBeforeLast(':') == t.substringBeforeLast(':')) continue
            val reason = (rule["reason"] as? String)?.let { ": $it" } ?: ""
            val via = e.attrs.str("via")?.let { " (added by plugin $it)" } ?: ""
            out.add(finding("gradle-forbidden-dependency",
                "$s depends on $t$via, which a forbid rule bans ($from -> $to)$reason", e.file, e.line, e.source))
        }
    }
    out += cycles(g, deps.filter { !it.attrs.bool("test") })
    return out
}

/** Tarjan's strongly connected components, iterative. */
private fun cycles(g: Graph, edges: List<Edge>): List<Finding> {
    val adj = edges.groupBy({ it.source }, { it.target }).mapValues { it.value.toSortedSet().toList() }
    val index = HashMap<String, Int>()
    val low = HashMap<String, Int>()
    val onStack = HashSet<String>()
    val stack = ArrayDeque<String>()
    val comps = ArrayList<List<String>>()
    var counter = 0
    for (start in adj.keys.sorted()) {
        if (start in index) continue
        val work = ArrayDeque<Pair<String, Iterator<String>>>()
        fun push(v: String) {
            index[v] = counter; low[v] = counter; counter++
            stack.addLast(v); onStack.add(v)
            work.addLast(v to (adj[v] ?: emptyList()).iterator())
        }
        push(start)
        while (work.isNotEmpty()) {
            val (node, it) = work.last()
            var advanced = false
            while (it.hasNext()) {
                val w = it.next()
                if (w !in index) { push(w); advanced = true; break }
                if (w in onStack) low[node] = minOf(low[node]!!, index[w]!!)
            }
            if (advanced) continue
            work.removeLast()
            if (work.isNotEmpty()) { val parent = work.last().first; low[parent] = minOf(low[parent]!!, low[node]!!) }
            if (low[node] == index[node]) {
                val comp = ArrayList<String>()
                while (true) { val w = stack.removeLast(); onStack.remove(w); comp.add(w); if (w == node) break }
                if (comp.size > 1) comps.add(comp.sorted())
            }
        }
    }
    return comps.map { comp ->
        val first = g.nodes[comp[0]]!!
        finding("gradle-cycle", "Module cycle: ${comp.joinToString(" -> ") { g.label(it) }}", first.file, first.line, comp[0])
    }
}

// ----- manifest -----
private fun manifest(g: Graph, config: Config): List<Finding> {
    val allowed = config.allowedPermissions?.toSet() ?: return emptyList()
    return g.edgesOf("uses_permission").filter { !it.attrs.bool("removed") && !isTestSet(it.attrs.str("source_set")) }
        .mapNotNull { e ->
            val name = e.target.substringAfter("::")
            if (name in allowed) null
            else finding("manifest-permission", "${g.label(e.source)} declares $name, which is not in allowed_permissions", e.file, e.line, e.target)
        }
}
