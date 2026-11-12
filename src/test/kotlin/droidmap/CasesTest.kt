package droidmap

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Small projects written to a temporary folder, one behaviour each. */
class CasesTest {
    @TempDir
    lateinit var tmp: Path

    private fun write(vararg files: Pair<String, String>): Path {
        for ((rel, text) in files) {
            val f = tmp.resolve(rel)
            Files.createDirectories(f.parent)
            Files.writeString(f, text.trimIndent() + "\n")
        }
        return tmp
    }

    /** The graph and the findings for a project made of [files]. */
    private fun build(vararg files: Pair<String, String>): Pair<Graph, List<Finding>> {
        val root = write(*files)
        val g = buildGraph(root)
        return g to runChecks(g, Config.load(root))
    }

    private fun Graph.edge(kind: String, src: String, dst: String) =
        edges.any { it.kind == kind && label(it.source) == src && label(it.target) == dst }

    private val app = arrayOf(
        "settings.gradle.kts" to """include(":app")""",
        "app/build.gradle.kts" to """plugins { id("com.android.application") }""",
    )

    private val ns = """xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto""""

    @Test
    fun xmlActionIntoAnotherGraphFile() {
        val (g, f) = build(*app,
            "app/src/main/res/navigation/a.xml" to """
                <navigation $ns android:id="@+id/a_graph" app:startDestination="@id/aFragment">
                  <fragment android:id="@+id/aFragment" android:name="com.x.AFragment">
                    <action android:id="@+id/to_b" app:destination="@id/bFragment"/>
                  </fragment>
                  <include app:graph="@navigation/b"/>
                </navigation>""",
            "app/src/main/res/navigation/b.xml" to """
                <navigation $ns android:id="@+id/b_graph" app:startDestination="@id/bFragment">
                  <fragment android:id="@+id/bFragment" android:name="com.x.BFragment"/>
                </navigation>""")
        assertEquals(emptyList(), f.map { it.message })
        assertTrue(g.edge("navigates_to", "@id/aFragment", "@id/bFragment"))
        assertEquals("app/src/main/res/navigation/b.xml", g.nodes.getValue("nav.dest::@id/bFragment").file)
    }

    @Test
    fun navigationPositionalArgumentsAndVariables() {
        val (g, f) = build(*app, "app/src/main/kotlin/com/x/Nav.kt" to """
            package com.x
            import androidx.navigation.compose.NavHost
            import androidx.navigation.compose.composable
            import androidx.navigation.compose.navigation

            val target = "home"

            fun AppNav(navController: NavHostController) {
                NavHost(navController, "home") {
                    composable("home") { }
                    navigation("inner", "graph") {
                        composable("inner") { }
                    }
                }
                var route = "ghost"
                route = "graph"
                navController.navigate(route)
                navController.navigate(target)
                val target = "nowhere"
            }""")
        assertEquals(emptyList(), f.map { it.message })
        assertTrue(g.edge("starts_at", "AppNav", "home"))
        assertTrue(g.nodes.getValue("nav.dest::graph").attrs.bool("graph"))
        assertFalse(g.nodes.getValue("nav.dest::inner").attrs.bool("graph"))
        assertTrue(g.edge("starts_at", "graph", "inner"))
        assertTrue(g.edge("navigates_to", "AppNav", "home")) // the top-level val: the local is declared after the call
        assertTrue(g.unresolved.any { it.kind == "nav_target" && it.text == "route" }) // a var is not a constant
    }

    @Test
    fun packagesNamedBuildAreReadButBuildOutputIsNot() {
        val vm = """
            import dagger.hilt.android.lifecycle.HiltViewModel
            import javax.inject.Inject
            @HiltViewModel class %s @Inject constructor() : ViewModel()"""
        val (g, _) = build(*app,
            "app/src/main/kotlin/com/x/build/BuildViewModel.kt" to "package com.x.build\n" + vm.trimIndent().format("BuildViewModel"),
            "app/build/generated/Gen.kt" to "package gen\n" + vm.trimIndent().format("GeneratedViewModel"))
        assertTrue("class::com.x.build.BuildViewModel" in g.nodes)
        assertFalse("class::gen.GeneratedViewModel" in g.nodes)
    }

    @Test
    fun inlineMigrationsAreNotReportedAsUnused() {
        val (g, f) = build(*app, "app/src/main/kotlin/com/x/db/Db.kt" to """
            package com.x.db
            import androidx.room.*
            import androidx.room.migration.Migration

            @Database(entities = [], version = 3, exportSchema = false)
            abstract class AppDb : RoomDatabase()

            val db = Room.databaseBuilder(ctx, AppDb::class.java, "x")
                .addMigrations(object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) {} })
                .addMigrations(Migration(2, 3) { })
                .build()

            val MIGRATION_9_10 = Migration(9, 10) { }""")
        assertEquals(listOf("<anonymous>", "<anonymous>", "MIGRATION_9_10"), g.byKind("room_migration").mapNotNull { it.attrs.str("name") }.sorted())
        assertEquals(listOf("MIGRATION_9_10"), f.filter { it.rule == "room-migration-unused" }.map { it.message.substringBefore(" ") })
    }

    @Test
    fun pluginsDeclaredWithApplyFalseAreNotApplied() {
        val (g, _) = build(
            "settings.gradle.kts" to """
                pluginManagement { includeBuild("build-logic") }
                include(":app", ":core")""",
            "gradle/libs.versions.toml" to """
                [plugins]
                conv = { id = "x.conv" }""",
            "build.gradle.kts" to """
                plugins {
                    alias(libs.plugins.conv) apply false
                    id("com.android.application") version "9.0" apply false
                }""",
            "app/build.gradle.kts" to """plugins { alias(libs.plugins.conv) }""",
            "core/build.gradle.kts" to """plugins { id("java") }""",
            "build-logic/convention/build.gradle.kts" to """
                gradlePlugin { plugins { register("conv") { id = "x.conv"; implementationClass = "ConvPlugin" } } }""",
            "build-logic/convention/src/main/kotlin/ConvPlugin.kt" to """
                class ConvPlugin : Plugin<Project> {
                    override fun apply(target: Project) { with(target) { dependencies { add("implementation", project(":core")) } } }
                }""")
        assertTrue(g.edge("depends_on", ":app", ":core"))
        assertFalse(g.edge("depends_on", ":", ":core"))
        assertEquals(null, g.nodes.getValue("module:::").attrs["plugins"])
    }

    private fun run(vararg args: String) = runMain(args.toList(), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()))

    @Test
    fun exitCodes() {
        val root = write(*app)
        assertEquals(0, run("check", root.toString()))
        assertEquals(2, run("check", root.toString(), "--graph", root.resolve("missing.json").toString()))
        Files.writeString(root.resolve("bad.json"), "{}")
        assertEquals(2, run("check", root.toString(), "--graph", root.resolve("bad.json").toString()))
        assertEquals(2, run("frobnicate"))
    }

    @Test
    fun checkWritesSarifAlongsideTheText() {
        val root = write(*app, "app/src/main/kotlin/com/x/Nav.kt" to """
            package com.x
            import androidx.navigation.compose.NavHost
            fun AppNav(navController: NavHostController) { navController.navigate("nowhere") }""")
        val sarif = root.resolve("out.sarif")
        assertEquals(1, run("check", root.toString(), "--sarif", sarif.toString()))
        val text = Files.readString(sarif)
        assertTrue("nav-unknown-target" in text && "\"uri\": \"app/src/main/kotlin/com/x/Nav.kt\"" in text, text)
    }

    @Test
    fun configTyposAreErrors() {
        val root = write(*app, ".droidmap.toml" to "[rules]\n\"nav-unreachible\" = \"off\"")
        assertEquals(2, run("check", root.toString()))
        Files.writeString(root.resolve(".droidmap.toml"), "[rules]\n\"nav-unreachable\" = \"eror\"\n")
        assertEquals(2, run("check", root.toString()))
        val mcp = McpServer(root, log = PrintStream(ByteArrayOutputStream())).handle(mapOf("jsonrpc" to "2.0", "id" to 1,
            "method" to "tools/call", "params" to mapOf("name" to "droidmap_check", "arguments" to emptyMap<String, Any>())))!!
        assertEquals(true, (mcp["result"] as Map<*, *>)["isError"])
    }

    @Test
    fun migrationTestsWithTheDriverApiAndMigrationsOnlyTestsUse() {
        val (_, f) = build(*app,
            "app/src/main/kotlin/com/x/db/Db.kt" to """
                package com.x.db
                import androidx.room.*
                import androidx.room.migration.Migration

                @Database(entities = [], version = 3, exportSchema = false)
                abstract class AppDb : RoomDatabase()

                val MIGRATION_1_2 = Migration(1, 2) { }
                val MIGRATION_2_3 = Migration(2, 3) { }
                val ALL = arrayOf(MIGRATION_1_2)""",
            "app/src/androidTest/kotlin/com/x/db/MigrationTest.kt" to """
                package com.x.db
                import androidx.room.testing.MigrationTestHelper

                class MigrationTest {
                    val helper = MigrationTestHelper(instrumentation, file, driver, AppDb::class)

                    @Test fun migrate2To3() {
                        helper.createDatabase(2)
                        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3))
                    }
                }""")
        // createDatabase(2) is version 2 (Room 2.7+ driver API), so the test covers 2->3 and not 1->2.
        assertEquals(listOf("1->2"), f.filter { it.rule == "room-migration-untested" }.map { it.message.substringAfter("(").substringBefore(")") })
        // Only the test uses MIGRATION_2_3: it is never registered with the database.
        assertEquals(listOf("MIGRATION_2_3"), f.filter { it.rule == "room-migration-unused" }.map { it.message.substringBefore(" ") })
    }

    private val schemaJson = """
        {"formatVersion": 1, "database": {"version": 1, "identityHash": "aaa", "entities": [{"tableName": "items", "fields": [
          {"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
          {"fieldPath": "title", "columnName": "item_title", "affinity": "TEXT", "notNull": true},
          {"fieldPath": "note", "columnName": "note", "affinity": "TEXT", "notNull": false},
          {"fieldPath": "address.city", "columnName": "home_city", "affinity": "TEXT", "notNull": false},
          {"fieldPath": "createdAt", "columnName": "createdAt", "affinity": "INTEGER", "notNull": true}]}]}}"""

    private fun roomProject(item: String, stamped: String = "var createdAt: Long = 0") = arrayOf(*app,
        "app/schemas/com.x.db.AppDb/1.json" to schemaJson,
        "app/src/main/kotlin/com/x/db/Db.kt" to """
            package com.x.db
            import androidx.room.*

            open class Stamped { $stamped }
            data class Address(val city: String)

            $item

            @Database(entities = [Item::class], version = 1)
            abstract class AppDb : RoomDatabase()""")

    @Test
    fun entitiesAreComparedWithTheCurrentSchemaJson() {
        // Column names, @ColumnInfo, a nullable @Embedded with a prefix, @Ignore and a superclass field all match 1.json.
        val (_, ok) = build(*roomProject("""
            @Entity(tableName = "items")
            data class Item(
                @PrimaryKey val id: Long,
                @ColumnInfo(name = "item_title") val title: String,
                val note: String?,
                @Embedded(prefix = "home_") val address: Address?,
                @Ignore val cached: String = "",
            ) : Stamped()"""))
        assertEquals(emptyList(), ok.filter { it.rule == "room-schema-stale" }.map { it.message })

        val (_, f) = build(*roomProject("""
            @Entity(tableName = "items")
            data class Item(
                @PrimaryKey val id: Long,
                @ColumnInfo(name = "item_title") val title: String?,
                @Embedded(prefix = "home_") val address: Address?,
                val rating: Int,
            ) : Stamped()""", stamped = "var createdAt: String = \"\""))
        val message = f.single { it.rule == "room-schema-stale" }.message
        for (part in listOf("items.rating is new", "items.note is gone", "items.createdAt is now TEXT (was INTEGER)", "items.item_title is now nullable")) {
            assertTrue(part in message, message)
        }
    }

    @Test
    fun schemaFileChangedSinceBase() {
        val root = write(*roomProject("""
            @Entity(tableName = "items")
            data class Item(@PrimaryKey val id: Long)"""))
        commitAll(root)
        val schema = root.resolve("app/schemas/com.x.db.AppDb/1.json")
        Files.writeString(schema, Files.readString(schema).replace("\"aaa\"", "\"bbb\""))
        val g = buildGraph(root, base = "HEAD")
        assertEquals(listOf("app/schemas/com.x.db.AppDb/1.json"), runChecks(g).filter { it.rule == "room-schema-changed" }.map { it.file })
        assertEquals(0, runChecks(buildGraph(root)).count { it.rule == "room-schema-changed" }) // only with a base
        assertEquals(2, run("check", root.toString(), "--base", "no-such-ref"))
    }

    private fun git(root: Path, vararg args: String) {
        val proc = ProcessBuilder(listOf("git", "-C", root.toString(), "-c", "user.name=t", "-c", "user.email=t@example.com",
            "-c", "commit.gpgsign=false") + args).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, proc.waitFor(), out)
    }

    /** A git repository at [root] with everything in it committed. */
    private fun commitAll(root: Path) {
        git(root, "init", "-q")
        git(root, "add", ".")
        git(root, "commit", "-q", "-m", "base")
    }

    @Test
    fun parsesAGitDiff() {
        val (files, deleted) = parseDiff("""
            diff --git a/A.kt b/A.kt
            --- a/A.kt
            +++ b/A.kt
            @@ -3,0 +4,2 @@ fun a() {
            @@ -10 +12 @@
            @@ -20,3 +21,0 @@
            diff --git a/New.kt b/New.kt
            --- /dev/null
            +++ b/New.kt
            @@ -0,0 +1,5 @@
            diff --git a/Gone.kt b/Gone.kt
            --- a/Gone.kt
            +++ /dev/null
            @@ -1,4 +0,0 @@""".trimIndent())
        assertEquals(listOf(4..5, 12..12, 21..21), files.single { it.path == "A.kt" }.ranges)
        assertEquals(listOf(10..10, 20..22), files.single { it.path == "A.kt" }.removed, "the lines removed at the base")
        assertTrue(files.single { it.path == "New.kt" }.whole)
        assertEquals(null, files.single { it.path == "New.kt" }.old)
        assertEquals(listOf("Gone.kt"), deleted)
    }

    @Test
    fun diffListsWhatAChangeRemoves() {
        val home = "app/src/main/kotlin/com/x/Home.kt"
        val root = write(*app,
            home to """
                package com.x
                import androidx.navigation.compose.composable

                @Composable fun HomeScreen() { }
                @Composable fun OldScreen() { }
                fun AppNav() {
                    NavHost(nav, "home") {
                        composable("home") { HomeScreen() }
                        composable("old") { OldScreen() }
                    }
                }""",
            "app/src/main/kotlin/com/x/Helper.kt" to """
                package com.x
                import javax.inject.Inject
                class Helper @Inject constructor()""",
            "app/src/main/kotlin/com/x/Consumer.kt" to """
                package com.x
                import dagger.hilt.android.lifecycle.HiltViewModel
                import javax.inject.Inject
                @HiltViewModel class Consumer @Inject constructor(helper: Helper) : ViewModel()""")
        commitAll(root)
        val file = root.resolve(home)
        Files.writeString(file, Files.readString(file).lines().filter { "Old" !in it && "\"old\"" !in it }.joinToString("\n"))
        Files.delete(root.resolve("app/src/main/kotlin/com/x/Helper.kt"))
        val buf = ByteArrayOutputStream()
        assertEquals(0, runMain(listOf("diff", root.toString(), "--format", "json"), PrintStream(buf), PrintStream(ByteArrayOutputStream())))
        @Suppress("UNCHECKED_CAST")
        val r = fromJson(compactJson.parseToJsonElement(buf.toString(Charsets.UTF_8))) as Map<String, Any?>
        assertEquals(listOf("class::com.x.Helper", "nav.dest::old", "fun::com.x.OldScreen"), r.maps("removed").map { it.str("id") })
        // Consumer still injects the removed Helper: the change breaks it.
        assertTrue("Consumer" in r.maps("affected").map { it.str("label") }, r.maps("affected").toString())
        val text = ByteArrayOutputStream()
        runMain(listOf("diff", root.toString()), PrintStream(text), PrintStream(ByteArrayOutputStream()))
        assertTrue("Removed (3): Helper (injectable), old (nav_destination), OldScreen (screen)" in text.toString(Charsets.UTF_8), text.toString(Charsets.UTF_8))
    }

    @Test
    fun testsThatReachAChangeThroughCalls() {
        write(*app,
            "app/src/main/kotlin/com/x/Data.kt" to """
                package com.x
                class Dao {
                    fun read() = 1
                    fun other() = 2
                }
                class Repo(private val dao: Dao) { fun load() = dao.read() }
                interface Store { fun get(): Int }
                class RealStore(private val dao: Dao) : Store { override fun get() = dao.other() }
                class HomeViewModel(private val repo: Repo, private val store: Store) {
                    private val helper by lazy { Repo(Dao()) }
                    fun refresh() = repo.load()
                    fun count() = store.get()
                }""",
            "app/src/test/kotlin/com/x/RepoTest.kt" to """
                package com.x
                import org.junit.Test
                class RepoTest { @Test fun loads() { val repo = Repo(Dao()); repo.load() } }
                class HomeViewModelTest { @Test fun refreshes() { HomeViewModel(Repo(Dao()), FakeStore()).refresh() } }
                class CountTest { @Test fun counts() { HomeViewModel(Repo(Dao()), FakeStore()).count() } }
                class FakeStore : Store { override fun get() = 0 }
                class UnrelatedTest { @Test fun nothing() { } }""",
            "app/src/test/java/com/x/RepoJavaTest.java" to """
                package com.x;
                import org.junit.Test;
                public class RepoJavaTest {
                    @Test public void loads() { Repo repo = new Repo(new Dao()); repo.load(); }
                }""")
        val code = Code.read(Project(tmp))
        val calls = Calls(code)
        fun testsFor(line: Int) = calls.testsReaching(calls.changedKeys(Changes("HEAD",
            listOf(FileChange("app/src/main/kotlin/com/x/Data.kt", listOf(line..line), whole = false)), emptyList()))).map { it.name }
        // Dao.read <- Repo.load <- HomeViewModel.refresh, through properties and locals typed by their declarations or constructors.
        assertEquals(listOf("HomeViewModelTest", "RepoJavaTest", "RepoTest"), testsFor(3).sorted())
        // RealStore.get is reached through the Store interface the ViewModel calls.
        assertEquals(listOf("CountTest"), testsFor(8))
        val refs = listOf(TestRef("com.x.ATest", "ATest", ":app", "a", false), TestRef("com.x.BTest", "BTest", ":core:data", "b", false),
            TestRef("com.x.UiTest", "UiTest", ":app", "c", instrumented = true))
        assertEquals(":app:testDebugUnitTest --tests com.x.ATest :core:data:testDebugUnitTest --tests com.x.BTest", testTasks(refs, "testDebugUnitTest"))
    }

    @Test
    fun gsonModelsInAShrunkReleaseBuild() {
        val api = "app/src/main/kotlin/com/x/api/Api.kt" to """
            package com.x.api
            import com.google.gson.Gson
            import com.google.gson.annotations.SerializedName
            import com.google.gson.reflect.TypeToken
            import retrofit2.converter.gson.GsonConverterFactory
            import retrofit2.http.GET

            interface Api {
                @GET("/top") suspend fun top(): Listing
                class Listing(val data: ListingData)
                class ListingData(val children: List<Post>, val after: String?)
            }
            class Post(@SerializedName("title") val title: String)
            class Settings(val theme: String, @Transient val cache: Int)
            class Named(@SerializedName("n") val n: Int)
            fun read(json: String) = Gson().fromJson(json, Settings::class.java)
            val type = object : TypeToken<List<Named>>() {}.type"""
        val (_, f) = build(*app, api)
        assertEquals(emptyList(), f.map { it.rule }, "release builds are not shrunk")
        write("app/build.gradle.kts" to """plugins { id("com.android.application") }
            android { buildTypes { release { isMinifyEnabled = true } } }""")
        val found = runChecks(buildGraph(tmp)).filter { it.rule == "gson-model-not-kept" }
        assertEquals(listOf("Listing", "ListingData", "Settings"), found.map { it.node!!.substringAfterLast('.') }.sorted())
        assertTrue(found.single { it.node!!.endsWith("Settings") }.message.startsWith(
            "Settings is read by Gson through reflection (fromJson in Api.kt), and release builds are shrunk by R8 (minifyEnabled in app/build.gradle.kts), " +
                "but its fields theme have no @SerializedName"), found.toString())
        // A keep rule for the package, or -dontobfuscate, protects them.
        write("app/proguard-rules.pro" to "-keep class com.x.api.** { *; }")
        assertEquals(emptyList(), runChecks(buildGraph(tmp)).filter { it.rule == "gson-model-not-kept" }.map { it.message })
    }

    @Test
    fun hiltFragmentInAnActivityThatIsNotAnEntryPoint() {
        val ns2 = """xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto""""
        val (_, f) = build(*app,
            "app/src/main/AndroidManifest.xml" to """<manifest $ns2><application android:name=".App"/></manifest>""",
            "app/src/main/kotlin/com/x/Screens.kt" to """
                package com.x
                import dagger.hilt.android.AndroidEntryPoint
                import dagger.hilt.android.HiltAndroidApp
                @HiltAndroidApp class App : Application()
                @AndroidEntryPoint class HomeFragment : Fragment()
                @AndroidEntryPoint class DetailFragment : DialogFragment()
                class PlainFragment : Fragment()
                class MainActivity : AppCompatActivity() {
                    override fun onCreate(b: Bundle?) { val binding = ActivityMainBinding.inflate(layoutInflater) }
                }
                class SettingsActivity : AppCompatActivity() {
                    fun show() { supportFragmentManager.beginTransaction().replace(R.id.c, DetailFragment()).commit() }
                }
                @AndroidEntryPoint open class BaseActivity : AppCompatActivity()
                class OkActivity : BaseActivity() { override fun onCreate(b: Bundle?) { setContentView(R.layout.activity_ok) } }""",
            "app/src/main/res/layout/activity_main.xml" to """<LinearLayout $ns2><include layout="@layout/content_main"/></LinearLayout>""",
            "app/src/main/res/layout/content_main.xml" to """
                <androidx.fragment.app.FragmentContainerView $ns2 android:name="androidx.navigation.fragment.NavHostFragment" app:navGraph="@navigation/main"/>""",
            "app/src/main/res/layout/activity_ok.xml" to """<fragment $ns2 android:name="com.x.HomeFragment"/>""",
            "app/src/main/res/navigation/main.xml" to """
                <navigation $ns2 app:startDestination="@id/home">
                  <fragment android:id="@+id/home" android:name="com.x.HomeFragment"/>
                  <fragment android:id="@+id/plain" android:name="com.x.PlainFragment"/>
                </navigation>""")
        val hosts = f.filter { it.rule == "hilt-fragment-host" }.map { it.message.substringBefore(", but") }
        assertEquals(listOf(
            "MainActivity hosts HomeFragment (an @AndroidEntryPoint fragment) through the navigation graph main in content_main.xml",
            "SettingsActivity hosts DetailFragment (an @AndroidEntryPoint fragment) through a fragment transaction (line 12)"), hosts.sorted())
    }

    @Test
    fun safeArgsDirectionsAndPopOnlyActions() {
        val (g, f) = build(*app,
            "app/src/main/res/navigation/nav_graph.xml" to """
                <navigation $ns android:id="@+id/nav_graph" app:startDestination="@id/home">
                  <fragment android:id="@+id/home" android:name="com.x.HomeFragment">
                    <action android:id="@+id/action_home_to_detail" app:destination="@id/detail"/>
                  </fragment>
                  <fragment android:id="@+id/detail" android:name="com.x.DetailFragment">
                    <action android:id="@+id/back_home" app:popUpTo="@id/home"/>
                  </fragment>
                  <fragment android:id="@+id/settings" android:name="com.x.SettingsFragment"/>
                  <action android:id="@+id/action_global_settings" app:destination="@id/settings"/>
                </navigation>""",
            "app/src/main/kotlin/com/x/HomeFragment.kt" to """
                package com.x
                import androidx.navigation.fragment.findNavController
                class HomeFragment : Fragment() {
                    fun open(id: Long) { findNavController().navigate(HomeFragmentDirections.actionHomeToDetail(id).setFrom("home")) }
                    fun settings() { findNavController().navigate(NavGraphDirections.actionGlobalSettings()) }
                    fun wrong() { findNavController().navigate(HomeFragmentDirections.actionHomeToNowhere()) }
                }""",
            "app/src/main/java/com/x/DetailFragment.java" to """
                package com.x;
                import androidx.navigation.NavDirections;
                import androidx.navigation.Navigation;
                public class DetailFragment extends Fragment {
                    void back(View v) {
                        NavDirections d = DetailFragmentDirections.backHome();
                        Navigation.findNavController(v).navigate(d);
                    }
                }""")
        assertTrue(g.edge("navigates_to", "open", "@id/detail"))
        assertTrue(g.edge("navigates_to", "settings", "@id/settings"), "a global action, through the graph's Directions class")
        assertTrue(g.edge("navigates_to", "back", "@id/home"), "Java, through a local")
        assertTrue(g.edge("navigates_to", "@id/detail", "@id/home"), "an action with only popUpTo goes back to it")
        assertEquals(listOf("HomeFragmentDirections.actionHomeToNowhere() matches no navigation XML action"),
            g.unresolved.filter { it.kind == "nav_target" }.map { it.reason })
        assertEquals(emptyList(), f.map { it.message })
    }

    @Test
    fun navigation3EntriesBuiltByHand() {
        val (g, f) = build(*app, "app/src/main/kotlin/com/x/Nav.kt" to """
            package com.x
            import androidx.navigation3.runtime.NavEntry
            import androidx.navigation3.runtime.rememberNavBackStack
            import androidx.navigation3.ui.NavDisplay

            data object Home : NavKey
            data class Detail(val id: Long) : NavKey
            data object Orphan : NavKey

            @Composable fun HomeScreen(onOpen: (Long) -> Unit) { }
            @Composable fun DetailScreen(id: Long) { }

            @Composable fun App() {
                val backStack = rememberNavBackStack(Home)
                NavDisplay(backStack = backStack, entryProvider = { key ->
                    when (key) {
                        is Home -> NavEntry(key) { HomeScreen(onOpen = { backStack.add(Detail(it)) }) }
                        is Detail -> NavEntry(key) { DetailScreen(key.id) }
                        Orphan -> NavEntry(key) { }
                        else -> error("unknown key")
                    }
                })
            }""")
        assertTrue(g.edge("shows", "Home", "HomeScreen") && g.edge("shows", "Detail", "DetailScreen"))
        assertTrue(g.edge("starts_at", "App", "Home") && g.edge("navigates_to", "App", "Detail"))
        assertEquals(listOf("nav-unreachable Orphan"), f.map { "${it.rule} ${g.label(it.node!!)}" })
    }

    @Test
    fun navigationHelpersSeveralLevelsDeep() {
        val (g, _) = build(*app, "app/src/main/kotlin/com/x/Nav.kt" to """
            package com.x
            import androidx.navigation.compose.NavHost
            import androidx.navigation.compose.composable

            fun AppNav(nav: NavHostController) { NavHost(nav, "home") { composable("home") { }; composable("item/{id}") { } } }
            class Router(private val nav: NavHostController) {
                fun go(route: String) = nav.navigate(route)
                fun track(route: String) { println(route); go(route) }
                fun openItem(id: String) = track("item/${'$'}id")
            }
            fun onHome(r: Router) = r.track("home")
            fun onItem(r: Router) = r.openItem("7")""")
        assertTrue(g.edge("navigates_to", "onHome", "home"), "onHome -> track -> go -> navigate")
        assertTrue(g.edge("navigates_to", "openItem", "item/{id}"))
        assertTrue(g.unresolved.none { it.kind == "nav_target" }, g.unresolved.toString())
    }

    @Test
    fun koinComponentScanCoversOnlyItsPackages() {
        val files = arrayOf(*app,
            "app/src/main/kotlin/com/x/di/AppModule.kt" to """
                package com.x.di
                import org.koin.core.annotation.ComponentScan
                import org.koin.core.annotation.Module
                @Module @ComponentScan("com.x.data") class AppModule""",
            "app/src/main/kotlin/com/x/data/Repo.kt" to """
                package com.x.data
                import org.koin.core.annotation.Single
                @Single class Repo""",
            "app/src/main/kotlin/com/x/feature/Store.kt" to """
                package com.x.feature
                import org.koin.core.annotation.Single
                interface Store
                @Single class FeatureStore : Store
                @Single class Unused""",
            "app/src/main/kotlin/com/x/data/ui/HomeViewModel.kt" to """
                package com.x.data.ui
                import com.x.data.Repo
                import com.x.feature.Store
                import org.koin.core.annotation.KoinViewModel
                @KoinViewModel class HomeViewModel(repo: Repo, store: Store) : ViewModel()""")
        val (_, f) = build(*files)
        assertEquals(listOf("HomeViewModel gets Store from Koin, but FeatureStore (@Single), which would provide it, is in com.x.feature, " +
            "which no @ComponentScan covers, so Koin never defines it (NoDefinitionFoundException at runtime): add the package to a module's @ComponentScan"),
            f.map { it.message })
        // KOIN_DEFAULT_MODULE puts the classes no scan finds in a default module.
        write("app/build.gradle.kts" to """plugins { id("com.android.application") }; ksp { arg("KOIN_DEFAULT_MODULE", "true") }""")
        assertEquals(emptyList(), runChecks(buildGraph(tmp)).map { it.message })
        // Without any @ComponentScan, every annotated class counts.
        write(*app)
        Files.delete(tmp.resolve("app/src/main/kotlin/com/x/di/AppModule.kt"))
        assertEquals(emptyList(), runChecks(buildGraph(tmp)).map { it.message })
    }

    @Test
    fun aNameWithoutAnImportIsNotAProjectClassOfAnotherPackage() {
        val (g, f) = build(*app,
            "app/src/main/kotlin/com/x/model/Result.kt" to "package com.x.model\nsealed class Result",
            "app/src/main/kotlin/com/x/ui/HomeViewModel.kt" to """
                package com.x.ui
                import dagger.hilt.android.lifecycle.HiltViewModel
                import javax.inject.Inject
                @HiltViewModel class HomeViewModel @Inject constructor(cached: Result<Int>) : ViewModel()""",
            "app/src/main/kotlin/com/x/ui/Base.kt" to """
                package com.x.ui
                abstract class Base { class Inner }
                class Sub : Base() { val inner = Inner() }""")
        // Kotlin's Result, not com.x.model.Result: no edge, and no missing binding to report.
        assertTrue(g.edges.none { it.target == "class::com.x.model.Result" })
        assertEquals(emptyList(), f.map { it.message })
        val code = Code.read(Project(tmp))
        val src = code.sources.single { it.path.endsWith("Base.kt") }
        assertEquals("com.x.ui.Base.Inner", code.index.resolveType("Inner", src, src.types.single { it.name == "Sub" }.psi)?.fq,
            "a class nested in a supertype")
    }

    @Test
    fun diffShowsWhatAChangeTouches() {
        val home = "app/src/main/kotlin/com/x/Home.kt"
        val root = write(*app, home to """
            package com.x
            import androidx.navigation.compose.composable
            import dagger.hilt.android.lifecycle.HiltViewModel
            import javax.inject.Inject

            class Repo @Inject constructor()
            @HiltViewModel class HomeViewModel @Inject constructor(repo: Repo) : ViewModel()
            @Composable fun HomeScreen(vm: HomeViewModel) { }
            fun AppNav() { NavHost(nav, "home") { composable("home") { HomeScreen(vm) } } }
            fun unrelated() { }""")
        commitAll(root)
        val file = root.resolve(home)
        Files.writeString(file, Files.readString(file).replace("class Repo @Inject constructor()", "class Repo @Inject constructor() {\n    fun load() = 1\n}"))
        val buf = ByteArrayOutputStream()
        assertEquals(0, runMain(listOf("diff", root.toString(), "--format", "json"), PrintStream(buf), PrintStream(ByteArrayOutputStream())))
        @Suppress("UNCHECKED_CAST")
        val r = fromJson(compactJson.parseToJsonElement(buf.toString(Charsets.UTF_8))) as Map<String, Any?>
        assertEquals(listOf("class::com.x.Repo"), r.maps("changed").map { it.str("id") })
        val affected = r.maps("affected").map { it.str("label") }
        assertTrue(listOf("HomeViewModel", "HomeScreen", "home").all { it in affected } && "unrelated" !in affected, affected.toString())
        // Unchanged code: nothing.
        git(root, "add", ".")
        git(root, "commit", "-q", "-m", "change")
        val text = ByteArrayOutputStream()
        runMain(listOf("diff", root.toString()), PrintStream(text), PrintStream(ByteArrayOutputStream()))
        assertTrue("No Android structure changed" in text.toString(Charsets.UTF_8), text.toString(Charsets.UTF_8))
    }

    @Test
    fun affectedModulesForCi() {
        val root = write(
            "settings.gradle.kts" to """include(":app", ":core:data", ":core:ui", ":feature:home")""",
            "build.gradle.kts" to """plugins { id("com.android.application") apply false }""",
            "gradle/libs.versions.toml" to "[versions]",
            "app/build.gradle.kts" to """dependencies { implementation(project(":feature:home")) }""",
            "feature/home/build.gradle.kts" to """dependencies { implementation(project(":core:data")); testImplementation(project(":core:ui")) }""",
            "core/data/build.gradle.kts" to "",
            "core/ui/build.gradle.kts" to "",
            "core/data/src/main/kotlin/Repo.kt" to "class Repo",
            "core/ui/src/main/res/values/strings.xml" to "<resources/>",
            "README.md" to "x",
        )
        commitAll(root)
        fun run(vararg args: String): List<String> {
            val buf = ByteArrayOutputStream()
            assertEquals(0, runMain(listOf("diff", root.toString()) + args, PrintStream(buf), PrintStream(ByteArrayOutputStream())))
            return buf.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        }
        fun change(rel: String) = Files.writeString(root.resolve(rel), Files.readString(root.resolve(rel)) + "\n")

        change("README.md")
        assertEquals(emptyList(), run("--format", "modules"), "docs touch no module")
        change("core/data/src/main/kotlin/Repo.kt")
        assertEquals(listOf(":app", ":core:data", ":feature:home"), run("--format", "modules"))
        git(root, "checkout", "-q", "--", ".")
        change("core/ui/src/main/res/values/strings.xml")
        assertEquals(listOf(":core:ui:testDebugUnitTest", ":feature:home:testDebugUnitTest"), run("--format", "tasks", "--task", "testDebugUnitTest"),
            "a resource counts, and so does a test dependency")
        change("gradle/libs.versions.toml")
        assertEquals(listOf(":app", ":core:data", ":core:ui", ":feature:home"), run("--format", "modules"), "the version catalog affects every module")
    }

    @Test
    fun daoWritesThroughAGenericBaseDao() {
        val (g, _) = build(*app, "app/src/main/kotlin/com/x/db/Daos.kt" to """
            package com.x.db
            import androidx.room.*

            @Entity data class Item(@PrimaryKey val id: Long)

            interface BaseDao<T> {
                @Insert fun insert(item: T)
                @Update fun updateAll(items: List<T>)
            }

            @Dao interface ItemDao : BaseDao<Item>""")
        assertEquals(listOf("insert", "updateAll"), g.edges.single { it.kind == "writes" }.attrs.strings("functions"))
    }

    @Test
    fun viewModelFromATypedProperty() {
        val (g, _) = build(*app, "app/src/main/kotlin/com/x/Home.kt" to """
            package com.x
            import androidx.hilt.navigation.compose.hiltViewModel
            import dagger.hilt.android.lifecycle.HiltViewModel
            import javax.inject.Inject

            @HiltViewModel class HomeViewModel @Inject constructor() : ViewModel()

            @Composable fun HomeScreen() { val viewModel: HomeViewModel = hiltViewModel() }""")
        assertTrue(g.edge("uses_viewmodel", "HomeScreen", "HomeViewModel"))
    }

    @Test
    fun menuItemsDeepLinksAndResourceIdsReachXmlDestinations() {
        val (_, f) = build(*app,
            "app/src/main/res/navigation/nav.xml" to """
                <navigation $ns android:id="@+id/nav" app:startDestination="@id/home">
                  <fragment android:id="@+id/home" android:name="com.x.Home"/>
                  <fragment android:id="@+id/tab" android:name="com.x.Tab"/>
                  <fragment android:id="@+id/linked" android:name="com.x.Linked">
                    <deepLink app:uri="example://linked"/>
                  </fragment>
                  <fragment android:id="@+id/topLevel" android:name="com.x.TopLevel"/>
                  <fragment android:id="@+id/orphan" android:name="com.x.Orphan"/>
                </navigation>""",
            "app/src/main/res/menu/bottom.xml" to """
                <menu xmlns:android="http://schemas.android.com/apk/res/android">
                  <item android:id="@+id/tab" android:title="Tab"/>
                </menu>""",
            "app/src/main/kotlin/com/x/Main.kt" to """
                package com.x
                val config = AppBarConfiguration(setOf(R.id.home, R.id.topLevel))""")
        assertEquals(listOf("nav.dest::@id/orphan"), f.filter { it.rule == "nav-unreachable" }.map { it.node })
    }

    @Test
    fun rootGraphStartsAndDeepLinkIds() {
        val (_, f) = build(*app, "app/src/main/res/navigation/nav.xml" to """
            <navigation $ns app:startDestination="@+id/home">
              <fragment android:id="@+id/home" android:name="com.x.Home"/>
              <fragment android:id="@+id/linked" android:name="com.x.Linked">
                <deepLink android:id="@+id/link" app:uri="example://linked"/>
              </fragment>
            </navigation>""")
        assertEquals(emptyList(), f.filter { it.rule != "nav-deeplink-not-in-manifest" }.map { it.message })
    }

    private fun manifest(application: String) = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
          $application
        </manifest>"""

    private val hiltApp = arrayOf(
        "settings.gradle.kts" to """include(":app")""",
        "app/build.gradle.kts" to """
            plugins { id("com.android.application") }
            android { namespace = "com.x" }""",
        "app/src/main/kotlin/com/x/App.kt" to """
            package com.x
            import dagger.hilt.android.AndroidEntryPoint
            import dagger.hilt.android.HiltAndroidApp

            @HiltAndroidApp open class App : Application()
            class DebugApp : App()
            @AndroidEntryPoint class MainActivity : ComponentActivity()""",
    )

    @Test
    fun theHiltApplicationMustBeTheOneTheManifestNames() {
        fun findings(application: String) = build(*hiltApp, "app/src/main/AndroidManifest.xml" to manifest(application)).second
            .filter { it.rule == "hilt-app-not-registered" }.map { it.message }
        assertEquals(emptyList(), findings("""<application android:name=".App"/>"""))
        assertEquals(emptyList(), findings("""<application android:name="com.x.DebugApp"/>"""), "a subclass works too")
        val wrong = findings("""<application android:name=".OtherApp"/>""")
        assertTrue(wrong.single().startsWith("App is the @HiltAndroidApp Application, but no AndroidManifest.xml names it") &&
            "(it names OtherApp), so MainActivity (an @AndroidEntryPoint) fails" in wrong.single(), wrong.toString())
        assertEquals(1, findings("""<application android:label="x"/>""").size, "no android:name at all")
    }

    @Test
    fun entryPointsWithoutAHiltApplication() {
        val (_, f) = build(*hiltApp.map { (k, v) -> k to v.replace("@HiltAndroidApp ", "") }.toTypedArray())
        assertTrue(f.single { it.rule == "hilt-app-not-registered" }.message.startsWith("No Application class is annotated @HiltAndroidApp"))
    }

    private val worker = "app/src/main/kotlin/com/x/SyncWorker.kt" to """
        package com.x
        import androidx.hilt.work.HiltWorker
        import dagger.assisted.Assisted
        import dagger.assisted.AssistedInject

        @HiltWorker class SyncWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters) :
            CoroutineWorker(context, params)"""

    private val configProvider = "app/src/main/kotlin/com/x/App.kt" to """
        package com.x
        import androidx.hilt.work.HiltWorkerFactory
        import androidx.work.Configuration
        import dagger.hilt.android.HiltAndroidApp
        import javax.inject.Inject

        @HiltAndroidApp class App : Application(), Configuration.Provider {
            @Inject lateinit var workerFactory: HiltWorkerFactory
            override val workManagerConfiguration get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
        }"""

    @Test
    fun hiltWorkersNeedHiltWorkerFactory() {
        val (_, f) = build(*app, worker)
        assertTrue(f.single { it.rule == "workmanager-setup" }.message.startsWith("SyncWorker is a @HiltWorker, but nothing in the app uses HiltWorkerFactory"))
    }

    @Test
    fun configurationProviderNeedsTheDefaultInitializerRemoved() {
        val removed = """
            <application android:name=".App">
              <provider android:name="androidx.startup.InitializationProvider" android:authorities="x" tools:node="merge">
                <meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup" tools:node="remove"/>
              </provider>
            </application>"""
        fun findings(application: String) = build(*app, worker, configProvider, "app/src/main/AndroidManifest.xml" to manifest(application)).second
            .filter { it.rule == "workmanager-setup" }.map { it.message }
        assertEquals(emptyList(), findings(removed))
        val kept = findings("""<application android:name=".App"/>""")
        assertTrue(kept.single().startsWith("App implements Configuration.Provider, but the manifest keeps WorkManager's default initializer"), kept.toString())
    }

    @Test
    fun deepLinksNeedAnIntentFilter() {
        val activity = """
            <application>
              <activity android:name=".MainActivity">
                <intent-filter>
                  <action android:name="android.intent.action.VIEW"/>
                  <data android:scheme="https"/>
                  <data android:host="*.example.com"/>
                </intent-filter>
                <nav-graph android:value="@navigation/main"/>
              </activity>
            </application>"""
        val (_, f) = build(*app,
            "app/src/main/AndroidManifest.xml" to manifest(activity),
            "app/src/main/kotlin/com/x/Nav.kt" to """
                package com.x
                import androidx.navigation.compose.composable
                import androidx.navigation.navDeepLink
                const val BASE = "https://www.example.com"
                fun AppNav() {
                    NavHost(nav, "item/{id}") {
                        composable("item/{id}", deepLinks = listOf(navDeepLink { uriPattern = "${'$'}BASE/item/{id}" })) { }
                        composable("open", deepLinks = listOf(navDeepLink { uriPattern = "app://open" })) { }
                    }
                }""",
            "app/src/main/res/navigation/main.xml" to """
                <navigation $ns app:startDestination="@id/home">
                  <include app:graph="@navigation/more"/>
                  <fragment android:id="@+id/home" android:name="com.x.Home"><deepLink app:uri="other://home"/></fragment>
                </navigation>""",
            "app/src/main/res/navigation/more.xml" to """
                <navigation $ns android:id="@+id/more" app:startDestination="@id/detail">
                  <fragment android:id="@+id/detail" android:name="com.x.Detail"><deepLink app:uri="other://detail"/></fragment>
                </navigation>""",
            "app/src/main/res/navigation/loose.xml" to """
                <navigation $ns app:startDestination="@id/loose">
                  <fragment android:id="@+id/loose" android:name="com.x.Loose"><deepLink app:uri="loose://x"/></fragment>
                </navigation>""")
        assertEquals(listOf("app://open", "loose://x"), f.filter { it.rule == "nav-deeplink-not-in-manifest" }
            .map { it.message.substringAfter("has the deep link ").substringBefore(",") }.sorted())
    }

    @Test
    fun columnNamesThatCannotBeReadAreNotCompared() {
        val (_, f) = build(*roomProject("""
            @Entity(tableName = "items")
            data class Item(@PrimaryKey @ColumnInfo(name = BaseColumns._ID) val id: Long)"""))
        assertEquals(emptyList(), f.filter { it.rule == "room-schema-stale" }.map { it.message })
    }

    @Test
    fun permissionsALibraryAddsToTheMergedManifest() {
        val android = "xmlns:android=\"http://schemas.android.com/apk/res/android\""
        val (_, f) = build(*app,
            ".droidmap.toml" to """[manifest]
allowed_permissions = ["android.permission.INTERNET"]""",
            "app/src/main/AndroidManifest.xml" to """
                <manifest $android><uses-permission android:name="android.permission.INTERNET"/></manifest>""",
            "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml" to """
                <manifest $android package="com.x">
                  <uses-permission android:name="android.permission.INTERNET"/>
                  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
                  <permission android:name="com.x.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature"/>
                  <uses-permission android:name="com.x.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
                </manifest>""",
            "app/build/intermediates/merged_manifests/releaseUnitTest/x/AndroidManifest.xml" to """
                <manifest $android><uses-permission android:name="android.permission.CAMERA"/></manifest>""",
            "app/build/outputs/logs/manifest-merger-release-report.txt" to """
                uses-permission#android.permission.INTERNET
                ADDED from /p/app/src/main/AndroidManifest.xml:3:5-67
                uses-permission#android.permission.ACCESS_NETWORK_STATE
                ADDED from [com.google.android.datatransport:transport-backend-cct:3.1.9] /c/AndroidManifest.xml:25:5-79
                	android:name
                		ADDED from [com.google.android.datatransport:transport-backend-cct:3.1.9] /c/AndroidManifest.xml:25:22-76""")
        val message = f.single().message
        assertTrue(":app's merged manifest (release) has android.permission.ACCESS_NETWORK_STATE, added by " +
            "com.google.android.datatransport:transport-backend-cct:3.1.9" in message, message)
        assertEquals("app/build.gradle.kts", f.single().file)
    }

    private fun javaApp(itemFields: String) = arrayOf(*app,
        "app/schemas/com.x.db.AppDatabase/2.json" to """
            {"formatVersion": 1, "database": {"version": 2, "identityHash": "a", "entities": [{"tableName": "items", "fields": [
              {"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
              {"fieldPath": "title", "columnName": "title", "affinity": "TEXT", "notNull": true},
              {"fieldPath": "note", "columnName": "note", "affinity": "TEXT", "notNull": false}]}]}}""",
        "app/schemas/com.x.db.AppDatabase/1.json" to """{"formatVersion": 1, "database": {"version": 1, "identityHash": "b", "entities": []}}""",
        "app/src/main/java/com/x/db/Item.java" to """
            package com.x.db;
            import androidx.annotation.NonNull;
            import androidx.room.*;

            @Entity(tableName = "items")
            public class Item {
                @PrimaryKey public long id;
                $itemFields
                @Ignore public String cached;
                public static final String TAG = "item";
            }""",
        "app/src/main/java/com/x/db/ItemDao.java" to """
            package com.x.db;
            import androidx.room.*;
            import java.util.List;

            @Dao
            public interface ItemDao {
                @Query("SELECT * FROM " + "items") List<Item> all();
                @Insert(onConflict = OnConflictStrategy.REPLACE) void insert(Item... items);
            }""",
        "app/src/main/java/com/x/db/AppDatabase.java" to """
            package com.x.db;
            import androidx.room.*;
            import androidx.room.migration.Migration;

            @Database(entities = {Item.class}, version = AppDatabase.VERSION)
            public abstract class AppDatabase extends RoomDatabase {
                static final int VERSION = 2;
                public abstract ItemDao itemDao();

                static final Migration MIGRATION_1_2 = new Migration(1, 2) {
                    @Override public void migrate(SupportSQLiteDatabase db) { }
                };

                static AppDatabase create(Context context) {
                    return Room.databaseBuilder(context, AppDatabase.class, "app").addMigrations(MIGRATION_1_2).build();
                }
            }""",
        "app/src/androidTest/java/com/x/db/MigrationTest.java" to """
            package com.x.db;
            import androidx.room.testing.MigrationTestHelper;

            public class MigrationTest {
                private static final String TEST_DB = "test";
                @Rule public MigrationTestHelper helper = new MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase.class);

                @Test public void migrate1To2() throws IOException {
                    helper.createDatabase(TEST_DB, 1).close();
                    helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2);
                }
            }""",
        "app/src/main/java/com/x/data/Repositories.java" to """
            package com.x.data;
            import com.x.db.AppDatabase;
            import com.x.db.ItemDao;
            import dagger.Binds;
            import dagger.Module;
            import dagger.Provides;
            import dagger.hilt.InstallIn;
            import dagger.hilt.components.SingletonComponent;
            import javax.inject.Inject;

            public interface ItemRepository { }

            class OfflineItemRepository implements ItemRepository {
                @Inject OfflineItemRepository(ItemDao dao) { }
            }

            @Module
            @InstallIn(SingletonComponent.class)
            abstract class DataModule {
                @Binds abstract ItemRepository bindItems(OfflineItemRepository impl);
                @Provides static ItemDao itemDao(AppDatabase db) { return db.itemDao(); }
                @Provides @Singleton static AppDatabase database(@ApplicationContext Context context) { return AppDatabase.create(context); }
            }""",
        "app/src/main/java/com/x/HomeViewModel.java" to """
            package com.x;
            import com.x.data.ItemRepository;
            import dagger.hilt.android.lifecycle.HiltViewModel;
            import javax.inject.Inject;

            @HiltViewModel
            public class HomeViewModel extends ViewModel {
                @Inject public HomeViewModel(ItemRepository items) { }
            }""",
        "app/src/main/java/com/x/MainActivity.java" to """
            package com.x;
            import androidx.navigation.fragment.NavHostFragment;
            import dagger.hilt.android.AndroidEntryPoint;
            import dagger.hilt.android.HiltAndroidApp;
            import javax.inject.Inject;

            class Analytics { }

            @HiltAndroidApp
            class App extends Application { }

            @AndroidEntryPoint
            public class MainActivity extends AppCompatActivity {
                @Inject Analytics analytics;

                @Override protected void onCreate(Bundle state) {
                    HomeViewModel vm = new ViewModelProvider(this).get(HomeViewModel.class);
                }

                void openDetail() {
                    NavHostFragment.findNavController(fragment).navigate(R.id.to_detail);
                }
            }""",
        "app/src/main/res/navigation/nav.xml" to """
            <navigation $ns android:id="@+id/nav" app:startDestination="@id/home">
              <fragment android:id="@+id/home" android:name="com.x.MainActivity">
                <action android:id="@+id/to_detail" app:destination="@id/detail"/>
              </fragment>
              <fragment android:id="@+id/detail" android:name="com.x.DetailFragment"/>
            </navigation>""",
        "app/src/main/AndroidManifest.xml" to """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.x">
              <application android:name=".App"><activity android:name=".MainActivity"/></application>
            </manifest>""")

    @Test
    fun javaSourcesAreRead() {
        val (g, f) = build(*javaApp("@NonNull public String title;\n    public String note;"))
        assertTrue(g.edge("contains_entity", "AppDatabase", "Item") && g.edge("contains_dao", "AppDatabase", "ItemDao"))
        assertEquals("items", g.nodes.getValue("class::com.x.db.Item").attrs.str("table"))
        assertTrue(g.edge("queries", "ItemDao", "Item"))
        assertEquals("insert", g.edges.single { it.kind == "writes" }.attrs.maps("replace").single().str("function"))
        assertEquals(2, g.nodes.getValue("class::com.x.db.AppDatabase").attrs.int("version"))
        assertTrue(g.edge("tests", "MigrationTest.migrate1To2", "MIGRATION_1_2 1->2"))
        assertTrue(g.edge("injects", "HomeViewModel", "ItemRepository") && g.edge("bound_by", "ItemRepository", "DataModule.bindItems"))
        assertTrue(g.edge("requires", "DataModule.bindItems", "OfflineItemRepository") && g.edge("injects", "OfflineItemRepository", "ItemDao"))
        assertTrue(g.edge("uses_viewmodel", "MainActivity", "HomeViewModel"))
        assertTrue(g.edge("navigates_to", "openDetail", "@id/detail"))
        assertEquals("app/src/main/java/com/x/MainActivity.java", g.nodes.getValue("class::com.x.MainActivity").file)
        // Only the binding nobody provides; the columns, the migration and its test all match.
        assertEquals(listOf("hilt-missing-binding Analytics"), f.map { "${it.rule} ${it.node?.substringAfterLast('.')}" })

        // A field added without a version bump; a String without @NonNull is a nullable column.
        val (_, stale) = build(*javaApp("public String title;\n    public String note;\n    public int rating;"))
        val message = stale.single { it.rule == "room-schema-stale" }.message
        assertTrue("items.rating is new" in message && "items.title is now nullable" in message, message)
    }

    @Test
    fun kotlinMultiplatformWithKoin() {
        val (g, f) = build(
            "settings.gradle.kts" to """include(":shared", ":testing", ":core", ":platform")""",
            "shared/build.gradle.kts" to """
                plugins { kotlin("multiplatform") }
                kotlin {
                    sourceSets {
                        commonMain.dependencies { implementation(projects.core) }
                        commonTest.dependencies { implementation(projects.testing) }
                        val androidMain by getting {
                            dependencies { implementation(project(":platform")) }
                        }
                    }
                }""",
            "testing/build.gradle.kts" to """
                plugins { kotlin("multiplatform") }
                kotlin { sourceSets { commonMain.dependencies { implementation(projects.shared) } } }""",
            "core/build.gradle.kts" to """plugins { kotlin("multiplatform") }""",
            "platform/build.gradle.kts" to """plugins { kotlin("multiplatform") }""",
            "shared/src/commonMain/kotlin/com/x/data/Data.kt" to """
                package com.x.data

                expect class Platform()
                interface ItemRepository
                interface Store
                class ItemStore(platform: Platform) : Store
                class OfflineItemRepository(store: Store, platform: Platform) : ItemRepository
                class Analytics
                class HomeViewModel(items: ItemRepository) : ViewModel()
                class DetailViewModel(analytics: Analytics) : ViewModel()""",
            "shared/src/androidMain/kotlin/com/x/data/Platform.android.kt" to "package com.x.data\nactual class Platform actual constructor()",
            "shared/src/iosMain/kotlin/com/x/data/Platform.ios.kt" to "package com.x.data\nactual class Platform actual constructor()",
            "shared/src/commonMain/kotlin/com/x/di/Modules.kt" to """
                package com.x.di
                import com.x.data.DetailViewModel
                import com.x.data.HomeViewModel
                import com.x.data.ItemRepository
                import com.x.data.ItemStore
                import com.x.data.OfflineItemRepository
                import com.x.data.Platform
                import com.x.data.Store
                import org.koin.core.module.dsl.singleOf
                import org.koin.core.module.dsl.viewModelOf
                import org.koin.dsl.bind
                import org.koin.dsl.module

                val dataModule = module {
                    single { Platform() }
                    single<ItemRepository> { OfflineItemRepository(get(), get()) }
                    singleOf(::ItemStore) bind Store::class
                    viewModelOf(::HomeViewModel)
                    factory { DetailViewModel(get()) }
                }""",
            "shared/src/commonMain/kotlin/com/x/data/Annotated.kt" to """
                package com.x.data
                import org.koin.core.annotation.KoinViewModel
                import org.koin.core.annotation.Single

                interface TimeSource
                @Single class Clock : TimeSource
                class Missing
                @KoinViewModel class SettingsViewModel(time: TimeSource, missing: Missing) : ViewModel()""",
            "shared/src/commonMain/kotlin/com/x/ui/Home.kt" to """
                package com.x.ui
                import com.x.data.HomeViewModel
                import org.koin.compose.viewmodel.koinViewModel

                @Composable fun HomeScreen() { val vm = koinViewModel<HomeViewModel>() }""")
        // Source sets: commonTest is a test dependency (so shared -> testing -> shared is no cycle).
        val deps = g.edges.filter { it.kind == "depends_on" }.associate { "${g.label(it.source)}->${g.label(it.target)}" to it.attrs }
        assertEquals("commonMain", deps.getValue(":shared->:core").str("source_set"))
        assertEquals(true to "commonTest", deps.getValue(":shared->:testing").let { it.bool("test") to it.str("source_set") })
        assertEquals("androidMain", deps.getValue(":shared->:platform").str("source_set"))
        // Koin: definitions bind their types, constructors' get() calls become injects edges, and the viewModel is used.
        assertTrue(g.edge("bound_by", "ItemRepository", "dataModule.single ItemRepository"))
        assertTrue(g.edge("requires", "dataModule.single ItemRepository", "OfflineItemRepository"))
        assertTrue(g.edge("injects", "OfflineItemRepository", "Store") && g.edge("injects", "OfflineItemRepository", "Platform"))
        assertTrue(g.edge("bound_by", "Store", "dataModule.single ItemStore") && g.edge("injects", "ItemStore", "Platform"))
        assertTrue(g.edge("uses_viewmodel", "HomeScreen", "HomeViewModel") && g.edge("bound_by", "TimeSource", "Clock"))
        // An expect class and its two actuals are one type.
        assertTrue(g.edge("bound_by", "Platform", "dataModule.single Platform"))
        assertEquals(listOf("koin-missing-definition Analytics", "koin-missing-definition Missing"),
            f.map { "${it.rule} ${it.node?.substringAfterLast('.')}" }.sorted())
    }
}
