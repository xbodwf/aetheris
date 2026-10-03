buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${property("kotlin_version")}")
        classpath("com.google.code.gson:gson:2.10.1")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<BasePluginExtension> {
        archivesName.set("${rootProject.property("archives_base_name")}-${project.name}")
    }

    version = rootProject.property("mod_version") as String
    group = rootProject.property("maven_group") as String

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
    }

    repositories {
        mavenCentral()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases/") }
        maven { url = uri("https://jitpack.io") }
    }

}
