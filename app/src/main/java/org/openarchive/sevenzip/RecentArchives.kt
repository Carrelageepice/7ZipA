package org.openarchive.sevenzip

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentArchive(val uri: Uri, val name: String)

/** Remembers the last few archives the user opened (kept in app preferences). */
object RecentArchives {
    private const val PREFS = "recent_archives"
    private const val KEY = "items"
    private const val MAX = 8

    fun load(context: Context): List<RecentArchive> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RecentArchive(Uri.parse(o.getString("uri")), o.getString("name"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Adds [uri] to the top of the list. Tries to keep read access across app
     * restarts; this only works for documents picked with the system picker.
     */
    fun add(context: Context, uri: Uri, name: String) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Not persistable (e.g. shared from another app): still remembered by name.
        }
        val items = load(context).filter { it.uri != uri }.toMutableList()
        items.add(0, RecentArchive(uri, name))
        save(context, items.take(MAX))
    }

    fun remove(context: Context, uri: Uri) =
        save(context, load(context).filter { it.uri != uri })

    fun clear(context: Context) = save(context, emptyList())

    /** True if the app can still read [uri] (permission kept, file still there). */
    fun isReadable(context: Context, uri: Uri): Boolean =
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }

    private fun save(context: Context, items: List<RecentArchive>) {
        val arr = JSONArray()
        for (i in items) {
            arr.put(JSONObject().put("uri", i.uri.toString()).put("name", i.name))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
