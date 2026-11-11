package droidmap

import droidmap.extract.GradleLexer
import droidmap.extract.routeMatches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinTest {
    private fun source(path: String, text: String) = KotlinReader.read(path, null, "main", text)

    @Test
    fun evaluatorResolvesConstantsTemplatesAndFunctions() {
        val a = source("r/Routes.kt", """
            package r
            import r.args.Args
            private const val BASE = "editor"
            const val EDITOR = "${'$'}BASE?${'$'}{Args.ID}={${'$'}{Args.ID}}" +
                "&shared={shared}"
            fun editorRoute(id: Long) = "${'$'}BASE?${'$'}{Args.ID}=${'$'}id"
            fun other(id: Long): String { return BASE + "/" + id }
            const val VERSION = 2 + 1
            val X = other(5)
            val Y = editorRoute(1).lowercase()
        """.trimIndent())
        val b = source("r/args/Args.kt", "package r.args\nclass Args { companion object { const val ID = \"id\" } }")
        val ev = Evaluator(Index(listOf(a, b)))
        fun value(name: String) = ev.eval(a.fields.first { it.name == name }.initializer)

        assertEquals("editor?id={id}&shared={shared}", value("EDITOR"))
        assertEquals("editor?id=$WILD", ev.eval((a.fns.first { it.name == "editorRoute" }.psi as org.jetbrains.kotlin.psi.KtNamedFunction).bodyExpression))
        assertEquals(3L, value("VERSION"))
        assertEquals("editor/$WILD", value("X"))
        assertNull(value("Y"))
    }

    @Test
    fun routeMatching() {
        assertTrue(routeMatches("editor?id={id}&shared={shared}", "editor?id=$WILD"))
        assertTrue(routeMatches("detail/{id}", "detail/$WILD"))
        assertFalse(routeMatches("detail/{id}", "detail"))
        assertFalse(routeMatches("editor?id={id}", "editor?other=$WILD"))
        assertFalse(routeMatches("home", "settings"))
    }

    @Test
    fun gradleLexerSurvivesGarbageAndKeepsLines() {
        val toks = GradleLexer.lex("/* a /* nested */ b */ include(\":app\") // tail\nimplementation project('x')\n\"unterminated")
        assertEquals(listOf("include", "(", ":app", ")", "implementation", "project", "(", "x", ")", "unterminated"), toks.map { it.text })
        assertEquals(2, toks[4].line)
        assertNull(GradleLexer.lex("\"a\$b\"")[0].plainString) // a template is not a plain string
    }

    @Test
    fun globMatching() {
        assertTrue(glob(":feature:home", ":feature:*"))
        assertTrue(glob("benchmarks/src/a.kt", "benchmarks/**"))
        assertFalse(glob(":core:data", ":feature:*"))
        assertTrue(glob("a1", "a[0-9]"))
    }

    @Test
    fun badKotlinNeverThrows() {
        val s = source("Bad.kt", "class { fun ( val = \n @@@ \"unterminated")
        Index(listOf(s))
        assertEquals("", s.pkg)
    }
}
