package io.kestra.jetbrains.api

import java.io.ByteArrayOutputStream
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.nio.file.Path
import java.util.UUID

sealed interface FormPart {
    val name: String

    data class Text(override val name: String, val value: String) : FormPart
    data class File(override val name: String, val fileName: String, val content: ByteArray) : FormPart

    // A file on disk, sent as it is read: a large file never sits in memory.
    data class LocalFile(override val name: String, val path: Path) : FormPart
}

class MultipartBody(val contentType: String, val bytes: ByteArray)

class StreamedMultipartBody(val contentType: String, val body: BodyPublisher)

object Multipart {
    // In memory, for parts without a LocalFile.
    fun build(parts: List<FormPart>, boundary: String = "kestra-" + UUID.randomUUID()): MultipartBody {
        val out = ByteArrayOutputStream()
        for (part in parts) {
            out.write(head(part, boundary).toByteArray(Charsets.UTF_8))
            when (part) {
                is FormPart.Text -> out.write(part.value.toByteArray(Charsets.UTF_8))
                is FormPart.File -> out.write(part.content)
                is FormPart.LocalFile -> throw IllegalArgumentException("${part.name} is a file on disk, use stream()")
            }
            out.write(CRLF.toByteArray())
        }
        out.write(tail(boundary).toByteArray(Charsets.UTF_8))
        return MultipartBody("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    // The same body as build(), with the files of LocalFile parts read from disk while the request is sent.
    // A missing file fails when the request is built, before anything is sent.
    fun stream(parts: List<FormPart>, boundary: String = "kestra-" + UUID.randomUUID()): StreamedMultipartBody {
        val publishers = ArrayList<BodyPublisher>()
        for (part in parts) {
            publishers += BodyPublishers.ofString(head(part, boundary))
            publishers += when (part) {
                is FormPart.Text -> BodyPublishers.ofString(part.value)
                is FormPart.File -> BodyPublishers.ofByteArray(part.content)
                is FormPart.LocalFile -> BodyPublishers.ofFile(part.path)
            }
            publishers += BodyPublishers.ofString(CRLF)
        }
        publishers += BodyPublishers.ofString(tail(boundary))
        return StreamedMultipartBody("multipart/form-data; boundary=$boundary", BodyPublishers.concat(*publishers.toTypedArray()))
    }

    private const val CRLF = "\r\n"

    private fun head(part: FormPart, boundary: String): String = "--$boundary$CRLF" + when (part) {
        is FormPart.Text -> "Content-Disposition: form-data; name=\"${escape(part.name)}\"$CRLF$CRLF"
        is FormPart.File -> fileHead(part.name, part.fileName)
        is FormPart.LocalFile -> fileHead(part.name, part.path.fileName.toString())
    }

    private fun fileHead(name: String, fileName: String) =
        "Content-Disposition: form-data; name=\"${escape(name)}\"; filename=\"${escape(fileName)}\"$CRLF" +
            "Content-Type: application/octet-stream$CRLF$CRLF"

    private fun tail(boundary: String) = "--$boundary--$CRLF"

    private fun escape(value: String) = value.replace("\"", "%22").replace("\r", "").replace("\n", "")
}
