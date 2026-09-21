package app.beyoureyes.core.data.reference

import app.beyoureyes.core.data.UuidV7
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Crash-recoverable filesystem half of local task deletion.
 *
 * Room and the private reference directory cannot share one transaction. We therefore move the
 * directory to an app-private journal before deleting the Room task. On the next process start a
 * journal entry is restored when the task still exists, and discarded when the task is gone.
 */
class ReferenceDeletionJournal(filesDir: File) {
    private val filesRoot = filesDir.canonicalFile
    private val referencesRoot = filesRoot.resolve(REFERENCE_DIRECTORY)
    private val journalRoot = filesRoot.resolve(JOURNAL_DIRECTORY)

    class Entry internal constructor(
        val taskId: String,
        internal val source: File,
        internal val staged: File,
    )

    fun stage(taskId: String): Entry? {
        val entry = entry(taskId)
        if (!entry.source.exists()) return null
        require(entry.source.isDirectory) { "task reference path must be a directory" }
        check(!entry.staged.exists()) { "task reference deletion is already staged" }
        ensureDirectory(journalRoot)
        move(entry.source, entry.staged)
        return entry
    }

    fun entries(): List<Entry> {
        if (!journalRoot.exists()) return emptyList()
        require(journalRoot.isDirectory) { "task reference deletion journal must be a directory" }
        return journalRoot.listFiles().orEmpty()
            .filter { it.name.let(UuidV7::isValid) }
            .map { entry(it.name) }
            .filter { it.staged.exists() }
            .sortedBy(Entry::taskId)
    }

    fun restore(entry: Entry) {
        requireOwned(entry)
        if (!entry.staged.exists()) return
        check(!entry.source.exists()) { "task reference restore destination already exists" }
        ensureDirectory(referencesRoot)
        move(entry.staged, entry.source)
        removeJournalRootIfEmpty()
    }

    fun discard(entry: Entry) {
        requireOwned(entry)
        if (entry.staged.exists()) deleteTreeWithoutFollowingLinks(entry.staged.toPath())
        removeJournalRootIfEmpty()
    }

    private fun entry(taskId: String): Entry {
        require(UuidV7.isValid(taskId)) { "taskId must be UUIDv7" }
        return Entry(
            taskId = taskId,
            source = referencesRoot.resolve(taskId),
            staged = journalRoot.resolve(taskId),
        ).also(::requireOwned)
    }

    private fun requireOwned(entry: Entry) {
        require(requireNotNull(entry.source.parentFile).canonicalFile == referencesRoot.canonicalFile)
        require(requireNotNull(entry.staged.parentFile).canonicalFile == journalRoot.canonicalFile)
        require(entry.source.name == entry.taskId && entry.staged.name == entry.taskId)
    }

    private fun ensureDirectory(directory: File) {
        check(directory.mkdirs() || directory.isDirectory) {
            "private reference directory could not be created"
        }
    }

    private fun move(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        } catch (first: IOException) {
            // Some Android filesystem providers report an unsupported atomic move as a generic
            // IOException. A same-filesDir rename is still non-copying and leaves one clear owner.
            if (!source.renameTo(destination)) throw first
        }
        check(!source.exists() && destination.exists()) { "task reference move was incomplete" }
    }

    private fun deleteTreeWithoutFollowingLinks(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun removeJournalRootIfEmpty() {
        if (journalRoot.listFiles()?.isEmpty() == true) journalRoot.delete()
    }

    private companion object {
        const val REFERENCE_DIRECTORY = "reference-images"
        const val JOURNAL_DIRECTORY = "reference-images-delete-journal"
    }
}
