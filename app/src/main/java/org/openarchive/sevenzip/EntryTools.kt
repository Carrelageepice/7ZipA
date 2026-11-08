package org.openarchive.sevenzip

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Opening and sharing files that were unpacked into the app's cache. */
object EntryTools {

    private fun authority(context: Context) = context.packageName + ".fileprovider"

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** Opens [file] in whatever app handles it. Returns false if none does. */
    fun open(context: Context, file: File): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, authority(context), file)
        } catch (_: IllegalArgumentException) {
            return false  // location not covered by file_paths.xml (e.g. an SD card)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeFor(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(intent, file.name))
    }

    /** Offers [file] to the share sheet. */
    fun share(context: Context, file: File): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, authority(context), file)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeFor(file.name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(intent, file.name))
    }

    /** Shares an archive that already has a content:// Uri (e.g. one we just created). */
    fun shareUri(context: Context, uri: Uri, name: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeFor(name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(intent, name))
    }

    /** Shows the folder the user picked, in their file manager. */
    fun openFolder(context: Context, tree: Uri): Boolean {
        val doc = try {
            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
            )
        } catch (_: Exception) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(doc, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent): Boolean =
        try {
            // From a non-Activity context the system insists on a new task.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
}
