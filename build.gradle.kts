import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

changelog {
    version = providers.gradleProperty("version")
    repositoryUrl = "https://github.com/kestra-io/jetbrains-kestra"
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
        // The IDE's What's New tab: the CHANGELOG.md section of this version, or Unreleased when it has none.
        // A local variable, so the lambda does not capture the project, which the configuration cache rejects.
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("version").map { version ->
            with(changelog) {
                renderItem(
                    (getOrNull(version) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
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

    // API compatibility with every IDE of verifyIdes, at the lowest supported release and the latest one.
    // -PverifyIdes=GO checks another IDE. Local only: CI downloads no IDE but the one `test` builds against.
    pluginVerification {
        ides {
            // 2025.3, not 2025.3.6: each IDE numbers its bugfix releases differently (there is no GoLand 2025.3.6),
            // and the first release of the line is the lowest one sinceBuild accepts.
            val lowest = providers.gradleProperty("platformVersion").map { it.split('.').take(2).joinToString(".") }
            providers.gradleProperty("verifyIdes").get().split(',').map(String::trim).filter(String::isNotEmpty).forEach { code ->
                val type = IntelliJPlatformType.fromCode(code)
                create(type, lowest)
                create(type, providers.gradleProperty("latestPlatformVersion"))
            }
        }
    }
}

// The test suite inside other IDEs than the one `test` uses (IntelliJ IDEA, lowest release). The Plugin Verifier
// misses what only fails once the plugin loads, such as 2026.x moving JCEF to a bundled plugin.
val testIdes = mapOf(
    "testIdeaLatest" to (IntelliJPlatformType.IntellijIdea to "latestPlatformVersion"),
    "testPyCharm" to (IntelliJPlatformType.PyCharm to "platformVersion"),
    "testPyCharmLatest" to (IntelliJPlatformType.PyCharm to "latestPlatformVersion"),
)

// Sandbox IDEs with the plugin loaded, for manual QA outside IntelliJ IDEA (`runIde`), at the latest release.
val runIdes = mapOf(
    "runPyCharm" to IntelliJPlatformType.PyCharm,
    "runWebStorm" to IntelliJPlatformType.WebStorm,
    "runGoLand" to IntelliJPlatformType.GoLand,
)

intellijPlatformTesting {
    testIdes.forEach { (name, target) ->
        testIde.register(name) {
            type = target.first
            version = providers.gradleProperty(target.second)
            plugins {
                bundledPlugins("org.jetbrains.plugins.yaml", "com.intellij.modules.json")
            }
            testFramework(TestFrameworkType.Platform)
            task {
                kestraTestSettings()
                // These tasks only get the instrumented test classes, not the test resources (graph.json…).
                classpath += files(sourceSets.test.map { it.output.resourcesDir!! }).builtBy(tasks.processTestResources)
            }
        }
    }
    runIdes.forEach { (name, ide) ->
        runIde.register(name) {
            type = ide
            version = providers.gradleProperty("latestPlatformVersion")
        }
    }
}

tasks.register("testAllIdes") {
    group = "verification"
    description = "Runs the tests inside every IDE of testIdes, plus the default test task."
    dependsOn(tasks.test, testIdes.keys)
}

tasks.test {
    kestraTestSettings()
}

fun Test.kestraTestSettings() {
    // Close to an IDE default heap: the instance flow schema is tens of MB.
    maxHeapSize = "2g"
    // Live-instance tests read these, see CONTRIBUTING.md.
    listOf("KESTRA_TEST_URL", "KESTRA_TEST_USERNAME", "KESTRA_TEST_PASSWORD", "KESTRA_TEST_TENANT").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
