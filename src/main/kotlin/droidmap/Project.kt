package droidmap

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Directories never scanned (any directory whose name starts with "." is skipped too). */
val SKIP_DIRS = setOf("build", "node_modules", "out", "droidmap-out", "graphify-out", "__pycache__", "venv")
private val PACKAGE_NAMES = setOf("build", "out")
const val MAX_FILE_BYTES = 2_000_000L
const val CONFIG_NAME = ".droidmap.toml"

class Config(
    val severities: Map<String, String> = emptyMap(),
    val allowedPermissions: List<String>? = null,
    val forbid: List<Map<String, Any?>> = emptyList(),
    val ignorePaths: List<String> = emptyList(),
) {
    companion object {
        private val SEVERITIES = listOf("error", "warning", "info", "off")

        private fun strings(raw: Any?): List<String>? = (raw as? TomlArray)?.let { arr -> (0 until arr.size()).map { arr.get(it).toString() } }

        /**
         * Reads .droidmap.toml. Throws IllegalArgumentException when it is not valid TOML or names
         * a rule or severity that does not exist, so a typo never silently leaves a rule as it was.
         */
        fun load(root: Path, path: Path? = null): Config {
            val p = path ?: root.resolve(CONFIG_NAME)
            if (!Files.isRegularFile(p)) {
                require(path == null) { "$p does not exist" }
                return Config()
            }
            val r = Toml.parse(Files.readString(p))
            require(!r.hasErrors()) { "$p: ${r.errors().first()}" }
            val rules: TomlTable? = r.getTable("rules")
            val severities = HashMap<String, String>()
            if (rules != null) for (k in rules.keySet()) {
                val v: Any? = rules.get(listOf(k))
                require(k in RULES) { "$p: unknown rule \"$k\" in [rules] (`droidmap rules` lists them)" }
                require(v.toString() in SEVERITIES) { "$p: rule \"$k\" is set to \"$v\"; use ${SEVERITIES.joinToString(", ")}" }
                severities[k] = v.toString()
            }
            val forbid = ArrayList<Map<String, Any?>>()
            (r.get(listOf("gradle", "forbid")) as? TomlArray)?.let { arr ->
                for (i in 0 until arr.size()) {
                    val t = arr.get(i) as? TomlTable ?: continue
                    val m = t.keySet().associateWith { k -> t.get(listOf(k)) }
                    require(m["from"] is String && m["to"] is String) { "$p: every [[gradle.forbid]] needs from and to" }
                    forbid.add(m)
                }
            }
            return Config(severities, strings(r.get(listOf("manifest", "allowed_permissions"))), forbid,
                strings(r.get(listOf("ignore", "paths"))) ?: emptyList())
        }
    }
}

private val globs = ConcurrentHashMap<String, Regex>()

/** Glob matching (case-sensitive): `*` matches anything, `/` and `:` included; `?` one character; `[..]` a set. */
fun glob(name: String, pattern: String): Boolean = globs.getOrPut(pattern) {
    val sb = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        when (val c = pattern[i]) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            '[' -> {
                val close = pattern.indexOf(']', i + 1)
                if (close < 0) sb.append("\\[") else {
                    var body = pattern.substring(i + 1, close)
                    if (body.startsWith("!")) body = "^" + body.substring(1)
                    sb.append('[').append(body.replace("\\", "\\\\")).append(']')
                    i = close
                }
            }
            else -> sb.append(Regex.escape(c.toString()))
        }
        i++
    }
    Regex(sb.toString())
}.matches(name)

fun sourceSetOf(rel: String): String? {
    val parts = rel.split('/')
    for (k in 0 until parts.size - 2) if (parts[k] == "src") return parts[k + 1]
    return null
}

fun isTestSet(sourceSet: String?): Boolean = sourceSet != null && "test" in sourceSet.lowercase()

/** The files of a project that droidmap reads, by kind, and its Gradle modules once settings have been read. */
class Project(root: Path, config: Config? = null) {
    val root: Path = root.toAbsolutePath().normalize()
    val config: Config = config ?: Config.load(this.root)
    val ktPaths = ArrayList<String>()
    val javaPaths = ArrayList<String>()
    val gradlePaths = ArrayList<String>()
    val manifestPaths = ArrayList<String>()
    val navXmlPaths = ArrayList<String>()
    val menuXmlPaths = ArrayList<String>()
    val tomlPaths = ArrayList<String>()
    val jsonPaths = ArrayList<String>()
    val skipped = ArrayList<String>()
    var modules: Map<String, String> = emptyMap() // gradle path -> dir ("" for the root)
        private set
    private var moduleDirs: List<Pair<String, String>> = emptyList()
    private val stamps = HashMap<String, String>()
    /** Changes when a file droidmap reads is added, removed or changed. */
    val fingerprint: String

    init {
        val digest = MessageDigest.getInstance("SHA-256")
        val found = ArrayList<Triple<String, Long, Long>>()
        Files.walkFileTree(this.root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == this@Project.root) return FileVisitResult.CONTINUE
                val name = dir.fileName.toString()
                // Under src/, "build" and "out" are package names, not build output.
                val inSources = name in PACKAGE_NAMES && this@Project.root.relativize(dir).any { it.toString() == "src" }
                return if (name.startsWith(".") || (name in SKIP_DIRS && !inSources)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !attrs.isSymbolicLink) {
                    found.add(Triple(this@Project.root.relativize(file).toString().replace('\\', '/'), attrs.size(), attrs.lastModifiedTime().toMillis()))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        found.sortBy { it.first }
        for ((rel, size, mtime) in found) {
            if (this.config.ignorePaths.any { glob(rel, it) }) continue
            val low = rel.substringAfterLast('/').lowercase()
            val dir = rel.substringBeforeLast('/', "")
            val bucket = when {
                low.endsWith(".kt") -> ktPaths
                low.endsWith(".java") -> javaPaths
                low.endsWith(".gradle.kts") || low.endsWith(".gradle") -> gradlePaths
                low == "androidmanifest.xml" -> manifestPaths
                low.endsWith(".xml") && "/res/navigation" in "/$dir" -> navXmlPaths
                low.endsWith(".xml") && "/res/menu" in "/$dir" -> menuXmlPaths
                low.endsWith(".versions.toml") -> tomlPaths
                low.endsWith(".json") && low.dropLast(5).isNotEmpty() && low.dropLast(5).all { it.isDigit() } -> jsonPaths
                else -> null
            } ?: continue
            stamps[rel] = "$size:$mtime"
            digest.update("$rel\u0000$size\u0000$mtime\n".toByteArray())
            if (size > MAX_FILE_BYTES) { skipped.add(rel); continue }
            bucket.add(rel)
        }
        fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun read(rel: String): String = String(Files.readAllBytes(root.resolve(rel)), Charsets.UTF_8)

    /** The size and modification time [rel] had when the project was scanned. */
    fun stamp(rel: String): String? = stamps[rel]

    fun setModules(m: Map<String, String>) {
        modules = m
        moduleDirs = m.entries.map { it.value to it.key }.sortedByDescending { it.first.length }
    }

    fun moduleOf(rel: String): String? {
        for ((d, p) in moduleDirs) if (d == "" || rel == d || rel.startsWith("$d/")) return p
        return null
    }
}
