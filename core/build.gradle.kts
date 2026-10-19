plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.affixio.door.CliKt")
}

tasks.test {
    useJUnitPlatform()
}

// Non-zero exit means "not admitted"; keep Gradle quiet about it. Use installDist for scripts.
tasks.named<JavaExec>("run") {
    isIgnoreExitValue = true
}
