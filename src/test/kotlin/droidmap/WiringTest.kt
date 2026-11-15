package droidmap

import droidmap.TestProject.Companion.APP
import droidmap.TestProject.Companion.NS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The rules that check how app code is wired to Android, and to what users already have saved. */
class WiringTest {
    @TempDir
    lateinit var tmp: Path

    private val project get() = TestProject(tmp)

    private fun manifest(components: String) = "app/src/main/AndroidManifest.xml" to """
        <manifest $NS package="com.x">
          <application>
            <activity android:name=".MainActivity" android:exported="true">
              <intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter>
            </activity>
            $components
          </application>
        </manifest>"""

    private fun activities(main: String, detail: String = "") = arrayOf(
        "app/src/main/kotlin/com/x/MainActivity.kt" to """
            package com.x
            import android.content.Intent
            class MainActivity : AppCompatActivity() {
                $main
            }""",
        "app/src/main/kotlin/com/x/DetailActivity.kt" to """
            package com.x
            class DetailActivity : AppCompatActivity() {
                $detail
            }""")

    @Test
    fun anActivityStartedButNotDeclared() {
        val p = project.write(*APP, manifest(""), *activities("fun open() { startActivity(Intent(this, DetailActivity::class.java)) }"))
        val f = p.findings().filter { it.rule == "component-not-in-manifest" }
        assertEquals(listOf("app/src/main/kotlin/com/x/MainActivity.kt:4"), f.map { "${it.file}:${it.line}" })
        assertTrue("ActivityNotFoundException" in f[0].message, f[0].message)
        p.write(manifest("""<activity android:name=".DetailActivity"/>"""))
        assertEquals(emptyList(), p.messages("component-not-in-manifest"))
    }

    @Test
    fun anExtraNoIntentPuts() {
        val reads = """override fun onCreate() { val a = intent.getStringExtra("itemId"); val b = intent.getLongExtra("item_id", 0) }"""
        val p = project.write(*APP, manifest("""<activity android:name=".DetailActivity"/>"""), *activities(
            """fun open(id: Long) { startActivity(Intent(this, DetailActivity::class.java).putExtra("item_id", id)) }
               fun other(id: Long) { val i = Intent(this, DetailActivity::class.java); i.putExtra("item_id", id); startActivity(i) }""", reads))
        val f = p.messages("intent-extra-missing")
        assertEquals(1, f.size, f.toString())
        assertTrue("reads the extra \"itemId\"" in f[0] && "none of the 2 Intents that are sent to it put it (they put \"item_id\")" in f[0], f[0])
        // An Intent handed to other code may get the extra there: then nothing is known.
        p.write(*activities("""fun open(id: Long) { startActivity(withItem(Intent(this, DetailActivity::class.java), id)) }""", reads))
        assertEquals(emptyList(), p.messages("intent-extra-missing"))
        // An activity other apps can start (an intent filter) gets extras droidmap cannot see.
        p.write(*activities("""fun open(id: Long) { startActivity(Intent(this, DetailActivity::class.java).apply { putExtra("item_id", id) }) }""", reads),
            manifest("""<activity android:name=".DetailActivity"><intent-filter><action android:name="x.VIEW"/></intent-filter></activity>"""))
        assertEquals(emptyList(), p.messages("intent-extra-missing"))
    }

    @Test
    fun aWorkerInputNoRequestPuts() {
        val worker = "app/src/main/kotlin/com/x/SyncWorker.kt" to """
            package com.x
            class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result { val id = inputData.getString("userId"); val n = inputData.getInt("count", 0); return Result.success() }
            }"""
        fun enqueue(body: String) = "app/src/main/kotlin/com/x/Sync.kt" to "package com.x\nfun sync(wm: WorkManager) {\n$body\n}"
        val p = project.write(*APP, worker, enqueue("""wm.enqueue(OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("user_id" to 1, "count" to 2)).build())"""))
        val f = p.messages("worker-input-missing")
        assertEquals(1, f.size, f.toString())
        assertTrue("reads the input \"userId\"" in f[0] && "(it puts \"count\", \"user_id\")" in f[0], f[0])
        p.write(enqueue("""val data = Data.Builder().putString("userId", "u").putInt("count", 1).build()
            wm.enqueue(OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data).build())"""))
        assertEquals(emptyList(), p.messages("worker-input-missing"))
    }

    @Test
    fun aNotificationForAChannelNeverCreated() {
        fun notifier(body: String) = "app/src/main/kotlin/com/x/Notifier.kt" to """
            package com.x
            const val REMINDERS = "reminders"
            class Notifier(private val context: Context) {
                fun channels(manager: NotificationManager) { channel(manager, "general") }
                private fun channel(manager: NotificationManager, id: String) { manager.createNotificationChannel(NotificationChannel(id, "n", 3)) }
                fun show() = NotificationCompat.Builder(context, REMINDERS).build()
                $body
            }"""
        val p = project.write(*APP, notifier(""))
        val f = p.messages("notification-channel-missing")
        assertEquals(1, f.size, f.toString())
        assertTrue("channel \"reminders\"" in f[0] && "it creates \"general\"" in f[0], f[0])
        p.write(notifier("fun more(manager: NotificationManager) { channel(manager, REMINDERS) }"))
        assertEquals(emptyList(), p.messages("notification-channel-missing"))
    }

    @Test
    fun aHiltViewModelWithoutHiltsFactory() {
        fun screen(get: String) = "app/src/main/kotlin/com/x/Home.kt" to """
            package com.x
            import androidx.navigation.compose.NavHost
            import androidx.navigation.compose.composable
            import dagger.hilt.android.lifecycle.HiltViewModel
            import javax.inject.Inject
            class Repo @Inject constructor()
            @HiltViewModel class HomeViewModel @Inject constructor(private val repo: Repo) : ViewModel()
            @HiltViewModel class PlainViewModel @Inject constructor(private val handle: SavedStateHandle) : ViewModel()
            fun AppNav(navController: NavHostController) { NavHost(navController, "home") { composable("home") { HomeScreen() } } }
            fun HomeScreen() { val vm: HomeViewModel = $get; val plain: PlainViewModel = viewModel() }
            class SettingsFragment : Fragment() { private val vm: HomeViewModel by viewModels() }
            @AndroidEntryPoint class OkFragment : Fragment() { private val vm: HomeViewModel by viewModels() }"""
        val p = project.write(*APP, screen("viewModel()"))
        val f = p.messages("hilt-viewmodel-factory").sorted()
        assertEquals(2, f.size, f.toString())
        assertTrue("HomeScreen gets HomeViewModel (a @HiltViewModel) with viewModel() in the navigation destination home" in f[0], f[0])
        assertTrue("SettingsFragment gets HomeViewModel" in f[1] && "@AndroidEntryPoint" in f[1], f[1])
        p.write(screen("hiltViewModel()"))
        assertEquals(1, p.messages("hilt-viewmodel-factory").size)
    }

    @Test
    fun twoDataStoresOnOneFileAndUnscopedProviders() {
        val p = project.write(*APP,
            "app/src/main/kotlin/com/x/Prefs.kt" to """
                package com.x
                val Context.settings by preferencesDataStore(name = "settings")""",
            "app/src/main/kotlin/com/x/More.kt" to """
                package com.x
                private val Context.store by preferencesDataStore("settings")""",
            "app/src/main/kotlin/com/x/di/Module.kt" to """
                package com.x.di
                import dagger.Module
                import dagger.Provides
                import javax.inject.Singleton
                @Module object StorageModule {
                    @Provides fun dataStore(context: Context): DataStore<Preferences> =
                        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("other") })
                    @Provides fun db(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()
                    @Provides @Singleton fun okDb(context: Context): OkDatabase = Room.databaseBuilder(context, OkDatabase::class.java, "ok.db").build()
                }""")
        val f = p.messages("datastore-duplicate-file", "room-database-not-singleton").sorted()
        assertEquals(3, f.size, f.toString())
        assertTrue("2 DataStores open settings.preferences_pb (app/src/main/kotlin/com/x/More.kt:2, app/src/main/kotlin/com/x/Prefs.kt:2)" in f[0], f[0])
        assertTrue("built in StorageModule.dataStore (a @Provides without @Singleton)" in f[1], f[1])
        assertTrue(f[2].startsWith("room-database-not-singleton AppDatabase is built in StorageModule.db"), f[2])
    }

    @Test
    fun aRoutePropertyWithoutANavType() {
        fun nav(register: String) = "app/src/main/kotlin/com/x/Nav.kt" to """
            package com.x
            import androidx.navigation.compose.NavHost
            import androidx.navigation.compose.composable
            import kotlinx.serialization.Serializable
            @Serializable data class User(val name: String)
            enum class Tab { A, B }
            @Serializable data class Profile(val id: String, val tab: Tab, val tags: List<String>, val user: User)
            @Serializable data class Plain(val id: Long, val tabs: List<Tab>)
            fun AppNav(navController: NavHostController) {
                NavHost(navController, Plain(1, emptyList())) { $register; composable<Plain> { } }
            }"""
        val p = project.write(*APP, nav("composable<Profile> { }"))
        val f = p.messages("nav-route-type-unsupported")
        assertEquals(1, f.size, f.toString())
        assertTrue("composable<Profile>() has no typeMap" in f[0] && "(user: User)" in f[0], f[0])
        p.write(nav("composable<Profile>(typeMap = mapOf(typeOf<User>() to UserType)) { }"))
        assertEquals(emptyList(), p.messages("nav-route-type-unsupported"))
    }

    @Test
    fun anEnumConstantRoomStoresByNameIsRemoved() {
        fun status(constants: String) = "app/src/main/kotlin/com/x/Status.kt" to "package com.x\nenum class Status { $constants }"
        val p = project.write(*APP, status("ACTIVE, PAUSED, CANCELLED"),
            "app/src/main/kotlin/com/x/Sub.kt" to """
                package com.x
                import androidx.room.Entity
                import androidx.room.PrimaryKey
                @Entity(tableName = "subscriptions") data class Sub(@PrimaryKey val id: Long, val status: Status)""").commitAll()
        p.write(status("ACTIVE, CANCELLED"))
        val f = p.messages("room-enum-constant-removed", base = "HEAD")
        assertEquals(1, f.size, f.toString())
        assertTrue("Status no longer has PAUSED" in f[0] && "in subscriptions.status" in f[0], f[0])
        assertEquals(emptyList(), p.messages("room-enum-constant-removed")) // only with a base
    }

    @Test
    fun aPeriodicWorkerIsRenamed() {
        val sync = "app/src/main/kotlin/com/x/sync/SyncWorker.kt" to """
            package com.x.sync
            class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params)"""
        fun schedule(pkg: String, policy: String) = "app/src/main/kotlin/com/x/Schedule.kt" to """
            package com.x
            import com.x.$pkg.SyncWorker
            fun schedule(wm: WorkManager) {
                wm.enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.$policy, PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build())
            }"""
        val p = project.write(*APP, sync, schedule("sync", "KEEP")).commitAll()
        p.delete(sync.first).write("app/src/main/kotlin/com/x/work/SyncWorker.kt" to sync.second.replace("com.x.sync", "com.x.work"), schedule("work", "KEEP"))
        val f = p.messages("workmanager-worker-renamed", base = "HEAD")
        assertEquals(1, f.size, f.toString())
        assertTrue("com.x.sync.SyncWorker is now com.x.work.SyncWorker" in f[0] && "cancelUniqueWork(\"sync\")" in f[0], f[0])
        p.write(schedule("work", "CANCEL_AND_REENQUEUE"))
        assertEquals(emptyList(), p.messages("workmanager-worker-renamed", base = "HEAD"))
    }
}
