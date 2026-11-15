package droidmap

/*
 * The findings of the extractors that check how app code is wired to Android (components, extras, notification channels,
 * ViewModel factories, single instances, route types) and to what users already have saved (enum constants, workers).
 * Each extractor records one meta list; each item becomes one finding at its file and line.
 */

private typealias Item = Map<String, Any?>

/** One finding per item of meta[[key]], at the item's file and line. */
private fun fromMeta(g: Graph, key: String, rule: Rule, node: (Item) -> String? = { null }, id: (Item) -> String? = { null }, message: (Item) -> String) =
    g.meta.maps(key).map { m -> finding(rule, message(m), m.str("file"), m.int("line"), node(m), id(m)) }

private fun simple(fq: String?) = fq?.substringAfterLast('.')
private fun quoted(items: List<String>) = items.joinToString(", ") { "\"$it\"" }

internal fun wiring(g: Graph): List<Finding> =
    fromMeta(g, "component_not_declared", Rule.COMPONENT_NOT_IN_MANIFEST, { "class::${it.str("class")}" }) { m ->
        val name = simple(m.str("class"))
        val effect = when (m.str("kind")) {
            "activity" -> "starting it throws ActivityNotFoundException (\"Unable to find explicit activity class ...; have you declared this activity in your AndroidManifest.xml?\")"
            "service" -> "Android does not find it, so it never runs (startService returns null, bindService false)"
            else -> "Android does not find it, so it never receives the broadcast"
        }
        val uses = m.int("uses") ?: 1
        "The code sends an explicit Intent to $name${if (uses > 1) " ($uses places; this is the first)" else ""}, but no AndroidManifest.xml " +
            "declares it, so $effect: add <${if (m.str("kind") == "receiver") "receiver" else m.str("kind")} android:name=\"${m.str("class")}\" /> to the manifest"
    } +
    fromMeta(g, "intent_extra_missing", Rule.INTENT_EXTRA_MISSING, { "class::${it.str("class")}" }, { "${it.str("class")} ${it.str("key")}" }) { m ->
        "${simple(m.str("class"))} reads the extra \"${m.str("key")}\", but ${missingFrom(m, "Intent that is sent to it", "Intents that are sent to it")}, " +
            "so it is always null (or the default): use one key on both sides, ideally one constant"
    } +
    fromMeta(g, "worker_input_missing", Rule.WORKER_INPUT_MISSING, { "class::${it.str("class")}" }, { "${it.str("class")} ${it.str("key")}" }) { m ->
        "${simple(m.str("class"))} reads the input \"${m.str("key")}\", but ${missingFrom(m, "work request for it", "work requests for it")}, " +
            "so it is always null (or the default): use one key on both sides, ideally one constant"
    } +
    fromMeta(g, "notification_channel_missing", Rule.NOTIFICATION_CHANNEL_MISSING, id = { it.str("channel") }) { m ->
        val created = m.strings("created")
        "A notification is built for the channel \"${m.str("channel")}\", but the app creates no channel with that id " +
            "(${if (created.isEmpty()) "it creates none" else "it creates ${quoted(created)}"}): from Android 8 the notification is not shown " +
            "(\"No Channel found\"), and a foreground service that shows it crashes (\"Bad notification for startForeground\"). Create the channel " +
            "(NotificationManagerCompat.createNotificationChannel) before posting, with the same id"
    } +
    fromMeta(g, "hilt_viewmodel_factory", Rule.HILT_VIEWMODEL_FACTORY, id = { "${it.str("user")} ${it.str("viewmodel")}" }) { m ->
        val vm = m.str("viewmodel")
        val (where, fix) = when (m.str("via")) {
            "viewModel" -> "with viewModel() in ${m.str("where")}, whose ViewModel owner is the back stack entry, with Navigation's factory" to
                "use hiltViewModel() (androidx.hilt:hilt-lifecycle-viewmodel-compose)"
            "viewModels" -> "with by viewModels() in ${m.str("user")}, which is not @AndroidEntryPoint, so it has the default factory" to
                "annotate ${m.str("user")} with @AndroidEntryPoint"
            else -> "with by navGraphViewModels(), which uses the graph's back stack entry and Navigation's factory" to "use hiltNavGraphViewModels()"
        }
        "${m.str("user")} gets $vm (a @HiltViewModel) $where: only Hilt's factory can call $vm's @Inject constructor, so it crashes when it is " +
            "created (\"Cannot create an instance of class ...$vm\"): $fix"
    } +
    fromMeta(g, "datastore_duplicate_file", Rule.DATASTORE_DUPLICATE_FILE, id = { it.str("datastore") ?: it.str("provider") }) { m ->
        val crash = "DataStore throws IllegalStateException (\"There are multiple DataStores active for the same file\")"
        val provider = m.str("provider")
        if (provider != null) "A DataStore is built in $provider, so each class it is injected into opens the file again and $crash: " +
            "annotate the provider @Singleton (a Koin single), so the app has one DataStore per file"
        else "${m.strings("sites").size} DataStores open ${m.str("datastore")} (${m.strings("sites").joinToString(", ")}): when the second is used, " +
            "$crash. Keep one, as a top-level delegate or a singleton, and share it"
    } +
    fromMeta(g, "room_database_unscoped", Rule.ROOM_DATABASE_NOT_SINGLETON, id = { it.str("provider") }) { m ->
        val db = m.str("database") ?: "The database"
        "$db is built in ${m.str("provider")}, so each class it is injected into opens another instance: each has its own invalidation " +
            "tracker, so a Flow or LiveData from one does not update when another writes, and each holds its own connections. " +
            "Annotate the provider @Singleton (a Koin single)"
    } +
    fromMeta(g, "nav_route_type_unsupported", Rule.NAV_ROUTE_TYPE_UNSUPPORTED, id = { "${it.str("route")} ${it.str("via")}" }) { m ->
        val props = m.strings("properties")
        "${m.str("via")} has no typeMap, but ${m.str("route")} has ${if (props.size == 1) "a property" else "properties"} that Navigation has " +
            "no NavType for (${props.joinToString(", ")}): it throws IllegalArgumentException (\"Route ${m.str("route")} could not find any NavType " +
            "for argument ...\"). Pass typeMap = mapOf(typeOf<...>() to a custom NavType), or use supported types (String, Int, Long, Float, Double, " +
            "Boolean, enums, and their lists and arrays)"
    } +
    fromMeta(g, "room_enum_constant_removed", Rule.ROOM_ENUM_CONSTANT_REMOVED, { "class::${it.str("enum")}" }, { "${it.str("enum")} ${it.strings("removed").joinToString(",")}" }) { m ->
        val removed = m.strings("removed")
        val cols = m.strings("columns").joinToString(", ")
        "${m.str("enum")} no longer has ${removed.joinToString(", ")} (removed or renamed since ${g.meta["base"]}), but Room stores ${m.str("enum")} by name " +
            "in $cols: rows that users saved with ${removed.first()} crash when read (IllegalArgumentException, \"Can't convert value to enum, unknown value: " +
            "${removed.first()}\"). Keep the constant (mark it @Deprecated), or add a migration that updates those rows " +
            "(UPDATE ... SET ... = '...' WHERE ... = '${removed.first()}'), or a @TypeConverter that maps unknown names to a default"
    } +
    fromMeta(g, "work_worker_renamed", Rule.WORKMANAGER_WORKER_RENAMED, id = { it.str("old") }) { m ->
        val old = m.str("old")
        val now = m.str("new")?.let { "is now $it" } ?: "is gone"
        val names = m.strings("names")
        val cancel = names.firstOrNull()?.let { "cancel it once (cancelUniqueWork(\"$it\")) or enqueue it with ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE" }
            ?: "cancel the old work once (by its tag or id)"
        "$old $now (since ${g.meta["base"]}), but the app enqueued it as periodic work${if (names.isNotEmpty()) " (${quoted(names)})" else ""}: " +
            "WorkManager keeps that work under the old class name, so on users' phones it fails every time it runs (\"Could not instantiate $old\")" +
            (if (m.strings("policies").any { it == "KEEP" || it == "UPDATE" }) ", and enqueueing it again with KEEP or UPDATE leaves the old work in place" else "") +
            ": $cancel, or keep a class with the old name"
    }

/** "no Intent that is sent to it puts one (they put "a", "b")": what the senders of a class put instead. */
private fun missingFrom(m: Item, one: String, many: String): String {
    val puts = m.strings("puts")
    val n = m.int("senders") ?: 1
    val they = if (puts.isEmpty()) "" else " (${if (n == 1) "it puts" else "they put"} ${quoted(puts)})"
    return "${if (n == 1) "the one $one puts no such key" else "none of the $n $many put it"}$they"
}
