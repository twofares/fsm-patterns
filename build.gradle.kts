// Build for a zero-FSM-library Kotlin sample.
// Dependencies are limited to the Kotlin stdlib (via the plugin) and ordinary
// unit-test tooling (JUnit). No state-machine, statechart, or FSM engines.
plugins {
    // Kotlin 2.1.21+ needed for broader JDK recognition; build runs on JDK 17–21.
    kotlin("jvm") version "2.1.21"
    application
}

group = "fsm.sample"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Test framework only — not used at runtime and not an FSM/statechart library.
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    // Demo entry that exercises multiple patterns with consistent console output.
    mainClass.set("fsm.sample.DemoMainKt")
}

// PURPOSE: Dedicated launch for product-evaluated tables + Mermaid export.
// `gradle runExport` — deterministic multi-machine Markdown/Mermaid document.
tasks.register<JavaExec>("runExport") {
    group = "application"
    description = "Print transition tables and Mermaid diagrams (stdlib export)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("fsm.sample.ExportMainKt")
}
