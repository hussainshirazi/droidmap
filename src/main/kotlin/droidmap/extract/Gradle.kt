package droidmap.extract

import droidmap.Graph
import droidmap.Project
import org.tomlj.Toml
import org.tomlj.TomlTable

fun moduleId(path: String) = "module::$path"

private val WRAPPERS = setOf("platform", "enforcedPlatform", "testFixtures", "project")
private val NOT_CONFIGS = setOf(
    "evaluationDependsOn", "include", "includeBuild", "findProject", "file", "files", "rootProject",
    "subprojects", "allprojects", "configure", "apply", "dependencies",
)

private fun camel(segment: String): String {
    val parts = segment.split('-', '_')
    return parts[0] + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}

private fun chain(toks: List<Tok>, start: Int): Pair<List<String>, Int> {
    val parts = ArrayList<String>()
    var k = start
    if (k < toks.size && toks[k].kind == 'i') {
        parts.add(toks[k].text)
        k++
        while (k + 1 < toks.size && toks[k].isOp(".") && toks[k + 1].kind == 'i') {
            parts.add(toks[k + 1].text)
            k += 2
        }
    }
    return parts to k
}

class ProjectRef(val configuration: String, val path: String, val line: Int)

/** Gradle: modules from settings, module dependencies, plugins, and what convention plugins add. */
class GradleScan(private val p: Project, private val g: Graph) {
    val modules = LinkedHashMap<String, String>() // path -> dir
    val includedBuilds = ArrayList<String>()
    private val accessors = HashMap<List<String>, String>()
    private val catalogPlugins = HashMap<String, String>() // "a.b.c" accessor -> plugin id
    private class Convention(val deps: List<ProjectRef>, val applies: List<String>, val file: String)
    private val convention = HashMap<String, Convention>()

    fun run() {
        readSettings()
        readCatalogs()
        readConventionPlugins()
        buildModules()
        g.meta["included_builds"] = includedBuilds
    }

    // ----- settings -----
    private fun readSettings() {
        modules[":"] = ""
        val overrides = HashMap<String, String>()
        for (rel in p.gradlePaths.filter { it == "settings.gradle.kts" || it == "settings.gradle" }) {
            val toks = GradleLexer.lex(p.read(rel))
            val m = GradleLexer.matchBrackets(toks)
            val n = toks.size
            for (k in toks.indices) {
                val t = toks[k]
                val afterDot = k > 0 && toks[k - 1].isOp(".")
                if (t.isId("include") && k + 1 < n && !afterDot) {
                    val names = ArrayList<String>()
                    if (toks[k + 1].isOp("(")) {
                        for (j in k + 2 until minOf(m[k + 1], n)) toks[j].plainString?.let { names.add(it) }
                    } else {
                        var j = k + 1
                        while (j < n && toks[j].plainString != null) {
                            names.add(toks[j].plainString!!)
                            if (j + 1 < n && toks[j + 1].isOp(",")) j += 2 else break
                        }
                    }
                    for (s in names) {
                        val path = if (s.startsWith(":")) s else ":$s"
                        modules.putIfAbsent(path, path.substring(1).replace(':', '/'))
                    }
                } else if (t.isId("includeBuild") && k + 2 < n && toks[k + 1].isOp("(")) {
                    toks[k + 2].plainString?.let { includedBuilds.add(it.trimEnd('/').removePrefix("./")) }
                } else if (t.isId("project") && k + 2 < n && toks[k + 1].isOp("(")) {
                    // project(":x").projectDir = file("dir")
                    val s = toks[k + 2].plainString ?: continue
                    val j = if (m[k + 1] >= 0) m[k + 1] + 1 else k + 3
                    if (j + 5 < n && toks[j].isOp(".") && toks[j + 1].isId("projectDir") && toks[j + 2].isOp("=") &&
                        toks[j + 3].kind == 'i' && toks[j + 4].isOp("(")) {
                        toks[j + 5].plainString?.let { overrides[if (s.startsWith(":")) s else ":$s"] = it.trimEnd('/').removePrefix("./") }
                    }
                }
            }
        }
        modules.putAll(overrides)
        for (path in modules.keys) if (path != ":") accessors[path.substring(1).split(':').map(::camel)] = path
        // Included builds (build-logic) are not modules of this build, but their files
        // should not count as the root module's.
        p.setModules(modules + includedBuilds.associate { "[$it]" to it })
    }

    private fun readCatalogs() {
        for (rel in p.tomlPaths.filter { it.endsWith("libs.versions.toml") }) {
            val r = Toml.parse(p.read(rel))
            if (r.hasErrors()) { g.addUnresolved("gradle_catalog", rel, rel, null, "invalid TOML"); continue }
            val plugins = r.getTable("plugins") ?: continue
            for (key in plugins.keySet()) {
                val v = plugins.get(listOf(key))
                val id = when (v) {
                    is TomlTable -> v.getString("id")
                    is String -> v.substringBefore(':')
                    else -> null
                } ?: continue
                catalogPlugins[key.replace('-', '.').replace('_', '.')] = id
            }
        }
    }

    // ----- dependencies -----
    private fun pluginFromChain(parts: List<String>): String? {
        if (parts.size >= 3 && parts[1] == "plugins") {
            val rest = parts.drop(2).filter { it !in setOf("get", "pluginId", "asProvider") }
            return catalogPlugins[rest.joinToString(".")]
        }
        return null
    }

    /** Every project(...) / projects.x.y reference with its configuration. */
    fun projectRefs(toks: List<Tok>, file: String?): List<ProjectRef> {
        val n = toks.size
        val out = ArrayList<ProjectRef>()
        for (k in toks.indices) {
            val t = toks[k]
            val afterDot = k > 0 && toks[k - 1].isOp(".")
            var path: String? = null
            if (t.isId("project") && k + 2 < n && toks[k + 1].isOp("(") && !afterDot) {
                var a = k + 2
                if (toks[a].kind == 'i' && a + 1 < n && toks[a + 1].isOp("=")) a += 2 // project(path = ":x")
                val s = toks.getOrNull(a)?.plainString ?: continue
                path = if (s.startsWith(":")) s else ":$s"
            } else if (t.isId("projects") && k + 2 < n && toks[k + 1].isOp(".") && !afterDot) {
                val (parts, _) = chain(toks, k + 2)
                for (cut in parts.size downTo 1) {
                    accessors[parts.subList(0, cut)]?.let { path = it }
                    if (path != null) break
                }
                if (path == null) {
                    g.addUnresolved("gradle_project", "projects." + parts.joinToString("."), file, t.line,
                        "type-safe accessor matches no included module")
                    continue
                }
            } else continue
            out.add(ProjectRef(configuration(toks, k), path, t.line))
        }
        return out
    }

    private fun configuration(toks: List<Tok>, k: Int): String {
        var j = k
        repeat(4) {
            if (j - 1 >= 0 && toks[j - 1].isOp("(")) {
                val callee = toks.getOrNull(j - 2) ?: return "unknown"
                val name = if (callee.kind == 'i' || callee.kind == 's') callee.text else null
                if (name in WRAPPERS) { j -= 2; return@repeat }
                return if (name != null && name !in NOT_CONFIGS) name else "unknown"
            }
            if (j - 1 >= 0 && toks[j - 1].isOp(",")) {
                // add("implementation", project(":x"))
                if (j - 4 >= 0 && toks[j - 2].kind == 's' && toks[j - 3].isOp("(") && toks[j - 4].isId("add")) return toks[j - 2].text
                return "unknown"
            }
            if (j - 1 >= 0 && toks[j - 1].kind == 'i' && toks[j - 1].line == toks[j].line) {
                val name = toks[j - 1].text // Groovy: implementation project(':x')
                return if (name !in NOT_CONFIGS) name else "unknown"
            }
            return "unknown"
        }
        return "unknown"
    }

    fun appliedPlugins(toks: List<Tok>): List<String> {
        val m = GradleLexer.matchBrackets(toks)
        val n = toks.size
        val ids = ArrayList<String>()
        for (k in toks.indices) {
            val t = toks[k]
            if (t.isId("plugins") && k + 1 < n && toks[k + 1].isOp("{")) {
                val end = m[k + 1]
                var j = k + 2
                while (j < end && j < n) {
                    val tj = toks[j]
                    val id = if (tj.isId("alias") && j + 1 < end && toks[j + 1].isOp("(")) {
                        val (parts, _) = chain(toks, j + 2)
                        pluginFromChain(parts) ?: ("alias:" + parts.joinToString("."))
                    } else if (tj.isId("id") && j + 1 < end) {
                        if (toks[j + 1].isOp("(") && j + 2 < end) toks[j + 2].plainString else toks[j + 1].plainString
                    } else if (tj.isId("kotlin") && j + 2 < end && toks[j + 1].isOp("(")) {
                        toks[j + 2].plainString?.let { "org.jetbrains.kotlin.$it" }
                    } else null
                    if (id != null && !declaredOnly(toks, j, end)) ids.add(id)
                    j++
                }
            }
            // apply(plugin = "x") / pluginManager.apply("x") / apply plugin: 'x'
            if (t.isId("apply") && k + 1 < n) {
                if (toks[k + 1].isOp("(") && k + 2 < n) {
                    var j = k + 2
                    if (toks[j].isId("plugin") && j + 2 < n && toks[j + 1].isOp("=")) j += 2
                    val s = toks[j].plainString
                    if (s != null) ids.add(s) else pluginFromChain(chain(toks, j).first)?.let { ids.add(it) }
                } else if (toks[k + 1].isId("plugin") && k + 3 < n && toks[k + 2].isOp(":")) {
                    toks[k + 3].plainString?.let { ids.add(it) }
                }
            }
        }
        return ids
    }

    /** `id("x") version "1" apply false` (on the same line as the entry at [j]) declares a plugin without applying it. */
    private fun declaredOnly(toks: List<Tok>, j: Int, end: Int): Boolean {
        var k = j + 1
        while (k + 1 < end && toks[k].line == toks[j].line) {
            if (toks[k].isId("apply")) {
                val next = if (toks[k + 1].isOp("(") && k + 2 < end) toks[k + 2] else toks[k + 1]
                return next.isId("false")
            }
            k++
        }
        return false
    }

    // ----- convention plugins -----
    private fun readConventionPlugins() {
        for (build in includedBuilds) {
            val prefix = build.trimEnd('/') + "/"
            val registrations = LinkedHashMap<String, String>() // plugin id -> implementation class
            for (rel in p.gradlePaths.filter { it.startsWith(prefix) }) {
                val toks = GradleLexer.lex(p.read(rel))
                val m = GradleLexer.matchBrackets(toks)
                for (k in toks.indices) {
                    if (!toks[k].isId("register") || k + 1 >= toks.size || !toks[k + 1].isOp("(")) continue
                    val close = m[k + 1]
                    if (close < 0 || close + 1 >= toks.size || !toks[close + 1].isOp("{")) continue
                    val bodyEnd = m[close + 1]
                    var pid: String? = null
                    var cls: String? = null
                    for (j in close + 2 until minOf(bodyEnd, toks.size)) {
                        if (toks[j].isId("id") && j + 2 < bodyEnd && toks[j + 1].isOp("=")) {
                            pid = toks[j + 2].plainString ?: pluginFromChain(chain(toks, j + 2).first)
                        }
                        if (toks[j].isId("implementationClass") && j + 2 < bodyEnd && toks[j + 1].isOp("=")) cls = toks[j + 2].plainString
                    }
                    if (pid != null && cls != null) registrations[pid] = cls
                }
            }
            if (registrations.isEmpty()) continue
            val classes = HashMap<String, String>()
            for (rel in p.ktPaths.filter { it.startsWith(prefix) }) {
                val text = p.read(rel)
                for (cls in registrations.values) {
                    if (Regex("\\bclass\\s+${Regex.escape(cls.substringAfterLast('.'))}\\b").containsMatchIn(text)) classes[cls] = rel
                }
            }
            for ((pid, cls) in registrations) {
                val rel = classes[cls]
                if (rel == null) {
                    g.addUnresolved("gradle_plugin", pid, null, null, "implementation class $cls not found")
                    continue
                }
                val toks = GradleLexer.lex(p.read(rel))
                convention[pid] = Convention(projectRefs(toks, rel), appliedPlugins(toks), rel)
            }
        }
    }

    private class Added(val ref: ProjectRef, val via: String)

    /** Project dependencies added by applying plugin [pid] (transitively). */
    private fun expand(pid: String, seen: MutableSet<String>): List<Added> {
        if (!seen.add(pid)) return emptyList()
        val info = convention[pid] ?: return emptyList()
        return info.deps.map { Added(it, pid) } + info.applies.flatMap { expand(it, seen) }
    }

    // ----- modules -----
    private fun buildModules() {
        for ((path, dir) in modules.entries.sortedBy { it.key }) {
            val build = listOf("build.gradle.kts", "build.gradle").map { if (dir.isEmpty()) it else "$dir/$it" }
                .firstOrNull { it in p.gradlePaths }
            g.addNode(moduleId(path), "module", path, build, if (build != null) 1 else null, path,
                mapOf("dir" to dir, "missing_build_file" to (if (build == null) true else null)))
        }
        for (path in modules.keys.sorted()) {
            val build = g.nodes[moduleId(path)]!!.file ?: continue
            val toks = GradleLexer.lex(p.read(build))
            val plugins = appliedPlugins(toks)
            if (plugins.isNotEmpty()) g.nodes[moduleId(path)]!!.attrs["plugins"] = plugins
            for (r in projectRefs(toks, build)) dep(path, r.path, r.configuration, null, build, r.line)
            for (pid in plugins) for (a in expand(pid, HashSet())) {
                dep(path, a.ref.path, a.ref.configuration, a.via, convention[a.via]!!.file, a.ref.line)
            }
        }
    }

    private fun dep(src: String, dst: String, conf: String, via: String?, file: String?, line: Int?) {
        if (dst !in modules) {
            g.addUnresolved("gradle_project", dst, file, line, "$src depends on a project not in settings")
            g.addNode(moduleId(dst), "module", dst, module = dst, attrs = mapOf("missing" to true))
        }
        val test = if ("test" in conf.lowercase()) true else null
        g.addEdge(moduleId(src), moduleId(dst), "depends_on", file, line, mapOf("configuration" to conf, "via" to via, "test" to test))
    }
}
