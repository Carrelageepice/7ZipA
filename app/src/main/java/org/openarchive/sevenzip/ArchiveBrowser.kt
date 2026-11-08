package org.openarchive.sevenzip

/** One row when browsing an archive folder by folder. */
data class BrowserItem(
    val name: String,
    val path: String,        // full path inside the archive; folders end with "/"
    val isDir: Boolean,
    val size: Long,          // files: own size; folders: total of everything inside
    val encrypted: Boolean,
    val fileCount: Int       // folders: number of files inside (recursive)
)

object ArchiveBrowser {

    private class Acc {
        var files = 0
        var size = 0L
        var encrypted = false
    }

    /** The folder named by [prefix] ("" = top, "a/b/" = inside a/b) as a sorted list. */
    fun childrenOf(entries: List<ArchiveEntry>, prefix: String): List<BrowserItem> {
        val dirs = LinkedHashMap<String, Acc>()
        val files = ArrayList<BrowserItem>()

        for (e in entries) {
            val full = e.name.replace('\\', '/').trimStart('/')
            if (!full.startsWith(prefix)) continue
            val rest = full.substring(prefix.length)
            if (rest.isEmpty()) continue

            val slash = rest.indexOf('/')
            if (slash < 0) {
                if (e.isDirectory) dirs.getOrPut(rest) { Acc() }
                else files.add(BrowserItem(rest, full, false, e.size, e.encrypted, 1))
            } else {
                val acc = dirs.getOrPut(rest.substring(0, slash)) { Acc() }
                if (!e.isDirectory) {
                    acc.files++
                    acc.size += e.size
                    if (e.encrypted) acc.encrypted = true
                }
            }
        }

        val result = ArrayList<BrowserItem>(dirs.size + files.size)
        for ((name, acc) in dirs.entries.sortedBy { it.key.lowercase() }) {
            result.add(BrowserItem(name, "$prefix$name/", true, acc.size, acc.encrypted, acc.files))
        }
        result.addAll(files.sortedBy { it.name.lowercase() })
        return result
    }

    /** "a/b/" -> "a/b/" parent is "a/", "a/" parent is "". */
    fun parentOf(prefix: String): String {
        if (prefix.isEmpty()) return ""
        val trimmed = prefix.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut < 0) "" else trimmed.substring(0, cut + 1)
    }
}
