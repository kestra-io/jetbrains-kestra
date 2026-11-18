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

    // The page's messages come from JavaScript: a malformed field must not throw on the browser's thread.
    fun testMessagesFromThePageAreParsedDefensively() {
        val request = KestraDocsView.parse("""{"id":3,"op":"definition","args":{"type":"a.B"}}""")!!
        assertEquals("definition", request.op)
        assertEquals(3, request.id)
        assertEquals("a.B", request.args.get("type").asString)

        assertNull(KestraDocsView.parse("""{"id":"not a number","op":"definition"}""")!!.id)
        assertNull(KestraDocsView.parse("""{"id":{"x":1},"op":"definition"}""")!!.id)
        assertTrue(KestraDocsView.parse("""{"op":"open","args":"nope"}""")!!.args.size() == 0)
        assertNull(KestraDocsView.parse("""{"op":{"nested":true}}"""))
        assertNull(KestraDocsView.parse("[1,2]"))
        assertNull(KestraDocsView.parse("not json"))
    }

    // The plugin browser's rows are built by docs.js and go through the same sanitizer as the server's markdown.
    // No JavaScript engine runs in these tests, so this checks the two against each other: nothing docs.js builds
    // itself may use a tag the sanitizer strips (a <button> row once made every row unclickable).
    fun testHtmlBuiltByThePageSurvivesItsSanitizer() {
        val js = DocsTest::class.java.getResource("/docs/docs.js")!!.readText()
        val forbidden = Regex("FORBID_TAGS:\\s*\\[([^\\]]*)]").find(js)!!.groupValues[1].split(',').map { it.trim().trim('\'') }.toSet()
        assertTrue(forbidden.toString(), "button" in forbidden)

        val built = js.substringAfter("// ---------- Plugin pages ----------").substringBefore("// ---------- Page ----------")
        val used = built.lines().filter { !it.trim().startsWith("//") }
            .flatMap { line -> Regex("<([a-zA-Z][a-zA-Z0-9]*)[\\s>/]").findAll(line).map { it.groupValues[1].lowercase() }.toList() }.toSet()
        assertTrue(used.toString(), "div" in used && "span" in used)
        assertEquals(emptySet<String>(), used.intersect(forbidden))
        assertTrue(js.contains("role=\"button\""))
    }

    // Most plugin groups have no sub-group: their elements sit on the root entry, and the group page has to list
    // them. No JavaScript engine runs in these tests, so this reads the page's source (the rendering itself was
    // checked by hand with node); the data it renders is checked on the Kotlin side.
    fun testGroupPageListsTheRootsOwnElements() {
        val js = DocsTest::class.java.getResource("/docs/docs.js")!!.readText()
        val group = js.substringAfter("function renderGroupPage(root, subs)").substringBefore("function renderSubPage")
        assertTrue(group.contains("elementRows(root)"))
        assertTrue(group.indexOf("sub:") < group.indexOf("elementRows(root)"))
        assertTrue(js.substringAfter("function elementRows(entry)").substringBefore("function renderSubPage").contains("type:\${element.cls}"))
        assertTrue(js.substringAfter("function renderSubPage(sub)").substringBefore("const BETA_NOTICE").contains("elementRows(sub)"))

        kestra.json("GET", "/plugins/groups/subgroups") {
            """[{"name":"plugin-script-dotnet","group":"io.kestra.plugin.scripts.dotnet","subGroup":null,"title":".NET","tasks":[{"cls":"io.kestra.plugin.scripts.dotnet.Script","title":"Run C#"}]}]"""
        }
        val root = DocsApi.handle("subgroups", JsonObject()).asJsonArray[0].asJsonObject
        assertTrue(root.get("subGroup").isJsonNull)
        assertEquals("io.kestra.plugin.scripts.dotnet.Script", root.getAsJsonArray("tasks")[0].asJsonObject.get("cls").asString)
    }

    fun testPageBundlesItsLibraries() {
        val html = KestraDocsView.page("window.kestraPost = function () {};")
        listOf("markdown-it 14.3.3", "markdown-it-container 4.0.0", "DOMPurify 3.4.17", "window.kestraDocs", ".nav-row").forEach { assertTrue(it, html.contains(it)) }
        assertFalse(html.contains("/*DOCS*/"))
        assertTrue(KestraDocsView.theme().get("ks-bg-base").asString.matches(Regex("#[0-9a-f]{6}")))
    }
}
