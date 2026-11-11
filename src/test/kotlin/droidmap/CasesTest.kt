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
        fun git(vararg args: String) {
            val proc = ProcessBuilder(listOf("git", "-C", root.toString(), "-c", "user.name=t", "-c", "user.email=t@example.com",
                "-c", "commit.gpgsign=false") + args).redirectErrorStream(true).start()
            val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, proc.waitFor(), out)
        }
        git("init", "-q")
        git("add", ".")
        git("commit", "-q", "-m", "base")
        val schema = root.resolve("app/schemas/com.x.db.AppDb/1.json")
        Files.writeString(schema, Files.readString(schema).replace("\"aaa\"", "\"bbb\""))
        val g = buildGraph(root, base = "HEAD")
        assertEquals(listOf("app/schemas/com.x.db.AppDb/1.json"), runChecks(g).filter { it.rule == "room-schema-changed" }.map { it.file })
        assertEquals(0, runChecks(buildGraph(root)).count { it.rule == "room-schema-changed" }) // only with a base
        assertEquals(2, run("check", root.toString(), "--base", "no-such-ref"))
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
        assertEquals(emptyList(), f.map { it.message })
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
            import javax.inject.Inject;

            class Analytics { }

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
              <application><activity android:name=".MainActivity"/></application>
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
        // An expect class and its two actuals are one type, so Platform() resolves without an import.
        assertTrue(g.edge("bound_by", "Platform", "dataModule.single Platform"))
        assertEquals(listOf("koin-missing-definition Analytics", "koin-missing-definition Missing"),
            f.map { "${it.rule} ${it.node?.substringAfterLast('.')}" }.sorted())
    }
}
