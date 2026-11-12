// Module 17's build. Versions live in gradle/libs.versions.toml (checked on 12 November 2026);
// transitive versions are locked in gradle.lockfile.
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // A refused build (a page naming a missing asset) and a refused setting are expected failures,
    // returned as values with sealed error types (code guidelines, rule 47).
    implementation(libs.result4j)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// ./gradlew check needs no Docker. The tests tagged "stack" need the running edge, shield and
// origin; scripts/stack.sh test starts them, runs those tests, and brings everything down.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    // The configuration rules read these files, so a change to any of them runs the tests again.
    systemProperty("seatwave.project.directory", projectDir.absolutePath)
    inputs.dir("nginx")
    inputs.file("compose.yaml")
    testLogging {
        events("failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the running stack; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // The origin's secret is read from .secrets/ in the project folder.
    systemProperty("seatwave.project.directory", projectDir.absolutePath)
    useJUnitPlatform {
        includeTags("stack")
    }
    // The stack is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The three demos (*Cli) are run by hand and their output is printed in the README and the module.
val demos = listOf("**/demo/**")

tasks.jacocoTestReport {
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(demos) })
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(demos) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// The 300,000-byte poster stand-in the demos also use (byte n is n x 31 modulo 256), written into
// site/static/ and site/share/ (both ignored by git), so the sample site has a real-sized image.
val prepareSampleSite = tasks.register("prepareSampleSite") {
    group = "seatwave"
    description = "Writes the 300,000-byte poster stand-in into site/static/ and site/share/."
    val posters = listOf(file("site/static/poster-arena.jpg"), file("site/share/poster-arena.jpg"))
    outputs.files(posters)
    doLast {
        val posterBytes = 300_000
        val bytePatternStep = 31
        val bytes = ByteArray(posterBytes) { index -> (index * bytePatternStep).toByte() }
        posters.forEach { poster ->
            poster.parentFile.mkdirs()
            poster.writeBytes(bytes)
        }
    }
}

// What the origin container runs: this project's jar and its runtime libraries, in one folder
// that compose.yaml mounts read-only (scripts/stack.sh up runs this task and versionAssets).
tasks.register<Sync>("stackFiles") {
    group = "seatwave"
    description = "Collects the jar and its runtime libraries into build/stack/lib for the origin."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("stack/lib"))
}

// One task per program, so a learner runs one command per example (code guidelines, rule 16).
// Paths in --args are read from the project folder, wherever Gradle was started.
fun registerProgram(taskName: String, programClass: String, text: String) =
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = text
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = programClass
        workingDir = projectDir
    }

// The build step of Figure 4: site/ to build/dist/, hashed names, manifest, pages rewritten.
registerProgram("versionAssets", "io.plainround.seatwave.cdn.assets.AssetVersioner", "Builds site/ into build/dist/").configure {
    dependsOn(prepareSampleSite)
    args("site", "build/dist")
}
registerProgram("edgeRules", "io.plainround.seatwave.cdn.demo.EdgeRulesDemoCli", "One edge through every rule on a stepped clock")
registerProgram("release", "io.plainround.seatwave.cdn.demo.ReleaseDemoCli", "A release under load, five ways (model)")
registerProgram("originLoad", "io.plainround.seatwave.cdn.demo.OriginLoadCli", "Origin load, first-visit time and egress (estimates)")
registerProgram("origin", "io.plainround.seatwave.cdn.origin.OriginServer", "The static origin: --args=\"build/dist 21700\"")
registerProgram("load", "io.plainround.seatwave.cdn.load.EdgeLoadClient", "First visits over HTTP: hit ratio, origin load, latency")
