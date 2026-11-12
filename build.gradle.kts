import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "droidmap"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    // The Kotlin compiler's own parser (PSI): the same one detekt and ktlint use.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.tomlj:tomlj:1.1.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The version lives here only: the code gets it as droidmap.VERSION.
val versionSource by tasks.registering {
    val dir = layout.buildDirectory.dir("generated/version")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(dir)
    doLast {
        val file = dir.get().file("droidmap/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package droidmap\n\nconst val VERSION = \"$v\"\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(versionSource) }

// The rule pages (docs/rules/*.md) go into the jar for `droidmap explain` and the SARIF help.
tasks.processResources { from("docs/rules") { into("rules") } }

application {
    mainClass.set("droidmap.MainKt")
    applicationName = "droidmap"
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// One runnable jar: java -jar build/libs/droidmap.jar
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds build/libs/droidmap.jar with every dependency inside."
    archiveFileName.set("droidmap.jar")
    manifest {
        attributes("Main-Class" to "droidmap.MainKt", "Implementation-Version" to project.version)
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

tasks.assemble { dependsOn(fatJar) }
