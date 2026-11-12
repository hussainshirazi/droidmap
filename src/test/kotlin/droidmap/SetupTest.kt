package droidmap

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Setting droidmap up (init, finding the project) and how its terminal output looks. */
class SetupTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun findsTheProjectRootFromASubfolder() {
        Files.writeString(tmp.resolve("settings.gradle.kts"), "include(\":app\")\n")
        val deep = Files.createDirectories(tmp.resolve("app/src/main"))
        assertEquals(tmp, findProjectRoot(deep))
        assertNull(findProjectRoot(Files.createTempDirectory("no-project")))
    }

    @Test
    fun initWritesTheWorkflowAndMcpConfigButNeverOverwrites() {
        val project = Files.createDirectories(tmp.resolve("android"))
        Files.createDirectories(tmp.resolve(".vscode"))
        Files.writeString(tmp.resolve(".mcp.json"), "{\"mcpServers\": {\"other\": {}}}\n")

        val steps = initProject(project, tmp)
        assertEquals(listOf(".github/workflows/droidmap.yml", ".mcp.json", ".vscode/mcp.json"), steps.map { it.file })
        assertEquals(listOf(true, false, true), steps.map { it.created })

        val workflow = Files.readString(tmp.resolve(".github/workflows/droidmap.yml"))
        assertTrue("uses: hussainshirazi/droidmap@v0" in workflow && "path: android" in workflow, workflow)
        assertTrue("\"servers\"" in Files.readString(tmp.resolve(".vscode/mcp.json")))
        // The existing .mcp.json is left alone; the output says what to add to it.
        assertEquals("{\"mcpServers\": {\"other\": {}}}\n", Files.readString(tmp.resolve(".mcp.json")))
        assertTrue("\"args\": [\"mcp\", \"android\"]" in steps[1].snippet!!)
        assertTrue("Add this to it" in formatInit(steps))

        // A second run changes nothing and has nothing more to say about files that mention droidmap.
        val again = initProject(project, tmp)
        assertTrue(again.none { it.created })
        assertEquals(1, again.count { it.snippet != null })
    }

    @Test
    fun colourOnlyWhenWantedAndShown() {
        val terminal = { true }
        assertFalse(Style.detect(mapOf("NO_COLOR" to "1", "FORCE_COLOR" to "1"), terminal).severity("error").contains('\u001B'))
        assertTrue(Style.detect(mapOf("FORCE_COLOR" to "1")) { false }.severity("error").contains('\u001B'))
        assertFalse(Style.detect(emptyMap()) { false }.bold("x").contains('\u001B'))
        assertFalse(Style.detect(mapOf("TERM" to "dumb"), terminal).bold("x").contains('\u001B'))
        assertEquals("x", Style.PLAIN.bold("x"))
    }

    @Test
    fun longMessagesWrapUnderTheFinding() {
        assertEquals(listOf("aaa bbb", "ccc"), wrapText("aaa bbb ccc", 7))
        assertEquals(listOf("averyveryverylongword", "b"), wrapText("averyveryverylongword b", 5))
        val f = Finding("room-migration-path", "error", "word ".repeat(40).trim(), "a/B.kt", 3, null)
        val lines = formatFindings(listOf(f)).lines()
        assertTrue(lines[0].startsWith("error   room-migration-path") && lines[0].endsWith("a/B.kt:3"), lines[0])
        assertTrue(lines.drop(1).takeWhile { it.isNotEmpty() }.all { it.startsWith("        ") && it.length <= 100 })
        assertEquals("1 error", lines.last())
    }
}
