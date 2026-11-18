package io.kestra.jetbrains.vfs

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.kestra.jetbrains.KestraNotifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
            for ((file, bytes) in contents) {
                // Flat and sanitized: the path comes from the instance.
                Files.write(target.resolve(file.location.replace(Regex("[^A-Za-z0-9._-]+"), "_")), bytes)
            }
        } catch (e: Exception) {
            LOG.warn("Could not keep the unsaved namespace files: ${contents.joinToString { it.first.location }}", e)
        }
    }

    // Tells once, at the next start, that copies were kept. They move aside so the next start stays quiet.
    fun report(project: Project) {
        val pending = runCatching { Files.list(directory).use { stream -> stream.filter { Files.isDirectory(it) && it.fileName.toString() != REPORTED }.toList() } }
            .getOrDefault(emptyList())
        if (pending.isEmpty()) return
        val reported = directory.resolve(REPORTED).also { runCatching { Files.createDirectories(it) } }
        // Only the one that moves them tells: another project opening at the same time finds nothing.
        val moved = pending.filter { runCatching { Files.move(it, reported.resolve(it.fileName), StandardCopyOption.ATOMIC_MOVE) }.isSuccess }
        if (moved.isNotEmpty()) {
            KestraNotifier.warn(project, "Changes to Kestra namespace files were not saved to the instance when the IDE closed. Copies are in $reported.")
        }
    }

    private const val REPORTED = "reported"
}
