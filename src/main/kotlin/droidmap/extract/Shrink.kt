package droidmap.extract

import droidmap.Code
import droidmap.Graph
import droidmap.Project
import droidmap.Source
import droidmap.Type
import droidmap.TypeKind
import droidmap.TypeRef
import droidmap.bool
import droidmap.classRefs
import droidmap.has

/** `isMinifyEnabled = true` (Kotlin DSL) or `minifyEnabled true` (Groovy). */
private val MINIFY = Regex("""\b(?:isMinifyEnabled|minifyEnabled)\s*(?:=\s*)?true\b""")
private val TYPE_TOKEN = Regex("""TypeToken\s*<(.+?)>\s*\(""")
private val NAME = Regex("""\b[A-Z]\w*\b""")
private val HTTP = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HTTP")
/** A keep rule that keeps the members (`{ *; }`, `{ <fields>; }`) of the classes it names. */
private val KEEP = Regex("""-keep(?:classmembers|classeswithmembers)?\s+(?:[^{\n]*?\s)?(?:class|interface|enum)\s+([\w.*?$]+)(?:\s+(?:extends|implements)\s+([\w.*?$]+))?[^{\n]*\{([^}]*)}""")

/**
 * Release shrinking against Gson: Gson reads and writes a class's fields by name through reflection, and R8
 * (a release build with minifyEnabled) renames fields. Finds the classes Gson reflects on (`fromJson(..., X::class.java)`,
 * `TypeToken<List<X>>`, and the types of a Retrofit API when the project uses GsonConverterFactory), and the
 * project classes their fields hold, and records, as meta `gson_unkept_models`, those with fields that have no
 * @SerializedName, no @Keep and no keep rule that keeps their members.
 */
class ShrinkScan(private val p: Project, private val code: Code, private val g: Graph) {
    private val index = code.index

    fun run() {
        val gson = code.sources.filter { !it.isTest && it.importsFrom("com.google.gson") }
        if (gson.isEmpty()) return
        val targets = targets().filter { !it.dontObfuscate }
        if (targets.isEmpty()) return

        val roots = LinkedHashMap<Type, String>() // model -> where Gson reflects on it
        for (s in gson) {
            for (c in s.calls) if (c.name == "fromJson" || c.name == "getAdapter") {
                val names = c.args.flatMap { a -> classRefs(a.psi).map { it.first } } + c.typeArgs.flatMap { leaves(it) }
                for (n in names) model(n, s, c.psi)?.let { roots.putIfAbsent(it, "${c.name} in ${s.path.substringAfterLast('/')}") }
            }
            for (m in TYPE_TOKEN.findAll(s.text)) for (n in NAME.findAll(m.groupValues[1])) {
                model(n.value, s, null)?.let { roots.putIfAbsent(it, "TypeToken in ${s.path.substringAfterLast('/')}") }
            }
        }
        if (code.sources.any { !it.isTest && "GsonConverterFactory" in it.text }) {
            for (s in code.sources) for (t in s.types) for (f in t.fns) {
                if (t.kind != TypeKind.INTERFACE || f.anns.none { it.name in HTTP }) continue
                val names = (listOfNotNull(f.returns) + f.params.filter { it.anns.has("Body") }.mapNotNull { it.type }).flatMap { leaves(it) }
                for (n in names) model(n, s, f.psi)?.let { roots.putIfAbsent(it, "the Retrofit API ${t.label} (GsonConverterFactory)") }
            }
        }
        // Gson also reflects on the classes held by a model's fields.
        val models = LinkedHashMap(roots)
        val todo = ArrayDeque(roots.keys)
        while (todo.isNotEmpty()) {
            val m = todo.removeFirst()
            for (f in stored(m)) for (n in f.type?.let { leaves(it) } ?: emptyList()) {
                val held = model(n, m.src, m.psi) ?: continue
                if (held !in models) { models[held] = "a field of ${m.label}"; todo.add(held) }
            }
        }
        for ((m, where) in models) {
            if (lineage(index, m).any { it.anns.has("Keep") }) continue
            val bare = stored(m).filter { f -> f.anns.none { it.name == "SerializedName" } }.mapNotNull { it.name }
            if (bare.isEmpty()) continue
            val t = targets.firstOrNull { t -> t.contains(m.src.path) && t.keeps.none { it.covers(m) } } ?: continue
            g.addMeta("gson_unkept_models", mapOf("class" to m.fq, "label" to m.label, "used_in" to where, "fields" to bare,
                "minify" to t.buildFile, "file" to m.src.path, "line" to m.line))
        }
    }

    /**
     * A module whose release build R8 shrinks, the folders of the code that goes into it (its own and its project
     * dependencies'), and the rules that apply to it: its own files and the consumer rules of those dependencies.
     */
    private class Target(val buildFile: String, val dirs: List<String>, val keeps: List<Keep>, val dontObfuscate: Boolean) {
        fun contains(path: String) = dirs.any { it.isEmpty() || path.startsWith("$it/") }
    }

    private fun targets(): List<Target> = p.gradlePaths.filter { rel ->
        rel.substringAfterLast('/').startsWith("build.gradle") && MINIFY.containsMatchIn(stripComments(p.read(rel), "//"))
    }.map { rel ->
        val dir = rel.substringBeforeLast('/', "")
        val dirs = moduleDirs(rel) ?: listOf(projectDir(dir))
        val files = p.proguardPaths.filter { f ->
            (dir.isEmpty() || f.startsWith("$dir/")) || ("consumer" in f.substringAfterLast('/') && dirs.any { it.isEmpty() || f.startsWith("$it/") })
        }
        val rules = files.map { stripComments(p.read(it), "#") }
        val keeps = rules.flatMap { r -> KEEP.findAll(r).filter { "*;" in it.groupValues[3] || "<fields>" in it.groupValues[3] }.toList() }
            .map { Keep(wildcard(it.groupValues[1]), it.groupValues[2].ifEmpty { null }) }
        Target(rel, dirs, keeps, rules.any { "-dontobfuscate" in it })
    }

    /** The folders of [buildFile]'s module and of the modules it depends on (not for tests), or null when the module is not known. */
    private fun moduleDirs(buildFile: String): List<String>? {
        val module = p.moduleOf(buildFile) ?: return null
        // Only a module's own build file: in a folder of several projects, everything falls under the root otherwise.
        if (moduleId(module) !in g.nodes || p.modules[module] != buildFile.substringBeforeLast('/', "")) return null
        val seen = linkedSetOf(moduleId(module))
        val todo = ArrayDeque(seen)
        while (todo.isNotEmpty()) for (e in g.outEdges(todo.removeFirst())) {
            if (e.kind == "depends_on" && !e.attrs.bool("test") && seen.add(e.target)) todo.add(e.target)
        }
        return seen.mapNotNull { id -> p.modules[g.label(id)] }
    }

    /** The folder of the Gradle project around [dir]: the nearest one with a settings file, or else [dir] itself. */
    private fun projectDir(dir: String): String {
        var d = dir
        while (true) {
            if (p.gradlePaths.any { it.substringBeforeLast('/', "") == d && it.substringAfterLast('/').startsWith("settings.gradle") }) return d
            if (d.isEmpty()) return dir
            d = d.substringBeforeLast('/', "")
        }
    }

    private inner class Keep(val pattern: Regex, val parent: String?) {
        fun covers(t: Type): Boolean {
            if (parent == null) return pattern.matches(t.fq)
            // -keep class * extends com.x.Base { *; }: a subclass of Base (named in full or by simple name).
            val p = wildcard(parent)
            return pattern.matches(t.fq) && lineage(index, t).any { x ->
                x.supers.any { s -> p.matches(s.type.name) || p.matches(s.type.simple) } || (x !== t && p.matches(x.fq))
            }
        }
    }

    /** A project class (not an enum, interface or object) that [name] refers to in [s]. */
    private fun model(name: String, s: Source, at: org.jetbrains.kotlin.com.intellij.psi.PsiElement?): Type? =
        index.resolveType(name, s, at)?.takeIf { it.kind == TypeKind.CLASS && !it.src.isTest }

    /** The fields Gson writes: stored and not static. */
    private fun stored(t: Type) = t.fields.filter { it.stored && !it.isStatic && it.anns.none { a -> a.name == "Transient" } }

    /** Every type name in a type: Call<List<RepoDto>> -> Call, List, RepoDto. */
    private fun leaves(t: TypeRef): List<String> = listOf(t.name) + t.args.flatMap { leaves(it) }

    /** A ProGuard class name pattern: `**` matches across packages, `*` within one, `?` one character. */
    private fun wildcard(p: String): Regex = Regex(buildString {
        var i = 0
        while (i < p.length) {
            when {
                p.startsWith("**", i) -> { append(".*"); i += 2 }
                p[i] == '*' -> { append("[^.]*"); i++ }
                p[i] == '?' -> { append("[^.]"); i++ }
                else -> { append(Regex.escape(p[i].toString())); i++ }
            }
        }
    })

    private fun stripComments(text: String, marker: String) = text.lines().joinToString("\n") { it.substringBefore(marker) }
}
