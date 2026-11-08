package org.openarchive.sevenzip

import java.io.IOException

data class ArchiveEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val encrypted: Boolean
)

/** Called from native code, at most ~10 times a second. Return false to cancel. */
fun interface ExtractProgress {
    /** Byte counts are of the unpacked data; [totalBytes] can be 0 early on. */
    fun onProgress(doneBytes: Long, totalBytes: Long, name: String): Boolean
}

data class ExtractSummary(
    val extracted: Int,
    val skipped: Int,   // unsafe paths and symlinks, never written
    val failed: Int,    // items with data/CRC errors
    val cancelled: Boolean
)

data class CreateSummary(
    val files: Int,
    val cancelled: Boolean
)

data class TestSummary(
    val verified: Int,
    val failed: Int,
    val cancelled: Boolean
)

/** Advanced compression settings; the defaults mean "let 7-Zip decide". */
data class CompressionOptions(
    val method: String = "",       // 7z: lzma2 lzma ppmd bzip2 deflate copy; zip: deflate deflate64 bzip2 lzma ppmd copy
    val solid: Int = -1,           // 7z only: 1 solid, 0 not solid, -1 default
    val dictMb: Int = 0,           // 7z + LZMA/LZMA2 only
    val threads: Int = 0,          // 0 = automatic
    val baseName: String = ""      // name of the .tar inside tar.gz / tar.xz / tar.bz2
)

open class ArchiveException(message: String) : IOException(message)
class PasswordRequiredException(message: String) : ArchiveException(message)
class WrongPasswordException(message: String) : ArchiveException(message)

object NativeBridge {
    private const val PASSWORD_REQUIRED = "[PASSWORD_REQUIRED]"
    private const val WRONG_PASSWORD = "[WRONG_PASSWORD]"

    private var loaded = false

    init {
        loaded = try {
            System.loadLibrary("sevenzip_android")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun engineStatus(): String =
        if (loaded) nativeEngineStatus()
        else "Native bridge unavailable. Check the NDK/CMake build."

    /**
     * [archivePath] is a file path or "fd:N". [nameHint] is the original file
     * name (used to pick the format). Throws [PasswordRequiredException] /
     * [WrongPasswordException] for encrypted archives, IOException otherwise.
     */
    fun listArchive(
        archivePath: String,
        nameHint: String,
        password: String?
    ): Array<ArchiveEntry> {
        check(loaded) { "The native 7-Zip engine is unavailable." }
        return mapErrors { nativeListArchive(archivePath, nameHint, password) }
    }

    /** Extracts everything into [outputDir]. Same exceptions as [listArchive]. */
    fun extractAll(
        archivePath: String,
        nameHint: String,
        password: String?,
        outputDir: String,
        progress: ExtractProgress?
    ): ExtractSummary {
        check(loaded) { "The native 7-Zip engine is unavailable." }
        val r = mapErrors {
            nativeExtractAll(archivePath, nameHint, password, outputDir, progress)
        }
        return ExtractSummary(r[0], r[1], r[2], r[3] != 0)
    }

    /**
     * Packs everything under [rootDir] into [outputPath]. [format] is "7z",
     * "zip" or "tar"; [level] 0 (store) to 9. [encryptNames] only applies to 7z
     * with a password. ZIP passwords must be basic ASCII. Progress counts bytes.
     */
    fun createArchive(
        outputPath: String,
        rootDir: String,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        progress: ExtractProgress?
    ): CreateSummary {
        check(loaded) { "The native 7-Zip engine is unavailable." }
        val r = mapErrors {
            nativeCreateArchive(
                outputPath, rootDir, format, level, password, encryptNames, progress
            )
        }
        return CreateSummary(r[0], r[1] != 0)
    }

    /** Call once at startup with a scratch folder (e.g. cacheDir/unwrap) so .tar.gz/.xz/.bz2 open as folders. */
    fun setTempDir(dir: String) {
        if (loaded) nativeSetTempDir(dir)
    }

    /** Extracts only the entries at [indices] (positions in [listArchive]'s result); folders include their contents. */
    fun extractEntries(
        archivePath: String,
        nameHint: String,
        password: String?,
        indices: IntArray,
        outputDir: String,
        progress: ExtractProgress?
    ): ExtractSummary {
        check(loaded) { "The native 7-Zip engine is unavailable." }
        val r = mapErrors {
            nativeExtractEntries(archivePath, nameHint, password, indices, outputDir, progress)
        }
        return ExtractSummary(r[0], r[1], r[2], r[3] != 0)
    }

    /** Checks every file's checksum without writing anything. */
    fun testArchive(
        archivePath: String,
        nameHint: String,
        password: String?,
        progress: ExtractProgress?
    ): TestSummary {
        check(loaded) { "The native 7-Zip engine is unavailable." }
        val r = mapErrors { nativeTestArchive(archivePath, nameHint, password, progress) }
        return TestSummary(r[0], r[1], r[2] != 0)
    }

    /** Like [createArchive] with advanced options. [format] may also be "tar.gz", "tar.xz" or "tar.bz2". */
    fun createArchiveEx(
        outputPath: String,
        rootDir: String,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        options: CompressionOptions,
        progress: ExtractProgress?
    ): CreateSummary {
        check(loaded) { "The native 7-Zip engine is unavailable." }
        val r = mapErrors {
            nativeCreateArchiveEx(
                outputPath, rootDir, format, level, password, encryptNames,
                options.method.ifEmpty { null }, options.solid, options.dictMb,
                options.threads, options.baseName.ifEmpty { null }, progress
            )
        }
        return CreateSummary(r[0], r[1] != 0)
    }

    /** Creates an archive from exactly these files/folders (absolute paths), each stored under its own name. */
    fun createArchiveFromPaths(
        outputPath: String,
        sources: List<String>,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        options: CompressionOptions,
        progress: ExtractProgress?
    ): CreateSummary {
        check(loaded) { "The native 7-Zip engine is unavailable." }
        val r = mapErrors {
            nativeCreateArchiveFromPaths(
                outputPath, sources.toTypedArray(), format, level, password, encryptNames,
                options.method.ifEmpty { null }, options.solid, options.dictMb,
                options.threads, options.baseName.ifEmpty { null }, progress
            )
        }
        return CreateSummary(r[0], r[1] != 0)
    }

    private inline fun <T> mapErrors(block: () -> T): T {
        try {
            return block()
        } catch (e: IOException) {
            val m = e.message ?: ""
            throw when {
                m.startsWith(PASSWORD_REQUIRED) ->
                    PasswordRequiredException(m.removePrefix(PASSWORD_REQUIRED).trim())
                m.startsWith(WRONG_PASSWORD) ->
                    WrongPasswordException(m.removePrefix(WRONG_PASSWORD).trim())
                else -> e
            }
        }
    }

    private external fun nativeEngineStatus(): String

    private external fun nativeListArchive(
        archivePath: String,
        nameHint: String,
        password: String?
    ): Array<ArchiveEntry>

    private external fun nativeCreateArchive(
        outputPath: String,
        rootDir: String,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        progress: ExtractProgress?
    ): IntArray

    private external fun nativeExtractAll(
        archivePath: String,
        nameHint: String,
        password: String?,
        outputDir: String,
        progress: ExtractProgress?
    ): IntArray

    private external fun nativeSetTempDir(dir: String)

    private external fun nativeExtractEntries(
        archivePath: String,
        nameHint: String,
        password: String?,
        indices: IntArray,
        outputDir: String,
        progress: ExtractProgress?
    ): IntArray

    private external fun nativeTestArchive(
        archivePath: String,
        nameHint: String,
        password: String?,
        progress: ExtractProgress?
    ): IntArray

    private external fun nativeCreateArchiveEx(
        outputPath: String,
        rootDir: String,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        method: String?,
        solid: Int,
        dictMb: Int,
        threads: Int,
        baseName: String?,
        progress: ExtractProgress?
    ): IntArray

    private external fun nativeCreateArchiveFromPaths(
        outputPath: String,
        sources: Array<String>,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        method: String?,
        solid: Int,
        dictMb: Int,
        threads: Int,
        baseName: String?,
        progress: ExtractProgress?
    ): IntArray
}
