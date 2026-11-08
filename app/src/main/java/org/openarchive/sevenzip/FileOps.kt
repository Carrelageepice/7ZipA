package org.openarchive.sevenzip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

/** One row in the file manager. */
data class FileEntry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val isArchive: Boolean
)

/** A storage volume the user can browse. */
data class StorageRoot(val label: String, val dir: File)

/** Thrown inside long file operations when the user presses Cancel. */
class OperationCancelled : Exception()

/** File-system logic for the built-in file manager (no UI here). */
object FileOps {

    private val ARCHIVE_EXTENSIONS = setOf(
        "7z", "zip", "rar", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz",
        "cab", "iso", "wim", "arj", "cpio", "rpm", "deb", "lzh", "lha", "z", "001"
    )

    fun isArchiveName(name: String): Boolean =
        ARCHIVE_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())

    // ---------------------------------------------------------------- access

    /** True if the app may read and write ordinary files on shared storage. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    // ---------------------------------------------------------------- volumes

    fun roots(context: Context): List<StorageRoot> {
        val list = mutableListOf(
            StorageRoot("Internal storage", Environment.getExternalStorageDirectory())
        )
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val sm = context.getSystemService(StorageManager::class.java)
                for (v in sm.storageVolumes) {
                    val dir = v.directory ?: continue
                    if (v.isPrimary || v.state != Environment.MEDIA_MOUNTED) continue
                    list.add(StorageRoot(v.getDescription(context) ?: "SD card", dir))
                }
            } catch (_: Exception) {
                // Only the internal storage is offered.
            }
        }
        return list
    }

    fun rootOf(roots: List<StorageRoot>, dir: File): StorageRoot? =
        roots.filter { dir.path == it.dir.path || dir.path.startsWith(it.dir.path + "/") }
            .maxByOrNull { it.dir.path.length }

    // ---------------------------------------------------------------- listing

    fun list(dir: File, showHidden: Boolean): List<FileEntry> {
        val children = dir.listFiles() ?: return emptyList()
        return children.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map {
                val isDir = it.isDirectory
                FileEntry(
                    file = it,
                    name = it.name,
                    isDir = isDir,
                    size = if (isDir) 0L else it.length(),
                    modified = it.lastModified(),
                    isArchive = !isDir && isArchiveName(it.name)
                )
            }
            .sortedWith(
                compareByDescending<FileEntry> { it.isDir }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            .toList()
    }

    /** "name.ext" -> "name (1).ext" until the name is free in [dir]. */
    fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    // ---------------------------------------------------------------- basic ops

    /** Returns an error message, or null on success. */
    fun rename(file: File, newName: String): String? {
        val n = newName.trim()
        if (n.isEmpty() || n == "." || n == ".." || n.contains('/')) return "That name is not valid."
        val target = File(file.parentFile, n)
        if (target.exists()) return "\"$n\" already exists."
        return if (file.renameTo(target)) null else "Could not rename."
    }

    fun makeFolder(parent: File, name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == ".." || n.contains('/')) return "That name is not valid."
        val target = File(parent, n)
        if (target.exists()) return "\"$n\" already exists."
        return if (target.mkdir()) null else "Could not create the folder."
    }

    fun totalSize(file: File): Long {
        if (!file.isDirectory) return file.length()
        var sum = 0L
        file.listFiles()?.forEach { sum += totalSize(it) }
        return sum
    }

    /** Copies [src] to [dst] (recursively), skipping symbolic links. */
    fun copyTree(src: File, dst: File, cancel: () -> Boolean, onBytes: (Long) -> Unit) {
        if (cancel()) throw OperationCancelled()
        if (java.nio.file.Files.isSymbolicLink(src.toPath())) return
        if (src.isDirectory) {
            if (!dst.mkdirs() && !dst.isDirectory) throw IOException("Could not create ${dst.name}.")
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name), cancel, onBytes) }
        } else {
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        onBytes(n.toLong())
                        if (cancel()) throw OperationCancelled()
                    }
                }
            }
            dst.setLastModified(src.lastModified())
        }
    }

    /**
     * Copies or moves [sources] into [destDir]. Names that already exist get
     * " (1)" appended. Returns the number of items handled.
     */
    fun paste(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        cancel: () -> Boolean,
        onBytes: (Long) -> Unit
    ): Int {
        var count = 0
        for (src in sources) {
            if (!src.exists()) continue
            if (src.isDirectory &&
                (destDir.path == src.path || destDir.path.startsWith(src.path + "/"))
            ) {
                throw IOException("Cannot put \"${src.name}\" inside itself.")
            }
            if (move && src.parentFile?.path == destDir.path) continue  // already here
            val target = uniqueFile(destDir, src.name)
            if (move && src.renameTo(target)) {
                count++
                continue
            }
            copyTree(src, target, cancel, onBytes)
            if (move) src.deleteRecursively()
            count++
        }
        return count
    }

    // ---------------------------------------------------------------- archives

    /** Extracts [archive] into a new folder next to it, straight to disk. */
    fun extractHere(
        context: Context,
        archive: File,
        password: String?,
        progress: ExtractProgress?
    ): Pair<ExtractSummary, File> {
        ArchiveOps.ensureTempReady(context)
        val out = uniqueFile(archive.parentFile ?: archive.absoluteFile.parentFile!!,
            ArchiveOps.folderNameFor(archive.name))
        try {
            val summary = Throttle.run(context, progress) { p ->
                NativeBridge.extractAll(archive.absolutePath, archive.name, password, out.absolutePath, p)
            }
            if (summary.cancelled) out.deleteRecursively()
            return Pair(summary, out)
        } catch (e: Exception) {
            out.deleteRecursively()  // do not leave a half-filled folder behind
            throw e
        }
    }

    /** Compresses [sources] into a new archive in [destDir]. Returns the file (null if cancelled). */
    fun compress(
        context: Context,
        sources: List<File>,
        destDir: File,
        baseName: String,
        format: String,
        password: String?,
        progress: ExtractProgress?
    ): Pair<CreateSummary, File?> {
        ArchiveOps.ensureTempReady(context)
        val safe = ArchiveOps.safeName(baseName)
        val finalFile = uniqueFile(destDir, safe + "." + ArchiveOps.extensionFor(format))
        val part = File(destDir, finalFile.name + ".part")
        try {
            val options = CompressionOptions(
                threads = Throttle.suggestedThreads(context), baseName = safe
            )
            val summary = Throttle.run(context, progress) { p ->
                NativeBridge.createArchiveFromPaths(
                    part.absolutePath, sources.map { it.absolutePath }, format, 5,
                    password, false, options, p
                )
            }
            if (summary.cancelled) {
                part.delete()
                return Pair(summary, null)
            }
            if (!part.renameTo(finalFile)) throw IOException("Could not save the archive.")
            return Pair(summary, finalFile)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }
}
