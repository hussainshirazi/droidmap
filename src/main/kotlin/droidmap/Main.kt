package droidmap

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

      build  [path] [-o DIR] [--node-link]        scan and write droidmap-out/graph.json and REPORT.md
      check  [path] [--format text|json|sarif] [--fail-on error|warning|info|never] [--sarif FILE]
      impact <node> [path] [--depth N] [--json]   what depends on a node
      show   <node> [path] [--json]                a node, its dependencies and users
      find   <text> [path] [--limit N] [--json]
      report [path] [-o FILE]                      the Markdown report
      rules                                        list the rules and their default severities
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

class Cli(private val out: PrintStream = System.out, private val err: PrintStream = System.err) {
    private fun root(path: String?): Path {
        val r = Path.of(path ?: ".").toAbsolutePath().normalize()
        if (!Files.isDirectory(r)) throw UsageError("$r is not a directory")
        return r
    }

    private fun config(root: Path, a: Args): Config = try {
        Config.load(root, a.opt("--config")?.let { Path.of(it) })
    } catch (e: IllegalArgumentException) {
        throw UsageError("cannot read the config: ${e.message}")
    }

    private fun load(root: Path, a: Args): Pair<Graph, Config> {
        val cfg = config(root, a)
        val saved = a.opt("--graph") ?: return buildGraph(root, cfg).first to cfg
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
        val rest = argv.drop(1)
        val common = setOf("--config", "--graph")
        return when (argv[0]) {
            "build" -> {
                val a = parseArgs(rest, setOf("--config", "--out"), setOf("--node-link"), mapOf("-o" to "--out"))
                val t0 = System.nanoTime()
                val r = root(a.positionals.firstOrNull())
                val cfg = config(r, a)
                val g = buildGraph(r, cfg).first
                val dir = a.opt("--out")?.let { Path.of(it) } ?: r.resolve(OUT_DIR)
                g.save(dir.resolve("graph.json"))
                if (a.flag("--node-link")) Files.writeString(dir.resolve("graph.node-link.json"), jsonText(g.toNodeLink()) + "\n")
                val findings = runChecks(g, cfg)
                Files.writeString(dir.resolve("REPORT.md"), reportMarkdown(g, findings, title(r)) + "\n")
                val ms = (System.nanoTime() - t0) / 1_000_000
                out.println("droidmap $VERSION: ${g.nodes.size} nodes, ${g.edges.size} edges, ${g.unresolved.size} unresolved, " +
                    "${findings.size} findings in $ms ms")
                out.println("Wrote ${dir.resolve("graph.json")} and ${dir.resolve("REPORT.md")}")
                0
            }
            "check" -> {
                val a = parseArgs(rest, common + setOf("--format", "--fail-on", "--sarif"), emptySet())
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
                    else -> out.println(formatFindings(findings))
                }
                a.opt("--sarif")?.let { Files.writeString(Path.of(it), jsonText(toSarif(findings, prefix)) + "\n") }
                when {
                    failOn == "never" -> 0
                    findings.any { (SEVERITY_ORDER[it.severity] ?: 9) <= SEVERITY_ORDER.getValue(failOn) } -> 1
                    else -> 0
                }
            }
            "impact", "show" -> {
                val a = parseArgs(rest, common + setOf("--depth"), setOf("--json"))
                val text = a.positionals.firstOrNull() ?: throw UsageError("${argv[0]} needs a node")
                val (g, _) = load(root(a.positionals.getOrNull(1)), a)
                val node = pick(g, text) ?: return 2
                if (argv[0] == "impact") {
                    val r = impact(g, node.id, a.opt("--depth")?.toIntOrNull() ?: 4)
                    out.println(if (a.flag("--json")) jsonText(r.toMap()) else formatImpact(r))
                } else {
                    val s = show(g, node.id)
                    out.println(if (a.flag("--json")) jsonText(s.toMap()) else formatShow(s))
                }
                0
            }
            "find" -> {
                val a = parseArgs(rest, common + setOf("--limit"), setOf("--json"))
                val text = a.positionals.firstOrNull() ?: throw UsageError("find needs a text")
                val (g, _) = load(root(a.positionals.getOrNull(1)), a)
                val hits = findNodes(g, text, a.opt("--limit")?.toIntOrNull() ?: 20)
                when {
                    a.flag("--json") -> out.println(jsonText(hits.map { Graph.nodeMap(it) }))
                    hits.isEmpty() -> out.println("No matches.")
                    else -> hits.forEach { out.println("${it.id}  [${it.kind}]  ${it.file ?: ""}${it.line?.let { l -> ":$l" } ?: ""}") }
                }
                if (hits.isEmpty()) 1 else 0
            }
            "report" -> {
                val a = parseArgs(rest, common + setOf("--out"), emptySet(), mapOf("-o" to "--out"))
                val r = root(a.positionals.firstOrNull())
                val (g, cfg) = load(r, a)
                val text = reportMarkdown(g, runChecks(g, cfg), title(r))
                val dest = a.opt("--out")
                if (dest != null) Files.writeString(Path.of(dest), text + "\n") else out.println(text)
                0
            }
            "rules" -> {
                RULES.forEach { (id, v) -> out.println("${id.padEnd(28)} ${v.first.padEnd(8)} ${v.second}") }
                0
            }
            "mcp" -> {
                val a = parseArgs(rest, setOf("--config"), emptySet())
                val r = root(a.positionals.firstOrNull())
                // The protocol owns stdout: anything else that prints goes to stderr.
                val protocol = FileOutputStream(FileDescriptor.out)
                System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
                McpServer(r, a.opt("--config")?.let { Path.of(it) }).serve(System.`in`, protocol)
            }
            else -> throw UsageError("unknown command '${argv[0]}'")
        }
    }
}

/** Runs the CLI and turns errors into exit codes: 2 for a usage error, 3 for a bug (never 1, which means findings). */
fun runMain(argv: List<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int = try {
    Cli(out, err).run(argv)
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
    val code = runMain(argv.toList())
    System.out.flush()
    exitProcess(code)
}
