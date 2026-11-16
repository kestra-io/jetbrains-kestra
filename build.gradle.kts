import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Stay within the Kotlin stdlib bundled by the lowest supported IDE (2025.3 ships 2.2).
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugins("org.jetbrains.plugins.yaml", "com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release version (1.2.0-beta.1) goes to the "beta" channel, a plain version to the default one.
        channels = providers.gradleProperty("version").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    // The lowest supported release and the latest one of IntelliJ IDEA, plus PyCharm and WebStorm,
    // where data and frontend engineers write flows.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("latestPlatformVersion"))
            create(IntelliJPlatformType.PyCharm, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.WebStorm, providers.gradleProperty("platformVersion"))
        }
    }
}

tasks.test {
    // Close to an IDE default heap: the instance flow schema is tens of MB.
    maxHeapSize = "2g"
    // Live-instance tests read these, see CONTRIBUTING.md.
    listOf("KESTRA_TEST_URL", "KESTRA_TEST_USERNAME", "KESTRA_TEST_PASSWORD").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
