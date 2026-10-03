plugins {
    id("net.neoforged.gradle.userdev")
}

base {
    archivesName.set("${rootProject.findProperty("archives_base_name")}-neoforge${project.findProperty("minecraft_version")}")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

runs {
    configureEach {
        workingDirectory.set(project.layout.projectDirectory.dir("run").dir(name))
        modSource(project.sourceSets.main.get())
    }
    register("client")
    register("server")
}

dependencies {
    implementation("net.neoforged:neoforge:${project.findProperty("neoforge_version")}")
    implementation(project(":common"))
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${project.findProperty("kotlin_version")}")
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
            exclude("aetheris.accesswidener")
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    from(project(":common").sourceSets["main"].output)
    from(project(":engine").sourceSets["main"].output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}
