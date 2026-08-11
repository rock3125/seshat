// Imported rather than fully qualified: inside the Gradle Kotlin DSL, `java`
// resolves to the Java plugin's extension, so `java.util.zip.ZipFile` does not
// name a class at all.
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Qdrant's official gRPC client (port 6334, not the REST 6333). Its
    // grpc/protobuf stack is declared `runtime` scope, so the generated message
    // supertypes are absent at COMPILE time — add them explicitly, versions
    // aligned with the client's own grpc 1.65.1 pin.
    implementation("io.qdrant:client:1.12.0")
    implementation("io.grpc:grpc-protobuf:1.65.1")
    implementation("io.grpc:grpc-stub:1.65.1")
    implementation("com.google.protobuf:protobuf-java:3.25.3")

    // Everything else is hand-rolled on the JDK: java.net.http for Gemini and
    // the JWKS fetch, com.sun.net.httpserver for the server, org.json for the
    // wire formats. No web framework, no JSON binding layer.
    implementation("org.json:json:20240303")

    // Postgres holds the chunks (and the document registry the library scanner
    // diffs against).
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}

// --- the fat jar -------------------------------------------------------------
//
// `java -jar build/libs/gateway-all.jar`, as the brief asks. Flattening jars
// into one archive has exactly one trap, and gRPC walks straight into it:
//
//   META-INF/services/* is a PLUGIN REGISTRY, not a resource. Several jars
//   legitimately publish the same file name with different contents —
//   grpc-core registers DnsNameResolverProvider, grpc-netty-shaded registers
//   UdsNameResolverProvider, both under io.grpc.NameResolverProvider. Any
//   duplicate strategy PICKS ONE, so the jar builds and runs and then fails at
//   the first connection with "Address types of NameResolver 'unix' for
//   'qdrant:6334' not supported by transport" — DNS resolution having silently
//   vanished from the build.
//
// So service files are concatenated rather than chosen between. This is the
// one thing the Shadow plugin would be doing for us; it is thirty lines, and
// it costs no plugin whose version has to track Gradle's.

val mergedServices = layout.buildDirectory.dir("merged-services")

val mergeServiceFiles = tasks.register("mergeServiceFiles") {
    val runtimeJars = configurations.runtimeClasspath
    val outputDir = mergedServices
    inputs.files(runtimeJars)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile.resolve("META-INF/services")
        target.deleteRecursively()
        target.mkdirs()
        // LinkedHashSet: registration order is meaningful to some providers,
        // and a duplicate line is not.
        val registry = linkedMapOf<String, LinkedHashSet<String>>()
        for (file in runtimeJars.get().filter { it.name.endsWith(".jar") }) {
            ZipFile(file).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (entry.isDirectory) continue
                    if (!entry.name.startsWith("META-INF/services/")) continue
                    val name = entry.name.removePrefix("META-INF/services/")
                    if (name.isEmpty() || name.contains('/')) continue
                    val lines = zip.getInputStream(entry).bufferedReader()
                        .readLines().map { it.trim() }.filter { it.isNotEmpty() }
                    registry.getOrPut(name) { linkedSetOf() }.addAll(lines)
                }
            }
        }
        for ((name, providers) in registry) {
            target.resolve(name).writeText(providers.joinToString("\n", postfix = "\n"))
        }
    }
}

val fatJar = tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = "MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(mergeServiceFiles)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        // The merged copies above are the only service files in the archive.
        exclude("META-INF/services/**")
        // A signed dependency's signature covers ITS jar, not this one — left
        // in, the JVM refuses to load the classes beside them.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
}

tasks.named("build") { dependsOn(fatJar) }
