package droidmap

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The project's parsed sources, with name resolution and constant evaluation over them. */
class Code(val sources: List<Source>) {
    val index = Index(sources)
    val eval = Evaluator(index)
    private val references = arrayOfNulls<Map<String, Int>>(2)

    /** How often each simple name is referred to in code (imports and package lines excluded). */
    fun references(includeTests: Boolean): Map<String, Int> {
        val k = if (includeTests) 1 else 0
        references[k]?.let { return it }
        val counts = HashMap<String, Int>()
        for (s in sources) if (includeTests || !s.isTest) s.names.forEach { (n, c) -> counts.merge(n, c, Int::plus) }
        return counts.also { references[k] = it }
    }

    companion object {
        private const val STACK_BYTES = 16L shl 20 // deeply nested expressions recurse deeply

        /**
         * Parses the project's Kotlin and Java files, several at a time. A file that has not changed
         * since [previous] (same size, modification time and module) is taken from it, not parsed again.
         */
        fun read(p: Project, previous: Code? = null): Code {
            val reuse = previous?.sources?.associateBy { it.path } ?: emptyMap()
            val paths = (p.ktPaths + p.javaPaths).sorted()
            val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 8)) { r ->
                Thread(null, r, "droidmap-parse", STACK_BYTES).apply { isDaemon = true }
            }
            try {
                val jobs: List<Future<Source>> = paths.map { rel ->
                    val module = p.moduleOf(rel)
                    val old = reuse[rel]?.takeIf { it.stamp == p.stamp(rel) && it.module == module }
                    if (old != null) CompletableFuture.completedFuture(old) else pool.submit(Callable { readSource(p, rel, module) })
                }
                val sources = ArrayList<Source>()
                for ((rel, job) in paths.zip(jobs)) {
                    try {
                        sources.add(job.get())
                    } catch (e: ExecutionException) {
                        p.skipped.add(rel) // unreadable
                    }
                }
                return Code(sources)
            } finally {
                pool.shutdownNow()
            }
        }

        private fun readSource(p: Project, rel: String, module: String?): Source {
            val text = p.read(rel)
            val ss = sourceSetOf(rel)
            val s = if (rel.endsWith(".java")) JavaReader.read(rel, module, ss, text) else KotlinReader.read(rel, module, ss, text)
            s.stamp = p.stamp(rel)
            return s
        }
    }
}
