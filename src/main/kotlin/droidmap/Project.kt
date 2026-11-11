package droidmap

import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
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
        /** Reads .droidmap.toml; throws IllegalArgumentException when it is not valid TOML. */
        fun load(root: Path, path: Path? = null): Config {
            val p = path ?: root.resolve(CONFIG_NAME)
            if (!Files.isRegularFile(p)) return Config()
            val r = Toml.parse(Files.readString(p))
            if (r.hasErrors()) throw IllegalArgumentException("$p: ${r.errors().first()}")
            val rules: TomlTable? = r.getTable("rules")
            val severities = HashMap<String, String>()
            if (rules != null) for (k in rules.keySet()) {
                val v: Any? = rules.get(listOf(k))
                severities[k] = v.toString()
            }
            val allowedRaw: Any? = r.get(listOf("manifest", "allowed_permissions"))
            val allowed: List<String>? = (allowedRaw as? TomlArray)?.let { arr -> (0 until arr.size()).map { arr.get(it).toString() } }
            val forbidRaw: Any? = r.get(listOf("gradle", "forbid"))
            val forbid = ArrayList<Map<String, Any?>>()
            (forbidRaw as? TomlArray)?.let { arr ->
                for (i in 0 until arr.size()) {
                    val t = arr.get(i) as? TomlTable ?: continue
                    val m = LinkedHashMap<String, Any?>()
                    for (k in t.keySet()) {
                        val v: Any? = t.get(listOf(k))
                        m[k] = v
                    }
                    forbid.add(m)
                }
            }
            val ignoreRaw: Any? = r.get(listOf("ignore", "paths"))
            val ignore: List<String> = (ignoreRaw as? TomlArray)?.let { arr -> (0 until arr.size()).map { arr.get(it).toString() } } ?: emptyList()
            return Config(severities, allowed, forbid, ignore)
        }
    }
}

/** Python-style fnmatch (case-sensitive): * matches anything including '/', ? one char, [..] a set. */
fun fnmatch(name: String, pattern: String): Boolean {
    val sb = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
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
    return Regex(sb.toString()).matches(name)
}

fun sourceSetOf(rel: String): String? {
    val parts = rel.split('/')
    for (k in 0 until parts.size - 2) if (parts[k] == "src") return parts[k + 1]
    return null
}

fun isTestSet(sourceSet: String?): Boolean = sourceSet != null && "test" in sourceSet.lowercase()

/** The Kotlin compiler's parser, set up once per process. */
object KotlinPsi {
    @Suppress("OPT_IN_USAGE_ERROR")
    @OptIn(CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
    private val factory: KtPsiFactory by lazy {
        setIdeaIoUseFallback()
        val config = CompilerConfiguration()
        config.extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        val env = KotlinCoreEnvironment.createForProduction(Disposer.newDisposable("droidmap"), config, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        KtPsiFactory(env.project, markGenerated = false)
    }

    @Synchronized
    fun parse(name: String, text: String): KtFile = factory.createFile(name, text.replace("\r\n", "\n"))
}

/** One parsed Kotlin file and what name resolution needs from it. */
class Source(val path: String, val module: String?, val sourceSet: String?, val file: KtFile) {
    val pkg: String = file.packageFqName.asString()
    val imports: Map<String, String>
    val starImports: List<String>
    private val lineStarts: IntArray

    init {
        val imp = LinkedHashMap<String, String>()
        val star = ArrayList<String>()
        for (d in file.importDirectives) {
            val fq = d.importedFqName?.asString() ?: continue
            if (d.isAllUnder) star.add(fq) else imp[d.aliasName ?: fq.substringAfterLast('.')] = fq
        }
        imports = imp
        starImports = star
        val text = file.text
        val starts = ArrayList<Int>()
        starts.add(0)
        text.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
        lineStarts = starts.toIntArray()
    }

    fun lineAt(offset: Int): Int {
        val k = lineStarts.binarySearch(offset)
        return if (k >= 0) k + 1 else -k - 1
    }

    fun line(el: PsiElement): Int = lineAt(el.textOffset)
    val isTest get() = isTestSet(sourceSet)
}

class Project(root: Path, config: Config? = null) {
    val root: Path = root.toAbsolutePath().normalize()
    val config: Config = config ?: Config.load(this.root)
    val ktPaths = ArrayList<String>()
    val gradlePaths = ArrayList<String>()
    val manifestPaths = ArrayList<String>()
    val navXmlPaths = ArrayList<String>()
    val tomlPaths = ArrayList<String>()
    val jsonPaths = ArrayList<String>()
    val skipped = ArrayList<String>()
    var modules: Map<String, String> = emptyMap() // gradle path -> dir ("" for the root)
        private set
    private var moduleDirs: List<Pair<String, String>> = emptyList()
    val sources = ArrayList<Source>()
    lateinit var index: Index
    lateinit var eval: Evaluator
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
            if (this.config.ignorePaths.any { fnmatch(rel, it) }) continue
            val name = rel.substringAfterLast('/')
            val low = name.lowercase()
            val dir = rel.substringBeforeLast('/', "")
            val bucket = when {
                low.endsWith(".kt") -> ktPaths
                low.endsWith(".gradle.kts") || low.endsWith(".gradle") -> gradlePaths
                low == "androidmanifest.xml" -> manifestPaths
                low.endsWith(".xml") && "/res/navigation" in "/$dir" -> navXmlPaths
                low.endsWith(".versions.toml") -> tomlPaths
                low.endsWith(".json") && low.dropLast(5).isNotEmpty() && low.dropLast(5).all { it.isDigit() } -> jsonPaths
                else -> null
            } ?: continue
            digest.update("$rel\u0000$size\u0000$mtime\n".toByteArray())
            if (size > MAX_FILE_BYTES) { skipped.add(rel); continue }
            bucket.add(rel)
        }
        fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun read(rel: String): String = String(Files.readAllBytes(root.resolve(rel)), Charsets.UTF_8)

    fun setModules(m: Map<String, String>) {
        modules = m
        moduleDirs = m.entries.map { it.value to it.key }.sortedByDescending { it.first.length }
    }

    fun moduleOf(rel: String): String? {
        for ((d, p) in moduleDirs) if (d == "" || rel == d || rel.startsWith("$d/")) return p
        return null
    }

    fun parseKotlin() {
        sources.clear()
        for (rel in ktPaths) {
            try {
                sources.add(Source(rel, moduleOf(rel), sourceSetOf(rel), KotlinPsi.parse(rel.substringAfterLast('/'), read(rel))))
            } catch (e: Exception) {
                skipped.add(rel)
            }
        }
        index = Index(sources)
        eval = Evaluator(index)
    }
}
