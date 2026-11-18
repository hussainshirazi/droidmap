package droidmap

import droidmap.TestProject.Companion.APP
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How AI agents reach droidmap besides stdio MCP: the HTTP transport and the Claude Code plugin's hooks. */
class AgentsTest {
    @TempDir
    lateinit var tmp: Path

    private fun nav(vararg targets: String) = "app/src/main/kotlin/com/x/Nav.kt" to
        "package com.x\nimport androidx.navigation.compose.NavHost\nimport androidx.navigation.compose.composable\n" +
        "fun AppNav(navController: NavHostController) {\n    NavHost(navController, \"home\") { composable(\"home\") { } }\n}\n" +
        targets.joinToString("") { "fun go_$it(navController: NavHostController) { navController.navigate(\"$it\") }\n" }

    @Test
    fun mcpOverHttp() {
        val p = TestProject(tmp.resolve("p")).write(*APP, nav("ghost"))
        val http = McpHttpServer(McpServer(p.root, log = PrintStream(ByteArrayOutputStream())), 0).start()
        try {
            val client = HttpClient.newHttpClient()
            fun send(body: String?, origin: String? = null): HttpResponse<String> {
                val b = HttpRequest.newBuilder(URI.create(http.url)).header("Accept", "application/json, text/event-stream")
                origin?.let { b.header("Origin", it) }
                val req = if (body == null) b.GET() else b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
                return client.send(req.build(), HttpResponse.BodyHandlers.ofString())
            }
            val init = send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
            assertEquals(200, init.statusCode())
            assertTrue("\"protocolVersion\":\"2025-06-18\"" in init.body(), init.body())
            assertEquals(202, send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())
            val check = send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"droidmap_check","arguments":{}}}""")
            assertTrue("nav-unknown-target" in check.body(), check.body())
            assertEquals(405, send(null).statusCode())
            assertEquals(403, send("""{"jsonrpc":"2.0","id":3,"method":"ping"}""", origin = "https://evil.example").statusCode())
            assertEquals(200, send("""{"jsonrpc":"2.0","id":3,"method":"ping"}""", origin = "http://localhost:5173").statusCode())
        } finally {
            http.stop()
        }
    }

    @Test
    fun everyToolSaysItOnlyReadsAndDescribesEveryParameter() {
        for (t in TOOLS) {
            val name = t["name"]
            val annotations = t["annotations"] as Map<*, *>
            assertEquals(true, annotations["readOnlyHint"], "$name")
            assertEquals(false, annotations["destructiveHint"], "$name")
            assertTrue((t["description"] as String).contains("Use it"), "$name says when to use it")
            val schema = t["inputSchema"] as Map<*, *>
            for ((param, spec) in schema["properties"] as Map<*, *>) {
                assertTrue(((spec as Map<*, *>)["description"] as? String).orEmpty().isNotBlank(), "$name.$param has no description")
            }
            for (param in (schema["required"] as? List<*>).orEmpty()) assertTrue(param in (schema["properties"] as Map<*, *>).keys, "$name.$param")
        }
    }

    @Test
    fun mcpDiffRunsTheRulesThatCompareWithTheBase() {
        fun status(constants: String) = "app/src/main/kotlin/com/x/Status.kt" to "package com.x\nenum class Status { $constants }"
        val p = TestProject(tmp.resolve("p")).write(*APP, status("ACTIVE, PAUSED"),
            "app/src/main/kotlin/com/x/Sub.kt" to """
                package com.x
                import androidx.room.Entity
                import androidx.room.PrimaryKey
                @Entity(tableName = "subscriptions") data class Sub(@PrimaryKey val id: Long, val status: Status)""").commitAll()
        p.write(status("ACTIVE"))
        val s = McpServer(p.root, log = PrintStream(ByteArrayOutputStream()))
        val r = s.handle(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
            "params" to mapOf("name" to "droidmap_diff", "arguments" to emptyMap<String, Any>())))!!["result"] as Map<*, *>
        val text = ((r["content"] as List<*>)[0] as Map<*, *>)["text"].toString()
        assertTrue("room-enum-constant-removed" in text, text)
    }

    @Test
    fun theStopHookBlocksOnlyOnErrorsTheSessionAdded() {
        val p = TestProject(tmp.resolve("p")).write(*APP, nav("ghost")) // an error before the session: not Claude's
        val data = tmp.resolve("data")
        fun hook(event: String, stopActive: Boolean = false): String {
            val out = ByteArrayOutputStream()
            val input = """{"session_id":"s-1","cwd":"${p.root.resolve("app").toString().replace("\\", "\\\\")}","stop_hook_active":$stopActive}"""
            assertEquals(0, Hooks(data, PrintStream(out), PrintStream(ByteArrayOutputStream())).run(event, input))
            return out.toString().trim()
        }
        assertEquals("", hook("session-start"))
        assertEquals("", hook("stop"))
        p.write(nav("ghost", "elsewhere"))
        val blocked = hook("stop")
        assertTrue(blocked.startsWith("{\"decision\":\"block\"") && "added 1 error" in blocked && "elsewhere" in blocked && "\"ghost\"" !in blocked, blocked)
        assertEquals("", hook("stop", stopActive = true)) // Claude stopped again: accepted
        assertEquals("", hook("stop"))
        // Outside a Gradle project, or with input it does not understand, it does nothing.
        assertEquals(0, Hooks(data, PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())).run("stop", "not json"))
    }
}
