package io.kestra.jetbrains

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kestra.jetbrains.docs.DocsApi
import io.kestra.jetbrains.docs.KestraDocsView
import io.kestra.jetbrains.flow.FlowYaml
import io.kestra.jetbrains.topology.PluginIcons

class DocsTest : KestraTestCase() {
    fun testTypeUnderTheCursor() {
        val source = """
            id: hello
            namespace: company.team
            tasks:
              - id: each
                type: io.kestra.plugin.core.flow.ForEach
                values: [a, b]
                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: hi
            triggers:
              - id: daily
                type: io.kestra.plugin.core.trigger.Schedule
                cron: "0 9 * * *"
        """.trimIndent()
        val file = myFixture.configureByText("flow.yml", source)
        fun at(marker: String) = FlowYaml.typeAt(file, source.indexOf(marker))
        assertEquals("io.kestra.plugin.core.log.Log", at("message: hi"))
        assertEquals("io.kestra.plugin.core.flow.ForEach", at("values"))
        assertEquals("io.kestra.plugin.core.trigger.Schedule", at("cron"))
        assertNull(at("namespace"))
    }

    fun testInstanceDataForThePage() {
        kestra.json("GET", "/plugins/io.kestra.plugin.core.log.Log") { """{"markdown":"# Log","schema":{"properties":{"title":"Log a message"}}}""" }
        kestra.json("GET", "/plugins/groups/subgroups") { """[{"name":"core","title":"Core","group":"io.kestra.plugin.core","subGroup":null}]""" }
        kestra.json("GET", "/plugins/icons/io.kestra.plugin.core") { """{"icon":{"icon":"PHN2Zy8+"}}""" }
        PluginIcons.instance.clear()

        val definition = DocsApi.handle("definition", JsonObject().apply { addProperty("type", "io.kestra.plugin.core.log.Log") }).asJsonObject
        assertEquals("Log a message", definition.getAsJsonObject("schema").getAsJsonObject("properties").get("title").asString)
        assertEquals("Core", DocsApi.handle("subgroups", JsonObject()).asJsonArray[0].asJsonObject.get("title").asString)
        val icons = DocsApi.handle("icons", JsonParser.parseString("""{"keys":["io.kestra.plugin.core","unknown.Type"]}""").asJsonObject).asJsonObject
        assertEquals("data:image/svg+xml;base64,PHN2Zy8+", icons.get("io.kestra.plugin.core").asString)
        assertFalse(icons.has("unknown.Type"))
        assertTrue(DocsApi.handle("home", JsonObject()).asString.startsWith("### Flow properties"))
        // Plugin endpoints are not tenant-scoped.
        assertTrue(kestra.requests.any { it.path == "/plugins/io.kestra.plugin.core.log.Log" })
    }

    fun testTitleComesFromTheFrontmatter() {
        assertEquals("Subflows in Kestra – Modularize", DocsApi.frontmatterTitle("---\ntitle: Subflows in Kestra – Modularize\nh1: x\n---\n# Body"))
        assertEquals("Quoted", DocsApi.frontmatterTitle("---\ntitle: \"Quoted\"\n---\n"))
        assertNull(DocsApi.frontmatterTitle("# No frontmatter\ntitle: not this"))
    }

    fun testPageBundlesItsLibraries() {
        val html = KestraDocsView.page("window.kestraPost = function () {};")
        listOf("markdown-it 14.3.3", "markdown-it-container 4.0.0", "DOMPurify 3.4.17", "window.kestraDocs", ".nav-row").forEach { assertTrue(it, html.contains(it)) }
        assertFalse(html.contains("/*DOCS*/"))
        assertTrue(KestraDocsView.theme().get("ks-bg-base").asString.matches(Regex("#[0-9a-f]{6}")))
    }
}
