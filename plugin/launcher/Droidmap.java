import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Starts droidmap for the Claude Code plugin, so the plugin needs nothing installed but Java 17 or newer.
 * The first time, it downloads the droidmap release that matches the plugin's version into the plugin's
 * data folder and checks it against the release's SHA-256; then it runs that jar in this JVM.
 *
 * Run as a source file: java Droidmap.java mcp
 * Environment: CLAUDE_PLUGIN_ROOT (the plugin, for its version) and CLAUDE_PLUGIN_DATA (where the jar is kept),
 * both set by Claude Code; DROIDMAP_VERSION overrides the version.
 */
public class Droidmap {
    private static final String RELEASES = "https://github.com/hussainshirazi/droidmap/releases/download/";

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() < 17) {
            fail("droidmap needs Java 17 or newer, and this is Java " + Runtime.version().feature()
                + ". Point DROIDMAP_JAVA at a newer java, such as Android Studio's jbr/bin/java.");
        }
        Path jar = jar();
        String main;
        try (JarFile jf = new JarFile(jar.toFile())) {
            main = jf.getManifest().getMainAttributes().getValue("Main-Class");
        }
        // Not closed: droidmap keeps loading classes from it for as long as it runs.
        URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        try {
            loader.loadClass(main).getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    /** The droidmap jar for this plugin version: the cached one, or else downloaded now (or an older cached one when offline). */
    private static Path jar() throws Exception {
        String version = version();
        Path dir = dataDir();
        Path jar = dir.resolve("droidmap-" + version + ".jar");
        if (Files.isRegularFile(jar)) return jar;
        Files.createDirectories(dir);
        try {
            download(version, jar);
            return jar;
        } catch (Exception e) {
            Path older = newestCached(dir);
            if (older == null) fail("droidmap could not download version " + version + ": " + e.getMessage());
            System.err.println("droidmap: could not download version " + version + " (" + e.getMessage() + "), so it runs " + older.getFileName());
            return older;
        }
    }

    private static String version() throws IOException {
        String fixed = System.getenv("DROIDMAP_VERSION");
        if (fixed != null && !fixed.isBlank()) return fixed.trim();
        String root = System.getenv("CLAUDE_PLUGIN_ROOT");
        if (root == null) fail("droidmap: CLAUDE_PLUGIN_ROOT is not set (Claude Code sets it for the plugin), and neither is DROIDMAP_VERSION");
        String manifest = Files.readString(Path.of(root, ".claude-plugin", "plugin.json"));
        Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([0-9][0-9A-Za-z.+-]*)\"").matcher(manifest);
        if (!m.find()) fail("droidmap: no version in " + Path.of(root, ".claude-plugin", "plugin.json"));
        return m.group(1);
    }

    private static Path dataDir() {
        String data = System.getenv("CLAUDE_PLUGIN_DATA");
        return data != null && !data.isBlank() ? Path.of(data) : Path.of(System.getProperty("user.home"), ".droidmap", "cache");
    }

    /** Downloads the release jar to a temporary file, checks its SHA-256, and moves it into place. */
    private static void download(String version, Path target) throws Exception {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        String base = RELEASES + "v" + version + "/droidmap.jar";
        HttpResponse<String> sum = http.send(HttpRequest.newBuilder(URI.create(base + ".sha256")).timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (sum.statusCode() != 200) throw new IOException("HTTP " + sum.statusCode() + " for " + base + ".sha256");
        String expected = sum.body().trim().split("\\s+")[0].toLowerCase();
        System.err.println("droidmap: downloading droidmap " + version + " (once, about 65 MB) from " + base);
        Path tmp = Files.createTempFile(target.getParent(), "droidmap-", ".part");
        try {
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofMinutes(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " for " + base);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = res.body(); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equals(expected)) throw new IOException("the download's SHA-256 is " + actual + ", but the release says " + expected);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (!Files.isRegularFile(target)) Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); // another start got there first otherwise
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Path newestCached(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("droidmap-.*\\.jar"))
                .max(Comparator.comparing(p -> p.toFile().lastModified())).orElse(null);
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
