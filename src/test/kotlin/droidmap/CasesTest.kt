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
        val g = buildGraph(root).first
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
}
