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
    "The node: its id as droidmap_find prints it, its name, or part of a name, such as 'TopicEntity', ':core:data', " +
    "'editor?id={id}' or 'AppDatabase'. When several nodes match, the call fails and lists their ids: call again with one of them.")

private val DEPTH_ARG = mapOf("type" to "integer", "minimum" to 1, "maximum" to 10, "default" to 4, "description" to
    "How many dependency steps to follow outwards: 1 lists only direct users; the default, 4, is enough for most changes.")

/** How every tool behaves: it reads the project and changes nothing. */
private const val READ_ONLY = "Read-only and local: it reads the source files, never runs Gradle or the app, and sends nothing anywhere."

/** One tool. Every droidmap tool only reads the project, so all of them carry the same read-only annotations. */
private fun tool(name: String, title: String, description: String, properties: Map<String, Any> = emptyMap(), required: List<String> = emptyList()) =
    mapOf("name" to name, "title" to title, "description" to description,
        "inputSchema" to buildMap { put("type", "object"); put("properties", properties); if (required.isNotEmpty()) put("required", required) },
        "annotations" to mapOf("title" to title, "readOnlyHint" to true, "destructiveHint" to false, "idempotentHint" to true, "openWorldHint" to false))

val TOOLS: List<Map<String, Any?>> = listOf(
    tool("droidmap_check", "Check the Android wiring",
        "Run droidmap's rules on the whole project and list what compiles but would crash or lose data at runtime: Room " +
            "(missing or failing migrations, schema files out of date, untested migration steps), navigation (routes nothing " +
            "registers, missing arguments, route types without a NavType, deep links no intent filter accepts), Hilt and Koin " +
            "bindings, WorkManager setup and inputs, manifest components, permissions, Intent extras and notification channels, " +
            "DataStores and databases built more than once, Gson and Moshi models that R8 renames, and Gradle module rules and cycles. " +
            "Use it after editing to confirm nothing new broke; use droidmap_diff for what a change touches and droidmap_explain " +
            "for how to fix a finding. $READ_ONLY Findings recorded in the project's droidmap-baseline.json are left out, with a " +
            "note saying how many. Returns plain text: each finding's severity, rule id and file:line, then the problem, and a " +
            "count by severity at the end; or \"No findings.\"",
        mapOf("min_severity" to mapOf("type" to "string", "enum" to listOf("error", "warning", "info"), "default" to "info",
            "description" to "Leave out findings less severe than this: 'error' lists only errors, 'warning' errors and " +
                "warnings, 'info' (the default) everything."))),
    tool("droidmap_impact", "What depends on this",
        "List everything that depends on one node, transitively: who injects a class, which DAOs and databases use an entity, " +
            "which screens use a ViewModel, which functions navigate to a destination, which modules depend on a module, and " +
            "the test classes whose calls reach it. Use it before changing a class, entity, route, ViewModel, binding or module, " +
            "to know what else to update and test; use droidmap_show for only the direct links in both directions, and " +
            "droidmap_diff once the change is made. $READ_ONLY Returns plain text: the node and its file, then up to 200 " +
            "dependents nearest first, each with its depth, kind, the edge it depends through and file:line, then the tests and " +
            "modules affected; or \"Nothing in the graph depends on it.\"",
        mapOf("node" to NODE_ARG, "depth" to DEPTH_ARG), listOf("node")),
    tool("droidmap_diff", "What a change touches",
        "Map a code change onto the graph: the declarations whose lines changed since a git ref (by default the uncommitted " +
            "changes, new files included), the ones removed, everything that depends on them (screens, destinations, ViewModels, " +
            "Room, bindings, modules), the test classes whose calls reach the changed code, and the findings in the changed " +
            "files, including those only a comparison shows (a shipped Room schema or a stored enum constant changed, a Worker " +
            "renamed). Use it after editing, to see what else to update and which tests to run; use droidmap_impact before " +
            "editing. Read-only: it runs git diff and reads the source files, never runs Gradle, and changes nothing; the project " +
            "must be in a git repository. Returns plain text in sections: Changed, Removed, Affected (grouped by kind), Tests that " +
            "cover it, and Findings in the changed files.",
        mapOf("base" to mapOf("type" to "string", "default" to "HEAD", "description" to
            "The git ref to compare with: a branch, tag or commit, such as 'main', 'origin/main' or 'v1.2.0'. The default, " +
                "'HEAD', compares the working tree with the last commit, so it shows the uncommitted changes. With a branch, " +
                "droidmap compares with the point where the current branch left it."),
            "depth" to DEPTH_ARG)),
    tool("droidmap_show", "Show one node",
        "Show one node of the graph: its kind, file:line and attributes (a destination's route and arguments, a database's " +
            "version, a binding's qualifier), then the nodes it depends on directly and the nodes that use it directly, each " +
            "with the edge kind and file:line. Use it to look closely at one class, entity, route or module; use droidmap_impact " +
            "for everything that depends on it transitively, and droidmap_find when you do not know its name. $READ_ONLY " +
            "Returns plain text.",
        mapOf("node" to NODE_ARG), listOf("node")),
    tool("droidmap_find", "Find nodes by name",
        "Search the graph's nodes by id or name: modules, Room databases, entities, DAOs and migrations, navigation " +
            "destinations and routes, screens, ViewModels, injectable classes, Hilt and Koin modules and bindings, components " +
            "and permissions. Case is ignored; an exact name ranks first, then a name that starts with the text, then one that " +
            "contains it. Use it to get the exact name or id that droidmap_impact and droidmap_show need when you know only part " +
            "of it. $READ_ONLY Returns plain text, one node per line: name, kind and file:line (with the id when names repeat); " +
            "or \"No node matches\".",
        mapOf("query" to mapOf("type" to "string", "description" to
            "The text to look for in node names and ids, such as 'Topic', 'UserDao', ':feature:home' or 'settings'."),
            "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 200, "default" to 20,
                "description" to "The most nodes to list (default 20).")),
        listOf("query")),
    tool("droidmap_report", "Project overview",
        "Write a Markdown overview of the whole project: a summary, the modules and their dependencies, Room databases with " +
            "their versions, migrations and test coverage, navigation destinations and how screens connect, dependency " +
            "injection, permissions and the current findings, with Mermaid diagrams of the modules and the screen flow. Use it " +
            "once to get oriented in an unfamiliar project; use droidmap_find, droidmap_show or droidmap_impact for one part. " +
            "$READ_ONLY Takes no arguments. Returns Markdown, which can be long on a large project."),
    tool("droidmap_explain", "Explain a rule",
        "Explain one droidmap rule: what it finds, why it matters (the crash or data loss it prevents) and how to fix what it " +
            "found, with code examples, and its page online. Use it when droidmap_check or droidmap_diff reports a finding you do " +
            "not know how to fix. It reads documentation bundled with droidmap, so it needs no project and changes nothing. " +
            "Returns Markdown.",
        mapOf("rule" to mapOf("type" to "string", "enum" to RULES.keys.toList(), "description" to
            "The rule's id, as each finding shows it, such as 'room-migration-path' or 'hilt-missing-binding'.")),
        listOf("rule")),
)

private class ToolError(message: String) : Exception(message)

/**
 * A Model Context Protocol server (JSON-RPC 2.0): over stdio with [serve], one message per line, or over HTTP with
 * [McpHttpServer], which passes each message to [reply]. Tools only.
 * The graph is rebuilt when a scanned file or the config changes.
 */
class McpServer(private val root: Path, private val configPath: Path? = null, private val log: PrintStream = System.err) {
    private var built: Built? = null
    private var config = Config()
    private var stamp: String? = null

    private fun configStamp(): String {
        val p = configPath ?: root.resolve(CONFIG_NAME)
        return try { "${Files.size(p)}:${Files.getLastModifiedTime(p).toMillis()}" } catch (e: Exception) { "-" }
    }

    /** The graph, rebuilt when a file or the config changed; files that did not change are not parsed again. */
    private fun current(): Pair<Built, Config> {
        val cfg = Config.load(root, configPath)
        val p = Project(root, cfg)
        val s = p.fingerprint + configStamp()
        val b = built?.takeIf { s == stamp } ?: buildGraph(p, previous = built?.code).also {
            built = it
            config = cfg
            stamp = s
            log.println("droidmap: graph built (${it.graph.nodes.size} nodes, ${it.graph.edges.size} edges)")
        }
        return b to config
    }

    /** The project's baseline file (droidmap-baseline.json at the root), read each time: it changes when someone updates it. */
    private fun baseline(): Baseline? = try {
        Baseline.find(root)
    } catch (e: IllegalArgumentException) {
        throw ToolError("cannot read the baseline: ${e.message}")
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
                        "instructions" to "droidmap answers Android structure questions from a static graph of this project, " +
                            "rebuilt from the source files when they change. Use droidmap_impact before changing an entity, route, " +
                            "ViewModel, binding or module, and droidmap_check and droidmap_diff after; droidmap_explain says how to " +
                            "fix a finding, and droidmap_find gives the exact name when you know only part of it.")
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
        if (name == "droidmap_explain") {
            val id = (args["rule"] as? String)?.trim().orEmpty()
            return explainRule(id) ?: throw ToolError("There is no rule \"$id\"; the rules are: ${RULES.keys.joinToString(", ")}")
        }
        val (built, cfg) = try {
            current()
        } catch (e: IllegalArgumentException) {
            throw ToolError("cannot read the config: ${e.message}")
        }
        val g = built.graph
        when (name) {
            "droidmap_diff" -> {
                val base = (args["base"] as? String)?.trim()?.ifEmpty { null } ?: "HEAD"
                if (!Git.isCommit(root, base)) throw ToolError("$base is not a commit in the git repository of $root")
                val changes = try { readChanges(root, base) } catch (e: UsageError) { throw ToolError(e.message ?: "git diff failed") }
                // Built with the base, as the CLI's diff is, so the rules that compare with it run too (a shipped schema or
                // enum constant changed, a Worker renamed). Only files changed since the last build are parsed again.
                val atBase = buildGraph(Project(root, cfg), base, previous = built.code)
                val all = runChecks(atBase.graph, cfg)
                val findings = baseline()?.apply(all)?.findings ?: all
                return formatDiff(diffReport(atBase, changes, findings, ((args["depth"] as? Number)?.toInt() ?: 4).coerceIn(1, 10)), markdown = false)
            }
            "droidmap_check" -> {
                val floor = SEVERITY_ORDER[args["min_severity"] as? String ?: "info"] ?: 2
                val all = runChecks(g, cfg)
                val b = baseline()
                val applied = b?.apply(all)
                val text = formatFindings((applied?.findings ?: all).filter { (SEVERITY_ORDER[it.severity] ?: 9) <= floor })
                val note = if (b != null && applied != null) baselineNote(b, applied, root) else null
                return if (note != null) "$text\n$note" else text
            }
            "droidmap_report" -> return reportMarkdown(g, runChecks(g, cfg), root.fileName?.toString() ?: "project")
            "droidmap_find" -> {
                val q = (args["query"] as? String)?.trim().orEmpty()
                if (q.isEmpty()) throw ToolError("query is required")
                val hits = findNodes(g, q, ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 200))
                if (hits.isEmpty()) return "No node matches '$q'."
                return formatFind(hits)
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
            formatImpact(impact(g, node.id, ((args["depth"] as? Number)?.toInt() ?: 4).coerceIn(1, 10), Calls(built.code)))
        } else formatShow(show(g, node.id))
    }

    private fun error(id: Any?, code: Int, message: String) =
        linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))

    /** The JSON text to answer one JSON-RPC message (or batch) with, or null when it needs no answer (a notification). Any transport uses it. */
    @Synchronized
    fun reply(text: String): String? {
        val reply: Any? = try {
            when (val msg: JsonElement = compactJson.parseToJsonElement(text)) {
                is JsonArray -> msg.mapNotNull { handle(fromJson(it)) }.ifEmpty { null }
                is JsonObject -> handle(fromJson(msg))
                else -> error(null, -32600, "Invalid Request")
            }
        } catch (e: Exception) {
            error(null, -32700, "Parse error")
        }
        return reply?.let { jsonText(it, pretty = false) }
    }

    /** Serves over stdio: one message per line. */
    fun serve(input: java.io.InputStream = System.`in`, output: OutputStream = System.out): Int {
        log.println("droidmap $VERSION MCP server for $root")
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            reply(line)?.let {
                output.write((it + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
        return 0
    }
}
