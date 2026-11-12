package droidmap

import droidmap.extract.moduleId
import java.nio.file.Path

/**
 * Lines changed in one file, on the new side; [whole] for a file that is new. [old] is its path at the base
 * (null for a new file) and [removed] the lines there that the change deleted or replaced.
 */
class FileChange(val path: String, val ranges: List<IntRange>, val whole: Boolean, val old: String? = path, val removed: List<IntRange> = emptyList())

/** The changes since [base]; [from] is the commit they are compared with (where the branch left [base]). */
class Changes(val base: String, val files: List<FileChange>, val deleted: List<String>, val from: String = base)

private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

/**
 * The changes in the working tree (committed or not, and new files) since the point where the current
 * branch left [base]: for a pull request, its own changes.
 */
fun readChanges(root: Path, base: String): Changes {
    val from = Git.mergeBase(root, base)
    val diff = Git.diff(root, from) ?: throw UsageError("git diff against $base failed (is $root in a git repository?)")
    val (files, deleted) = parseDiff(diff)
    val untracked = Git.untracked(root).filter { u -> files.none { it.path == u } }.map { FileChange(it, emptyList(), whole = true, old = null) }
    return Changes(base, files + untracked, deleted, from)
}

/** Reads `git diff -U0`: each file's changed line ranges (a deletion counts as the line it was at), and the lines removed at the base. */
fun parseDiff(diff: String): Pair<List<FileChange>, List<String>> {
    val ranges = LinkedHashMap<String, MutableList<IntRange>>()
    val removed = HashMap<String, MutableList<IntRange>>()
    val olds = HashMap<String, String?>()
    val whole = HashSet<String>()
    val deleted = ArrayList<String>()
    var old: String? = null
    var current: String? = null
    for (line in diff.lineSequence()) {
        when {
            line.startsWith("diff --git ") -> { old = null; current = null }
            line.startsWith("--- ") -> old = line.removePrefix("--- ").removePrefix("a/")
            line.startsWith("rename from ") -> old = line.removePrefix("rename from ")
            line.startsWith("rename to ") -> line.removePrefix("rename to ").let { current = it; ranges.getOrPut(it) { ArrayList() }; whole.add(it); olds[it] = old }
            line.startsWith("+++ ") -> {
                val path = line.removePrefix("+++ ")
                if (path == "/dev/null") { old?.let { deleted.add(it) }; current = null }
                else path.removePrefix("b/").let {
                    current = it
                    ranges.getOrPut(it) { ArrayList() }
                    if (old == "/dev/null") whole.add(it)
                    olds[it] = old?.takeIf { o -> o != "/dev/null" }
                }
            }
            line.startsWith("@@") -> {
                val file = current ?: continue
                val m = HUNK.find(line) ?: continue
                val oldStart = m.groupValues[1].toInt()
                val oldCount = m.groupValues[2].ifEmpty { "1" }.toInt()
                if (oldCount > 0) removed.getOrPut(file) { ArrayList() }.add(oldStart until oldStart + oldCount)
                val start = m.groupValues[3].toInt()
                val count = m.groupValues[4].ifEmpty { "1" }.toInt()
                ranges.getValue(file).add(if (count == 0) start..start else start until start + count)
            }
        }
    }
    return ranges.map { (path, r) -> FileChange(path, r, path in whole, if (path in olds) olds[path] else path, removed[path] ?: emptyList()) } to deleted
}

/**
 * The project as it was at [Changes.from], when the change removed lines from a file droidmap reads (or
 * deleted one): the current files with the changed ones put back as they were. Files the change did not
 * touch are not parsed again. Null when nothing was removed.
 */
fun baseGraph(built: Built, changes: Changes): Graph? {
    val p = built.project
    val root = p.root
    val gone = changes.deleted.filter(p::reads)
    if (changes.files.none { it.removed.isNotEmpty() && it.old?.let(p::reads) == true } && gone.isEmpty()) return null
    val overlay = HashMap<String, String?>()
    for (f in changes.files) {
        if (!p.reads(f.path) && f.old?.let(p::reads) != true) continue
        overlay[f.path] = null // a new file was not there; a renamed one is back at its old path
        f.old?.let { overlay[it] = Git.show(root, changes.from, it) }
    }
    for (d in gone) overlay[d] = Git.show(root, changes.from, d)
    return buildGraph(Project(root, p.config, overlay), previous = built.code).graph
}

/** Files whose change can affect every module: the build setup rather than one module's code. */
private val BUILD_SETUP = Regex("""^(settings\.gradle(\.kts)?|build\.gradle(\.kts)?|gradle\.properties|gradle/.+|buildSrc/.+|build-logic/.+)$""")

/**
 * The modules whose tests a change can break, sorted: each module with a changed (or deleted) file, and every
 * module that depends on one of them, test dependencies included. A change to the build setup (settings, the
 * root build file, the version catalog, convention plugins) affects every module. Only [g]'s modules are needed.
 */
fun affectedModules(g: Graph, p: Project, changes: Changes): List<String> {
    // The root project counts only when it is a module with code (it applies a plugin), not a folder of settings and docs.
    val isCode = { m: String -> m != ":" || g.nodes[moduleId(":")]?.attrs?.strings("plugins")?.isNotEmpty() == true }
    val all = g.byKind("module").filter { !it.attrs.bool("missing") }.map { it.label }.filter(isCode)
    val paths = changes.files.map { it.path } + changes.deleted
    if (paths.any { BUILD_SETUP.matches(it) || p.moduleOf(it)?.startsWith("[") == true }) return all.sorted()
    // A module that uses a changed one only in its tests has its tests affected, but its own code is unchanged,
    // so what depends on it is not.
    val reached = HashMap<String, Boolean>() // module id -> whether its code is affected
    val todo = ArrayDeque(paths.mapNotNull { p.moduleOf(it) }.filter(isCode).distinct().map { moduleId(it) to true })
    while (todo.isNotEmpty()) {
        val (m, code) = todo.removeFirst()
        if (m !in g.nodes || reached[m] == true || (reached[m] == false && !code)) continue
        reached[m] = code
        if (code) g.inEdges(m).filter { it.kind == "depends_on" }.forEach { todo.addLast(it.source to !it.attrs.bool("test")) }
    }
    return reached.keys.map { g.label(it) }.filter { it in all }.sorted()
}

/** What a change touches: the nodes whose code changed, and everything that depends on them. */
class DiffReport(
    val base: String, val files: List<String>, val read: Int, val deleted: List<String>,
    val changed: List<Node>, val affected: List<Dependent>, val modules: List<String>, val findings: List<Finding>,
    val removed: List<Node> = emptyList(),
)

private fun Node.end(): Int = attrs.int("end_line") ?: line!!

/** Whether [a]'s declaration has [b]'s inside it (and is larger). */
private fun contains(a: Node, b: Node) = a.line!! <= b.line!! && b.end() <= a.end() && (a.line != b.line || a.end() != b.end())

/**
 * Maps [changes] onto the graph: for each changed line, the innermost declarations around it changed (a
 * function, not the class around it); a module, an XML destination or a schema file changes with its file.
 * The nodes that depend on a changed one are affected. With [base] (the graph at the base, see [baseGraph]),
 * the declarations the change removed are listed too, and what depended on them is affected.
 */
fun diffReport(g: Graph, changes: Changes, findings: List<Finding>, read: Set<String>, depth: Int = 4, base: Graph? = null): DiffReport {
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
    val removed = if (base == null) emptyList() else removedNodes(g, base, changes)
    val ids = (changed + removed).map { it.id }.toHashSet()
    val affected = HashMap<String, Dependent>()
    fun add(d: Dependent) {
        if (d.id in ids) return
        val prev = affected[d.id]
        if (prev == null || d.depth < prev.depth) affected[d.id] = d
    }
    for (c in changed) impact(g, c.id, depth).dependents.forEach(::add)
    // What used a removed declaration, and is still there.
    for (r in removed) impact(base!!, r.id, depth).dependents.filter { it.id in g.nodes }.forEach(::add)
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
        findings.filter { it.file in byFile }, removed)
}

/**
 * The declarations in [base] that the change removed: in the lines it deleted (or in a deleted file), and
 * no longer in [g]. One inside another removed one (a function of a removed class) is left out.
 */
private fun removedNodes(g: Graph, base: Graph, changes: Changes): List<Node> {
    val lines = HashMap<String, List<IntRange>>()
    for (f in changes.files) if (f.old != null && f.removed.isNotEmpty()) lines[f.old] = f.removed
    val gone = changes.deleted.toHashSet()
    val out = base.nodes.values.filter { n ->
        val file = n.file ?: return@filter false
        // Still declared (a node without a file is only something the code refers to).
        if (n.kind == "ref" || g.nodes[n.id]?.file != null) return@filter false
        if (file in gone) return@filter true
        val rs = lines[file] ?: return@filter false
        val line = n.line ?: return@filter true // an XML destination has no line
        rs.any { r -> line <= r.last && r.first <= n.end() }
    }
    return out.filter { n -> out.none { m -> m !== n && m.file == n.file && m.line != null && n.line != null && contains(m, n) } }
        .sortedWith(compareBy({ it.kind }, { it.label }))
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
    if (r.changed.isEmpty() && r.removed.isEmpty()) {
        out += listOf("", "No Android structure changed: none of the changed lines are in a module, Room class, navigation destination, " +
            "ViewModel or binding droidmap knows.")
    } else {
        fun list(ns: List<Node>) = names(ns.map { if (markdown) "`${it.label}` ${it.kind}" else "${it.label} (${it.kind})" }, code = false)
        if (r.changed.isNotEmpty()) out += listOf("", "${b}Changed$b (${r.changed.size}): " + list(r.changed))
        if (r.removed.isNotEmpty()) out += listOf("", "${b}Removed$b (${r.removed.size}): " + list(r.removed))
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
            out += listOf("", "${b}Affected$b (what depends on the changed${if (r.removed.isNotEmpty()) " or removed" else ""} code):", "")
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
            val rule = RULES[f.rule]?.let { "[`${f.rule}`](${it.url})" } ?: "`${f.rule}`"
            out.add(if (markdown) "- **${f.severity}** $rule `$where`: ${f.message}" else "  ${f.severity} ${f.rule} $where\n      ${f.message}")
        }
        if (r.findings.size > SHOWN) out.add("${if (markdown) "- " else "  "}and ${r.findings.size - SHOWN} more")
    }
    if (markdown) out += listOf("", "<sub>droidmap $VERSION: static analysis of the source; it reads no generated code.</sub>")
    return out.joinToString("\n")
}

fun DiffReport.toMap(): Map<String, Any?> = linkedMapOf(
    "base" to base, "files" to files, "read" to read, "deleted" to deleted,
    "changed" to changed.map { linkedMapOf("id" to it.id, "label" to it.label, "kind" to it.kind, "file" to it.file, "line" to it.line) },
    "removed" to removed.map { linkedMapOf("id" to it.id, "label" to it.label, "kind" to it.kind, "file" to it.file, "line" to it.line) },
    "affected" to affected.map { it.toMap() }, "modules" to modules, "findings" to findings.map { it.toMap() },
)

/** The first line of droidmap's pull request comment, so the GitHub Action can find and update it. */
const val COMMENT_MARKER = "<!-- droidmap -->"
