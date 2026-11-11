package droidmap

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

private val NODE_ARG = mapOf("type" to "string", "description" to
    "Node id, name or part of a name (e.g. 'TopicEntity', ':core:data', 'editor?id={id}', 'AppDatabase').")

val TOOLS: List<Map<String, Any?>> = listOf(
    mapOf("name" to "droidmap_check",
        "description" to "Run droidmap's Android rules (Room migration paths, schema files and migration tests; navigation " +
            "targets with no destination; Hilt missing or duplicate bindings; Gradle module rules and cycles; manifest " +
            "permission allowlist) and list the findings.",
        "inputSchema" to mapOf("type" to "object", "properties" to mapOf(
            "min_severity" to mapOf("type" to "string", "enum" to listOf("error", "warning", "info"),
                "description" to "Only show findings this severe or worse (default: info).")))),
    mapOf("name" to "droidmap_impact",
        "description" to "Everything that depends on a node, transitively: who injects a class, which DAOs and databases use " +
            "an entity, which screens use a ViewModel, which functions navigate to a destination, which modules depend on a " +
            "module, and which tests cover it. Use before changing a class, entity, route or module.",
        "inputSchema" to mapOf("type" to "object", "required" to listOf("node"), "properties" to mapOf(
            "node" to NODE_ARG, "depth" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10, "default" to 4)))),
    mapOf("name" to "droidmap_show",
        "description" to "One node with its attributes, its direct dependencies and its direct users.",
        "inputSchema" to mapOf("type" to "object", "required" to listOf("node"), "properties" to mapOf("node" to NODE_ARG))),
    mapOf("name" to "droidmap_find",
        "description" to "Search graph nodes (modules, Room entities/DAOs/databases/migrations, navigation destinations, " +
            "screens, ViewModels, Hilt bindings, permissions) by id or name.",
        "inputSchema" to mapOf("type" to "object", "required" to listOf("query"), "properties" to mapOf(
            "query" to mapOf("type" to "string"), "limit" to mapOf("type" to "integer", "default" to 20)))),
    mapOf("name" to "droidmap_report",
        "description" to "A Markdown overview of the project: modules and their dependencies, Room databases with migration " +
            "and test coverage, navigation destinations, Hilt bindings, findings.",
        "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())),
)

private class ToolError(message: String) : Exception(message)

/**
 * A Model Context Protocol server over stdio (JSON-RPC 2.0, one message per line). Tools only.
 * The graph is rebuilt when a scanned file or the config changes.
 */
class McpServer(private val root: Path, private val configPath: Path? = null, private val log: PrintStream = System.err) {
    private var graph: Graph? = null
    private var config = Config()
    private var stamp: String? = null

    private fun configStamp(): String {
        val p = configPath ?: root.resolve(CONFIG_NAME)
        return try { "${Files.size(p)}:${Files.getLastModifiedTime(p).toMillis()}" } catch (e: Exception) { "-" }
    }

    private fun current(): Pair<Graph, Config> {
        val cfg = Config.load(root, configPath)
        val s = Project(root, cfg).fingerprint + configStamp()
        if (graph == null || s != stamp) {
            graph = buildGraph(root, cfg).first
            config = cfg
            stamp = s
            log.println("droidmap: graph built (${graph!!.nodes.size} nodes, ${graph!!.edges.size} edges)")
        }
        return graph!! to config
    }

    @Suppress("UNCHECKED_CAST")
    fun handle(msg: Any?): Map<String, Any?>? {
        if (msg !is Map<*, *>) return error(null, -32600, "Invalid Request")
        val m = msg as Map<String, Any?>
        val method = m["method"] as? String ?: return null // a response to something we never send
        val isRequest = m.containsKey("id")
        val id = m["id"]
        val params = m["params"] as? Map<String, Any?> ?: emptyMap()
        val result: Any? = try {
            when {
                method == "initialize" -> {
                    val asked = params["protocolVersion"] as? String
                    linkedMapOf(
                        "protocolVersion" to (if (asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS[1]),
                        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                        "serverInfo" to mapOf("name" to "droidmap", "version" to VERSION),
                        "instructions" to "droidmap answers Android structure questions from a static graph of this project. " +
                            "Use droidmap_impact before changing an entity, route, ViewModel, binding or module, and droidmap_check after.")
                }
                method == "ping" -> emptyMap<String, Any>()
                method == "tools/list" -> mapOf("tools" to TOOLS)
                method == "tools/call" -> {
                    val name = params["name"] as? String
                    if (TOOLS.none { it["name"] == name }) return error(id, -32602, "Unknown tool: $name")
                    call(name!!, params["arguments"] as? Map<String, Any?> ?: emptyMap())
                }
                method.startsWith("notifications/") -> return null
                else -> return if (isRequest) error(id, -32601, "Method not found: $method") else null
            }
        } catch (e: Exception) { // never let one bad call stop the server
            log.println("droidmap: $method failed: $e")
            return if (isRequest) error(id, -32603, "Internal error: ${e.message}") else null
        }
        return if (isRequest) linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result) else null
    }

    private fun call(name: String, args: Map<String, Any?>): Map<String, Any?> = try {
        mapOf("content" to listOf(mapOf("type" to "text", "text" to callText(name, args))), "isError" to false)
    } catch (e: ToolError) {
        mapOf("content" to listOf(mapOf("type" to "text", "text" to e.message)), "isError" to true)
    }

    private fun callText(name: String, args: Map<String, Any?>): String {
        val (g, cfg) = current()
        when (name) {
            "droidmap_check" -> {
                val floor = SEVERITY_ORDER[args["min_severity"] as? String ?: "info"] ?: 2
                return formatFindings(runChecks(g, cfg).filter { (SEVERITY_ORDER[it.severity] ?: 9) <= floor })
            }
            "droidmap_report" -> return reportMarkdown(g, runChecks(g, cfg), root.fileName?.toString() ?: "project")
            "droidmap_find" -> {
                val q = (args["query"] as? String)?.trim().orEmpty()
                if (q.isEmpty()) throw ToolError("query is required")
                val hits = findNodes(g, q, (args["limit"] as? Number)?.toInt() ?: 20)
                if (hits.isEmpty()) return "No node matches '$q'."
                return hits.joinToString("\n") { "${it.id}  [${it.kind}]  ${it.file ?: ""}${it.line?.let { l -> ":$l" } ?: ""}" }
            }
        }
        val text = (args["node"] as? String)?.trim().orEmpty()
        if (text.isEmpty()) throw ToolError("node is required")
        val (node, hits) = resolveOne(g, text)
        if (node == null) {
            if (hits.isEmpty()) throw ToolError("No node matches '$text'. Use droidmap_find first.")
            throw ToolError("'$text' matches ${hits.size} nodes; call again with one of these ids:\n" +
                hits.joinToString("\n") { "${it.id}  [${it.kind}]" })
        }
        return if (name == "droidmap_impact") {
            formatImpact(impact(g, node.id, ((args["depth"] as? Number)?.toInt() ?: 4).coerceIn(1, 10)))
        } else formatShow(show(g, node.id))
    }

    private fun error(id: Any?, code: Int, message: String) =
        linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))

    fun serve(input: java.io.InputStream = System.`in`, output: OutputStream = System.out): Int {
        log.println("droidmap $VERSION MCP server for $root")
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val reply: Any? = try {
                when (val msg: JsonElement = compactJson.parseToJsonElement(line)) {
                    is JsonArray -> msg.mapNotNull { handle(fromJson(it)) }.ifEmpty { null }
                    is JsonObject -> handle(fromJson(msg))
                    else -> error(null, -32600, "Invalid Request")
                }
            } catch (e: Exception) {
                error(null, -32700, "Parse error")
            }
            if (reply != null) {
                output.write((jsonText(reply, pretty = false) + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
        return 0
    }
}
