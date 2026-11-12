package droidmap

/** ANSI colours for terminal output. [PLAIN] adds nothing, and is what files, pipes and the MCP server get. */
class Style(private val on: Boolean) {
    private fun wrap(code: String, text: String) = if (on) "\u001B[${code}m$text\u001B[0m" else text

    fun bold(s: String) = wrap("1", s)
    fun dim(s: String) = wrap("2", s)
    fun green(s: String) = wrap("32", s)

    /** The colour of a finding's severity. */
    fun severity(severity: String, s: String = severity) = when (severity) {
        "error" -> wrap("1;31", s)
        "warning" -> wrap("33", s)
        else -> wrap("36", s)
    }

    companion object {
        val PLAIN = Style(false)

        /** Colour when the output is a terminal that shows it; NO_COLOR turns it off and FORCE_COLOR on. */
        fun detect(env: Map<String, String> = System.getenv(), isTerminal: () -> Boolean = ::stdoutIsTerminal): Style = Style(when {
            !env["NO_COLOR"].isNullOrEmpty() -> false
            !env["FORCE_COLOR"].isNullOrEmpty() && env["FORCE_COLOR"] != "0" -> true
            env["TERM"] == "dumb" || !isTerminal() -> false
            // Only terminals known to read ANSI codes on Windows: the old console window prints them as text.
            System.getProperty("os.name").startsWith("Windows") ->
                listOf("WT_SESSION", "TERM_PROGRAM", "ANSICON", "COLORTERM").any { !env[it].isNullOrEmpty() }
            else -> true
        })
    }
}

private fun stdoutIsTerminal(): Boolean {
    val console = System.console() ?: return false
    // Since Java 22 a console exists even when the output is piped; isTerminal() tells them apart.
    return runCatching { java.io.Console::class.java.getMethod("isTerminal").invoke(console) as Boolean }.getOrDefault(true)
}

/** [text] broken into lines of at most [width] characters at spaces (a longer word stays whole). */
fun wrapText(text: String, width: Int): List<String> {
    val lines = ArrayList<String>()
    var line = StringBuilder()
    for (word in text.split(' ')) {
        if (line.isNotEmpty() && line.length + 1 + word.length > width) {
            lines.add(line.toString())
            line = StringBuilder()
        }
        if (line.isNotEmpty()) line.append(' ')
        line.append(word)
    }
    lines.add(line.toString())
    return lines
}
