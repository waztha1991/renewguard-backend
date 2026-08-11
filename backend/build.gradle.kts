plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.insurance.renewal"
version = "1.0.0"

application {
    mainClass.set("com.insurance.renewal.backend.ApplicationKt")
}

dependencies {
    val ktor = "2.3.12"
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktor")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.eclipse.angus:angus-mail:2.0.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // Allow binding from emulator / LAN
    systemProperty("io.ktor.development", "true")
}
