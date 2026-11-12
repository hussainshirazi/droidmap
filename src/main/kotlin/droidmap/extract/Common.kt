package droidmap.extract

import droidmap.Fn
import droidmap.Graph
import droidmap.Type

/** [t] and its project supertypes, nearest first. */
fun lineage(index: droidmap.Index, t: Type): List<Type> {
    val out = arrayListOf(t)
    var i = 0
    while (i < out.size && out.size < 32) {
        val x = out[i++]
        for (s in x.supers) index.resolveType(s.type.name, x.src, null)?.let { a -> if (out.none { it === a }) out.add(a) }
    }
    return out
}

/** The simple names of every supertype of [t] and its project supertypes, framework ones included (Fragment, AppCompatActivity). */
fun superNames(index: droidmap.Index, t: Type): Set<String> = lineage(index, t).flatMap { x -> x.supers.map { it.type.simple } }.toSet()

/** An attribute that is set only when true, so graphs stay small. */
fun flag(b: Boolean): Boolean? = if (b) true else null

fun classId(fq: String) = "class::$fq"
fun funId(fq: String) = "fun::$fq"

fun nodeForClass(g: Graph, t: Type, kind: String, attrs: Map<String, Any?> = emptyMap()): String =
    g.addNode(classId(t.fq), kind, t.label, t.src.path, t.line, t.src.module, mapOf("source_set" to t.src.sourceSet, "end_line" to t.endLine) + attrs)

fun nodeForFun(g: Graph, f: Fn, kind: String, attrs: Map<String, Any?> = emptyMap()): String? {
    val fq = f.fq ?: return null
    return g.addNode(funId(fq), kind, f.name, f.src.path, f.line, f.src.module, mapOf("end_line" to f.endLine) + attrs)
}
