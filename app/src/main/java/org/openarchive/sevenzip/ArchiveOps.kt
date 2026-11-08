package org.openarchive.sevenzip

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/** Glue between Android's content:// world and the native, path-based engine. */
object ArchiveOps {

    /**
     * Gives [block] something the native engine can open for [uri]: "fd:N"
     * (the already-open descriptor, no copy) when the provider hands out a
     * normal seekable file, otherwise a temporary copy in the cache.
     */
    /** Scratch folder for nested archives (.tar.gz and friends) and creation steps. */
    fun prepareTempDir(context: Context) {
        val dir = File(context.cacheDir, "unwrap")
        dir.deleteRecursively()  // leftovers from a crash
        dir.mkdirs()
        context.cacheDir.listFiles()?.forEach {
            val n = it.name
            if (n.startsWith("extract_") || n.startsWith("create_") ||
                n.startsWith("created_") || n.startsWith("sevenzip_")
            ) it.deleteRecursively()
        }
        NativeBridge.setTempDir(dir.absolutePath)
    }

    private var tempReady = false

    fun <T> withArchivePath(context: Context, uri: Uri, block: (String) -> T): T {
        val resolver = context.contentResolver
        if (!tempReady) {
            prepareTempDir(context)
            tempReady = true
        }

        val pfd = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        }
        if (pfd != null) {
            pfd.use {
                if (it.statSize >= 0) {
                    try {
                        return block("fd:${it.fd}")
                    } catch (e: IOException) {
                        // Only fall back if the descriptor itself could not be
                        // opened; real archive errors must reach the caller.
                        if (e.message?.startsWith("Could not open") != true) throw e
                    }
                }
            }
        }

        val temp = File.createTempFile("sevenzip_", ".archive", context.cacheDir)
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Could not open the selected file.")
            input.use { source ->
                temp.outputStream().use { destination -> source.copyTo(destination) }
            }
            return block(temp.absolutePath)
        } finally {
            temp.delete()
        }
    }

    fun listArchive(
        context: Context,
        archive: Uri,
        nameHint: String,
        password: String?
    ): List<ArchiveEntry> =
        withArchivePath(context, archive) { path ->
            NativeBridge.listArchive(path, nameHint, password).toList()
        }

    /**
     * Extracts [archive] into the folder the user picked ([tree]), inside a
     * subfolder named after the archive.
     *
     * Normal mode: the engine needs a real directory, so files go to the app
     * cache first and are then copied into the chosen folder (needs free cache
     * space about equal to the extracted size).
     *
     * With "Manage all files" on and a folder on internal storage, the engine
     * writes straight into the folder: no copy, no extra space, much faster.
     */
    fun extractToTree(
        context: Context,
        archive: Uri,
        nameHint: String,
        password: String?,
        tree: Uri,
        progress: ExtractProgress?
    ): ExtractSummary = extractInto(context, archive, nameHint, password, null, tree, progress)

    /** Like [extractToTree] but only for the entries at [indices] (positions in the listing). */
    fun extractSelectedToTree(
        context: Context,
        archive: Uri,
        nameHint: String,
        password: String?,
        indices: IntArray,
        tree: Uri,
        progress: ExtractProgress?
    ): ExtractSummary = extractInto(context, archive, nameHint, password, indices, tree, progress)

    private fun extractInto(
        context: Context,
        archive: Uri,
        nameHint: String,
        password: String?,
        indices: IntArray?,
        tree: Uri,
        progress: ExtractProgress?
    ): ExtractSummary =
        Throttle.run(context, progress) { p ->
            extractIntoImpl(context, archive, nameHint, password, indices, tree, p)
        }

    private fun extractIntoImpl(
        context: Context,
        archive: Uri,
        nameHint: String,
        password: String?,
        indices: IntArray?,
        tree: Uri,
        progress: ExtractProgress?
    ): ExtractSummary {
        fun doExtract(path: String, out: String): ExtractSummary =
            if (indices == null) NativeBridge.extractAll(path, nameHint, password, out, progress)
            else NativeBridge.extractEntries(path, nameHint, password, indices, out, progress)

        val direct = if (canUseDirectPaths()) treeToFile(tree) else null
        if (direct != null && (direct.isDirectory || direct.mkdirs())) {
            val target = uniqueChild(direct, folderNameFor(nameHint))
            val summary = try {
                withArchivePath(context, archive) { path -> doExtract(path, target.absolutePath) }
            } catch (e: Exception) {
                target.deleteRecursively()  // do not leave a half-filled folder behind
                throw e
            }
            if (summary.cancelled) target.deleteRecursively()
            return summary
        }

        val temp = File(context.cacheDir, "extract_${System.nanoTime()}")
        try {
            val summary = withArchivePath(context, archive) { path -> doExtract(path, temp.absolutePath) }
            if (!summary.cancelled) {
                val root = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                // One subfolder per archive keeps shared folders (Download...)
                // tidy. If the name is taken, the provider adds " (1)" itself.
                val target = DocumentsContract.createDocument(
                    context.contentResolver, root,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    folderNameFor(nameHint)
                ) ?: throw IOException("Could not create the destination folder.")
                copyToTree(context, temp, target)
            }
            return summary
        } finally {
            temp.deleteRecursively()
        }
    }

    /** True when the user has switched on "Manage all files" for this app. */
    fun canUseDirectPaths(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()

    /** Real folder behind a picker Uri, for internal storage only ("primary:Download/x"). */
    fun treeToFile(tree: Uri): File? {
        val id = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            return null
        }
        val volume = id.substringBefore(':')
        if (volume != "primary") return null
        val rel = id.substringAfter(':', "")
        if (rel.split('/').any { it == ".." }) return null
        return File(android.os.Environment.getExternalStorageDirectory(), rel)
    }

    /** "photos.tar.gz" -> "photos", "report.7z" -> "report". */
    fun folderNameFor(nameHint: String): String {
        var base = nameHint.substringAfterLast('/').substringAfterLast('\\')
        base = base.substringBeforeLast('.', base)
        if (base.endsWith(".tar", ignoreCase = true)) base = base.dropLast(4)
        base = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
        return base.ifEmpty { "extracted" }
    }

    private fun copyToTree(context: Context, source: File, parentDocument: Uri) {
        val resolver = context.contentResolver
        val children = source.listFiles()?.sortedBy { it.name } ?: return

        for (child in children) {
            if (child.isDirectory) {
                val dir = DocumentsContract.createDocument(
                    resolver, parentDocument,
                    DocumentsContract.Document.MIME_TYPE_DIR, child.name
                ) ?: throw IOException("Could not create folder ${child.name}")
                copyToTree(context, child, dir)
            } else {
                val doc = DocumentsContract.createDocument(
                    resolver, parentDocument,
                    "application/octet-stream", child.name
                ) ?: throw IOException("Could not create file ${child.name}")
                val out = resolver.openOutputStream(doc)
                    ?: throw IOException("Could not write ${child.name}")
                out.use { sink -> child.inputStream().use { it.copyTo(sink) } }
            }
        }
    }

    // ------------------------------------------------------------- test / select / preview

    /** Verifies every checksum in the archive; nothing is written. */
    fun testArchive(
        context: Context,
        archive: Uri,
        nameHint: String,
        password: String?,
        progress: ExtractProgress?
    ): TestSummary =
        Throttle.run(context, progress) { p ->
            withArchivePath(context, archive) { path ->
                NativeBridge.testArchive(path, nameHint, password, p)
            }
        }

    /**
     * Extracts one entry into the app's cache and returns the file, for
     * preview / "open with" / sharing. Returns null if nothing was written.
     */
    fun extractEntryToCache(
        context: Context,
        archive: Uri,
        nameHint: String,
        password: String?,
        index: Int,
        progress: ExtractProgress?
    ): File? {
        val root = File(context.cacheDir, "preview")
        root.deleteRecursively()  // keep only the latest preview
        if (!root.mkdirs()) throw IOException("Could not prepare a temporary folder.")
        val summary = Throttle.run(context, progress) { p ->
            withArchivePath(context, archive) { path ->
                NativeBridge.extractEntries(
                    path, nameHint, password, intArrayOf(index), root.absolutePath, p
                )
            }
        }
        if (summary.cancelled) return null
        // The entry sits at its archive-relative path; find the one file.
        return root.walkTopDown().firstOrNull { it.isFile }
    }

    // ------------------------------------------------------------- creating

    /** Something the user chose to add: a file, or a whole folder (a tree URI). */
    data class CreateSource(val uri: Uri, val name: String, val isFolder: Boolean)

    /** File extension and MIME type for the formats the engine can create. */
    fun extensionFor(format: String): String = when (format) {
        "zip" -> "zip"
        "tar" -> "tar"
        "tar.gz" -> "tar.gz"
        "tar.xz" -> "tar.xz"
        "tar.bz2" -> "tar.bz2"
        else -> "7z"
    }

    private fun mimeFor(format: String): String = when (format) {
        "zip" -> "application/zip"
        "tar" -> "application/x-tar"
        "tar.gz" -> "application/gzip"
        "tar.xz" -> "application/x-xz"
        "tar.bz2" -> "application/x-bzip2"
        else -> "application/x-7z-compressed"
    }

    /** Same rule the engine applies: ZIP passwords are printable ASCII, max 99. */
    fun isValidZipPassword(password: String): Boolean =
        password.length <= 99 && password.all { it.code in 0x20..0x7E }

    /** A name that is safe as a single path component. */
    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
        return cleaned.ifEmpty { "file" }
    }

    /**
     * Copies the sources into a temporary folder, compresses it, and saves the
     * archive into [tree] as "<archiveName>.<ext>". Needs free cache space of
     * roughly the input size plus the archive size.
     */
    fun createToTree(
        context: Context,
        sources: List<CreateSource>,
        archiveName: String,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        tree: Uri,
        progress: ExtractProgress?,
        options: CompressionOptions = CompressionOptions()
    ): CreateSummary =
        createToTreeEx(
            context, sources, archiveName, format, level, password,
            encryptNames, tree, progress, options
        ).first

    /** Same as [createToTree] but also returns the Uri of the new archive (null if cancelled). */
    fun createToTreeEx(
        context: Context,
        sources: List<CreateSource>,
        archiveName: String,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        tree: Uri,
        progress: ExtractProgress?,
        options: CompressionOptions = CompressionOptions()
    ): Pair<CreateSummary, Uri?> =
        Throttle.run(context, progress) { p ->
            createToTreeImpl(
                context, sources, archiveName, format, level, password,
                encryptNames, tree, p,
                if (options.threads == 0) {
                    options.copy(threads = Throttle.suggestedThreads(context))
                } else {
                    options
                }
            )
        }

    private fun createToTreeImpl(
        context: Context,
        sources: List<CreateSource>,
        archiveName: String,
        format: String,
        level: Int,
        password: String?,
        encryptNames: Boolean,
        tree: Uri,
        progress: ExtractProgress?,
        options: CompressionOptions
    ): Pair<CreateSummary, Uri?> {
        if (!tempReady) {
            prepareTempDir(context)
            tempReady = true
        }
        val stamp = System.nanoTime()
        val stage = File(context.cacheDir, "create_$stamp")
        val packed = File(context.cacheDir, "created_$stamp.tmp")
        try {
            if (!stage.mkdirs()) throw IOException("Could not prepare a temporary folder.")
            for (source in sources) {
                if (source.isFolder) {
                    val rootId = DocumentsContract.getTreeDocumentId(source.uri)
                    val target = uniqueChild(stage, safeName(source.name))
                    stageFolder(context, source.uri, rootId, target)
                } else {
                    val target = uniqueChild(stage, safeName(source.name))
                    stageFile(context, source.uri, target)
                }
            }

            val summary = NativeBridge.createArchiveEx(
                packed.absolutePath, stage.absolutePath, format, level,
                password, encryptNames,
                options.copy(baseName = options.baseName.ifEmpty { safeName(archiveName) }),
                progress
            )
            if (summary.cancelled) return Pair(summary, null)

            val root = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val fileName = safeName(archiveName) + "." + extensionFor(format)
            val doc = DocumentsContract.createDocument(
                context.contentResolver, root, mimeFor(format), fileName
            ) ?: throw IOException("Could not create the archive file in the destination folder.")
            val out = context.contentResolver.openOutputStream(doc)
                ?: throw IOException("Could not write the archive file.")
            out.use { sink -> packed.inputStream().use { it.copyTo(sink) } }
            return Pair(summary, doc)
        } finally {
            stage.deleteRecursively()
            packed.delete()
        }
    }

    private fun uniqueChild(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$name ($n)")
            n++
        }
        return candidate
    }

    private fun stageFile(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not read " + target.name)
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        queryLastModified(context, uri)?.let { target.setLastModified(it) }
    }

    private fun queryLastModified(context: Context, uri: Uri): Long? =
        try {
            context.contentResolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
            }
        } catch (_: Exception) {
            null
        }

    /** Recursively copies a folder of a document tree into [destDir]. */
    private fun stageFolder(context: Context, tree: Uri, documentId: String, destDir: File) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        class Child(val id: String, val name: String, val mime: String, val modified: Long)

        val list = ArrayList<Child>()
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    Child(
                        c.getString(0),
                        c.getString(1) ?: "file",
                        c.getString(2) ?: "",
                        if (c.isNull(3)) 0L else c.getLong(3)
                    )
                )
            }
        }

        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Could not prepare a temporary folder.")
        }
        for (child in list) {
            val target = uniqueChild(destDir, safeName(child.name))
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                stageFolder(context, tree, child.id, target)
            } else {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
                val input = resolver.openInputStream(docUri)
                    ?: throw IOException("Could not read " + child.name)
                input.use { source -> target.outputStream().use { source.copyTo(it) } }
                if (child.modified > 0) target.setLastModified(child.modified)
            }
        }
    }

    /** Display name of a document tree's root folder. */
    fun treeRootName(context: Context, tree: Uri): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        return try {
            context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "folder"
        } catch (_: Exception) {
            "folder"
        }
    }
}
