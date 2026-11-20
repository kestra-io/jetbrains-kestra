package io.kestra.jetbrains.vfs

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.kestra.jetbrains.KestraNotifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

// Local copies of the namespace file changes that never reached the instance when the IDE closed, so that
// dropping them is never silent. One folder per exit in the IDE's system directory, reported at the next start.
object UnsavedCopies {
    private val LOG = logger<UnsavedCopies>()

    val directory: Path get() = PathManager.getSystemDir().resolve("kestra-unsaved")

    fun keep(contents: List<Pair<KestraVirtualFile, ByteArray>>) {
        if (contents.isEmpty()) return
        val target = directory.resolve(System.currentTimeMillis().toString())
        try {
            Files.createDirectories(target)
            for ((index, entry) in contents.withIndex()) {
                val (file, bytes) = entry
                // Flat and sanitized, the path comes from the instance, and numbered: /a/b.txt and /a_b.txt
                // sanitize to the same name.
                Files.write(target.resolve("${index + 1}-" + file.location.replace(Regex("[^A-Za-z0-9._-]+"), "_")), bytes)
            }
        } catch (e: Exception) {
            LOG.warn("Could not keep the unsaved namespace files: ${contents.joinToString { it.first.location }}", e)
        }
    }

    // Tells once, at the next start, that copies were kept. They move aside so the next start stays quiet, and
    // are deleted after a month: they were told about, and are not meant to pile up.
    fun report(project: Project) {
        val pending = runCatching { Files.list(directory).use { stream -> stream.filter { Files.isDirectory(it) && it.fileName.toString() != REPORTED }.toList() } }
            .getOrDefault(emptyList())
        val reported = directory.resolve(REPORTED)
        deleteOlderThan(reported, System.currentTimeMillis() - RETENTION_MILLIS)
        if (pending.isEmpty()) return
        runCatching { Files.createDirectories(reported) }
        // Only the one that moves them tells: another project opening at the same time finds nothing.
        val moved = pending.filter { runCatching { Files.move(it, reported.resolve(it.fileName), StandardCopyOption.ATOMIC_MOVE) }.isSuccess }
        if (moved.isNotEmpty()) {
            KestraNotifier.warn(project, "Changes to Kestra namespace files were not saved to the instance when the IDE closed. Copies are in $reported.")
        }
    }

    // A folder is named after the time of the exit that kept it.
    private fun deleteOlderThan(reported: Path, limit: Long) {
        val old = runCatching { Files.list(reported).use { stream -> stream.filter { (it.fileName.toString().toLongOrNull() ?: Long.MAX_VALUE) < limit }.toList() } }
            .getOrDefault(emptyList())
        old.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private const val REPORTED = "reported"
    private val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(30)
}
