package droidmap

fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

private const val MESSAGE_INDENT = 8
private const val LINE_WIDTH = 100

/** Findings as two or more lines each: severity, rule and place, then the message wrapped under them. */
fun formatFindings(findings: List<Finding>, style: Style = Style.PLAIN): String {
    if (findings.isEmpty()) return style.green("No findings.")
    val lines = ArrayList<String>()
    val indent = " ".repeat(MESSAGE_INDENT)
    for (f in findings) {
        lines.add("${style.severity(f.severity, f.severity.padEnd(7))} ${style.bold(f.rule.padEnd(28))} ${style.dim(location(f.file, f.line, none = "-"))}")
        wrapText(f.message, LINE_WIDTH - MESSAGE_INDENT).forEach { lines.add(indent + it) }
    }
    val counts = findings.groupingBy { it.severity }.eachCount()
    lines.add("")
    lines.add(listOf("error", "warning", "info").filter { (counts[it] ?: 0) > 0 }
        .joinToString(", ") { style.severity(it, "${counts[it]} ${plural(counts.getValue(it), it)}") })
    return lines.joinToString("\n")
}

/** SARIF 2.1.0 for GitHub code scanning; [prefix] is put before every file path (see `check --sarif`). */
fun toSarif(findings: List<Finding>, prefix: String = ""): Map<String, Any?> {
    val level = mapOf("error" to "error", "warning" to "warning", "info" to "note")
    val rules = RULES.values.map { r ->
        linkedMapOf("id" to r.id, "shortDescription" to mapOf("text" to r.description),
            "defaultConfiguration" to mapOf("level" to (level[r.severity] ?: "none")))
    }
    val results = findings.map { f ->
        val r = linkedMapOf<String, Any?>("ruleId" to f.rule, "level" to (level[f.severity] ?: "note"), "message" to mapOf("text" to f.message))
        if (f.file != null) {
            val phys = linkedMapOf<String, Any?>("artifactLocation" to mapOf("uri" to prefix + f.file))
            if (f.line != null) phys["region"] = mapOf("startLine" to f.line)
            r["locations"] = listOf(mapOf("physicalLocation" to phys))
        }
        r
    }
    return linkedMapOf(
        "\$schema" to "https://json.schemastore.org/sarif-2.1.0.json", "version" to "2.1.0",
        "runs" to listOf(mapOf("tool" to mapOf("driver" to linkedMapOf("name" to "droidmap", "version" to VERSION, "rules" to rules)),
            "results" to results)),
    )
}
