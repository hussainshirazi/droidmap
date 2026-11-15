package droidmap.extract

import droidmap.Call
import droidmap.Code
import droidmap.Graph
import droidmap.Node
import droidmap.Source
import droidmap.arg
import droidmap.bool
import droidmap.classRefs
import droidmap.int
import droidmap.str
import droidmap.strings

/** DataStore property delegates and the argument that names their file. */
private val DATASTORE_DELEGATES = mapOf("preferencesDataStore" to "name", "dataStore" to "fileName")
private val DATASTORE_FACTORIES = setOf("PreferenceDataStoreFactory", "DataStoreFactory")
/** The calls that name a DataStore's file in a factory's produceFile, and the suffix they add to the name. */
private val DATASTORE_FILES = mapOf("preferencesDataStoreFile" to ".preferences_pb", "dataStoreFile" to "")
private val MAIN_SETS = setOf("main", "commonMain", "androidMain")

/**
 * Objects an app must make only once. A DataStore owns its file: a second DataStore on the same file throws IllegalStateException
 * ("There are multiple DataStores active for the same file"). A Room database built twice is two instances whose Flows do not see
 * each other's writes. Finds two DataStores with one file in one app, and either one built in a dependency injection provider that
 * is not a singleton (a @Provides without @Singleton, a Koin factory), which builds another each time it is injected.
 * Recorded as meta datastore_duplicate_file and room_database_unscoped.
 */
class SingleInstances(private val code: Code, private val g: Graph) {
    private val values = Values(code)
    private val koinDefinitions by lazy { g.byKind("koin_definition").groupBy { it.file } }

    private class Store(val call: Call, val file: String?)

    fun run() {
        val stores = ArrayList<Store>()
        for (s in code.sources) {
            if (s.isTest) continue
            for (c in s.calls) when {
                c.name in DATASTORE_DELEGATES && c.assignedTo?.delegate != null -> stores.add(Store(c, delegateFile(c)))
                c.name.startsWith("create") && c.receiver?.substringAfterLast('.') in DATASTORE_FACTORIES -> {
                    stores.add(Store(c, factoryFile(c)))
                    provider(c)?.let { g.addMeta("datastore_duplicate_file", linkedMapOf("provider" to it, "file" to s.path, "line" to c.line)) }
                }
                c.name == "build" && c.receiver?.contains("databaseBuilder") == true -> provider(c)?.let {
                    g.addMeta("room_database_unscoped", linkedMapOf("provider" to it, "database" to database(c), "file" to s.path, "line" to c.line))
                }
            }
        }
        for ((file, same) in stores.filter { it.file != null }.groupBy { it.file!! }) {
            val clash = same.filter { a -> same.any { b -> b !== a && together(a.call.src, b.call.src) } }.sortedWith(compareBy({ it.call.src.path }, { it.call.line }))
            if (clash.size < 2) continue
            g.addMeta("datastore_duplicate_file", linkedMapOf("datastore" to file, "sites" to clash.map { "${it.call.src.path}:${it.call.line}" },
                "file" to clash[0].call.src.path, "line" to clash[0].call.line))
        }
    }

    /** `by preferencesDataStore(name = "settings")` keeps datastore/settings.preferences_pb. */
    private fun delegateFile(c: Call): String? {
        val name = values.constant(c.args.arg(DATASTORE_DELEGATES.getValue(c.name), 0)?.expr) ?: return null
        return if (c.name == "preferencesDataStore") "$name.preferences_pb" else name
    }

    /** The file a factory's produceFile names: `{ context.preferencesDataStoreFile("settings") }`. */
    private fun factoryFile(c: Call): String? {
        val named = c.src.calls.firstOrNull { it.name in DATASTORE_FILES && it !== c && it.isInside(c.psi) } ?: return null
        return values.constant(named.args.firstOrNull()?.expr)?.let { it + DATASTORE_FILES.getValue(named.name) }
    }

    /** The database class of `Room.databaseBuilder(context, AppDatabase::class.java, "app.db")...build()`. */
    private fun database(build: Call): String? {
        val builder = build.src.calls.firstOrNull { it.name == "databaseBuilder" && build.psi.parent.textRange.contains(it.psi.textRange) } ?: return null
        return builder.typeArgs.firstOrNull()?.simple ?: classRefs(builder.args.getOrNull(1)?.psi).firstOrNull()?.first?.substringAfterLast('.')
    }

    /** The provider around [c] when it is a DI provider that is not a singleton: its name and how it is declared. Null otherwise. */
    private fun provider(c: Call): String? {
        val hilt = c.fn?.fq?.let { g.nodes[funId(it)] }?.takeIf { it.hasRole("hilt_provider") }
        if (hilt != null) return when (val scope = hilt.attrs.str("scope")) {
            "Singleton" -> null
            null -> "${hilt.label} (a @Provides without @Singleton)"
            else -> "${hilt.label} (a @Provides scoped @$scope)"
        }
        val koin = koinDefinitions[c.src.path]?.firstOrNull { inside(it, c) } ?: return null
        val style = koin.attrs.str("style")
        return if (style == "single" || style == "singleton") null else "${koin.label} (a Koin $style)"
    }

    private fun inside(n: Node, c: Call) = (n.line ?: Int.MAX_VALUE) <= c.line && c.line <= (n.attrs.int("end_line") ?: -1)

    /** Whether code in [a] and [b] runs in one app: source sets built together and modules of one application. */
    private fun together(a: Source, b: Source): Boolean {
        if (a.sourceSet != b.sourceSet && a.sourceSet !in MAIN_SETS && b.sourceSet !in MAIN_SETS) return false // two variants
        if (a.module == b.module) return true
        val apps = appModules
        return apps.isEmpty() || apps.any { a.module in it && b.module in it }
    }

    /** For each application module, the modules it is built from (itself included). */
    private val appModules: List<Set<String?>> by lazy {
        val deps = g.edgesOf("depends_on").filter { !it.attrs.bool("test") }.groupBy({ it.source }, { it.target })
        g.byKind("module").filter { m -> m.attrs.strings("plugins").any { "application" in it } }.map { m ->
            val seen = LinkedHashSet<String>()
            val todo = ArrayDeque(listOf(m.id))
            while (todo.isNotEmpty()) { val x = todo.removeFirst(); if (seen.add(x)) todo.addAll(deps[x].orEmpty()) }
            seen.map { g.nodes[it]?.label }.toSet()
        }
    }
}
