plugins {
    id("fabric-loom")
}

dependencies {
    minecraft("com.mojang:minecraft:${project.findProperty("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    compileOnly("net.fabricmc:fabric-loader:${project.findProperty("fabric_loader_version")}")
    compileOnly("org.spongepowered:mixin:0.8.7")
    annotationProcessor("org.spongepowered:mixin:0.8.7")
    annotationProcessor("com.google.code.gson:gson:2.10.1")
    annotationProcessor("com.google.guava:guava:33.0.0-jre")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${project.findProperty("kotlin_version")}")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation(project(":engine"))
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}
