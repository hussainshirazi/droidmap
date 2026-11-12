package io.github.hussainshirazi.droidmap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;

/**
 * Adds {@code droidmapCheck} to the root project: droidmap's Android checks, failing the build on findings at
 * {@code droidmap.failOn} or worse. Apply it to the root project only.
 */
public class DroidmapPlugin implements Plugin<Project> {
    /** process(Flavor)ReleaseMainManifest: the task that writes a variant's merged manifest. */
    private static final Pattern RELEASE_MANIFEST = Pattern.compile("process\\w*ReleaseMainManifest");

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("Apply io.github.hussainshirazi.droidmap to the root project: droidmap checks the whole project at once.");
        }
        DroidmapExtension ext = project.getExtensions().create("droidmap", DroidmapExtension.class);
        ext.getFailOn().convention("error");
        ext.getMergedManifest().convention(false);
        ext.getVersion().convention(pluginVersion());

        project.getTasks().register("droidmapCheck", DroidmapCheck.class, t -> {
            t.setGroup("verification");
            t.setDescription("Checks the Android wiring with droidmap: Room migrations and schemas, navigation, Hilt and Koin, Gradle modules, permissions.");
            t.getProjectDir().set(project.getLayout().getProjectDirectory());
            t.getFailOn().set(ext.getFailOn());
            t.getConfig().set(ext.getConfig());
            t.getVersion().set(ext.getVersion());
            t.getJar().set(ext.getJar());
            t.getCacheDir().set(new File(project.getGradle().getGradleUserHomeDir(), "caches/droidmap"));
            // Resolved when the task graph is built, after every module is configured.
            t.dependsOn((Callable<List<Task>>) () -> {
                List<Task> merges = new ArrayList<>();
                if (!ext.getMergedManifest().get()) return merges;
                for (Project p : project.getAllprojects()) {
                    merges.addAll(p.getTasks().matching(x -> RELEASE_MANIFEST.matcher(x.getName()).matches()));
                }
                if (merges.isEmpty()) project.getLogger().warn("droidmap: mergedManifest is on, but no module has a process...ReleaseMainManifest task");
                return merges;
            });
        });
    }

    private static String pluginVersion() {
        try (InputStream in = DroidmapPlugin.class.getResourceAsStream("/droidmap-plugin.properties")) {
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
