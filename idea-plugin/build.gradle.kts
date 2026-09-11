// IDEA plugin for AetherCode.
//
// Uses gradle-intellij-plugin. Build with:
//   ./gradlew :idea-plugin:buildPlugin
//
// The plugin hosts a ToolWindow with a chat panel backed by the same
// `org.aethercode.sdk.AetherCodeEngine` that the CLI uses.

plugins {
    kotlin("jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "org.aethercode"
version = "0.1.0"

repositories {
    mavenCentral()
    // Local Maven repo where the aethercode modules are installed.
    maven {
        name = "aethercodeLocal"
        url = uri("${rootDir}/../cache/mvn_repo")
    }
}

dependencies {
    implementation("org.aethercode:aethercode-core:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-llm:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-tools:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-permission:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-memory:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-prompts:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-sdk:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-tui:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-mcp:0.1.0-SNAPSHOT")
    implementation("org.aethercode:aethercode-bridge:0.1.0-SNAPSHOT")

    implementation(kotlin("stdlib-jdk8"))

    // R7: unit tests for the integration services. These cover the
    // pure-logic paths (snapshot equality, palette slots, daemon wire
    // format). Platform-touching paths (LaF listener, FileEditorManager)
    // are covered by IntelliJ's light test fixtures in a later round.
    testImplementation("junit:junit:4.13.2")
}

intellij {
    version.set("2024.3")
    type.set("IC")
    plugins.set(listOf())
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
