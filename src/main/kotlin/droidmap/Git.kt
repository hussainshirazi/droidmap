package droidmap

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The few read-only git calls droidmap makes, only when asked to compare with a base (`--base REF`). */
object Git {
    private fun run(root: Path, vararg args: String): Pair<Int, ByteArray> {
        val proc = try {
            ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) {
            return -1 to ByteArray(0) // git is not installed
        }
        val out = proc.inputStream.readBytes()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out
    }

    /** Whether [ref] names a commit in the repository that holds [root]. */
    fun isCommit(root: Path, ref: String): Boolean =
        !ref.startsWith("-") && run(root, "rev-parse", "--verify", "--quiet", "$ref^{commit}").first == 0

    /** The file [rel] (relative to [root]) as it is at [ref], or null when it does not exist there. */
    fun show(root: Path, ref: String, rel: String): String? {
        val (code, out) = run(root, "show", "$ref:./$rel")
        return if (code == 0) String(out, Charsets.UTF_8) else null
    }
}
