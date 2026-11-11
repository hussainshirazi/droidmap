package droidmap

import droidmap.extract.moduleId
import java.nio.file.Path

/** Lines changed in one file, on the new side; [whole] for a file that is new. */
class FileChange(val path: String, val ranges: List<IntRange>, val whole: Boolean)

class Changes(val base: String, val files: List<FileChange>, val deleted: List<String>)

private val HUNK = Regex("""^@@ -\S+ \+(\d+)(?:,(\d+))? @@""")

/**
 * The changes in the working tree (committed or not, and new files) since the point where the current
 * branch left [base]: for a pull request, its own changes.
 */
fun readChanges(root: Path, base: String): Changes {
    val from = Git.mergeBase(root, base)
    val diff = Git.diff(root, from) ?: throw UsageError("git diff against $base failed (is $root in a git repository?)")
    val (files, deleted) = parseDiff(diff)
    val untracked = Git.untracked(root).filter { u -> files.none { it.path == u } }.map { FileChange(it, emptyList(), whole = true) }
    return Changes(base, files + untracked, deleted)
}

/** Reads `git diff -U0`: each file's changed line ranges (a deletion counts as the line it was at). */
fun parseDiff(diff: String): Pair<List<FileChange>, List<String>> {
    val ranges = LinkedHashMap<String, MutableList<IntRange>>()
    val whole = HashSet<String>()
    val deleted = ArrayList<String>()
    var old: String? = null
    var current: String? = null
    for (line in diff.lineSequence()) {
        when {
            line.startsWith("diff --git ") -> { old = null; current = null }
            line.startsWith("--- ") -> old = line.removePrefix("--- ").removePrefix("a/")
            line.startsWith("rename to ") -> line.removePrefix("rename to ").let { current = it; ranges.getOrPut(it) { ArrayList() }; whole.add(it) }
            line.startsWith("+++ ") -> {
                val path = line.removePrefix("+++ ")
                if (path == "/dev/null") { old?.let { deleted.add(it) }; current = null }
                else path.removePrefix("b/").let { current = it; ranges.getOrPut(it) { ArrayList() }; if (old == "/dev/null") whole.add(it) }
            }
            line.startsWith("@@") -> {
                val file = current ?: continue
                val m = HUNK.find(line) ?: continue
                val start = m.groupValues[1].toInt()
                val count = m.groupValues[2].ifEmpty { "1" }.toInt()
                ranges.getValue(file).add(if (count == 0) start..start else start until start + count)
            }
        }
    }
    return ranges.map { (path, r) -> FileChange(path, r, path in whole) } to deleted
}

/** What a change touches: the nodes whose code changed, and everything that depends on them. */
class DiffReport(
    val base: String, val files: List<String>, val read: Int, val deleted: List<String>,
    val changed: List<Node>, val affected: List<Dependent>, val modules: List<String>, val findings: List<Finding>,
)

private fun Node.end(): Int = attrs.int("end_line") ?: line!!

/** Whether [a]'s declaration has [b]'s inside it (and is larger). */
private fun contains(a: Node, b: Node) = a.line!! <= b.line!! && b.end() <= a.end() && (a.line != b.line || a.end() != b.end())

/**
 * Maps [changes] onto the graph: for each changed line, the innermost declarations around it changed (a
 * function, not the class around it); a module, an XML destination or a schema file changes with its file.
 * The nodes that depend on a changed one are affected.
 */
fun diffReport(g: Graph, changes: Changes, findings: List<Finding>, read: Set<String>, depth: Int = 4): DiffReport {
    val byFile = changes.files.associateBy { it.path }
    val hit = LinkedHashSet<Node>()
    for ((file, nodes) in g.nodes.values.filter { it.kind != "ref" && it.file in byFile }.groupBy { it.file!! }) {
        val fc = byFile.getValue(file)
        if (fc.whole) { hit += nodes; continue }
        val (spanned, whole) = nodes.partition { it.line != null && it.kind != "module" }
        if (fc.ranges.isNotEmpty()) hit += whole
        for (r in fc.ranges) {
            val around = spanned.filter { it.line!! <= r.last && r.first <= it.end() }
            hit += around.filter { n -> around.none { m -> m !== n && contains(n, m) } }
        }
    }
    val changed = hit.sortedWith(compareBy({ it.kind }, { it.label }))
    val ids = changed.map { it.id }.toHashSet()
    val affected = HashMap<String, Dependent>()
    for (c in changed) for (d in impact(g, c.id, depth).dependents) {
        if (d.id in ids) continue
        val prev = affected[d.id]
        if (prev == null || d.depth < prev.depth) affected[d.id] = d
    }
    // The modules with changed code, then every module that depends on them.
    val modules = LinkedHashSet<String>()
    val todo = ArrayDeque(changed.mapNotNull { it.module }.distinct().sorted().map { moduleId(it) }.filter { it in g.nodes })
    while (todo.isNotEmpty()) {
        val m = todo.removeFirst()
        if (!modules.add(m)) continue
        g.inEdges(m).filter { it.kind == "depends_on" }.forEach { todo.addLast(it.source) }
    }
    val files = changes.files.map { it.path }.sorted()
    return DiffReport(changes.base, files, files.count { it in read }, changes.deleted.sorted(), changed,
        affected.values.sortedWith(compareBy({ it.depth }, { it.kind }, { it.label })), modules.map { g.label(it) },
        findings.filter { it.file in byFile })
}

/** The headings affected nodes are listed under, by node kind; the rest is "Other code". */
private val GROUPS = listOf(
    "Screens" to setOf("screen"),
    "Navigation destinations" to setOf("nav_destination"),
    "ViewModels" to setOf("viewmodel"),
    "Room" to setOf("room_database", "room_entity", "room_dao", "room_migration", "room_schema"),
    "Dependency injection" to setOf("hilt_provider", "hilt_module", "koin_definition", "koin_module", "entry_point", "injectable"),
    "Components" to setOf("component"),
    "Tests" to setOf("test"),
)
private const val SHOWN = 15

private fun names(items: List<String>, code: Boolean): String {
    val shown = items.take(SHOWN).joinToString(", ") { if (code) "`$it`" else it }
    return if (items.size > SHOWN) "$shown and ${items.size - SHOWN} more" else shown
}

/** The report as Markdown (for a pull request comment, starting with [COMMENT_MARKER]) or as plain text. */
fun formatDiff(r: DiffReport, markdown: Boolean): String {
    val b = if (markdown) "**" else ""
    val out = ArrayList<String>()
    if (markdown) out += listOf(COMMENT_MARKER, "### droidmap: what this change touches", "")
    out.add("Compared with ${if (markdown) "`${r.base}`" else r.base}: ${r.files.size} changed ${plural(r.files.size, "file")}, " +
        "${r.read} of them read by droidmap" + (if (r.deleted.isNotEmpty()) ", ${r.deleted.size} deleted" else "") + ".")
    if (r.changed.isEmpty()) {
        out += listOf("", "No Android structure changed: none of the changed lines are in a module, Room class, navigation destination, " +
            "ViewModel or binding droidmap knows.")
    } else {
        val changed = r.changed.map { if (markdown) "`${it.label}` ${it.kind}" else "${it.label} (${it.kind})" }
        out += listOf("", "${b}Changed$b (${r.changed.size}): " + names(changed, code = false))
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (d in r.affected) {
            if (d.kind == "module") continue // listed under Modules
            val group = GROUPS.firstOrNull { d.kind in it.second }?.first ?: "Other code"
            groups.getOrPut(group) { ArrayList() }.add(d.label)
        }
        if (r.modules.isNotEmpty()) groups["Modules"] = r.modules.toMutableList()
        val order = GROUPS.map { it.first } + listOf("Other code", "Modules")
        if (groups.isEmpty()) out += listOf("", "Nothing else in the graph depends on it.")
        else {
            out += listOf("", "${b}Affected$b (what depends on the changed code):", "")
            if (markdown) out += listOf("| | |", "|---|---|")
            for (k in order) groups[k]?.let { items ->
                val list = names(items.distinct().sorted(), markdown)
                out.add(if (markdown) "| $k | $list |" else "  $k: $list")
            }
        }
        val tests = (r.changed.filter { it.kind == "test" }.map { it.label } + r.affected.filter { it.kind == "test" }.map { it.label }).distinct().sorted()
        if (tests.isNotEmpty()) out += listOf("", "${b}Tests that cover it:$b " + names(tests, markdown))
    }
    if (r.findings.isNotEmpty()) {
        out += listOf("", "${b}Findings in the changed files$b (${r.findings.size}):")
        for (f in r.findings.take(SHOWN)) {
            val where = location(f.file, f.line)
            out.add(if (markdown) "- **${f.severity}** `${f.rule}` `$where`: ${f.message}" else "  ${f.severity} ${f.rule} $where\n      ${f.message}")
        }
        if (r.findings.size > SHOWN) out.add("${if (markdown) "- " else "  "}and ${r.findings.size - SHOWN} more")
    }
    if (markdown) out += listOf("", "<sub>droidmap $VERSION: static analysis of the source; it reads no generated code.</sub>")
    return out.joinToString("\n")
}

fun DiffReport.toMap(): Map<String, Any?> = linkedMapOf(
    "base" to base, "files" to files, "read" to read, "deleted" to deleted,
    "changed" to changed.map { linkedMapOf("id" to it.id, "label" to it.label, "kind" to it.kind, "file" to it.file, "line" to it.line) },
    "affected" to affected.map { it.toMap() }, "modules" to modules, "findings" to findings.map { it.toMap() },
)

/** The first line of droidmap's pull request comment, so the GitHub Action can find and update it. */
const val COMMENT_MARKER = "<!-- droidmap -->"
