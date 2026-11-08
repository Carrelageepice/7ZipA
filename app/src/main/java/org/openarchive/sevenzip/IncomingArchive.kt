package org.openarchive.sevenzip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** Archive handed to the app by "Open with" / "Share" from another app. */
data class IncomingArchive(val uri: Uri, val name: String)

object IncomingArchives {

    /**
     * Returns the archive in [intent] (ACTION_VIEW or ACTION_SEND), or null.
     * Reading is granted for the lifetime of the activity; we do not need to
     * keep it afterwards.
     */
    @Suppress("DEPRECATION")
    fun from(context: Context, intent: Intent?): IncomingArchive? {
        if (intent == null) return null
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        }
        if (uri == null) return null
        return IncomingArchive(uri, displayName(context, uri))
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
            // fall through to the path
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archive"
    }
}
