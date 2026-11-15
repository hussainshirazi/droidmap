package droidmap

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** A project written to a folder for a test: its files, its findings, and git. */
class TestProject(val root: Path) {
    fun write(vararg files: Pair<String, String>): TestProject {
        for ((rel, text) in files) {
            val f = root.resolve(rel)
            Files.createDirectories(f.parent)
            Files.writeString(f, text.trimIndent() + "\n")
        }
        return this
    }

    fun delete(vararg rels: String): TestProject = apply { rels.forEach { Files.deleteIfExists(root.resolve(it)) } }

    /** The findings, with [base] compared like `check --base`. */
    fun findings(base: String? = null): List<Finding> {
        val cfg = Config.load(root)
        return runChecks(buildGraph(root, cfg, base), cfg)
    }

    /** "rule message" of each finding of [rules] (all when none are given). */
    fun messages(vararg rules: String, base: String? = null): List<String> =
        findings(base).filter { rules.isEmpty() || it.rule in rules }.map { "${it.rule} ${it.message}" }

    fun git(vararg args: String) {
        val proc = ProcessBuilder(listOf("git", "-C", root.toString(), "-c", "user.name=t", "-c", "user.email=t@example.com",
            "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false") + args).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, proc.waitFor(), out)
    }

    /** Commits everything (making the repository the first time). */
    fun commitAll(message: String = "base"): TestProject {
        if (!Files.isDirectory(root.resolve(".git"))) git("init", "-q")
        git("add", "-A")
        git("commit", "-q", "-m", message)
        return this
    }

    companion object {
        /** An app module, :app. */
        val APP = arrayOf(
            "settings.gradle.kts" to """include(":app")""",
            "app/build.gradle.kts" to """plugins { id("com.android.application") }""",
        )
        const val NS = """xmlns:android="http://schemas.android.com/apk/res/android""""
    }
}
