plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "com.github.emotionbug"
version = System.getenv("PLUGIN_VERSION") ?: "dev"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("com.intellij.platform.images")
    }

    implementation("org.apache.poi:poi-ooxml:5.5.1")

    implementation("org.seleniumhq.selenium:selenium-java:4.39.0")
    implementation("io.github.bonigarcia:webdrivermanager:6.3.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            1.0.0
            <ul>
                <li>Initial release of Mermaid Live Editor</li>
                <li>Real-time preview of Mermaid diagrams</li>
                <li>Zoom and Pan functionality with mouse</li>
                <li>Export diagrams to SVG and PPTX</li>
                <li>Support for built-in, CDN, and local Mermaid.js files</li>
                <li>Syntax highlighting and error annotations</li>
            </ul>
        """.trimIndent()

        vendor {
            name = "emotionbug"
            email = "take-me-home@kakao.com"
            url = "https://github.com/emotionbug"
        }
    }

    signing {
        certificateChain.set(
            providers.environmentVariable("CERTIFICATE_CHAIN")
        )
        privateKey.set(
            providers.environmentVariable("PRIVATE_KEY")
        )
        password.set(
            providers.environmentVariable("PRIVATE_KEY_PASSWORD")
        )
    }

    publishing {
        token.set(
            providers.environmentVariable("PUBLISH_TOKEN")
        )
        channels.set(listOf("default"))
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    instrumentCode {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
