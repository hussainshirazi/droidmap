package droidmap

import java.nio.file.Files
import java.nio.file.Path

/** What `droidmap init` did with one file. */
class InitStep(val file: String, val created: Boolean, val what: String, val snippet: String? = null)

private fun workflow(path: String): String {
    val lines = arrayListOf(
        "name: droidmap",
        "on: [push, pull_request]",
        "",
        "jobs:",
        "  droidmap:",
        "    runs-on: ubuntu-latest",
        "    permissions:",
        "      contents: read",
        "      security-events: write   # findings as code scanning alerts",
        "      pull-requests: write     # the comment that says what the pull request touches",
        "    steps:",
        "      - uses: actions/checkout@v4",
        "      - uses: hussainshirazi/droidmap@v0",
    )
    val hint = "upload-sarif: \"false\"   # for a private repository without GitHub code scanning"
    lines += if (path == ".") listOf("        # with:", "        #   $hint") else listOf("        with:", "          path: $path", "          # $hint")
    return lines.joinToString("\n") + "\n"
}

/** The MCP server entry the agents' config files share; VS Code calls the top key "servers" and wants a type. */
private fun mcpConfig(path: String, vscode: Boolean): String = buildList {
    add("{")
    add("  \"${if (vscode) "servers" else "mcpServers"}\": {")
    add("    \"droidmap\": {")
    if (vscode) add("      \"type\": \"stdio\",")
    add("      \"command\": \"droidmap\",")
    add("      \"args\": [\"mcp\", \"$path\"]")
    add("    }")
    add("  }")
    add("}")
}.joinToString("\n") + "\n"

/**
 * Writes the files that connect droidmap to [project]: a GitHub workflow, and the MCP configuration for
 * Claude Code (`.mcp.json`) and, when their folder exists, Cursor and VS Code. They go to [repo] (the git
 * root, or the project itself). A file that already exists is never changed: its step carries the text to add.
 */
fun initProject(project: Path, repo: Path): List<InitStep> {
    val path = if (project == repo) "." else repo.relativize(project).joinToString("/")
    val steps = ArrayList<InitStep>()
    fun write(rel: String, text: String, what: String, snippetWhenExists: Boolean) {
        val file = repo.resolve(rel)
        if (Files.exists(file)) {
            val mentionsDroidmap = "droidmap" in Files.readString(file)
            steps.add(InitStep(rel, created = false, what = what, snippet = if (snippetWhenExists && !mentionsDroidmap) text else null))
        } else {
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
            steps.add(InitStep(rel, created = true, what = what))
        }
    }
    write(".github/workflows/droidmap.yml", workflow(path), "checks every push and comments on pull requests", snippetWhenExists = false)
    write(".mcp.json", mcpConfig(path, vscode = false), "lets Claude Code ask droidmap about this project", snippetWhenExists = true)
    if (Files.isDirectory(repo.resolve(".cursor"))) {
        write(".cursor/mcp.json", mcpConfig(path, vscode = false), "the same for Cursor", snippetWhenExists = true)
    }
    if (Files.isDirectory(repo.resolve(".vscode"))) {
        write(".vscode/mcp.json", mcpConfig(path, vscode = true), "the same for VS Code", snippetWhenExists = true)
    }
    return steps
}

fun formatInit(steps: List<InitStep>, style: Style = Style.PLAIN): String {
    val width = steps.maxOf { it.file.length }
    val lines = ArrayList<String>()
    for (s in steps) {
        lines.add("${if (s.created) style.green("created") else style.dim("exists ")} ${s.file.padEnd(width)}  ${s.what}")
    }
    for (s in steps.filter { it.snippet != null }) {
        lines += listOf("", "${s.file} already exists, so droidmap left it alone. Add this to it:", "", s.snippet!!.trimEnd())
    }
    lines += listOf("", "Commit these files so the team and CI get the same setup. Try it now: droidmap check")
    return lines.joinToString("\n")
}
