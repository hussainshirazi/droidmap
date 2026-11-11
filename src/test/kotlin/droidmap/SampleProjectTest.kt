package droidmap

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The synthetic project in src/test/resources/sample triggers every rule. */
class SampleProjectTest {
    companion object {
        val root: Path = Path.of(SampleProjectTest::class.java.getResource("/sample/settings.gradle.kts")!!.toURI()).parent
        val g: Graph by lazy { buildGraph(root) }
    }

    private fun edge(kind: String, src: String, dst: String) =
        g.edges.filter { it.kind == kind && g.label(it.source) == src && g.label(it.target) == dst }

    private fun assertEdge(kind: String, src: String, dst: String) =
        assertTrue(edge(kind, src, dst).isNotEmpty(), "missing $kind edge $src -> $dst")

    @Test
    fun gradleModulesAccessorsGroovyAndConventionPlugins() {
        val labels = g.byKind("module").map { it.label }.toSet()
        assertTrue(labels.containsAll(listOf(":", ":app", ":core:data", ":core:database", ":feature:home", ":feature:detail-screen")))
        assertEdge("depends_on", ":app", ":feature:detail-screen") // projects.feature.detailScreen
        assertEdge("depends_on", ":core:data", ":core:database") // Groovy
        assertEquals("sample.feature", edge("depends_on", ":feature:home", ":core:data").single().attrs["via"])
        assertEdge("depends_on", ":feature:detail-screen", ":core:data") // id("sample.feature")
        assertEquals(true, edge("depends_on", ":app", ":core:data").single().attrs["test"])
    }

    @Test
    fun roomGraph() {
        val db = g.nodes.getValue("class::com.example.db.AppDatabase")
        assertEquals("room_database", db.kind)
        assertEquals(5, db.attrs.int("version"))
        assertEdge("contains_entity", "AppDatabase", "ItemEntity")
        assertEdge("contains_dao", "AppDatabase", "ItemDao")
        assertEdge("queries", "ItemDao", "ItemEntity")
        assertEquals("insert", edge("writes", "ItemDao", "ItemEntity").single().attrs.maps("replace").single()["function"])
        val steps = g.byKind("room_migration").map { Triple(it.attrs.int("from_version"), it.attrs.int("to_version"), it.attrs.str("style")) }
            .sortedBy { it.first }
        assertEquals(listOf(Triple(1, 2, "manual"), Triple(2, 3, "auto"), Triple(3, 4, "manual")), steps)
        assertEquals(listOf(1, 2, 4), g.byKind("room_schema").mapNotNull { it.attrs.int("version") }.sorted())
        val tested = g.edgesOf("tests")
        assertEquals(1, tested.size)
        assertTrue("1->2" in tested[0].target)
    }

    @Test
    fun navigationGraph() {
        val dests = g.byKind("nav_destination").associateBy { it.label }
        assertTrue(dests.getValue("home").attrs.bool("registered") && dests.getValue("detail/{id}").attrs.bool("registered"))
        assertEdge("navigates_to", "AppNav", "detail/{id}") // detailRoute(id) -> "detail/*"
        assertEdge("starts_at", "AppNav", "home")
        assertEdge("shows", "home", "HomeScreen")
        assertEdge("shows", "ProfileKey", "ProfileScreen") // Navigation 3 entry<ProfileKey>
        assertEdge("navigates_to", "openProfile", "ProfileKey")
        assertFalse(dests.getValue("MissingKey").attrs.bool("registered"))
        assertEdge("navigates_to", "@id/listFragment", "@id/detailFragment") // XML action
        assertEdge("starts_at", "@id/nav_graph", "@id/listFragment")
        // navigateToTab(TAB_A) resolves through the helper's route parameter
        assertEquals("navigateToTab", edge("navigates_to", "AppNav", "tab_a").single().attrs["via"])
        // TAB_B is only used in a list of tabs: referenced, so not reported as unreachable
        assertEquals(1, dests.getValue("tab_b").attrs.int("references"))
        assertTrue(g.unresolved.none { it.kind == "nav_target" && "route" == it.text })
    }

    @Test
    fun hiltGraph() {
        assertEquals("viewmodel", g.nodes.getValue("class::com.example.feature.home.HomeViewModel").kind)
        assertEdge("injects", "HomeViewModel", "ItemRepository")
        assertEdge("injects", "HomeViewModel", "TagRepository")
        assertEdge("bound_by", "ItemRepository", "DataModule.bindItems")
        assertEdge("requires", "DataModule.bindItems", "OfflineItemRepository")
        assertEdge("injects", "OfflineItemRepository", "ItemDao")
        assertEdge("uses_viewmodel", "HomeScreen", "HomeViewModel")
        assertEdge("uses_viewmodel", "MainActivity", "HomeViewModel")
        assertEdge("injects", "MainActivity", "ItemRepository") // @Inject lateinit var
        val quals = g.byKind("hilt_provider").filter { it.attrs.str("returns") == "CoroutineDispatcher" }
            .map { it.attrs.str("qualifier") ?: "" }.sorted()
        assertEquals(listOf("Dispatcher(Which.Default)", "Dispatcher(Which.IO)"), quals)
    }

    @Test
    fun manifest() {
        val perms = g.edgesOf("uses_permission").associateBy { it.target }
        assertEquals(true, perms.getValue("perm::android.permission.ACCESS_NETWORK_STATE").attrs["removed"])
        assertEdge("declares", ":app", "MainActivity")
    }

    @Test
    fun findings() {
        val by = runChecks(g, Config.load(root)).groupBy { it.rule }
        assertEquals(mapOf(
            "room-migration-path" to 1, "room-schema-missing" to 1, "room-migration-untested" to 1, "room-migration-unused" to 1,
            "room-destructive-fallback" to 1, "room-insert-replace" to 1, "nav-unknown-target" to 2, "nav-unreachable" to 2,
            "hilt-missing-binding" to 1, "hilt-duplicate-binding" to 1, "gradle-unknown-project" to 1,
            "gradle-forbidden-dependency" to 1, "manifest-permission" to 1,
        ), by.mapValues { it.value.size }.toSortedMap())
        assertTrue("1, 2, 3, 4" in by.getValue("room-migration-path")[0].message)
        assertTrue("3, 5" in by.getValue("room-schema-missing")[0].message)
        assertTrue("2->3, 3->4" in by.getValue("room-migration-untested")[0].message)
        assertEquals(setOf("AppNav", "profileEntry"), by.getValue("nav-unknown-target").map { it.message.substringBefore(" navigates") }.toSet())
        assertEquals(listOf("nav.dest::@id/unusedFragment", "nav.dest::orphan"), by.getValue("nav-unreachable").mapNotNull { it.node }.sorted())
        assertTrue("TagRepository" in by.getValue("hilt-missing-binding")[0].message)
        assertTrue("Clock" in by.getValue("hilt-duplicate-binding")[0].message)
        assertTrue("CAMERA" in by.getValue("manifest-permission")[0].message)
        assertEquals("error", by.getValue("room-insert-replace")[0].severity) // raised by .droidmap.toml
    }

    @Test
    fun impactFollowsHiltAndNavigation() {
        val r = impact(g, resolveOne(g, "ItemDao").first!!.id)
        val labels = r.dependents.map { it.label }.toSet()
        assertTrue(labels.containsAll(listOf("OfflineItemRepository", "DataModule.bindItems", "ItemRepository", "HomeViewModel",
            "HomeScreen", "MainActivity", "AppDatabase", "home")), labels.toString())
        assertFalse("CoroutineDispatcher" in labels)
        val entity = impact(g, resolveOne(g, "ItemEntity").first!!.id)
        assertFalse("DatabaseModule.provideDatabase" in entity.dependents.map { it.label }) // not through the database
    }

    @Test
    fun graphIsDeterministicAndHasNoAbsolutePaths() {
        val again = buildGraph(root)
        val a = jsonText(g.toMap())
        assertEquals(a, jsonText(again.toMap()))
        assertFalse(root.toString() in a || root.toString().replace('\\', '/') in a)
        val tmp = Files.createTempFile("graph", ".json")
        g.save(tmp)
        val loaded = Graph.load(tmp)
        assertEquals(a, jsonText(loaded.toMap()))
        Files.delete(tmp)
    }

    private fun cli(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true, Charsets.UTF_8), PrintStream(ByteArrayOutputStream())).run(args.toList())
        return code to out.toString(Charsets.UTF_8)
    }

    @Test
    fun cliExitCodesSarifAndBuild() {
        assertEquals(1, cli("check", root.toString()).first)
        assertEquals(0, cli("check", root.toString(), "--fail-on", "never").first)
        val sarif = compactJson.parseToJsonElement(cli("check", root.toString(), "--format", "sarif", "--fail-on", "never").second).jsonObject
        assertEquals("2.1.0", sarif["version"]!!.jsonPrimitive.content)
        assertTrue(sarif["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray.isNotEmpty())
        val out = Files.createTempDirectory("droidmap-out")
        assertEquals(0, cli("build", root.toString(), "-o", out.toString(), "--node-link").first)
        assertEquals(SCHEMA, compactJson.parseToJsonElement(out.resolve("graph.json").readText()).jsonObject["schema"]!!.jsonPrimitive.content)
        assertTrue(compactJson.parseToJsonElement(out.resolve("graph.node-link.json").readText()).jsonObject["links"]!!.jsonArray.isNotEmpty())
        val report = out.resolve("REPORT.md").readText()
        assertTrue("## Room" in report)
        // Two diagrams: the modules (a convention plugin's dependency dotted) and the screen flow (an unregistered route in red).
        assertEquals(2, Regex("```mermaid").findAll(report).count())
        assertTrue(" -.-> " in report && " ==> " in report && "class " in report, report)
        val (code, text) = cli("impact", "HomeViewModel", root.toString())
        assertEquals(0, code)
        assertTrue("HomeScreen" in text)
        assertEquals(2, cli("impact", "no-such-node-anywhere", root.toString()).first)
        kotlin.test.assertFailsWith<UsageError> { cli("frobnicate") } // main() turns this into exit code 2
        kotlin.test.assertFailsWith<UsageError> { cli("check", "--format", "xml", root.toString()) }
    }

    @Test
    fun mcpServerHandlesProtocol() {
        val s = McpServer(root, log = PrintStream(ByteArrayOutputStream()))
        val init = s.handle(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "initialize",
            "params" to mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>())))!!
        assertEquals("2025-06-18", (init["result"] as Map<*, *>)["protocolVersion"])
        assertEquals(null, s.handle(mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized")))
        val tools = ((s.handle(mapOf("jsonrpc" to "2.0", "id" to 2, "method" to "tools/list"))!!["result"] as Map<*, *>)["tools"] as List<*>)
            .map { (it as Map<*, *>)["name"] }
        assertTrue(tools.containsAll(listOf("droidmap_check", "droidmap_impact", "droidmap_find")))
        val r = s.handle(mapOf("jsonrpc" to "2.0", "id" to 3, "method" to "tools/call",
            "params" to mapOf("name" to "droidmap_impact", "arguments" to mapOf("node" to "TagRepository"))))!!["result"] as Map<*, *>
        assertEquals(false, r["isError"])
        assertTrue("HomeViewModel" in ((r["content"] as List<*>)[0] as Map<*, *>)["text"].toString())
        val bad = s.handle(mapOf("jsonrpc" to "2.0", "id" to 4, "method" to "tools/call",
            "params" to mapOf("name" to "droidmap_show", "arguments" to mapOf("node" to "nothing-like-this"))))!!["result"] as Map<*, *>
        assertEquals(true, bad["isError"])
        assertEquals(-32601, (s.handle(mapOf("jsonrpc" to "2.0", "id" to 5, "method" to "nope"))!!["error"] as Map<*, *>)["code"])
    }

    @Test
    fun mcpServerOverStreams() {
        val input = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2099-01-01"}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            "not json",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"droidmap_check","arguments":{"min_severity":"error"}}}""",
        ).joinToString("\n") + "\n"
        val out = ByteArrayOutputStream()
        McpServer(root, log = PrintStream(ByteArrayOutputStream())).serve(ByteArrayInputStream(input.toByteArray()), out)
        val lines = out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { compactJson.parseToJsonElement(it).jsonObject }
        assertEquals(3, lines.size)
        assertEquals("2025-06-18", lines[0]["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content) // unknown -> default
        assertEquals(-32700, lines[1]["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val text = lines[2]["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("room-migration-path" in text)
        assertNotNull(lines[2]["id"])
    }
}
