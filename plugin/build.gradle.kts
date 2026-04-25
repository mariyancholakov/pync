plugins {
    id("java")
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.pync"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
    }
    implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<JavaCompile>().configureEach {
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

sourceSets {
    main {
        kotlin {
            exclude("com/pync/intellij/**")
        }
    }
    test {
        kotlin {
            exclude("**")
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "251"
        }
    }
    buildSearchableOptions = false
}
kotlin {
    jvmToolchain(8)
}