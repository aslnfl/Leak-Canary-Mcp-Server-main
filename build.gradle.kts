plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("com.gradleup.shadow") version "9.0.0-beta4"
    application
}

group = "com.leakcanary.mcp"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk:0.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    // LeakCanary's shark library for deserializing heap analysis BLOBs from the app's database
    implementation("com.squareup.leakcanary:shark:2.14")
}

application {
    mainClass.set("com.leakcanary.mcp.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveBaseName.set("leakcanary-mcp-server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}
