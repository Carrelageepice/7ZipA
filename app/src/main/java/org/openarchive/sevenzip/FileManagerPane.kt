package org.openarchive.sevenzip

import android.Manifest
import android.content.Context
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Process-wide scope for file jobs, so they finish even if you switch tabs. */
private val FileJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private class Clip(val files: List<File>, val move: Boolean)

private class PasswordAsk(val archive: File, val wrong: Boolean)

private const val PREFS = "settings"
private const val KEY_DIR = "fm_dir"

/**
 * The built-in file manager: browse storage, open archives, and work on files
 * (compress, extract here, copy, move, rename, delete, share).
 */
@Composable
fun FileManagerPane(
    modifier: Modifier = Modifier,
    goTo: File? = null,                 // set by the storage button in the top bar
    onGoToHandled: () -> Unit = {},
    onOpenArchive: (File) -> Unit
) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(FileOps.hasAccess(context)) }

    if (hasAccess) {
        FileManagerContent(modifier, goTo, onGoToHandled, onOpenArchive)
    } else {
        AccessGate(modifier) { hasAccess = FileOps.hasAccess(context) }
    }
}

@Composable
private fun AccessGate(modifier: Modifier, recheck: () -> Unit) {
    val legacy = android.os.Build.VERSION.SDK_INT < 30
    val askLegacy = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { recheck() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Files", style = MaterialTheme.typography.titleLarge)
                Text(
                    "To browse and manage your files, the app needs access to all files " +
                        "on this device. Nothing leaves your phone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (legacy) {
                    Button(
                        onClick = {
                            askLegacy.launch(
                                arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow access") }
                } else {
                    AllFilesAccessToggle(onChanged = { recheck() })
                    OutlinedButton(onClick = recheck, modifier = Modifier.fillMaxWidth()) {
                        Text("I've allowed it, check again")
                    }
                }
                Text(
                    "You can still open archives from the Archives tab without this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun FileManagerContent(
    modifier: Modifier,
    goTo: File?,
    onGoToHandled: () -> Unit,
    onOpenArchive: (File) -> Unit
) {
    val context = LocalContext.current
    val roots = remember { FileOps.roots(context) }

    fun savedDir(): File {
        val saved = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null)
        } catch (_: Exception) {
            null
        }
        val f = saved?.let { File(it) }
        return if (f != null && f.isDirectory && FileOps.rootOf(roots, f) != null) f else roots[0].dir
    }

    var dir by remember { mutableStateOf(savedDir()) }
    var showHidden by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var listing by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clip by remember { mutableStateOf<Clip?>(null) }

    var busy by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var askDelete by remember { mutableStateOf(false) }
    var askRename by remember { mutableStateOf<File?>(null) }
    var askNewFolder by remember { mutableStateOf(false) }
    var askCompress by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf<PasswordAsk?>(null) }

    val root = FileOps.rootOf(roots, dir) ?: roots[0]
    val atRoot = dir.path == root.dir.path

    fun navigate(to: File) {
        dir = to
        selected = emptySet()
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DIR, to.absolutePath).apply()
        } catch (_: Exception) {
            // Remembering the folder is a convenience.
        }
    }

    // The storage button in the top bar asks us to jump to another volume.
    LaunchedEffect(goTo) {
        if (goTo != null) {
            if (goTo.isDirectory) navigate(goTo)
            onGoToHandled()
        }
    }

    LaunchedEffect(dir, refresh, showHidden) {
        val listed = withContext(Dispatchers.IO) { FileOps.list(dir, showHidden) }
        listing = listed
        val present = listed.map { it.file.path }.toSet()
        selected = selected.filter { it in present }.toSet()
    }

    BackHandler(enabled = selected.isNotEmpty() || !atRoot) {
        if (selected.isNotEmpty()) selected = emptySet()
        else dir.parentFile?.let { navigate(it) }
    }

    // Runs a long operation off the main thread with progress, cancel and a notification.
    fun runJob(
        title: String,
        onPassword: ((Boolean) -> Unit)? = null,
        work: (ExtractProgress) -> String
    ) {
        if (busy) return
        busy = true
        cancelRequested = false
        status = "$title..."
        JobControl.begin(context, title)
        FileJobScope.launch {
            val progress = ExtractProgress { done, total, name ->
                status = if (total > 0) {
                    "$title  ${formatSize(done)} / ${formatSize(total)}  $name"
                } else {
                    "$title  $name"
                }
                JobControl.progress(context, done, total, name)
                !cancelRequested && !JobControl.cancelled
            }
            var asked = false
            val result = try {
                withContext(Dispatchers.IO) { work(progress) }
            } catch (e: PasswordRequiredException) {
                if (onPassword != null) { asked = true; onPassword(false); "" } else "A password is required."
            } catch (e: WrongPasswordException) {
                if (onPassword != null) { asked = true; onPassword(true); "" } else "Wrong password."
            } catch (e: OperationCancelled) {
                "Cancelled."
            } catch (e: Exception) {
                "Error: " + (e.message ?: "the operation failed.")
            }
            busy = false
            status = if (asked) null else result
            selected = emptySet()
            refresh++
            JobControl.end(context, if (asked) "Waiting for a password." else result)
        }
    }

    fun extractHere(archive: File, password: String?) {
        runJob(
            "Extracting ${archive.name}",
            onPassword = { wrong -> askPassword = PasswordAsk(archive, wrong) }
        ) { progress ->
            val (summary, out) = FileOps.extractHere(context, archive, password, progress)
            when {
                summary.cancelled -> "Extraction cancelled."
                else -> {
                    val parts = mutableListOf("Extracted ${summary.extracted} files into ${out.name}")
                    if (summary.failed > 0) parts += "${summary.failed} had errors"
                    if (summary.skipped > 0) parts += "${summary.skipped} unsafe entries skipped"
                    parts.joinToString(", ") + "."
                }
            }
        }
    }

    val selectedFiles = listing.filter { it.file.path in selected }

    Column(modifier = modifier.fillMaxSize()) {

        // ---- where am I (switch storage with the card icon in the top bar)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 6.dp)
        ) {
            Text(
                "/" + dir.path.removePrefix(root.dir.path).trimStart('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { dir.parentFile?.let { navigate(it) } }, enabled = !atRoot) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Up")
            }
            IconButton(onClick = { askNewFolder = true }, enabled = !busy) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
            }
            TextButton(onClick = { showHidden = !showHidden }) {
                Text(if (showHidden) "Hidden: on" else "Hidden: off")
            }
        }

        // ---- action bars
        if (selected.isNotEmpty()) {
            val allSelected = listing.isNotEmpty() && selected.size >= listing.size
            val one = selectedFiles.singleOrNull()
            val tiny = MaterialTheme.typography.labelMedium
            val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)

            @Composable
            fun RowScope.Act(
                label: String,
                enabled: Boolean = true,
                color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
                onClick: () -> Unit
            ) {
                TextButton(
                    onClick = onClick,
                    enabled = enabled,
                    contentPadding = pad,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(label, style = tiny, color = color, maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis)
                }
            }

            // Always exactly two rows of five buttons, so the bar never grows.
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Act(if (allSelected) "None" else "All") {
                            selected = if (allSelected) emptySet()
                            else listing.map { it.file.path }.toSet()
                        }
                        Act("Compress", enabled = !busy) { askCompress = true }
                        Act("Copy") {
                            clip = Clip(selectedFiles.map { it.file }, false)
                            selected = emptySet()
                        }
                        Act("Cut") {
                            clip = Clip(selectedFiles.map { it.file }, true)
                            selected = emptySet()
                        }
                        Act("Delete", enabled = !busy, color = MaterialTheme.colorScheme.error) {
                            askDelete = true
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Act("Extract", enabled = !busy && one != null && one.isArchive) {
                            one?.let { extractHere(it.file, null) }
                        }
                        Act("Browse", enabled = one != null && one.isArchive) {
                            one?.let { onOpenArchive(it.file) }
                        }
                        Act("Rename", enabled = one != null) {
                            one?.let { askRename = it.file }
                        }
                        Act("Share", enabled = one != null && !one.isDir) {
                            one?.let {
                                if (!EntryTools.share(context, it.file)) {
                                    status = "Could not share this file."
                                }
                            }
                        }
                        Act("Cancel (${selected.size})") { selected = emptySet() }
                    }
                }
            }
        } else if (clip != null) {
            val c = clip!!
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                ) {
                    Text(
                        (if (c.move) "Move " else "Copy ") + "${c.files.size} item" +
                            (if (c.files.size == 1) "" else "s"),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge
                    )
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            val target = dir
                            val move = c.move
                            val files = c.files
                            clip = null
                            var done = 0L
                            runJob(if (move) "Moving" else "Copying") { progress ->
                                val total = files.sumOf { FileOps.totalSize(it) }
                                val n = FileOps.paste(
                                    files, target, move,
                                    cancel = { cancelRequested || JobControl.cancelled },
                                    onBytes = {
                                        done += it
                                        progress.onProgress(done, total, "")
                                    }
                                )
                                (if (move) "Moved " else "Copied ") + "$n item" +
                                    (if (n == 1) "" else "s") + "."
                            }
                        }
                    ) { Text("Paste here") }
                    TextButton(onClick = { clip = null }) { Text("Cancel") }
                }
            }
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 18.dp)
            ) {
                Text(
                    status ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { cancelRequested = true }) { Text("Cancel") }
            }
        } else if (status != null) {
            Text(
                status!!,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider()

        // ---- the list
        if (listing.isEmpty()) {
            Text(
                "This folder is empty.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(18.dp)
            )
        }

        val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(listing, key = { it.file.path }) { entry ->
                val isSelected = entry.file.path in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (selected.isNotEmpty()) {
                                    selected = if (isSelected) selected - entry.file.path
                                    else selected + entry.file.path
                                } else if (entry.isDir) {
                                    navigate(entry.file)
                                } else if (entry.isArchive) {
                                    onOpenArchive(entry.file)
                                } else if (!EntryTools.open(context, entry.file)) {
                                    status = "No app can open ${entry.name}."
                                }
                            },
                            onLongClick = {
                                selected = if (isSelected) selected - entry.file.path
                                else selected + entry.file.path
                            }
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    if (selected.isNotEmpty()) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                        Spacer(Modifier.padding(start = 8.dp))
                    }
                    Icon(
                        imageVector = when {
                            entry.isDir -> Icons.Default.Folder
                            entry.isArchive -> Icons.Default.Archive
                            else -> Icons.Default.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp)
                    ) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            (if (entry.isDir) "" else formatSize(entry.size) + "  ·  ") +
                                dateFormat.format(Date(entry.modified)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ---- dialogs
    if (askDelete) {
        val victims = selectedFiles.map { it.file }
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete ${victims.size} item" + if (victims.size == 1) "?" else "s?") },
            text = { Text("This cannot be undone. Folders are deleted with everything inside.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    runJob("Deleting") {
                        var n = 0
                        for (f in victims) {
                            if (cancelRequested || JobControl.cancelled) throw OperationCancelled()
                            if (f.deleteRecursively()) n++
                        }
                        "Deleted $n of ${victims.size} item" + if (victims.size == 1) "." else "s."
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } }
        )
    }

    askRename?.let { target ->
        NameDialog(
            title = "Rename",
            initial = target.name,
            confirmLabel = "Rename",
            onDismiss = { askRename = null },
            onConfirm = { name ->
                val error = FileOps.rename(target, name)
                askRename = null
                selected = emptySet()
                status = error
                refresh++
            }
        )
    }

    if (askNewFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { askNewFolder = false },
            onConfirm = { name ->
                val error = FileOps.makeFolder(dir, name)
                askNewFolder = false
                status = error
                refresh++
            }
        )
    }

    if (askCompress) {
        val chosen = selectedFiles.map { it.file }
        CompressDialog(
            suggestedName = if (chosen.size == 1) chosen[0].nameWithoutExtension.ifEmpty { chosen[0].name }
            else dir.name.ifEmpty { "archive" },
            onDismiss = { askCompress = false },
            onConfirm = { name, format, password ->
                askCompress = false
                val destination = dir
                runJob("Compressing") { progress ->
                    val (summary, file) =
                        FileOps.compress(context, chosen, destination, name, format, password, progress)
                    if (summary.cancelled || file == null) "Compression cancelled."
                    else "Created ${file.name} (${summary.files} files)."
                }
            }
        )
    }

    askPassword?.let { ask ->
        PasswordDialogSimple(
            wrong = ask.wrong,
            onSubmit = { pw ->
                askPassword = null
                extractHere(ask.archive, pw)
            },
            onCancel = {
                askPassword = null
                status = "Extraction cancelled."
            }
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompressDialog(
    suggestedName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, format: String, password: String?) -> Unit
) {
    var name by remember { mutableStateOf(suggestedName) }
    var format by remember { mutableStateOf("7z") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    val isTar = format.startsWith("tar")
    val zipBad = format == "zip" && password.isNotEmpty() && !ArchiveOps.isValidZipPassword(password)
    val zipHelp: (@Composable () -> Unit)? =
        if (zipBad) {
            { Text("ZIP passwords: basic keyboard characters only, max 99.") }
        } else {
            null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Archive name") },
                    suffix = { Text("." + ArchiveOps.extensionFor(format)) }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("7z" to "7z", "zip" to "ZIP", "tar.gz" to "TAR.GZ", "tar.xz" to "TAR.XZ")
                        .forEach { (key, label) ->
                            FilterChip(
                                selected = format == key,
                                onClick = { format = key },
                                label = { Text(label) }
                            )
                        }
                }
                if (!isTar) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("Password (optional)") },
                        isError = zipBad,
                        supportingText = zipHelp,
                        visualTransformation = if (visible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { visible = !visible }) {
                                Text(if (visible) "Hide" else "Show")
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !zipBad,
                onClick = { onConfirm(name, format, if (isTar) null else password.ifEmpty { null }) }
            ) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PasswordDialogSimple(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            Column {
                if (wrong) {
                    Text(
                        "Wrong password, try again.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(if (visible) "Hide" else "Show")
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotEmpty()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
