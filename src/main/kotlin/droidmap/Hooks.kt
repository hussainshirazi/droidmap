package droidmap

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Session files older than this are removed when a session starts. */
private val KEEP_SESSIONS = Duration.ofDays(7)
private const val SHOWN_IN_REASON = 10

/**
 * The Claude Code plugin's hooks: `droidmap hook session-start` and `droidmap hook stop`, with the hook's JSON on stdin
 * (`session_id`, `cwd`, and for Stop `stop_hook_active`). When a session starts, it remembers the project's findings. When Claude
 * is about to stop, it checks again and, if the session added errors, blocks the stop with them as the reason, so Claude fixes
 * them first; if Claude stops again right away, those errors are accepted for the rest of the session. It never gets in the way
 * otherwise: outside an Android project, or on any error, it prints nothing and exits 0.
 */
class Hooks(private val dataDir: Path, private val out: PrintStream, private val err: PrintStream) {
    fun run(event: String, input: String): Int {
        if (event != "session-start" && event != "stop") throw UsageError("hook is session-start or stop")
        try {
            @Suppress("UNCHECKED_CAST")
            val m = fromJson(compactJson.parseToJsonElement(input)) as? Map<String, Any?> ?: return 0
            val root = (m["cwd"] as? String)?.let { findProjectRoot(Path.of(it).toAbsolutePath().normalize()) } ?: return 0
            val session = (m["session_id"] as? String)?.filter { it.isLetterOrDigit() || it == '-' }?.ifEmpty { null } ?: return 0
            val file = dataDir.resolve("sessions").resolve("$session.json")
            when {
                event == "session-start" -> if (!Files.exists(file)) { prune(); Baseline.write(file, findings(root)) }
                m["stop_hook_active"] == true -> Baseline.write(file, findings(root)) // stopped again after a block: accept them
                else -> stop(root, file)
            }
        } catch (e: Exception) {
            err.println("droidmap hook $event: $e")
        }
        return 0
    }

    private fun findings(root: Path): List<Finding> {
        val cfg = Config.load(root)
        return runChecks(buildGraph(Project(root, cfg)).graph, cfg)
    }

    private fun stop(root: Path, file: Path) {
        val now = findings(root)
        if (!Files.exists(file)) return Baseline.write(file, now) // no start was seen: from now on
        val added = Baseline.load(file).apply(now).findings.filter { it.severity == "error" }
        if (added.isEmpty()) return
        val list = added.take(SHOWN_IN_REASON).joinToString("\n") { "- ${it.rule} ${location(it.file, it.line)}: ${it.message}" } +
            if (added.size > SHOWN_IN_REASON) "\n- and ${added.size - SHOWN_IN_REASON} more (droidmap check lists them)" else ""
        val reason = "droidmap: the changes in this session added ${added.size} ${plural(added.size, "error")} that compile but fail at runtime:\n$list\n" +
            "Fix them before finishing (droidmap_explain with the rule says how). If one is wrong, tell the user; do not hide it."
        out.println(jsonText(mapOf("decision" to "block", "reason" to reason), pretty = false))
    }

    private fun prune() {
        val dir = dataDir.resolve("sessions")
        if (!Files.isDirectory(dir)) return
        val old = Instant.now().minus(KEEP_SESSIONS)
        Files.list(dir).use { files -> files.filter { Files.getLastModifiedTime(it).toInstant().isBefore(old) }.forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        /** Where session files are kept: the plugin's data folder that Claude Code gives hooks, or ~/.droidmap/hooks. */
        fun dataDir(): Path = System.getenv("CLAUDE_PLUGIN_DATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".droidmap", "hooks")
    }
}
