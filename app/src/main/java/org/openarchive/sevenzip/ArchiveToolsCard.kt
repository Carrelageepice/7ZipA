package org.openarchive.sevenzip

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything you can do with an already-opened archive beyond "extract all":
 * check its integrity, search the file list, extract only some entries, and
 * open or share a single file from inside it.
 *
 * Drop it anywhere in your screen once an archive is listed:
 *   ArchiveToolsCard(archive, name, password, entries, destination)
 */
@Composable
fun ArchiveToolsCard(
    archive: Uri,
    archiveName: String,
    password: String?,
    entries: List<ArchiveEntry>,
    destination: Uri?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember(archive) { mutableStateOf("") }
    val selected = remember(archive) { mutableStateListOf<Int>() }
    var busy by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember(archive) { mutableStateOf("") }

    fun progressCallback() = ExtractProgress { done, total, _ ->
        if (total > 0) progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        !cancel
    }

    // Runs [work] off the main thread with a shared busy/progress/error wrapper.
    fun startTask(work: () -> String) {
        if (busy) return
        busy = true
        cancel = false
        progress = 0f
        message = ""
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) { work() }
            } catch (e: PasswordRequiredException) {
                "This archive needs a password. Enter it and try again."
            } catch (e: WrongPasswordException) {
                "Wrong password."
            } catch (e: Exception) {
                e.message ?: "Something went wrong."
            } finally {
                busy = false
            }
        }
    }

    val shown = remember(entries, query) {
        val q = query.trim().lowercase()
        entries.withIndex().filter { q.isEmpty() || it.value.name.lowercase().contains(q) }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Archive tools", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        startTask {
                            val r = ArchiveOps.testArchive(
                                context, archive, archiveName, password, progressCallback()
                            )
                            when {
                                r.cancelled -> "Check cancelled."
                                r.failed == 0 -> "All good: ${r.verified} items verified."
                                else -> "${r.failed} items are damaged or the password is wrong."
                            }
                        }
                    }
                ) { Text("Test integrity") }

                if (busy) {
                    TextButton(onClick = { cancel = true }) { Text("Cancel") }
                }
            }

            if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search in archive") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        for (e in shown) if (e.index !in selected) selected.add(e.index)
                    }
                ) { Text("Select shown") }
                TextButton(enabled = selected.isNotEmpty(), onClick = { selected.clear() }) {
                    Text("Clear")
                }
                Text("${selected.size} selected", style = MaterialTheme.typography.bodySmall)
            }

            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Only the first rows are drawn; searching narrows the rest.
                for (item in shown.take(300)) {
                    val e = item.value
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = item.index in selected,
                            onCheckedChange = { on ->
                                if (on) selected.add(item.index) else selected.remove(item.index)
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!e.isDirectory) {
                                Text(
                                    formatSize(e.size),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (!e.isDirectory) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    startTask {
                                        val f = ArchiveOps.extractEntryToCache(
                                            context, archive, archiveName, password,
                                            item.index, progressCallback()
                                        )
                                        when {
                                            f == null -> "Cancelled."
                                            EntryTools.open(context, f) -> "Opened ${f.name}."
                                            else -> "No app can open ${f.name}."
                                        }
                                    }
                                }
                            ) { Text("Open") }
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    startTask {
                                        val f = ArchiveOps.extractEntryToCache(
                                            context, archive, archiveName, password,
                                            item.index, progressCallback()
                                        )
                                        when {
                                            f == null -> "Cancelled."
                                            EntryTools.share(context, f) -> "Sharing ${f.name}."
                                            else -> "Could not share ${f.name}."
                                        }
                                    }
                                }
                            ) { Text("Share") }
                        }
                    }
                }
                if (shown.size > 300) {
                    Text(
                        "Showing 300 of ${shown.size}. Type in the search box to narrow down.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                if (shown.isEmpty()) {
                    Text("Nothing matches.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                enabled = !busy && selected.isNotEmpty() && destination != null,
                onClick = {
                    val tree = destination ?: return@Button
                    val ids = selected.toIntArray()
                    startTask {
                        val r = ArchiveOps.extractSelectedToTree(
                            context, archive, archiveName, password, ids, tree,
                            progressCallback()
                        )
                        when {
                            r.cancelled -> "Cancelled."
                            r.failed > 0 -> "Extracted ${r.extracted}, ${r.failed} damaged."
                            else -> "Extracted ${r.extracted} files."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (destination == null) "Pick a destination folder first"
                    else "Extract selected"
                )
            }
        }
    }
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}
