package droidmap.extract

import droidmap.Fn
import droidmap.Graph
import droidmap.Type

/** An attribute that is set only when true, so graphs stay small. */
fun flag(b: Boolean): Boolean? = if (b) true else null

fun classId(fq: String) = "class::$fq"
fun funId(fq: String) = "fun::$fq"

fun nodeForClass(g: Graph, t: Type, kind: String, attrs: Map<String, Any?> = emptyMap()): String =
    g.addNode(classId(t.fq), kind, t.label, t.src.path, t.line, t.src.module, mapOf("source_set" to t.src.sourceSet) + attrs)

fun nodeForFun(g: Graph, f: Fn, kind: String, attrs: Map<String, Any?> = emptyMap()): String? {
    val fq = f.fq ?: return null
    return g.addNode(funId(fq), kind, f.name, f.src.path, f.line, f.src.module, attrs)
}
