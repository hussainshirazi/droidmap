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
    fun versionCatalogPlugins() {
        val plugins = droidmap.extract.catalogPlugins("""
            [versions]
            agp = "9.0"
            [plugins]
            android-application = { id = "com.android.application", version.ref = "agp" }
            kotlin_jvm = "org.jetbrains.kotlin.jvm:2.4.21" # a comment
            "quoted-key" = { id = 'x.quoted' }
            multi = {
                id = "x.multi",
                version.ref = "agp"
            }
            [plugins.table-style]
            id = "x.table"
            version = "1"
            [libraries]
            core = { module = "a:b", version = "1" }
        """.trimIndent())
        assertEquals(mapOf("android.application" to "com.android.application", "kotlin.jvm" to "org.jetbrains.kotlin.jvm",
            "quoted.key" to "x.quoted", "multi" to "x.multi", "table.style" to "x.table"), plugins)
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

    @Test
    fun readmeListsEveryKindAndRule() {
        val readme = java.nio.file.Files.readString(java.nio.file.Path.of("README.md"))
        fun listed(heading: String) = Regex("$heading: (.*)\\.").find(readme)!!.groupValues[1].split(", ").map { it.trim('`') }.toSet()
        assertEquals(NODE_KINDS - "ref", listed("Node kinds"))
        assertEquals(EDGE_KINDS, listed("Edge kinds"))
        for (r in Rule.entries) assertTrue("| `${r.id}` | ${r.severity} |" in readme, "README's rules table misses ${r.id} (${r.severity})")
    }
}
