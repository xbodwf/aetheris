plugins {
    id("fabric-loom")
}

dependencies {
    minecraft("com.mojang:minecraft:${project.findProperty("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${project.findProperty("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.findProperty("fabric_api_version")}")

    implementation(project(":common"))
    implementation(project(":engine"))
    modImplementation("net.fabricmc:fabric-language-kotlin:1.12.3+kotlin.2.0.21")
    compileOnly("org.spongepowered:mixin:0.8.7")
    annotationProcessor("org.spongepowered:mixin:0.8.7")
    annotationProcessor("com.google.code.gson:gson:2.10.1")
    annotationProcessor("com.google.guava:guava:33.0.0-jre")
}

sourceSets {
    main {
        java {
            srcDir(project(":common").sourceSets["main"].java)
        }
        kotlin {
            srcDir(project(":common").sourceSets["main"].kotlin)
        }
        resources {
            srcDir(project(":common").sourceSets["main"].resources)
            exclude("architectury.common.json")
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.remapJar {
    archiveBaseName.set("${rootProject.findProperty("archives_base_name")}-fabric${project.findProperty("minecraft_version")}")
}

val commonSource = project(":common").sourceSets["main"]
val engineSource = project(":engine").sourceSets["main"]
tasks.jar {
    from(commonSource.output)
    from(engineSource.output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
