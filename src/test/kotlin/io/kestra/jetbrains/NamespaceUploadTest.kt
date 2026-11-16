package io.kestra.jetbrains

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import io.kestra.jetbrains.api.NamespaceFilesApi
import io.kestra.jetbrains.api.NamespaceFilesException
import io.kestra.jetbrains.namespace.LocalFile
import io.kestra.jetbrains.namespace.NamespacePaths
import io.kestra.jetbrains.namespace.NamespaceUpload
import io.kestra.jetbrains.settings.KestraSettings
import java.io.File

class NamespaceUploadTest : KestraTestCase() {
    fun testExcludedNamesAndPaths() {
        val defaults = KestraSettings.DEFAULT_EXCLUDED_NAMES
        listOf(".env", ".env.local", "server.PEM", "id_rsa.pub", ".git", "node_modules").forEach { assertTrue(it, NamespacePaths.isExcluded(it, defaults)) }
        listOf("env", "flow.yml", "pem.txt", "my.env").forEach { assertFalse(it, NamespacePaths.isExcluded(it, defaults)) }
        assertTrue(NamespacePaths.matches("data-2024.csv", "data*.csv"))
        assertTrue(NamespacePaths.matches("a.secret.b", "*secret*"))

        assertNull(NamespacePaths.validate("/scripts/run.py", requireFileName = true))
        assertNotNull(NamespacePaths.validate("scripts/run.py", requireFileName = true))
        assertNotNull(NamespacePaths.validate("/scripts/", requireFileName = true))
        assertNotNull(NamespacePaths.validate("/a/../b", requireFileName = false))
        assertEquals("/base/dir/f.txt", NamespacePaths.join("/base/", "dir/f.txt"))
        assertEquals("/f.txt", NamespacePaths.join("/", "f.txt"))
        assertEquals("/a%20b/c%23d.txt", NamespaceFilesApi.encodePath("/a b/c#d.txt"))
    }

    fun testGatherKeepsFolderNamesAndSkipsExcluded() {
        val root = FileUtil.createTempDirectory("ns", null)
        File(root, "project/scripts").mkdirs()
        File(root, "project/scripts/run.py").writeText("print(1)")
        File(root, "project/.env").writeText("SECRET=1")
        File(root, "project/node_modules/x").mkdirs()
        File(root, "project/node_modules/x/i.js").writeText("")
        File(root, "loose.txt").writeText("x")
        File(root, "key.pem").writeText("x")
        val vfs = LocalFileSystem.getInstance()
        fun vf(path: String) = vfs.refreshAndFindFileByIoFile(File(root, path))!!

        val files = NamespaceUpload.gather(listOf(vf("project"), vf("loose.txt"), vf("key.pem")), KestraSettings.DEFAULT_EXCLUDED_NAMES)
        assertEquals(listOf("project/scripts/run.py", "loose.txt"), files.map { it.relative })
    }

    fun testApiListsReadsAndReportsTheServerReason() {
        kestra.json("GET", "/namespaces/search") { """{"results":[{"id":"company"},{"id":"company.team"}],"total":2}""" }
        kestra.json("GET", "/namespaces/company.team/files/directory") {
            """[{"type":"Directory","size":0,"fileName":"dir","lastModifiedTime":1},{"type":"File","size":6,"fileName":"a b.txt","lastModifiedTime":2}]"""
        }
        kestra.json("GET", "/namespaces/company.team/files", status = 404) {
            """{"type":"about:blank","title":"Resource not found","status":404,"detail":"File '/nope.txt' was not found in namespace 'company.team'."}"""
        }
        val api = NamespaceFilesApi()
        assertEquals(listOf("company", "company.team"), api.namespaces())
        val entries = api.list("company.team", "/")
        assertEquals(listOf("dir" to true, "a b.txt" to false), entries.map { it.name to it.directory })
        val error = runCatching { api.read("company.team", "/nope.txt") }.exceptionOrNull() as NamespaceFilesException
        assertEquals(404, error.status)
        assertTrue(error.message, error.message!!.contains("File '/nope.txt' was not found"))
        assertTrue(kestra.requests.any { it.path == "/namespaces/search?existing=true&size=200&page=1&sort=id%3Aasc" })
    }

    fun testUploadStopsWhenPermissionIsDenied() {
        var calls = 0
        kestra.handle("POST", "/namespaces/company.team/files") { exchange, request ->
            calls++
            val denied = request.path.contains("secret")
            FakeKestra.respond(exchange, if (denied) 403 else 200, if (denied) """{"message":"Missing NAMESPACE UPDATE permission"}""" else "")
        }
        val root = FileUtil.createTempDirectory("ns", null)
        val names = listOf("a.txt", "secret.txt", "c.txt")
        names.forEach { File(root, it).writeText("content of $it") }
        val vfs = LocalFileSystem.getInstance()
        val files = names.map { LocalFile(vfs.refreshAndFindFileByIoFile(File(root, it))!!, "dir/$it") }

        NamespaceUpload.run(project, "company.team", files, "/base")
        val deadline = System.currentTimeMillis() + 10_000
        while (calls < 2 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        Thread.sleep(200)

        val uploads = kestra.requests.filter { it.method == "POST" }
        assertEquals(listOf("/namespaces/company.team/files?path=/base/dir/a.txt", "/namespaces/company.team/files?path=/base/dir/secret.txt"), uploads.map { it.path })
        assertTrue(uploads[0].body.contains("filename=\"content\""))
        assertTrue(uploads[0].body.contains("content of a.txt"))
    }
}
