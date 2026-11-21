plugins {
    `java-library`
    `java-test-fixtures`
    application
    id("org.graalvm.buildtools.native") version "0.11.1"
}

group = "io.artemis"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

val micronautVersion = "4.10.6"
val lombokVersion = "1.18.42"

dependencies {
    implementation(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    testAnnotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))

    // Lombok must generate constructors before Micronaut inspects the AST.
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut:micronaut-graal")
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")

    implementation("io.micronaut:micronaut-runtime")
    implementation("org.rocksdb:rocksdbjni:10.2.1")
    runtimeOnly("ch.qos.logback:logback-classic")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    // Apply to both main and test compilation, including transitive processors.
    doFirst {
        options.annotationProcessorPath = files(options.annotationProcessorPath!!.files.sortedBy {
            if (it.name.startsWith("lombok-")) 0 else 1
        })
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    providers.gradleProperty("demoResultsDir").orNull?.let {
        reports.junitXml.outputLocation.set(file(it))
    }
    systemProperty("artemis.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

application {
    mainClass.set("io.artemis.Application")
}

graalvmNative {
    // Use JAVA_HOME/GRAALVM_HOME explicitly when a regular JDK is also installed.
    toolchainDetection.set(false)
    metadataRepository {
        enabled.set(true)
        version.set("0.3.23")
    }
    binaries {
        named("main") {
            imageName.set("artemis-spike")
            mainClass.set(application.mainClass)
            sharedLibrary.set(false)
            fallback.set(false)
            buildArgs.add("--initialize-at-run-time=org.rocksdb")
        }
    }
}

tasks.register<Exec>("nativeRocksDbSpike") {
    group = "verification"
    description = "Builds and tests RocksDB in independent native processes, including error paths."
    dependsOn("nativeCompile")
    commandLine("bash", "demo/native-smoke.sh",
        layout.buildDirectory.file("native/nativeCompile/artemis-spike").get().asFile.absolutePath)
}

tasks.register<JavaExec>("rocksDbSpike") {
    group = "verification"
    description = "Runs the RocksDB batch/reopen smoke test on the JVM."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    args("roundtrip", layout.buildDirectory.dir("rocksdb-spike-jvm").get().asFile.absolutePath)
}

