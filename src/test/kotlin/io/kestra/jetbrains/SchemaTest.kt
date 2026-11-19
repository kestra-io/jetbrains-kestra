package io.kestra.jetbrains

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import io.kestra.jetbrains.schema.KestraSchemaService

class SchemaTest : KestraTestCase() {
    private val schema = """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object","properties":{"id":{"type":"string"}}}"""

    private fun stored(): String = String(KestraSchemaService.instance.schemaFile()!!.toNioPath().let(java.nio.file.Files::readAllBytes))

    override fun setUp() {
        super.setUp()
        VfsRootAccess.allowRootAccess(testRootDisposable, PathManager.getSystemPath())
    }

    // hasSchema() trusts any file that exists, so nothing that is not a whole schema may replace a working one.
    fun testAnAnswerThatIsNotASchemaKeepsTheCachedOne() {
        kestra.json("GET", "/plugins/schemas/flow") { schema }
        KestraSchemaService.instance.download()

        listOf("<html>Sign in to the proxy</html>", schema.dropLast(20), "[]", "$schema trailing").forEach { body ->
            kestra.handle("GET", "/plugins/schemas/flow") { exchange, _ -> FakeKestra.respond(exchange, 200, body) }
            // Captures the service: a lambda capturing nothing compiles to a method named test…, which JUnit 3 takes for a test.
            val service = KestraSchemaService.instance
            assertThrows(io.kestra.jetbrains.api.KestraApiException::class.java) { service.download() }
            assertEquals(body, schema, stored())
        }
    }

    // One schema on disk: the one of an instance used before, and an interrupted download, are deleted.
    fun testOnlyTheConfiguredInstancesSchemaIsKept() {
        kestra.json("GET", "/plugins/schemas/flow") { schema }
        KestraSchemaService.instance.download()
        val directory = KestraSchemaService.instance.schemaFile()!!.toNioPath().parent
        val other = java.nio.file.Files.writeString(directory.resolve("flow-schema-0000000000000000.json"), "{}")
        val partial = java.nio.file.Files.writeString(directory.resolve("flow-schema-123.tmp"), "{")
        val unrelated = java.nio.file.Files.writeString(directory.resolve("notes.txt"), "x")
        try {
            KestraSchemaService.instance.download()

            assertFalse(java.nio.file.Files.exists(other))
            assertFalse(java.nio.file.Files.exists(partial))
            assertTrue(java.nio.file.Files.exists(unrelated))
            assertEquals(listOf(KestraSchemaService.instance.schemaFile()!!.toNioPath()), java.nio.file.Files.list(directory).use { files -> files.filter { it.fileName.toString().startsWith("flow-schema-") }.toList() })
            assertEquals(schema, stored())
        } finally {
            java.nio.file.Files.deleteIfExists(unrelated)
        }
    }

    fun testDownloadedSchemaAttachesToFlowsOnly() {
        // setUp allows the IDE system folder, where the schema is cached: the test framework of 2024.3 to 2025.2 keeps it out of the VFS.
        kestra.json("GET", "/plugins/schemas/flow") {
            """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object","properties":{"id":{"type":"string"},"namespace":{"type":"string"},"description":{"type":"string"}}}"""
        }
        KestraSchemaService.instance.download()
        assertTrue(KestraSchemaService.instance.hasSchema())
        val service = JsonSchemaService.Impl.get(project)
        service.reset()

        val flow = myFixture.configureByText("flow.yml", FLOW).virtualFile
        assertTrue(service.getSchemaFilesForFile(flow).any { it == KestraSchemaService.instance.schemaFile() })

        val other = myFixture.configureByText("compose.yml", "services:\n  web: {}\n").virtualFile
        assertTrue(service.getSchemaFilesForFile(other).none { it == KestraSchemaService.instance.schemaFile() })
    }
}
