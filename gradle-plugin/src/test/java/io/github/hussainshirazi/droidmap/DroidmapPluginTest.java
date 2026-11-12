package io.github.hussainshirazi.droidmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real Gradle builds of a small project, running the droidmap jar built by the main build (system property droidmap.jar). */
public class DroidmapPluginTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private void write(String rel, String text) throws IOException {
        File f = new File(tmp.getRoot(), rel);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text);
    }

    private GradleRunner gradle(String... args) {
        return GradleRunner.create().withProjectDir(tmp.getRoot()).withPluginClasspath().withArguments(args);
    }

    private void project(String failOn) throws IOException {
        String jar = System.getProperty("droidmap.jar").replace('\\', '/');
        write("settings.gradle.kts", "rootProject.name = \"demo\"\ninclude(\":app\")\n");
        write("build.gradle.kts", "plugins { id(\"io.github.hussainshirazi.droidmap\") }\n"
            + "droidmap {\n    jar.set(file(\"" + jar + "\"))\n    failOn.set(\"" + failOn + "\")\n}\n");
        write("app/build.gradle.kts", "");
        // A warning: fallbackToDestructiveMigration (droidmap reads the source; this build does not compile it).
        write("app/src/main/kotlin/com/x/Db.kt", "package com.x\nimport androidx.room.Room\n\n"
            + "fun db(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, \"x\").fallbackToDestructiveMigration().build()\n");
    }

    @Test
    public void checkFailsOnFindingsAtFailOnOrWorse() throws IOException {
        project("warning");
        BuildResult r = gradle("droidmapCheck").buildAndFail();
        assertTrue(r.getOutput(), r.getOutput().contains("room-destructive-fallback"));
        assertTrue(r.getOutput(), r.getOutput().contains("droidmap found problems at or above failOn = \"warning\""));
    }

    @Test
    public void checkPassesBelowFailOn() throws IOException {
        project("error");
        BuildResult r = gradle("droidmapCheck").build();
        assertEquals(TaskOutcome.SUCCESS, r.task(":droidmapCheck").getOutcome());
        assertTrue(r.getOutput(), r.getOutput().contains("room-destructive-fallback"));
        // It runs every time: its input is the whole project's source.
        assertEquals(TaskOutcome.SUCCESS, gradle("droidmapCheck", "--configuration-cache").build().task(":droidmapCheck").getOutcome());
    }

    @Test
    public void runsOnGradle8() throws IOException {
        // Most Android projects (AGP 8) build with Gradle 8: the plugin must not need anything newer.
        // (Gradle 8.7 runs on Java 21 at most; CI runs this on Java 17.)
        Assume.assumeTrue("Gradle 8.7 needs Java 21 or older", Runtime.version().feature() <= 21);
        project("error");
        BuildResult r = gradle("droidmapCheck").withGradleVersion("8.7").build();
        assertEquals(TaskOutcome.SUCCESS, r.task(":droidmapCheck").getOutcome());
    }

    @Test
    public void onlyOnTheRootProject() throws IOException {
        write("settings.gradle.kts", "include(\":app\")\n");
        write("app/build.gradle.kts", "plugins { id(\"io.github.hussainshirazi.droidmap\") }\n");
        assertTrue(gradle("help").buildAndFail().getOutput().contains("Apply io.github.hussainshirazi.droidmap to the root project"));
    }
}
