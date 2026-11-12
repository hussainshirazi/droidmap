package droidmap

import droidmap.extract.navGraphName

/**
 * The rules that run over a built graph. Each finding names its rule, so it can be configured in
 * .droidmap.toml ([rules] rule-id = "error" | "warning" | "info" | "off").
 */
enum class Rule(val id: String, val severity: String, val description: String) {
    ROOM_MIGRATION_PATH("room-migration-path", "error", "Every known schema version has a migration path to the current version"),
    ROOM_SCHEMA_MISSING("room-schema-missing", "warning", "Schema JSON is exported for the current version and every version before it"),
    ROOM_SCHEMA_STALE("room-schema-stale", "warning", "The entities match the schema JSON of the database's current version (else the version was not bumped)"),
    ROOM_SCHEMA_CHANGED("room-schema-changed", "warning", "With --base: no schema JSON of a version that existed there has changed (a shipped schema must never change)"),
    ROOM_MIGRATION_UNTESTED("room-migration-untested", "warning", "Every migration step is run by a MigrationTestHelper test"),
    ROOM_MIGRATION_UNUSED("room-migration-unused", "warning", "Every manual Migration is used by app code (for example in addMigrations), not only by tests"),
    ROOM_DESTRUCTIVE_FALLBACK("room-destructive-fallback", "warning", "fallbackToDestructiveMigration deletes user data when a migration is missing"),
    ROOM_INSERT_REPLACE("room-insert-replace", "off", "@Insert/@Upsert with OnConflictStrategy.REPLACE deletes and re-inserts rows"),
    NAV_UNKNOWN_TARGET("nav-unknown-target", "error", "navigate() to a route or key that no destination registers"),
    NAV_UNREACHABLE("nav-unreachable", "warning", "A destination that nothing navigates to, starts at, deep-links to, opens from a menu or references"),
    NAV_DEEPLINK_NOT_IN_MANIFEST("nav-deeplink-not-in-manifest", "warning", "A navigation deep link whose scheme and host no activity's intent filter accepts, so the link does not open the app"),
    HILT_APP_NOT_REGISTERED("hilt-app-not-registered", "error", "@AndroidEntryPoint classes without a @HiltAndroidApp Application that the manifest names: a crash when they open"),
    WORKMANAGER_SETUP("workmanager-setup", "warning", "@HiltWorker classes that nothing gives HiltWorkerFactory to, or a Configuration.Provider that the default WorkManager initializer bypasses"),
    HILT_MISSING_BINDING("hilt-missing-binding", "warning", "An injected project type with no @Inject constructor, @Provides or @Binds"),
    HILT_DUPLICATE_BINDING("hilt-duplicate-binding", "error", "The same type and qualifier is bound twice in one source set"),
    KOIN_MISSING_DEFINITION("koin-missing-definition", "warning", "A type Koin is asked for (get(), inject(), koinInject()) that no Koin definition provides"),
    GRADLE_UNKNOWN_PROJECT("gradle-unknown-project", "error", "A project dependency on a module that settings does not include"),
    GRADLE_FORBIDDEN_DEPENDENCY("gradle-forbidden-dependency", "error", "A module dependency banned by a [[gradle.forbid]] rule"),
    GRADLE_CYCLE("gradle-cycle", "error", "Modules that depend on each other in a cycle"),
    MANIFEST_PERMISSION("manifest-permission", "error", "A permission (in a source manifest, or added by a library to the merged one) that is not in [manifest] allowed_permissions"),
}

/** Rules by id, sorted. */
val RULES: Map<String, Rule> = Rule.entries.associateBy { it.id }.toSortedMap()
val SEVERITY_ORDER = mapOf("error" to 0, "warning" to 1, "info" to 2)

data class Finding(val rule: String, var severity: String, val message: String, val file: String?, val line: Int?, val node: String?) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "rule" to rule, "severity" to severity, "message" to message, "file" to file, "line" to line, "node" to node)
}

private fun finding(rule: Rule, message: String, file: String? = null, line: Int? = null, node: String? = null) =
    Finding(rule.id, rule.severity, message, file, line, node)

fun runChecks(g: Graph, config: Config = Config()): List<Finding> {
    val all = room(g) + nav(g) + deepLinks(g) + di(g) + hiltApp(g) + workManager(g) + gradle(g, config) + manifest(g, config)
    val out = ArrayList<Finding>()
    for (f in all) {
        val sev = config.severities[f.rule] ?: f.severity
        if (sev == "off") continue
        if (sev in SEVERITY_ORDER) f.severity = sev
        out.add(f)
    }
    return out.sortedWith(compareBy<Finding>({ SEVERITY_ORDER[it.severity] ?: 9 }, { it.rule }, { it.file ?: "" }, { it.line ?: 0 }))
}

/** How many unresolved items of [kind] there are, as a hint for a finding: " (3 navigate calls could not be resolved)". */
private fun hint(g: Graph, kind: String, what: String): String {
    val n = g.unresolved.count { it.kind == kind }
    return if (n > 0) " ($n $what could not be resolved)" else ""
}

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
            out.add(finding(Rule.ROOM_MIGRATION_PATH,
                "${db.label} is at version $version but there is no migration path from ${plural(stuck.size, "version")} " +
                    "${stuck.joinToString(", ")}: users on ${if (stuck.size > 1) "those versions" else "it"} hit " +
                    "IllegalStateException (or lose data with a destructive fallback)", db.file, db.line, db.id))
        }
        if (db.attrs["export_schema"] != false) {
            val have = schemas[fq] ?: emptySet()
            if (have.isEmpty()) {
                out.add(finding(Rule.ROOM_SCHEMA_MISSING,
                    "${db.label} exports schemas but no <version>.json was found under a '$fq' folder: set " +
                        "room.schemaLocation (or the Room Gradle plugin's schemaDirectory) and commit the files", db.file, db.line, db.id))
            } else {
                val missing = ((have + version).min()..version).filter { it !in have }
                if (missing.isNotEmpty()) {
                    out.add(finding(Rule.ROOM_SCHEMA_MISSING,
                        "${db.label}: no schema JSON for ${plural(missing.size, "version")} ${missing.joinToString(", ")}" +
                            (if (version in missing) " (including the current version: build and commit it)" else ""),
                        db.file, db.line, db.id))
                }
            }
        }
        val drift = db.attrs.strings("schema_drift")
        if (drift.isNotEmpty()) {
            out.add(finding(Rule.ROOM_SCHEMA_STALE,
                "${db.label}'s entities no longer match $version.json, the schema of its current version ($version): " +
                    "${drift.joinToString(", ")}. Bump the version and add a migration; if version $version has not " +
                    "shipped yet, rebuild to regenerate $version.json and commit it.", db.file, db.line, db.id))
        }
        val untested = migs.filter { m -> g.inEdges(m.id).none { it.kind == "tests" } }
        if (untested.isNotEmpty()) {
            val steps = untested.map { it.attrs.int("from_version")!! to it.attrs.int("to_version")!! }
                .sortedWith(compareBy({ it.first }, { it.second }))
            out.add(finding(Rule.ROOM_MIGRATION_UNTESTED,
                "${db.label}: ${untested.size} of ${migs.size} migration steps have no MigrationTestHelper test " +
                    "(${steps.joinToString(", ") { "${it.first}->${it.second}" }})", db.file, db.line, db.id))
        }
    }
    for (n in g.byKind("room_migration")) {
        if (n.attrs.str("style") == "manual" && n.attrs.int("references") == 0) {
            out.add(finding(Rule.ROOM_MIGRATION_UNUSED,
                "${n.attrs.str("name")} (${n.attrs.int("from_version")}->${n.attrs.int("to_version")}) is never used by app code " +
                    "(tests aside): is it passed to addMigrations()?", n.file, n.line, n.id))
        }
    }
    for (s in g.byKind("room_schema")) {
        val before = s.attrs.str("base_identity_hash") ?: continue
        if (before == s.attrs.str("identity_hash")) continue
        val v = s.attrs.int("version")
        out.add(finding(Rule.ROOM_SCHEMA_CHANGED,
            "${s.file} changed since ${g.meta["base"]}: the schema of version $v is different. If version $v has shipped, " +
                "phones that have it crash on update (\"Room cannot verify the data integrity\"): bump the version and add " +
                "a migration instead. If it has not shipped, this is expected.", s.file, null, s.id))
    }
    for (fb in g.meta.maps("room_destructive_fallback")) {
        out.add(finding(Rule.ROOM_DESTRUCTIVE_FALLBACK,
            "${fb.str("call")}() deletes the user's database whenever a migration is missing", fb.str("file"), fb.int("line")))
    }
    for (e in g.edgesOf("writes")) for (r in e.attrs.maps("replace")) {
        out.add(finding(Rule.ROOM_INSERT_REPLACE,
            "${r.str("function")} uses OnConflictStrategy.REPLACE, which deletes the old row (and cascades) instead of updating it",
            r.str("file") ?: e.file, r.int("line"), e.source))
    }
    return out
}

// ----- navigation -----
private fun nav(g: Graph): List<Finding> {
    val out = ArrayList<Finding>()
    for (n in g.byKind("nav_destination")) {
        val incoming = g.inEdges(n.id)
        if (!n.attrs.bool("registered")) {
            for (e in incoming) if (e.kind == "navigates_to" || e.kind == "starts_at") {
                out.add(finding(Rule.NAV_UNKNOWN_TARGET,
                    "${g.label(e.source)} navigates to ${n.label}, but no destination registers it", e.file, e.line, n.id))
            }
            continue
        }
        val reached = incoming.any { it.kind == "navigates_to" || it.kind == "starts_at" }
        val entered = n.attrs.bool("deep_link") || n.attrs.bool("menu_item") || n.attrs.bool("start") || (n.attrs.int("references") ?: 0) > 0
        if (reached || entered || n.attrs.bool("graph")) continue
        out.add(finding(Rule.NAV_UNREACHABLE,
            "Nothing navigates to ${n.label}: it is not a start destination, deep link or menu item, and nothing else refers to it" +
                hint(g, "nav_target", "navigate calls"), n.file, n.line, n.id))
    }
    return out
}

/** (scheme, host) of an absolute URI whose scheme and host are constant, lower case; null otherwise. */
private fun schemeAndHost(uri: String): Pair<String, String>? {
    val m = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]+)""").find(uri) ?: return null
    val (scheme, host) = m.destructured
    if ((scheme + host).any { it in "{}$*" || it == WILD }) return null
    return scheme.lowercase() to host.lowercase()
}

/** Whether an intent filter's data (its schemes and hosts, any of which may be a placeholder or a *. wildcard) accepts the URL. */
private fun accepts(filter: Map<String, Any?>, scheme: String, host: String): Boolean {
    fun any(v: String) = '$' in v
    val schemes = filter.strings("schemes")
    val hosts = filter.strings("hosts")
    if (schemes.none { any(it) || it.equals(scheme, ignoreCase = true) }) return false
    return hosts.isEmpty() || hosts.any { h -> any(h) || h.equals(host, ignoreCase = true) || (h.startsWith("*") && host.endsWith(h.drop(1).lowercase())) }
}

private fun deepLinks(g: Graph): List<Finding> {
    val filters = g.meta.maps("link_filters")
    // XML graphs the manifest names with <nav-graph> get their intent filters at build time, with the graphs they include.
    val covered = g.meta.maps("manifest_nav_graphs").mapNotNull { it.str("graph") }.toMutableSet()
    val includes = g.meta.maps("nav_includes").groupBy({ it.str("from") }, { it.str("graph") })
    val todo = ArrayDeque(covered.toList())
    while (todo.isNotEmpty()) for (inc in includes[todo.removeFirst()] ?: emptyList()) if (inc != null && covered.add(inc)) todo.addLast(inc)
    val out = ArrayList<Finding>()
    for (n in g.byKind("nav_destination")) {
        val xmlGraph = n.file?.takeIf { n.attrs.str("api") == "xml" }?.let { navGraphName(it) }
        if (xmlGraph != null && xmlGraph in covered) continue
        for (uri in n.attrs.strings("deep_link_uris")) {
            val (scheme, host) = schemeAndHost(uri) ?: continue
            if (filters.any { accepts(it, scheme, host) }) continue
            val fix = "add an <intent-filter> with <data android:scheme=\"$scheme\" android:host=\"$host\" /> to the activity that hosts it" +
                (if (xmlGraph != null) ", or <nav-graph android:value=\"@navigation/$xmlGraph\" />" else "")
            out.add(finding(Rule.NAV_DEEPLINK_NOT_IN_MANIFEST,
                "${n.label} has the deep link $uri, but no activity's intent filter accepts $scheme://$host, so the link opens a browser " +
                    "or nothing instead of the app (an explicit intent from inside the app still works): $fix", n.file, n.line, n.id))
        }
    }
    return out
}

// ----- Hilt and Koin -----
private fun di(g: Graph): List<Finding> {
    val out = ArrayList<Finding>()
    for (e in g.edges) {
        if ((e.kind != "injects" && e.kind != "requires") || e.attrs.bool("constructs")) continue // a Koin definition makes what it constructs
        val t = g.nodes[e.target] ?: continue
        if (t.file == null) continue // not a project class
        if (t.roles.any { it in setOf("injectable", "viewmodel", "hilt_module") } || t.attrs.bool("assisted_factory")) continue
        if (g.outEdges(t.id).any { it.kind == "bound_by" }) continue
        out.add(if (e.attrs.bool("koin")) {
            finding(Rule.KOIN_MISSING_DEFINITION,
                "${g.label(e.source)} gets ${t.label} from Koin, but no Koin definition provides it (NoDefinitionFoundException " +
                    "at runtime)" + hint(g, "koin_definition", "Koin definitions"), e.file, e.line, t.id)
        } else {
            finding(Rule.HILT_MISSING_BINDING,
                "${g.label(e.source)} injects ${t.label}, but nothing binds it (no @Inject constructor, @Provides or @Binds in this project)",
                e.file, e.line, t.id)
        })
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
            out.add(finding(Rule.HILT_DUPLICATE_BINDING,
                "${key.first}${key.second?.let { " @$it" } ?: ""} is bound more than once: ${c.map { it.label }.sorted().joinToString(", ")}",
                c[0].file, c[0].line, c[0].id))
        }
    }
    return out
}

private fun appCode(n: Node) = !isTestSet(n.attrs.str("source_set"))

/** Hilt needs its @HiltAndroidApp class (or a subclass) to be the Application the manifest names, or every @AndroidEntryPoint crashes. */
private fun hiltApp(g: Graph): List<Finding> {
    val entryPoints = g.nodes.values.filter { it.attrs.bool("android_entry_point") && !it.attrs.bool("hilt_app") && appCode(it) }.sortedBy { it.id }
    if (entryPoints.isEmpty()) return emptyList()
    val declared = g.edgesOf("declares").filter { it.attrs.str("type") == "application" && !isTestSet(it.attrs.str("source_set")) }
        .mapNotNull { g.nodes[it.target] }
    if (declared.any { '$' in it.label || '{' in it.label }) return emptyList() // a manifest placeholder: cannot tell
    val apps = g.nodes.values.filter { it.attrs.bool("hilt_app") && appCode(it) }.sortedBy { it.id }
    val crash = if (entryPoints.size == 1) "${entryPoints[0].label} (an @AndroidEntryPoint) fails with IllegalStateException when Android creates it"
        else "${entryPoints[0].label} and ${entryPoints.size - 1} other @AndroidEntryPoint classes fail with IllegalStateException when Android creates them"
    if (apps.isEmpty()) {
        // Only for an app: a library's entry points get their Application from the app that uses the library.
        val isApp = declared.isNotEmpty() || g.byKind("module").any { m -> m.attrs.strings("plugins").any { "application" in it } }
        if (!isApp) return emptyList()
        return listOf(finding(Rule.HILT_APP_NOT_REGISTERED,
            "No Application class is annotated @HiltAndroidApp, so $crash: annotate the Application and name it in the manifest's <application android:name>",
            entryPoints[0].file, entryPoints[0].line, entryPoints[0].id))
    }
    return apps.mapNotNull { app ->
        val fq = app.id.substringAfter("::")
        if (declared.any { it.id == app.id || fq in it.attrs.strings("supertypes") }) return@mapNotNull null
        val named = declared.map { it.label }.distinct().sorted()
        finding(Rule.HILT_APP_NOT_REGISTERED,
            "${app.label} is the @HiltAndroidApp Application, but no AndroidManifest.xml names it (or a subclass) in <application android:name>" +
                (if (named.isNotEmpty()) " (it names ${named.joinToString(", ")})" else "") + ", so $crash",
            app.file, app.line, app.id)
    }
}

/** WorkManager makes @HiltWorker classes only through HiltWorkerFactory, and asks the Application for it only once its own initializer is removed. */
private fun workManager(g: Graph): List<Finding> {
    val out = ArrayList<Finding>()
    val workers = g.meta.maps("hilt_workers")
    if (workers.isNotEmpty() && g.meta["hilt_worker_factory_used"] == false) {
        val w = workers[0]
        out.add(finding(Rule.WORKMANAGER_SETUP,
            "${workers.mapNotNull { it.str("name") }.sorted().joinToString(", ")} ${if (workers.size > 1) "are @HiltWorkers" else "is a @HiltWorker"}, " +
                "but nothing in the app uses HiltWorkerFactory: WorkManager's default factory cannot call an @AssistedInject constructor, " +
                "so the work fails (\"Could not instantiate\"). Give WorkManager the factory: the Application implements Configuration.Provider " +
                "with setWorkerFactory(workerFactory)", w.str("file"), w.int("line")))
    }
    if (g.meta["work_initializer_removed"] != true) for (p in g.meta.maps("work_config_providers")) {
        out.add(finding(Rule.WORKMANAGER_SETUP,
            "${p.str("name")} implements Configuration.Provider, but the manifest keeps WorkManager's default initializer, which starts " +
                "WorkManager with the default configuration first, so this one${if (workers.isNotEmpty()) " (and its worker factory)" else ""} " +
                "is never used: remove androidx.work.WorkManagerInitializer from androidx.startup.InitializationProvider with tools:node=\"remove\"",
            p.str("file"), p.int("line")))
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
            out.add(finding(Rule.GRADLE_UNKNOWN_PROJECT, "${g.label(e.source)} depends on ${t.label}, which settings does not include",
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
            if (s == t || !glob(s, from) || !glob(t, to)) continue
            if (rule["allow_same_parent"] == true && s.substringBeforeLast(':') == t.substringBeforeLast(':')) continue
            val reason = (rule["reason"] as? String)?.let { ": $it" } ?: ""
            val via = e.attrs.str("via")?.let { " (added by plugin $it)" } ?: ""
            out.add(finding(Rule.GRADLE_FORBIDDEN_DEPENDENCY,
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
        finding(Rule.GRADLE_CYCLE, "Module cycle: ${comp.joinToString(" -> ") { g.label(it) }}", first.file, first.line, comp[0])
    }
}

// ----- manifest -----
private fun manifest(g: Graph, config: Config): List<Finding> {
    val allowed = config.allowedPermissions?.toSet() ?: return emptyList()
    return g.edgesOf("uses_permission").filter { !it.attrs.bool("removed") && !isTestSet(it.attrs.str("source_set")) }
        .mapNotNull { e ->
            val name = e.target.substringAfter("::")
            when {
                name in allowed -> null
                e.attrs.bool("merged") -> finding(Rule.MANIFEST_PERMISSION,
                    "${g.label(e.source)}'s merged manifest (${e.attrs.strings("variants").joinToString(", ")}) has $name" +
                        (e.attrs.strings("added_by").takeIf { it.isNotEmpty() }?.let { ", added by ${it.joinToString(", ")}" } ?: "") +
                        ", which is not in allowed_permissions: remove it with tools:node=\"remove\" in the app's manifest, or allow it" +
                        (if (e.attrs.bool("stale")) " (the merged manifest is older than the build files: build again to refresh it)" else ""),
                    e.file, e.line, e.target)
                else -> finding(Rule.MANIFEST_PERMISSION, "${g.label(e.source)} declares $name, which is not in allowed_permissions", e.file, e.line, e.target)
            }
        }
}
