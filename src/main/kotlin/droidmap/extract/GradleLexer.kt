package droidmap.extract

/**
 * A small, error-tolerant tokenizer shared by Kotlin DSL and Groovy build files. The Gradle
 * patterns droidmap reads (include, project(...), projects.x.y, plugins { }, register { })
 * look the same in both languages at the token level, so one tokenizer serves both.
 */
class Tok(val kind: Char, val text: String, val line: Int, val literal: Boolean = true) {
    // kind: 'i' identifier, 'n' number, 's' string, 'o' operator
    fun isOp(t: String) = kind == 'o' && text == t
    fun isId(t: String? = null) = kind == 'i' && (t == null || text == t)

    /** A string with no ${...} template inside. */
    val plainString: String? get() = if (kind == 's' && literal) text else null
}

object GradleLexer {
    private val OPS2 = setOf("::", "->", "?.", "?:", "!!", "..", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=")

    fun lex(src: String): List<Tok> {
        val toks = ArrayList<Tok>()
        var i = 0
        var line = 1
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '\n' -> { line++; i++ }
                c == ' ' || c == '\t' || c == '\r' || c == '\u000c' || c == '﻿' -> i++
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    val j = src.indexOf('\n', i)
                    i = if (j < 0) n else j
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        when {
                            src.startsWith("/*", i) -> { depth++; i += 2 }
                            src.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> { if (src[i] == '\n') line++; i++ }
                        }
                    }
                }
                c == '"' || c == '\'' -> {
                    val start = line
                    val triple = src.startsWith("\"\"\"", i) || src.startsWith("'''", i)
                    val quote = if (triple) src.substring(i, i + 3) else c.toString()
                    i += quote.length
                    val sb = StringBuilder()
                    var literal = true
                    while (i < n && !src.startsWith(quote, i)) {
                        val ch = src[i]
                        if (!triple && ch == '\n') break
                        if (ch == '\\' && i + 1 < n) { sb.append(src[i + 1]); i += 2; continue }
                        if (ch == '$' && c == '"') literal = false
                        if (ch == '\n') line++
                        sb.append(ch)
                        i++
                    }
                    if (src.startsWith(quote, i)) i += quote.length
                    toks.add(Tok('s', sb.toString(), start, literal))
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    toks.add(Tok('i', src.substring(i, j), line))
                    i = j
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '_' || (src[j] == '.' && j + 1 < n && src[j + 1].isDigit()))) j++
                    toks.add(Tok('n', src.substring(i, j), line))
                    i = j
                }
                else -> {
                    val two = if (i + 1 < n) src.substring(i, i + 2) else ""
                    if (two in OPS2) { toks.add(Tok('o', two, line)); i += 2 } else { toks.add(Tok('o', c.toString(), line)); i++ }
                }
            }
        }
        return toks
    }

    /** For each ( [ { token, the index of its partner (or toks.size if unclosed); -1 elsewhere. */
    fun matchBrackets(toks: List<Tok>): IntArray {
        val pairs = mapOf(")" to "(", "]" to "[", "}" to "{")
        val out = IntArray(toks.size) { -1 }
        val stack = ArrayDeque<Int>()
        toks.forEachIndexed { k, t ->
            if (t.kind != 'o') return@forEachIndexed
            if (t.text == "(" || t.text == "[" || t.text == "{") stack.addLast(k)
            else if (t.text in pairs) {
                while (stack.isNotEmpty()) {
                    val o = stack.removeLast()
                    if (toks[o].text == pairs[t.text]) { out[o] = k; out[k] = o; break }
                    out[o] = k
                }
            }
        }
        for (o in stack) out[o] = toks.size
        return out
    }
}
