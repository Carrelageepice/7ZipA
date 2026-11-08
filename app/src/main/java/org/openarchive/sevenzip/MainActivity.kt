
package org.openarchive.sevenzip

import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SevenZipApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SevenZipApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableStateOf(ThemePrefs.load(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var advancedMode by remember { mutableStateOf(AppPrefs.advancedOptions(context)) }

    var selectedArchive by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("No archive selected") }

    var archiveEntries by remember {
        mutableStateOf<List<ArchiveEntry>>(emptyList())
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val engineStatus = remember { NativeBridge.engineStatus() }

    var isExtracting by remember { mutableStateOf(false) }
    var extractStatus by remember { mutableStateOf<String?>(null) }
    val cancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    var archivePassword by remember { mutableStateOf<String?>(null) }

    // Destination folder, remembered across extractions and app restarts.
    var destinationTree by remember { mutableStateOf(loadSavedTree(context)) }
    var extractAfterPick by remember { mutableStateOf(false) }
    var createAfterPick by remember { mutableStateOf(false) }

    // "Create an archive" form
    var createSources by remember { mutableStateOf<List<ArchiveOps.CreateSource>>(emptyList()) }
    var createFormat by remember { mutableStateOf("7z") }
    var createLevel by remember { mutableStateOf(5) }
    var compressionOptions by remember { mutableStateOf(CompressionOptions()) }
    var createName by remember { mutableStateOf("archive") }
    var createPassword by remember { mutableStateOf("") }
    var showCreatePassword by remember { mutableStateOf(false) }
    var encryptNames by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var createStatus by remember { mutableStateOf<String?>(null) }
    var lastCreated by remember { mutableStateOf<Pair<Uri, String>?>(null) }  // for the Share button
    val createCancel = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var passwordPrompt by remember { mutableStateOf<PasswordPrompt?>(null) }

    // Lists the archive; asks for a password (and retries) when needed.
    fun loadArchive(uri: Uri, name: String, password: String?) {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val entries = withContext(Dispatchers.IO) {
                    ArchiveOps.listArchive(context, uri, name, password)
                }
                archivePassword = password
                archiveEntries = entries
            } catch (e: ArchiveException) {
                if (e is PasswordRequiredException || e is WrongPasswordException) {
                    passwordPrompt = PasswordPrompt(
                        wrong = e is WrongPasswordException,
                        onSubmit = { pw ->
                            passwordPrompt = null
                            loadArchive(uri, name, pw)
                        },
                        onCancel = {
                            passwordPrompt = null
                            errorMessage = "A password is required to open this archive."
                        }
                    )
                } else {
                    errorMessage = e.message
                }
            } catch (e: Exception) {
                errorMessage = e.message
                    ?: "An unknown error occurred while reading the archive."
            } finally {
                isLoading = false
            }
        }
    }

    // Extracts into the chosen folder; asks for a password (and retries) when needed.
    fun runExtraction(archive: Uri, name: String, tree: Uri, password: String?) {
        cancelFlag.set(false)
        isExtracting = true
        extractStatus = "Starting..."

        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    ArchiveOps.extractToTree(
                        context, archive, name, password, tree,
                        ExtractProgress { doneBytes, totalBytes, entryName ->
                            extractStatus = if (totalBytes > 0) {
                                "Extracting ${formatFileSize(doneBytes)} / " +
                                    "${formatFileSize(totalBytes)}  $entryName"
                            } else {
                                "Extracting  $entryName"
                            }
                            !cancelFlag.get()
                        }
                    )
                }
                archivePassword = password
                extractStatus = when {
                    summary.cancelled -> "Extraction cancelled."
                    else -> {
                        val parts = mutableListOf(
                            "Done: ${summary.extracted} files extracted into " +
                                ArchiveOps.folderNameFor(name)
                        )
                        if (summary.failed > 0) parts += "${summary.failed} had errors"
                        if (summary.skipped > 0) parts += "${summary.skipped} unsafe entries or links skipped"
                        parts.joinToString(", ") + "."
                    }
                }
            } catch (e: ArchiveException) {
                if (e is PasswordRequiredException || e is WrongPasswordException) {
                    extractStatus = null
                    passwordPrompt = PasswordPrompt(
                        wrong = e is WrongPasswordException,
                        onSubmit = { pw ->
                            passwordPrompt = null
                            runExtraction(archive, name, tree, pw)
                        },
                        onCancel = {
                            passwordPrompt = null
                            extractStatus = "Extraction cancelled."
                        }
                    )
                } else {
                    extractStatus = "Error: " + e.message
                }
            } catch (e: SecurityException) {
                destinationTree = null
                saveTree(context, null)
                extractStatus = "Access to the destination folder was lost. " +
                    "Please choose the folder again."
            } catch (e: Exception) {
                extractStatus = "Error: " + (e.message ?: "extraction failed.")
            } finally {
                isExtracting = false
            }
        }
    }

    // Compresses the chosen files/folders and saves the archive into [tree].
    fun runCreate(tree: Uri) {
        val sources = createSources
        val format = createFormat
        // Casual mode always uses the defaults, whatever was picked earlier.
        val level = if (advancedMode) createLevel else 5
        val options = if (advancedMode) compressionOptions else CompressionOptions()
        val name = createName
        val password = if (format.startsWith("tar")) null else createPassword.ifEmpty { null }
        val encrypt = encryptNames && format == "7z" && password != null

        createCancel.set(false)
        isCreating = true
        createStatus = "Preparing files..."
        lastCreated = null

        scope.launch {
            try {
                val (summary, createdUri) = withContext(Dispatchers.IO) {
                    ArchiveOps.createToTreeEx(
                        context, sources, name, format, level, password, encrypt, tree,
                        ExtractProgress { doneBytes, totalBytes, entryName ->
                            createStatus = if (totalBytes > 0) {
                                "Compressing ${formatFileSize(doneBytes)} / " +
                                    "${formatFileSize(totalBytes)}  $entryName"
                            } else {
                                "Compressing  $entryName"
                            }
                            !createCancel.get()
                        },
                        options
                    )
                }
                if (createdUri != null) {
                    lastCreated = Pair(
                        createdUri,
                        ArchiveOps.safeName(name) + "." + ArchiveOps.extensionFor(format)
                    )
                }
                createStatus = if (summary.cancelled) {
                    "Cancelled."
                } else {
                    "Done: ${ArchiveOps.safeName(name)}.${ArchiveOps.extensionFor(format)} " +
                        "created (${summary.files} files) in ${treeLabel(tree)}."
                }
            } catch (e: SecurityException) {
                destinationTree = null
                saveTree(context, null)
                createStatus = "Access to the destination folder was lost. " +
                    "Please choose the folder again."
            } catch (e: Exception) {
                createStatus = "Error: " + (e.message ?: "creating the archive failed.")
            } finally {
                isCreating = false
            }
        }
    }

    // Opens an archive from anywhere: picker, recent list, or another app.
    fun openArchiveUri(uri: Uri, name: String) {
        selectedArchive = uri
        selectedName = name

        archiveEntries = emptyList()
        errorMessage = null
        extractStatus = null
        archivePassword = null

        RecentArchives.add(context, uri, name)  // also keeps read access when possible
        loadArchive(uri, name, null)
    }

    val openArchive = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) openArchiveUri(uri, getFileName(context, uri))
    }

    // Archive handed over by "Open with" / "Share" from another app.
    LaunchedEffect(Unit) {
        val incoming = IncomingArchives.from(context, (context as? android.app.Activity)?.intent)
        if (incoming != null) openArchiveUri(incoming.uri, incoming.name)
    }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree != null) {
            // Keep the permission so the picker isn't needed again.
            try {
                context.contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Still usable for this session.
            }
            saveTree(context, tree)
            destinationTree = tree

            val archive = selectedArchive
            if (extractAfterPick && archive != null) {
                runExtraction(archive, selectedName, tree, archivePassword)
            } else if (createAfterPick) {
                runCreate(tree)
            }
        }
        extractAfterPick = false
        createAfterPick = false
    }

    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val added = uris
                .filter { u -> createSources.none { it.uri == u } }
                .map { ArchiveOps.CreateSource(it, getFileName(context, it), false) }
            val wasEmpty = createSources.isEmpty()
            createSources = createSources + added
            if (wasEmpty && added.size == 1 && createName == "archive") {
                createName = ArchiveOps.folderNameFor(added[0].name)
            }
            createStatus = null
        }
    }

    val pickSourceFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree != null && createSources.none { it.uri == tree }) {
            val folderName = ArchiveOps.treeRootName(context, tree)
            val wasEmpty = createSources.isEmpty()
            createSources = createSources + ArchiveOps.CreateSource(tree, folderName, true)
            if (wasEmpty && createName == "archive") createName = ArchiveOps.safeName(folderName)
            createStatus = null
        }
    }

    AppTheme(themeMode) {
        passwordPrompt?.let { PasswordDialog(it) }
        BackHandler(enabled = showSettings) { showSettings = false }

        if (showSettings) SettingsScreen(
            themeMode = themeMode,
            onThemeChange = {
                themeMode = it
                ThemePrefs.save(context, it)
            },
            advancedOptions = advancedMode,
            onAdvancedChange = { advancedMode = it },
            onBack = { showSettings = false }
        ) else Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "7Zip",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Based on 7Zip's engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings"
                            )
                        }
                    }
                )
            }
        ) { padding ->

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                item {
                    RecentArchivesCard(refreshKey = selectedArchive) { uri, name ->
                        openArchiveUri(uri, name)
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(Modifier.padding(18.dp)) {

                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Archive,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Text(
                                    "  Open an archive",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                selectedName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    openArchive.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null
                                )
                                Text("  Choose file")
                            }

                            if (isLoading) {
                                Spacer(Modifier.height(12.dp))

                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    "Reading archive...",
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Error: $errorMessage",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (selectedArchive != null && !isLoading && errorMessage == null) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                Text(
                                    "Extract",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(Modifier.height(8.dp))

                                if (isExtracting) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { cancelFlag.set(true) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Cancel") }
                                } else {
                                    val dest = destinationTree
                                    Button(
                                        onClick = {
                                            val archive = selectedArchive
                                            if (dest != null && archive != null) {
                                                runExtraction(archive, selectedName, dest, archivePassword)
                                            } else {
                                                extractAfterPick = true
                                                pickFolder.launch(null)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            if (dest != null) "Extract all to ${treeLabel(dest)}"
                                            else "Extract all to folder..."
                                        )
                                    }

                                    if (dest != null) {
                                        TextButton(
                                            onClick = {
                                                extractAfterPick = false
                                                pickFolder.launch(dest)
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Change folder...") }
                                    }

                                    PerformanceSection()
                                }

                                extractStatus?.let {
                                    Text(
                                        it,
                                        modifier = Modifier.padding(top = 8.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                val doneTree = destinationTree
                                if (doneTree != null && extractStatus?.startsWith("Done") == true) {
                                    TextButton(
                                        onClick = {
                                            if (!EntryTools.openFolder(context, doneTree)) {
                                                extractStatus = "No app found to show the folder."
                                            }
                                        }
                                    ) { Text("Open destination folder") }
                                }
                            }
                        }
                    }
                }

                if (selectedArchive != null && !isLoading && errorMessage == null) {
                    item {
                        Text(
                            "Archive contents",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Text(
                            run {
                                val files = archiveEntries.count { !it.isDirectory }
                                val folders = archiveEntries.size - files
                                val total = archiveEntries.sumOf { if (it.isDirectory) 0L else it.size }
                                "$files files, $folders folders, ${formatFileSize(total)} unpacked" +
                                    if (archiveEntries.any { it.encrypted }) "  ·  encrypted" else ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (archiveEntries.isNotEmpty()) {
                        item {
                            ArchiveToolsCard(
                                archive = selectedArchive!!,
                                archiveName = selectedName,
                                password = archivePassword,
                                entries = archiveEntries,
                                destination = destinationTree
                            )
                        }
                    }

                    if (archiveEntries.isEmpty()) {
                        item {
                            Text(
                                "The archive contains no entries.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(archiveEntries) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) {
                                        Icons.Default.Folder
                                    } else {
                                        Icons.Default.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                ) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    if (!entry.isDirectory) {
                                        Text(
                                            formatFileSize(entry.size) +
                                                if (entry.encrypted) "  ·  encrypted" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            HorizontalDivider()
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {

                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )

                                Text(
                                    "  Create an archive",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                                    enabled = !isCreating,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Add files") }

                                OutlinedButton(
                                    onClick = { pickSourceFolder.launch(null) },
                                    enabled = !isCreating,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Add folder") }
                            }

                            Spacer(Modifier.height(8.dp))

                            if (createSources.isEmpty()) {
                                Text(
                                    "Nothing added yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                createSources.forEach { source ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = if (source.isFolder) {
                                                Icons.Default.Folder
                                            } else {
                                                Icons.Default.InsertDriveFile
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            source.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 8.dp)
                                        )
                                        TextButton(
                                            onClick = { createSources = createSources - source },
                                            enabled = !isCreating
                                        ) { Text("Remove") }
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Text("Format", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "7z" to "7z", "zip" to "ZIP", "tar" to "TAR",
                                    "tar.gz" to "TAR.GZ", "tar.xz" to "TAR.XZ",
                                    "tar.bz2" to "TAR.BZ2"
                                ).forEach { (key, label) ->
                                    FilterChip(
                                        selected = createFormat == key,
                                        onClick = { createFormat = key },
                                        label = { Text(label) },
                                        enabled = !isCreating
                                    )
                                }
                            }

                            if (advancedMode && createFormat != "tar") {
                                Spacer(Modifier.height(8.dp))
                                Text("Compression", style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        0 to "Store", 1 to "Fast", 5 to "Normal", 7 to "Maximum"
                                    ).forEach { (value, label) ->
                                        FilterChip(
                                            selected = createLevel == value,
                                            onClick = { createLevel = value },
                                            label = { Text(label) },
                                            enabled = !isCreating
                                        )
                                    }
                                }
                            }

                            if (advancedMode && (createFormat == "7z" || createFormat == "zip")) {
                                Spacer(Modifier.height(8.dp))
                                CompressionOptionsCard(createFormat, compressionOptions) {
                                    compressionOptions = it
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = createName,
                                onValueChange = { createName = it },
                                singleLine = true,
                                label = { Text("Archive name") },
                                suffix = { Text("." + ArchiveOps.extensionFor(createFormat)) },
                                enabled = !isCreating,
                                modifier = Modifier.fillMaxWidth()
                            )

                            val zipPasswordBad = createFormat == "zip" &&
                                createPassword.isNotEmpty() &&
                                !ArchiveOps.isValidZipPassword(createPassword)

                            if (!createFormat.startsWith("tar")) {
                                Spacer(Modifier.height(8.dp))
                                val passwordHelp: (@Composable () -> Unit)? =
                                    if (zipPasswordBad) {
                                        {
                                            Text(
                                                "ZIP passwords: basic keyboard characters only " +
                                                    "(no accents or emoji), max 99. " +
                                                    "Use 7z for other characters."
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                OutlinedTextField(
                                    value = createPassword,
                                    onValueChange = { createPassword = it },
                                    singleLine = true,
                                    label = { Text("Password (optional)") },
                                    visualTransformation = if (showCreatePassword) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password
                                    ),
                                    trailingIcon = {
                                        TextButton(
                                            onClick = { showCreatePassword = !showCreatePassword }
                                        ) { Text(if (showCreatePassword) "Hide" else "Show") }
                                    },
                                    isError = zipPasswordBad,
                                    supportingText = passwordHelp,
                                    enabled = !isCreating,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (createFormat == "7z" && createPassword.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = encryptNames,
                                        onCheckedChange = { encryptNames = it },
                                        enabled = !isCreating
                                    )
                                    Text("Also encrypt file names")
                                }
                            }
                            if (createFormat == "zip" && createPassword.isNotEmpty() && !zipPasswordBad) {
                                Text(
                                    "ZIP uses AES-256. Windows' built-in ZIP viewer can't open " +
                                        "it; 7-Zip, WinZip and most apps can.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            if (isCreating) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { createCancel.set(true) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Cancel") }
                            } else {
                                PerformanceSection()
                                val dest = destinationTree
                                val canCreate = createSources.isNotEmpty() &&
                                    createName.isNotBlank() && !zipPasswordBad
                                Button(
                                    onClick = {
                                        if (dest != null) {
                                            runCreate(dest)
                                        } else {
                                            createAfterPick = true
                                            pickFolder.launch(null)
                                        }
                                    },
                                    enabled = canCreate,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (dest != null) "Create in ${treeLabel(dest)}"
                                        else "Create archive..."
                                    )
                                }
                                if (dest != null) {
                                    TextButton(
                                        onClick = {
                                            extractAfterPick = false
                                            createAfterPick = false
                                            pickFolder.launch(dest)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Change folder...") }
                                }
                            }

                            createStatus?.let {
                                Text(
                                    it,
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            lastCreated?.let { (uri, fileName) ->
                                TextButton(
                                    onClick = {
                                        if (!EntryTools.shareUri(context, uri, fileName)) {
                                            createStatus = "No app available to share this file."
                                        }
                                    }
                                ) { Text("Share $fileName") }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Engine status",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            engineStatus,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                item {
                    Text(
                        "Development build · Rev 0.14 · WIP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var name = "Selected archive"

    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

            if (index >= 0) {
                name = cursor.getString(index) ?: name
            }
        }
    }

    return name
}
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = bytes.toDouble() / 1024.0
    var unit = 0

    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024.0
        unit++
    }

    return String.format("%.2f %s", size, units[unit])
}


private class PasswordPrompt(
    val wrong: Boolean,
    val onSubmit: (String) -> Unit,
    val onCancel: () -> Unit
)

@Composable
private fun PasswordDialog(prompt: PasswordPrompt) {
    var text by remember(prompt) { mutableStateOf("") }
    var visible by remember(prompt) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = prompt.onCancel,
        title = { Text("Password required") },
        text = {
            Column {
                if (prompt.wrong) {
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
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
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
            TextButton(
                onClick = { prompt.onSubmit(text) },
                enabled = text.isNotEmpty()
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = prompt.onCancel) { Text("Cancel") }
        }
    )
}


private const val PREFS_NAME = "sevenzip_settings"
private const val KEY_DESTINATION = "extract_tree_uri"

/** The saved destination, or null if none or its permission is gone. */
private fun loadSavedTree(context: Context): Uri? {
    return try {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DESTINATION, null) ?: return null
        val uri = Uri.parse(saved)
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (stillGranted) uri else null
    } catch (_: Exception) {
        null
    }
}

private fun saveTree(context: Context, uri: Uri?) {
    try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (uri != null) putString(KEY_DESTINATION, uri.toString())
            else remove(KEY_DESTINATION)
        }.apply()
    } catch (_: Exception) {
        // Remembering the folder is a convenience; ignore failures.
    }
}

/** "primary:Download/Archives" -> "Download/Archives". */
private fun treeLabel(tree: Uri): String =
    try {
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':')
            .ifEmpty { "Internal storage" }
    } catch (_: Exception) {
        "the selected folder"
    }
