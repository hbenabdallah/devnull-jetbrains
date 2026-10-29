plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "io.github.hbenabdallah"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.5")
        bundledPlugin("Git4Idea")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    // The live test against a /dev/null site only runs when one is given.
    environment("DEVNULL_E2E_URL", System.getenv("DEVNULL_E2E_URL") ?: "")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
