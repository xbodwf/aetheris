pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases/") }
    }
    plugins {
        id("fabric-loom") version("1.10-SNAPSHOT")
        id("net.neoforged.gradle.userdev") version("7.1.38")
        id("org.jetbrains.kotlin.jvm") version("2.0.0")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
}

include("engine")
include("common")
include("fabric")
include("neoforge")

rootProject.name = "aetheris"
