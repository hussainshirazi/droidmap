package droidmap.extract

import droidmap.Code
import droidmap.Graph
import droidmap.Source
import droidmap.has

/**
 * WorkManager: the @HiltWorker classes, whether app code uses HiltWorkerFactory, and the classes that
 * implement Configuration.Provider (the manifest side is read by ManifestScan). The workmanager-setup rule reads them.
 */
class WorkScan(private val code: Code, private val g: Graph) {
    private fun isConfigProvider(name: String, s: Source): Boolean = when (name) {
        "androidx.work.Configuration.Provider" -> true
        "Configuration.Provider" -> s.imports["Configuration"] == "androidx.work.Configuration" || "androidx.work" in s.starImports
        "Provider" -> s.imports["Provider"] == "androidx.work.Configuration.Provider"
        else -> false
    }

    fun run() {
        val app = code.sources.filter { !it.isTest }
        for (s in app) for (t in s.types) {
            if (t.anns.has("HiltWorker")) g.addMeta("hilt_workers", linkedMapOf("name" to t.label, "file" to s.path, "line" to t.line))
            if (t.supers.any { isConfigProvider(it.type.name, s) }) {
                g.addMeta("work_config_providers", linkedMapOf("name" to t.label, "file" to s.path, "line" to t.line))
            }
        }
        if ("hilt_workers" in g.meta) g.meta["hilt_worker_factory_used"] = app.any { (it.names["HiltWorkerFactory"] ?: 0) > 0 }
    }
}
