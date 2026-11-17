package io.kestra.jetbrains

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.jcef.JBCefApp
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.file.Path

// A class the plugin uses can live in the core of one IDE release and in a bundled plugin of another: JCEF moved to
// one in 2026.x. Without a <depends> on that plugin, the plugin class loader cannot see the class and the feature
// fails at runtime, which the Plugin Verifier does not report. Run inside each IDE by the testAllIdes tasks.
class PluginDependenciesTest : BasePlatformTestCase() {
    // One class per bundled plugin the code imports from.
    private val usedClasses = listOf(JBCefApp::class.java, YAMLFile::class.java, JsonSchemaService::class.java)

    fun testPluginsProvidingUsedClassesAreDeclared() {
        val self = PluginManagerCore.getPlugin(PluginId.getId("io.kestra.jetbrains"))
            ?: error("The Kestra plugin is not loaded in the test IDE")
        @Suppress("DEPRECATION")
        val declared = self.dependencies.map { it.pluginId }.toSet()
        // A class's plugin is the one whose directory holds its jar; the core's classes are under the IDE's lib.
        val plugins = PluginManagerCore.loadedPlugins.filter { it.pluginId != PluginManagerCore.CORE_ID && it.pluginId != self.pluginId }
        for (cls in usedClasses) {
            val jar = jarOf(cls)
            val owner = plugins.firstOrNull { jar.startsWith(it.pluginPath) } ?: continue
            assertTrue(
                "${cls.name} comes from the bundled plugin ${owner.pluginId}: add <depends>${owner.pluginId}</depends> to plugin.xml",
                owner.pluginId in declared,
            )
        }
    }

    // From the class file's URL, jar:file:/…/x.jar!/a/B.class: plugin class loaders leave the code source null.
    private fun jarOf(cls: Class<*>): Path {
        val url = cls.getResource("/" + cls.name.replace('.', '/') + ".class") ?: error("No class file for ${cls.name}")
        assertEquals("${cls.name} is not loaded from a jar: $url", "jar", url.protocol)
        return Path.of(java.net.URI(url.path.substringBefore("!/")))
    }
}
