package io.github.hussainshirazi.droidmap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Runs {@code droidmap check} on the project. The droidmap release jar is downloaded once into Gradle's cache
 * folder and checked against the release's SHA-256. It always runs: its input is the whole project's source.
 */
public abstract class DroidmapCheck extends DefaultTask {
    private static final String RELEASES = "https://github.com/hussainshirazi/droidmap/releases/download/";

    @Internal public abstract DirectoryProperty getProjectDir();
    @Input public abstract Property<String> getFailOn();
    @Internal public abstract RegularFileProperty getConfig();
    @Input public abstract Property<String> getVersion();
    @Internal public abstract RegularFileProperty getJar();
    @Internal public abstract DirectoryProperty getCacheDir();

    @Inject protected abstract ExecOperations getExec();

    public DroidmapCheck() {
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    public void check() throws Exception {
        File jar = getJar().isPresent() ? getJar().get().getAsFile() : download(getVersion().get());
        List<String> args = new ArrayList<>(List.of("check", getProjectDir().get().getAsFile().getAbsolutePath(), "--fail-on", getFailOn().get()));
        if (getConfig().isPresent()) args.addAll(List.of("--config", getConfig().get().getAsFile().getAbsolutePath()));
        ExecResult result = getExec().javaexec(spec -> {
            spec.classpath(jar);
            spec.getMainClass().set("droidmap.MainKt");
            spec.args(args);
            spec.jvmArgs("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC");
            spec.setIgnoreExitValue(true);
        });
        switch (result.getExitValue()) {
            case 0 -> { }
            case 1 -> throw new GradleException("droidmap found problems at or above failOn = \"" + getFailOn().get()
                + "\" (listed above). `droidmap explain <rule>` says why each one matters and how to fix it.");
            default -> throw new GradleException("droidmap could not run (exit code " + result.getExitValue() + ")");
        }
    }

    /** The release jar in the cache, downloaded and checked first if it is not there yet. */
    private File download(String version) throws Exception {
        Path dir = getCacheDir().get().getAsFile().toPath().resolve(version);
        Path jar = dir.resolve("droidmap.jar");
        if (Files.isRegularFile(jar)) return jar.toFile();
        Files.createDirectories(dir);
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        String url = RELEASES + "v" + version + "/droidmap.jar";
        HttpResponse<String> sum = http.send(HttpRequest.newBuilder(URI.create(url + ".sha256")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (sum.statusCode() != 200) throw new GradleException("droidmap: HTTP " + sum.statusCode() + " for " + url + ".sha256 (is " + version + " released?)");
        String expected = sum.body().trim().split("\\s+")[0].toLowerCase();
        getLogger().lifecycle("droidmap: downloading droidmap {} (once, about 65 MB)", version);
        Path tmp = Files.createTempFile(dir, "droidmap-", ".part");
        try {
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new GradleException("droidmap: HTTP " + res.statusCode() + " for " + url);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equals(expected)) throw new GradleException("droidmap: the download's SHA-256 is " + actual + ", but the release says " + expected);
            try {
                Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (!Files.isRegularFile(jar)) Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return jar.toFile();
    }
}
