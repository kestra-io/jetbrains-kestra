package io.kestra.jetbrains.api

import java.io.ByteArrayOutputStream
import java.util.UUID

sealed interface FormPart {
    val name: String

    data class Text(override val name: String, val value: String) : FormPart
    data class File(override val name: String, val fileName: String, val content: ByteArray) : FormPart
}

class MultipartBody(val contentType: String, val bytes: ByteArray)

object Multipart {
    fun build(parts: List<FormPart>, boundary: String = "kestra-" + UUID.randomUUID()): MultipartBody {
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
        for (part in parts) {
            write("--$boundary\r\n")
            when (part) {
                is FormPart.Text -> {
                    write("Content-Disposition: form-data; name=\"${escape(part.name)}\"\r\n\r\n")
                    write(part.value)
                }
                is FormPart.File -> {
                    write("Content-Disposition: form-data; name=\"${escape(part.name)}\"; filename=\"${escape(part.fileName)}\"\r\n")
                    write("Content-Type: application/octet-stream\r\n\r\n")
                    out.write(part.content)
                }
            }
            write("\r\n")
        }
        write("--$boundary--\r\n")
        return MultipartBody("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    private fun escape(value: String) = value.replace("\"", "%22").replace("\r", "").replace("\n", "")
}
