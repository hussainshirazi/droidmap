package io.github.hussainshirazi.droidmap;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * <pre>
 * droidmap {
 *     failOn = "warning"      // error (the default), warning, info or never
 *     mergedManifest = true   // run each processReleaseMainManifest first, so library permissions are checked too
 * }
 * </pre>
 */
public abstract class DroidmapExtension {
    /** Fail the build on findings this severe or worse: error (the default), warning, info, or never. */
    public abstract Property<String> getFailOn();

    /** Run the release manifest merge of every module first, so the permissions libraries add are checked too. */
    public abstract Property<Boolean> getMergedManifest();

    /** A config file other than .droidmap.toml at the project root. */
    public abstract RegularFileProperty getConfig();

    /** The droidmap release to run; by default the plugin's own version. */
    public abstract Property<String> getVersion();

    /** A droidmap.jar to run instead of downloading one (offline builds, or a build of droidmap itself). */
    public abstract RegularFileProperty getJar();
}
