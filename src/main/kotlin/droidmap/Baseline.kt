package droidmap

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The file `check --update-baseline` writes at the project root, and that check, diff and the MCP server then read. */
const val BASELINE_NAME = "droidmap-baseline.json"

/**
 * What identifies a finding from one run to the next: its rule, its file and what it is about (the item
 * [Finding.key] names, or else its node, or else its message). Not its line, so a finding stays the same
 * when code above it moves, and not the message when there is a node, so a detail such as a count can change.
 */
fun identity(f: Finding): List<String> = listOf(f.rule, f.file ?: "", f.key ?: f.node ?: f.message)

/** A short stable hash of [identity], for SARIF's partialFingerprints (GitHub follows an alert across commits with it). */
fun fingerprint(f: Finding): String =
    MessageDigest.getInstance("SHA-256").digest(identity(f).joinToString("\u0000").toByteArray()).take(16).joinToString("") { "%02x".format(it) }

/**
 * Findings a project accepts for now (`droidmap check --update-baseline`), so that checks report only new ones:
 * the way to adopt droidmap on a project that already has findings.
 */
class Baseline(val path: Path, private val entries: List<List<String>>) {
    /** [findings] without those in the baseline, each entry accounting for one finding; [fixed] counts entries nothing matched. */
    class Applied(val findings: List<Finding>, val hidden: Int, val fixed: Int)

    fun apply(findings: List<Finding>): Applied {
        val left = HashMap<List<String>, Int>()
        for (e in entries) left.merge(e, 1, Int::plus)
        val shown = ArrayList<Finding>()
        for (f in findings) {
            val id = identity(f)
            val n = left[id] ?: 0
            if (n > 0) left[id] = n - 1 else shown.add(f)
        }
        return Applied(shown, findings.size - shown.size, left.values.sum())
    }

    companion object {
        private const val FORMAT = 1

        /** Writes [findings] as the baseline at [path], sorted so that its changes read well in a diff. */
        fun write(path: Path, findings: List<Finding>) {
            val items = findings.map { f ->
                val (rule, file, key) = identity(f)
                linkedMapOf("rule" to rule, "file" to file.ifEmpty { null }, "key" to key, "message" to f.message)
            }.sortedWith(compareBy({ it["rule"] }, { it["file"] ?: "" }, { it["key"] }))
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(path, jsonText(linkedMapOf("droidmap_baseline" to FORMAT, "version" to VERSION, "findings" to items)) + "\n")
        }

        /** Reads a baseline file; IllegalArgumentException when it is not one. */
        @Suppress("UNCHECKED_CAST")
        fun load(path: Path): Baseline {
            val root = try {
                fromJson(compactJson.parseToJsonElement(Files.readString(path))) as? Map<String, Any?>
            } catch (e: Exception) {
                null
            }
            require(root != null && root["droidmap_baseline"] is Number) { "$path is not a droidmap baseline file" }
            require(root.int("droidmap_baseline")!! <= FORMAT) { "$path was written by a newer droidmap: update droidmap" }
            val entries = root.maps("findings").map { e ->
                val rule = e.str("rule")
                val key = e.str("key")
                require(rule != null && key != null) { "$path: every finding needs a rule and a key" }
                listOf(rule, e.str("file") ?: "", key)
            }
            return Baseline(path, entries)
        }

        /** [explicit] (which must exist), or else the baseline file at the project root when there is one. */
        fun find(root: Path, explicit: Path? = null): Baseline? {
            if (explicit != null) {
                require(Files.isRegularFile(explicit)) { "$explicit does not exist (droidmap check --update-baseline writes it)" }
                return load(explicit)
            }
            val default = root.resolve(BASELINE_NAME)
            return if (Files.isRegularFile(default)) load(default) else null
        }
    }
}

/** The line check prints under its findings when a baseline left some out. */
fun baselineNote(b: Baseline, a: Baseline.Applied, root: Path): String? {
    val name = runCatching { root.relativize(b.path.toAbsolutePath().normalize()).toString().replace('\\', '/') }.getOrNull() ?: b.path.toString()
    val parts = ArrayList<String>()
    if (a.hidden > 0) parts.add("${a.hidden} known ${plural(a.hidden, "finding")} in $name ${if (a.hidden == 1) "is" else "are"} not shown")
    if (a.fixed > 0) parts.add("${a.fixed} ${plural(a.fixed, "finding")} in it ${if (a.fixed == 1) "is" else "are"} fixed: `droidmap check --update-baseline` drops ${if (a.fixed == 1) "it" else "them"}")
    return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "$it." }
}
