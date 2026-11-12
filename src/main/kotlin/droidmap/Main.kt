package droidmap

import droidmap.extract.GradleScan
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

const val OUT_DIR = "droidmap-out"

private val USAGE = """
    droidmap $VERSION: an Android-aware project graph for developers and AI agents.

    Usage: droidmap <command> [options]
    Start here, from anywhere inside your project: `droidmap init` sets up CI and AI agents,
    `droidmap check` finds problems, `droidmap diff` shows what your changes touch.

      init   [path]                                write the GitHub workflow and MCP config (never overwrites)
      build  [path] [-o DIR] [--node-link]        scan and write droidmap-out/graph.json and REPORT.md
      check  [path] [--format text|json|sarif] [--fail-on error|warning|info|never] [--sarif FILE]
             [--base REF]                           also compare Room schema files with a git commit
      impact <node> [path] [--depth N] [--json]   what depends on a node
      show   <node> [path] [--json]                a node, its dependencies and users
      find   <text> [path] [--limit N] [--json]
      diff   [path] [--base REF] [--depth N] [--format text|markdown|json|modules|tasks] [--task NAME]
                                                   what a change touches (default: uncommitted changes); modules and
                                                   tasks list the modules whose tests it can break, for CI
      report [path] [-o FILE]                      the Markdown report, with module and screen diagrams
      rules                                        list the rules and their default severities
      explain <rule>                               why a rule matters and how to fix what it finds
      mcp    [path]                                run as an MCP server over stdio

    Common options: --config FILE (default <path>/.droidmap.toml), --graph FILE (use a saved graph.json)

    Exit codes: 0 done, 1 findings at --fail-on or above (check) or no match (find),
    2 usage error, 3 internal error.
""".trimIndent()

class UsageError(message: String) : Exception(message)

class Args(val positionals: List<String>, private val opts: Map<String, String>, private val flags: Set<String>) {
    fun opt(name: String): String? = opts[name]
    fun flag(name: String) = name in flags
}

fun parseArgs(argv: List<String>, valued: Set<String>, flagNames: Set<String>, aliases: Map<String, String> = emptyMap()): Args {
    val pos = ArrayList<String>()
    val opts = HashMap<String, String>()
    val flags = HashSet<String>()
    var i = 0
    while (i < argv.size) {
        val a = aliases[argv[i]] ?: argv[i]
        when {
            a.startsWith("--") && '=' in a -> {
                val (k, v) = a.split('=', limit = 2)
                if (k !in valued) throw UsageError("unknown option $k")
                opts[k] = v
            }
            a in valued -> {
                if (i + 1 >= argv.size) throw UsageError("$a needs a value")
                opts[a] = argv[++i]
            }
            a in flagNames -> flags.add(a)
            a.startsWith("-") && a.length > 1 -> throw UsageError("unknown option $a")
            else -> pos.add(a)
        }
        i++
    }
    return Args(pos, opts, flags)
}

private fun hasSettings(dir: Path) = Files.isRegularFile(dir.resolve("settings.gradle.kts")) || Files.isRegularFile(dir.resolve("settings.gradle"))

/** The folder of the nearest settings.gradle(.kts) at or above [from], so droidmap works from any folder inside a project. */
fun findProjectRoot(from: Path): Path? = generateSequence(from) { it.parent }.firstOrNull { hasSettings(it) }

class Cli(private val out: PrintStream = System.out, private val err: PrintStream = System.err, private val style: Style = Style.PLAIN) {
    /** The project folder: [path] as given, or (without one) the nearest Gradle project at or above the current folder. */
    private fun root(path: String?): Path {
        val given = Path.of(path ?: ".").toAbsolutePath().normalize()
        if (!Files.isDirectory(given)) throw UsageError("$given is not a directory")
        if (hasSettings(given)) return given
        if (path != null) {
            err.println("droidmap: warning: $given has no settings.gradle(.kts); is it the project's root folder?")
            return given
        }
        return findProjectRoot(given)
            ?: throw UsageError("$given is not inside a Gradle project (no settings.gradle(.kts) here or above): " +
                "run droidmap from your Android project's root folder, or pass its path")
    }

    private fun config(root: Path, a: Args): Config = try {
        Config.load(root, a.opt("--config")?.let { Path.of(it) })
    } catch (e: IllegalArgumentException) {
        throw UsageError("cannot read the config: ${e.message}")
    }

    /** --base REF, checked to be a commit. */
    private fun base(root: Path, a: Args): String? = a.opt("--base")?.also {
        if (!Git.isCommit(root, it)) throw UsageError("--base $it is not a commit in the git repository of $root (is it fetched?)")
    }

    private fun load(root: Path, a: Args): Pair<Graph, Config> {
        val cfg = config(root, a)
        val base = base(root, a)
        val saved = a.opt("--graph") ?: return buildGraph(root, cfg, base) to cfg
        if (base != null) throw UsageError("--base compares source files, so it cannot be used with --graph")
        val g = try {
            Graph.load(Path.of(saved))
        } catch (e: Exception) {
            throw UsageError("cannot read the graph $saved: ${e.message ?: e}")
        }
        return g to cfg
    }

    private fun pick(g: Graph, text: String): Node? {
        val (node, hits) = resolveOne(g, text)
        if (node == null) {
            if (hits.isEmpty()) err.println("No node matches '$text'. Try `droidmap find`.")
            else {
                err.println("'$text' matches ${hits.size} nodes; use one of these ids:")
                hits.forEach { err.println("  ${it.id}  [${it.kind}]") }
            }
        }
        return node
    }

    private fun title(r: Path) = r.fileName?.toString() ?: "project"

    /** "android/" when the project root is the android folder under the current directory; "" otherwise. */
    private fun sarifPrefix(root: Path): String {
        val cwd = Path.of("").toAbsolutePath().normalize()
        if (!root.startsWith(cwd) || root == cwd) return ""
        return cwd.relativize(root).joinToString("/") + "/"
    }

    fun run(argv: List<String>): Int {
        if (argv.isEmpty() || argv[0] in setOf("-h", "--help", "help")) {
            out.println(USAGE)
            return if (argv.isEmpty()) 2 else 0
        }
        if (argv[0] == "--version") {
            out.println("droidmap $VERSION")
            return 0
        }
        val command = COMMANDS[argv[0]] ?: throw UsageError("unknown command '${argv[0]}'")
        return command(this, argv.drop(1))
    }

    private fun build(rest: List<String>): Int {
        val a = parseArgs(rest, setOf("--config", "--out", "--base"), setOf("--node-link"), mapOf("-o" to "--out"))
        val t0 = System.nanoTime()
        val r = root(a.positionals.firstOrNull())
        val cfg = config(r, a)
        val g = buildGraph(r, cfg, base(r, a))
        val dir = a.opt("--out")?.let { Path.of(it) } ?: r.resolve(OUT_DIR)
        g.save(dir.resolve("graph.json"))
        if (a.flag("--node-link")) Files.writeString(dir.resolve("graph.node-link.json"), jsonText(g.toNodeLink()) + "\n")
        val findings = runChecks(g, cfg)
        Files.writeString(dir.resolve("REPORT.md"), reportMarkdown(g, findings, title(r)) + "\n")
        val ms = (System.nanoTime() - t0) / 1_000_000
        out.println("droidmap $VERSION: ${g.nodes.size} nodes, ${g.edges.size} edges, ${g.unresolved.size} unresolved, " +
            "${findings.size} findings in $ms ms")
        out.println("Wrote ${dir.resolve("graph.json")} and ${dir.resolve("REPORT.md")}")
        return 0
    }

    private fun check(rest: List<String>): Int {
        val a = parseArgs(rest, COMMON + setOf("--format", "--fail-on", "--sarif", "--base"), emptySet())
        val format = a.opt("--format") ?: "text"
        val failOn = a.opt("--fail-on") ?: "error"
        if (format !in setOf("text", "json", "sarif")) throw UsageError("--format is text, json or sarif")
        if (failOn != "never" && failOn !in SEVERITY_ORDER) throw UsageError("--fail-on is error, warning, info or never")
        val r = root(a.positionals.firstOrNull())
        val (g, cfg) = load(r, a)
        val findings = runChecks(g, cfg)
        // SARIF paths are relative to where droidmap runs (the repository root in CI), so a
        // project in a subfolder (droidmap check android) still points at the right files.
        val prefix = sarifPrefix(r)
        when (format) {
            "json" -> out.println(jsonText(findings.map { it.toMap() }))
            "sarif" -> out.println(jsonText(toSarif(findings, prefix)))
            else -> out.println(formatFindings(findings, style))
        }
        a.opt("--sarif")?.let { Files.writeString(Path.of(it), jsonText(toSarif(findings, prefix)) + "\n") }
        val floor = SEVERITY_ORDER[failOn] ?: return 0
        return if (findings.any { (SEVERITY_ORDER[it.severity] ?: 9) <= floor }) 1 else 0
    }

    private fun impactOrShow(rest: List<String>, impact: Boolean): Int {
        val a = parseArgs(rest, COMMON + setOf("--depth"), setOf("--json"))
        val text = a.positionals.firstOrNull() ?: throw UsageError("${if (impact) "impact" else "show"} needs a node")
        val (g, _) = load(root(a.positionals.getOrNull(1)), a)
        val node = pick(g, text) ?: return 2
        if (impact) {
            val r = impact(g, node.id, a.opt("--depth")?.toIntOrNull() ?: 4)
            out.println(if (a.flag("--json")) jsonText(r.toMap()) else formatImpact(r, style = style))
        } else {
            val s = show(g, node.id)
            out.println(if (a.flag("--json")) jsonText(s.toMap()) else formatShow(s, style))
        }
        return 0
    }

    private fun find(rest: List<String>): Int {
        val a = parseArgs(rest, COMMON + setOf("--limit"), setOf("--json"))
        val text = a.positionals.firstOrNull() ?: throw UsageError("find needs a text")
        val (g, _) = load(root(a.positionals.getOrNull(1)), a)
        val hits = findNodes(g, text, a.opt("--limit")?.toIntOrNull() ?: 20)
        when {
            a.flag("--json") -> out.println(jsonText(hits.map { Graph.nodeMap(it) }))
            hits.isEmpty() -> out.println("No matches.")
            else -> out.println(formatFind(hits, style))
        }
        return if (hits.isEmpty()) 1 else 0
    }

    private fun diff(rest: List<String>): Int {
        val a = parseArgs(rest, setOf("--config", "--base", "--depth", "--format", "--task"), emptySet())
        val format = a.opt("--format") ?: "text"
        if (format !in setOf("text", "markdown", "json", "modules", "tasks")) throw UsageError("--format is text, markdown, json, modules or tasks")
        val r = root(a.positionals.firstOrNull())
        val cfg = config(r, a)
        val base = base(r, a) ?: "HEAD".also { if (!Git.isCommit(r, it)) throw UsageError("$r is not in a git repository with a commit") }
        if (format == "modules" || format == "tasks") {
            // Only the module graph is needed: no source file is parsed.
            val p = Project(r, cfg)
            val g = Graph().also { GradleScan(p, it).run() }
            val task = a.opt("--task") ?: "test"
            affectedModules(g, p, readChanges(r, base)).forEach { m ->
                out.println(if (format == "modules") m else "${if (m == ":") "" else m}:$task")
            }
            return 0
        }
        val built = buildGraph(Project(r, cfg))
        val changes = readChanges(r, base)
        val report = diffReport(built.graph, changes, runChecks(built.graph, cfg), built.project.readPaths(),
            a.opt("--depth")?.toIntOrNull() ?: 4, baseGraph(built, changes))
        out.println(if (format == "json") jsonText(report.toMap()) else formatDiff(report, markdown = format == "markdown"))
        return 0
    }

    private fun report(rest: List<String>): Int {
        val a = parseArgs(rest, COMMON + setOf("--out"), emptySet(), mapOf("-o" to "--out"))
        val r = root(a.positionals.firstOrNull())
        val (g, cfg) = load(r, a)
        val text = reportMarkdown(g, runChecks(g, cfg), title(r))
        val dest = a.opt("--out")
        if (dest != null) Files.writeString(Path.of(dest), text + "\n") else out.println(text)
        return 0
    }

    private fun rules(): Int {
        RULES.values.forEach { out.println("${style.bold(it.id.padEnd(28))} ${style.severity(it.severity, it.severity.padEnd(8))} ${it.description}") }
        return 0
    }

    private fun explain(rest: List<String>): Int {
        val id = parseArgs(rest, emptySet(), emptySet()).positionals.firstOrNull() ?: throw UsageError("explain needs a rule (`droidmap rules` lists them)")
        out.println(explainRule(id) ?: throw UsageError("there is no rule \"$id\" (`droidmap rules` lists them)"))
        return 0
    }

    private fun init(rest: List<String>): Int {
        val a = parseArgs(rest, emptySet(), emptySet())
        val project = root(a.positionals.firstOrNull())
        val repo = Git.topLevel(project)?.takeIf { project.startsWith(it) } ?: project
        out.println(formatInit(initProject(project, repo), style))
        return 0
    }

    private fun mcp(rest: List<String>): Int {
        val a = parseArgs(rest, setOf("--config"), emptySet())
        val r = root(a.positionals.firstOrNull())
        // The protocol owns stdout: anything else that prints goes to stderr.
        val protocol = FileOutputStream(FileDescriptor.out)
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
        return McpServer(r, a.opt("--config")?.let { Path.of(it) }).serve(System.`in`, protocol)
    }

    private companion object {
        val COMMON = setOf("--config", "--graph")
        val COMMANDS: Map<String, Cli.(List<String>) -> Int> = mapOf(
            "init" to { init(it) }, "build" to { build(it) }, "check" to { check(it) }, "impact" to { impactOrShow(it, impact = true) },
            "show" to { impactOrShow(it, impact = false) }, "find" to { find(it) }, "diff" to { diff(it) },
            "report" to { report(it) }, "rules" to { rules() }, "explain" to { explain(it) }, "mcp" to { mcp(it) },
        )
    }
}

/** Runs the CLI and turns errors into exit codes: 2 for a usage error, 3 for a bug (never 1, which means findings). */
fun runMain(argv: List<String>, out: PrintStream = System.out, err: PrintStream = System.err, style: Style = Style.PLAIN): Int = try {
    Cli(out, err, style).run(argv)
} catch (e: UsageError) {
    err.println("droidmap: ${e.message}")
    err.println("Run `droidmap --help` for usage.")
    2
} catch (e: Exception) {
    err.println("droidmap: internal error, please report it with the trace below")
    e.printStackTrace(err)
    3
}

fun main(argv: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    val code = runMain(argv.toList(), style = Style.detect())
    System.out.flush()
    exitProcess(code)
}
