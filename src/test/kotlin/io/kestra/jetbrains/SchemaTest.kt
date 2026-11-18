package io.kestra.jetbrains

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import io.kestra.jetbrains.schema.KestraSchemaService

class SchemaTest : KestraTestCase() {
    fun testDownloadedSchemaAttachesToFlowsOnly() {
        // The schema is cached in the IDE system folder, which the test framework of 2024.3 to 2025.2 keeps out of the VFS.
        VfsRootAccess.allowRootAccess(testRootDisposable, PathManager.getSystemPath())
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
