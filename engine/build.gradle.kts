plugins {
    `java-library`
}

// Pure JVM module: no Minecraft, no loader, no mixin. The simulation core must be
// compilable and testable on its own so chemistry bugs never hide behind the game toolchain.
dependencies {
    // Gson is a plain JVM library (no Minecraft), used only to read species data files.
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}
