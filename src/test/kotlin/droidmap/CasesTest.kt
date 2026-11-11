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

    @Test
    fun exitCodes() {
        val root = write(*app)
        fun run(vararg args: String) = runMain(args.toList(), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()))
        assertEquals(0, run("check", root.toString()))
        assertEquals(2, run("check", root.toString(), "--graph", root.resolve("missing.json").toString()))
        Files.writeString(root.resolve("bad.json"), "{}")
        assertEquals(2, run("check", root.toString(), "--graph", root.resolve("bad.json").toString()))
        assertEquals(2, run("frobnicate"))
    }
}
