// The droidmap Gradle plugin (`./gradlew droidmapCheck`). Written in Java so that it runs on Gradle 8 as well as 9:
// a plugin compiled with a newer Gradle's Kotlin can call Kotlin library functions an older Gradle does not have.
// Build and test: ./gradlew fatJar && ./gradlew -p gradle-plugin test (the tests run the CLI jar built next door).
plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.hussainshirazi"
// The release workflow passes -Pversion=X.Y.Z; otherwise the CLI's version, which is the jar the plugin downloads.
if (version == Project.DEFAULT_VERSION) {
    version = Regex("""(?m)^version = "([^"]+)"""").find(file("../build.gradle.kts").readText())!!.groupValues[1]
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

gradlePlugin {
    website.set("https://github.com/hussainshirazi/droidmap")
    vcsUrl.set("https://github.com/hussainshirazi/droidmap")
    plugins {
        create("droidmap") {
            id = "io.github.hussainshirazi.droidmap"
            implementationClass = "io.github.hussainshirazi.droidmap.DroidmapPlugin"
            displayName = "droidmap"
            description = "Android checks (Room migrations, navigation, Hilt, Koin, Gradle modules, permissions) as a Gradle task: droidmapCheck."
            tags.set(listOf("android", "room", "navigation", "hilt", "koin", "static-analysis"))
        }
    }
}

tasks.processResources {
    val v = version.toString()
    inputs.property("version", v)
    filesMatching("droidmap-plugin.properties") { expand("version" to v) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty("droidmap.jar", file("../build/libs/droidmap.jar").absolutePath)
}
