package com.adshield.pro.ui.screens.cloud

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adshield.pro.data.cloud.BackupRowDto
import com.adshield.pro.data.cloud.CloudFileKind
import com.adshield.pro.data.cloud.CloudNodeDto
import com.adshield.pro.data.cloud.ShareRowDto
import com.adshield.pro.data.cloud.fileKind
import com.adshield.pro.data.cloud.formatCloudBytes
import com.adshield.pro.ui.components.CyberPanel
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.ElectricBlue
import com.adshield.pro.ui.theme.SkyBlueGlow
import com.adshield.pro.ui.viewmodel.CloudSection
import com.adshield.pro.ui.viewmodel.CloudUiState
import com.adshield.pro.ui.viewmodel.CloudViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The AdShield Cloud dashboard.
 *
 * Ten sections, all driven by the real API: nothing here renders a capacity, a file or a progress
 * value that the backend did not report.
 */
@Composable
fun CloudDashboardScreen(
    viewModel: CloudViewModel = hiltViewModel(),
    onOpenLegacyProviders: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.workingStatus.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val quarantine by viewModel.quarantine.collectAsStateWithLifecycle()

    var section by remember { mutableStateOf(CloudSection.OVERVIEW) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris, background = false)
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris, background = true)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }
    LaunchedEffect(state.pendingOpenUrl) {
        val url = state.pendingOpenUrl ?: return@LaunchedEffect
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        viewModel.consumePendingUrl()
    }

    // Telegram is a self-contained provider: it does not depend on AdShield Cloud endpoints,
    // a Supabase session or a Worker, so it gets its own full-screen surface instead of being
    // gated by the Cloud empty state. All other sections keep their existing layout.
    if (section == CloudSection.TELEGRAM) {
        TelegramCloudScreen(onBack = { section = CloudSection.OVERVIEW })
    } else {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CloudStatusHeader(status = status, usage = state.usage)
            CloudSectionTabs(current = section, onSelect = { section = it })

            if (!state.endpoints.isConfigured && section != CloudSection.SETTINGS) {
                CloudEmptyState(
                    message = "AdShield Cloud is not connected yet. Open Settings and point it at your free Worker + Supabase project (see cloud/README.md in the repository)."
                )
            } else if (state.signedIn || section == CloudSection.SETTINGS) {
                when (section) {
                    CloudSection.OVERVIEW -> OverviewSection(viewModel, state, section) { section = it }
                    CloudSection.FILES -> FilesSection(viewModel, state, { picker.launch(arrayOf("*/*")) })
                    CloudSection.PHOTOS -> MediaSection(viewModel, state, CloudFileKind.PHOTO, "Photos")
                    CloudSection.VIDEOS -> MediaSection(viewModel, state, CloudFileKind.VIDEO, "Videos")
                    CloudSection.DOCUMENTS -> MediaSection(viewModel, state, CloudFileKind.DOCUMENT, "Documents")
                    CloudSection.BACKUPS -> BackupsSection(viewModel, state)
                    CloudSection.SHARED -> SharedSection(viewModel, state, context)
                    CloudSection.UPLOADS -> UploadsSection(
                        viewModel, state, jobs, progress, quarantine,
                        onPick = { picker.launch(arrayOf("*/*")) },
                        onPickBackground = { backgroundPicker.launch(arrayOf("*/*")) }
                    )
                    CloudSection.SECURITY -> SecuritySection(viewModel, state, quarantine)
                    CloudSection.SETTINGS -> SettingsSection(viewModel, state, onOpenLegacyProviders)
                    // Unreachable: the Telegram provider renders its own surface above.
                    CloudSection.TELEGRAM -> Unit
                }
            } else {
                AuthSection(viewModel, state)
            }
        }
    }
    }
}

// ---------------------------------------------------------------- shared bits

private fun saveToDevice(context: Context, url: String, name: String, mime: String?) {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
    if (!mime.isNullOrBlank()) request.setMimeType(mime)
    context.getSystemService(DownloadManager::class.java).enqueue(request)
}

private fun openExternally(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun formatDate(epochMs: Long): String =
    if (epochMs <= 0) "—" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun SectionTitle(text: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- overview

@Composable
private fun OverviewSection(
    vm: CloudViewModel,
    state: CloudUiState,
    current: CloudSection,
    onSection: (CloudSection) -> Unit
) {
    val kinds = state.kinds
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudStatTile(
                label = "Files", value = "${kinds?.totalOrZero() ?: 0}",
                caption = "stored objects", color = SkyBlueGlow, modifier = Modifier.weight(1f)
            )
            CloudStatTile(
                label = "Storage", value = formatCloudBytes(state.usage?.usedBytes ?: 0L),
                caption = "of ${formatCloudBytes(state.usage?.quotaBytes ?: 0L)} free", color = CyberCyan,
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudStatTile("Photos", "${kinds?.photosOrZero() ?: 0}", "images in the cloud", ElectricBlue, Modifier.weight(1f))
            CloudStatTile("Videos", "${kinds?.videosOrZero() ?: 0}", "video files", ElectricBlue, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudStatTile("Audio", "${kinds?.audioOrZero() ?: 0}", "music and sound", ElectricBlue, Modifier.weight(1f))
            CloudStatTile("Documents", "${kinds?.documentsOrZero() ?: 0}", "pdf, text, office", ElectricBlue, Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudActionButton("Refresh", Icons.Default.Refresh, { vm.refresh() })
            CloudSecondaryButton("Reconcile storage", Icons.Default.Cloud, { vm.reconcile() })
        }

        CyberPanel(
            title = "Recent activity",
            subtitle = "the last actions recorded in your audit log"
        ) {
            if (state.audit.isEmpty()) {
                Text("No activity recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            } else {
                state.audit.take(6).forEach { entry ->
                    CloudInfoRow(
                        label = "${entry.action} · ${entry.detail ?: ""}".take(48),
                        value = formatDate(entry.createdAt)
                    )
                }
            }
        }

        CyberPanel(
            title = "Jump to a section",
            subtitle = "every screen below is backed by a real API call"
        ) {
            val sections = listOf(
                CloudSection.FILES to "Browse folders, rename, move, copy, delete and restore",
                CloudSection.PHOTOS to "Thumbnails loaded from signed URLs",
                CloudSection.VIDEOS to "Stream or save your videos",
                CloudSection.DOCUMENTS to "Open documents in your own apps",
                CloudSection.BACKUPS to "Back up and restore the full DNS configuration",
                CloudSection.SHARED to "Expiring share links you can revoke",
                CloudSection.UPLOADS to "Pause, resume and retry transfers",
                CloudSection.SECURITY to "Audit log, quota truth and quarantine",
                CloudSection.TELEGRAM to "Telegram provider: direct TDLib account, real uploads/downloads"
            )
            sections.forEach { (target, description) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sectionLabel(target), color = SkyBlueGlow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onSection(target) }) { Text("Open", fontSize = 11.sp) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- files

@Composable
private fun FilesSection(
    vm: CloudViewModel,
    state: CloudUiState,
    onPick: () -> Unit
) {
    var menuNode by remember { mutableStateOf<CloudNodeDto?>(null) }
    var renameNode by remember { mutableStateOf<CloudNodeDto?>(null) }
    var moveNode by remember { mutableStateOf<CloudNodeDto?>(null) }
    var createFolder by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudActionButton("Upload", Icons.Default.CloudUpload, onPick)
            CloudSecondaryButton("New folder", Icons.Default.Folder, { createFolder = true })
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = buildString {
                    append("Folder: /")
                    state.path.forEach { append(it.name).append('/') }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (state.path.isNotEmpty()) {
                TextButton(onClick = { vm.navigateUp() }) { Text("Up", fontSize = 11.sp) }
            }
        }
        var query by remember(state.search) { mutableStateOf(state.search) }
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it; vm.setSearch(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search files by name", fontSize = 11.sp) }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudSecondaryButton(
                text = if (state.showingTrash) "Show files" else "Show trash",
                icon = Icons.Default.Delete,
                onClick = { vm.showTrash(!state.showingTrash) }
            )
            CloudSecondaryButton("Reload", Icons.Default.Refresh, { vm.refresh() })
        }

        val nodes = if (state.showingTrash) state.trashed else state.nodes
        if (nodes.isEmpty()) {
            CloudEmptyState(
                message = if (state.showingTrash) "The trash is empty." else "Nothing here yet. Upload a file to get started."
            )
        } else {
            nodes.forEach { node ->
                Box {
                    CloudNodeRow(
                        node = node,
                        onOpen = { if (node.isFolder) vm.openFolder(node) else vm.download(node) },
                        onDownload = if (node.isFolder) null else ({ vm.download(node) }),
                        onMenu = { menuNode = node }
                    )
                    CloudNodeMenu(
                        node = node,
                        expanded = menuNode?.id == node.id,
                        onDismiss = { menuNode = null },
                        onRename = { renameNode = node },
                        onCopy = { vm.copy(node) },
                        onMove = { moveNode = node },
                        onShare = { vm.createShare(node, 86_400L) },
                        onVersions = { vm.loadVersions(node) },
                        onTrash = { vm.trash(node) },
                        onRestore = { vm.restore(node) },
                        onPurge = { vm.purge(node) }
                    )
                }
            }
        }
    }

    renameNode?.let { node ->
        CloudTextInputDialog(
            title = "Rename",
            label = "New name",
            initialValue = node.name,
            onConfirm = { vm.rename(node, it); renameNode = null },
            onDismiss = { renameNode = null }
        )
    }
    if (createFolder) {
        CloudTextInputDialog(
            title = "New folder",
            label = "Folder name",
            onConfirm = { vm.createFolder(it); createFolder = false },
            onDismiss = { createFolder = false }
        )
    }
    moveNode?.let { node ->
        val folders = state.nodes.filter { it.isFolder }
        val roots = if (state.path.isEmpty()) folders else folders
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { moveNode = null },
            title = { Text("Move “${node.name}” to", fontSize = 14.sp) },
            text = {
                Column {
                    if (state.path.isNotEmpty()) {
                        TextButton(onClick = { vm.move(node, null); moveNode = null }) { Text("Root folder", fontSize = 12.sp) }
                    }
                    roots.forEach { folder ->
                        TextButton(onClick = { vm.move(node, folder.id); moveNode = null }) {
                            Text(folder.name, fontSize = 12.sp)
                        }
                    }
                    if (roots.isEmpty()) {
                        Text("Create a folder first.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveNode = null }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------- media sections

@Composable
private fun MediaSection(
    vm: CloudViewModel,
    state: CloudUiState,
    kind: CloudFileKind,
    title: String
) {
    var nodes by remember { mutableStateOf<List<CloudNodeDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(kind) {
        loading = true
        nodes = runCatching { vm.media(kind) }.getOrDefault(emptyList())
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, "${nodes.size} item(s) stored in Backblaze B2")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudSecondaryButton("Reload", Icons.Default.Refresh, {
                scope.launch { loading = true; nodes = runCatching { vm.media(kind) }.getOrDefault(emptyList()); loading = false }
            })
        }
        if (loading) {
            CloudSmallSpinner(Modifier.padding(8.dp))
        } else if (nodes.isEmpty()) {
            CloudEmptyState(message = "No $title stored yet.")
        } else if (kind == CloudFileKind.PHOTO) {
            val urls = remember { mutableStateMapOf<String, String>() }
            LaunchedEffect(nodes) {
                nodes.forEach { node ->
                    if (!urls.containsKey(node.id)) {
                        vm.urlFor(node.id)?.let { urls[node.id] = it }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nodes.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { node ->
                            CloudPhotoTile(
                                node = node,
                                thumbnailUrl = urls[node.id],
                                onClick = { vm.download(node) }
                            )
                        }
                    }
                }
            }
        } else {
            nodes.forEach { node ->
                CloudNodeRow(
                    node = node,
                    onOpen = { vm.download(node) },
                    onDownload = {
                        scope.launch {
                            val url = vm.urlFor(node.id)
                            if (url != null) {
                                if (kind == CloudFileKind.VIDEO) openExternally(context, url)
                                else saveToDevice(context, url, node.name, node.contentType)
                            }
                        }
                    },
                    onMenu = { }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- backups

@Composable
private fun BackupsSection(vm: CloudViewModel, state: CloudUiState) {
    var confirmRestore by remember { mutableStateOf<BackupRowDto?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupRowDto?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CyberPanel(
            title = "Configuration backup",
            subtitle = "DNS resolver, protection mode, rules, filter lists, per-app firewall and preferences"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudActionButton("Back up now", Icons.Default.Backup, { vm.backupNow() })
                CloudSecondaryButton("Reload", Icons.Default.Refresh, { vm.refresh() })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.autoBackup, onCheckedChange = { vm.setAutoBackup(it) })
                Spacer(Modifier.width(8.dp))
                Text(
                    "Daily automatic backup on unmetered Wi-Fi",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            CloudInfoRow("Last automatic backup", formatDate(state.lastAutoBackupEpochMs))
            CloudInfoRow(
                "Client-side encryption",
                when {
                    state.vaultUnlocked -> "ON — AES-256-GCM before upload"
                    state.vaultConfigured -> "Configured, vault locked"
                    else -> "Off (provider-side encryption at rest)"
                },
                if (state.vaultUnlocked) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        CyberPanel(title = "Stored backups", subtitle = "each entry was hashed after upload and can be re-verified") {
            if (state.backups.isEmpty()) {
                Text("No backups yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            } else {
                state.backups.forEach { backup ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            backup.label.ifBlank { "Backup" },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatCloudBytes(backup.sizeBytes)} · ${formatDate(backup.createdAt)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Text(
                            "sha256 ${backup.sha256.take(24)}…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                        Row {
                            TextButton(onClick = { vm.verifyBackup(backup.id) }) { Text("Verify", fontSize = 11.sp) }
                            TextButton(onClick = { confirmRestore = backup }) { Text("Restore", fontSize = 11.sp) }
                            TextButton(onClick = { confirmDelete = backup }) {
                                Text("Delete", fontSize = 11.sp, color = Color(0xFFEF4444))
                            }
                        }
                        state.lastBackupVerification?.takeIf { it.first == backup.id }?.let { (_, ok) ->
                            CloudBadge(
                                text = if (ok) "verified" else "verification failed",
                                icon = if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                                color = if (ok) Color(0xFF22C55E) else Color(0xFFEF4444)
                            )
                        }
                    }
                }
            }
        }

        state.lastRestoreReport?.let { report ->
            CyberPanel(title = "Last restore", subtitle = "applied sections") {
                report.appliedSections.forEach { item ->
                    Text("• $item", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }

    confirmRestore?.let { backup ->
        CloudConfirmDialog(
            title = "Restore this backup?",
            message = "Your current rules and filter lists are replaced by the backup from ${formatDate(backup.createdAt)}. Settings are written through the same setters the UI uses.",
            confirmLabel = "Restore",
            onConfirm = { vm.restoreBackup(backup.id); confirmRestore = null },
            onDismiss = { confirmRestore = null }
        )
    }
    confirmDelete?.let { backup ->
        CloudConfirmDialog(
            title = "Delete this backup?",
            message = "The stored object is removed and its bytes are returned to your allowance.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.deleteBackup(backup.id); confirmDelete = null },
            onDismiss = { confirmDelete = null }
        )
    }
}

// ---------------------------------------------------------------- shared

@Composable
private fun SharedSection(vm: CloudViewModel, state: CloudUiState, context: Context) {
    var sharePicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CyberPanel(
            title = "Share links",
            subtitle = "each link is a random token; opening it issues a short-lived download URL"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudActionButton("Share a file", Icons.Default.Link, { sharePicker = true })
                CloudSecondaryButton("Reload", Icons.Default.Refresh, { vm.refresh() })
            }
            Spacer(Modifier.height(8.dp))
            if (state.shares.isEmpty()) {
                Text("No active links.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            } else {
                state.shares.forEach { share ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            share.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "created ${formatDate(share.createdAt)} · expires ${
                                share.expiresAt?.let { formatDate(it) } ?: "never"
                            } · ${share.downloads} open(s)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        Row {
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("AdShield share", shareLink(vm, share.token)))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy link", fontSize = 11.sp) }
                            TextButton(onClick = { vm.revokeShare(share.id) }) {
                                Text("Revoke", fontSize = 11.sp, color = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }
        }
    }

    if (sharePicker) {
        val files = state.nodes.filter { !it.isFolder }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { sharePicker = false },
            title = { Text("Share which file?", fontSize = 14.sp) },
            text = {
                Column {
                    if (files.isEmpty()) {
                        Text("Open My Files first, then share from there.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    files.forEach { file ->
                        TextButton(onClick = {
                            vm.createShare(file, 86_400L)
                            sharePicker = false
                        }) { Text(file.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sharePicker = false }) { Text("Cancel") } }
        )
    }
}

private fun shareLink(vm: CloudViewModel, token: String): String =
    "${vm.endpointsBaseUrl().trimEnd('/')}/v1/share/$token"

// ---------------------------------------------------------------- uploads

@Composable
private fun UploadsSection(
    vm: CloudViewModel,
    state: CloudUiState,
    jobs: List<com.adshield.pro.data.cloud.UploadJob>,
    progress: Map<String, com.adshield.pro.data.cloud.UploadProgress>,
    quarantine: List<com.adshield.pro.data.repository.QuarantineEntry>,
    onPick: () -> Unit,
    onPickBackground: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudActionButton("Upload now", Icons.Default.CloudUpload, onPick)
            CloudSecondaryButton("Upload in background", Icons.Default.History, onPickBackground)
        }
        CyberPanel(
            title = "Transfers",
            subtitle = "multipart, resumable; pause and resume re-send only the missing parts"
        ) {
            if (jobs.isEmpty()) {
                Text("No transfers in progress.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            } else {
                jobs.forEach { job ->
                    UploadJobRow(
                        job = job,
                        progress = progress[job.id],
                        onPause = { vm.pauseUpload(job.id) },
                        onResume = { vm.resumeUpload(job.id) },
                        onCancel = { vm.cancelUpload(job.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        CyberPanel(title = "Quarantine", subtitle = "files the on-device scanner refused to upload") {
            if (quarantine.isEmpty()) {
                Text("Nothing has been refused.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            } else {
                quarantine.forEach { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(entry.name, color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${entry.threatName ?: "suspicious file"} · risk ${entry.riskScore}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        entry.findings.take(2).forEach { finding ->
                            Text("• $finding", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- security

@Composable
private fun SecuritySection(
    vm: CloudViewModel,
    state: CloudUiState,
    quarantine: List<com.adshield.pro.data.repository.QuarantineEntry>
) {
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var sessionValid by remember { mutableStateOf<Boolean?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CyberPanel(title = "Storage truth", subtitle = "what the provider reports versus what the database counts") {
            state.reconcile?.let { reconcile ->
                CloudInfoRow("Provider bytes", formatCloudBytes(reconcile.b2Bytes))
                CloudInfoRow("Database bytes", formatCloudBytes(reconcile.databaseBytes))
                CloudInfoRow("Drift", formatCloudBytes(reconcile.driftBytes), if (reconcile.driftBytes == 0L) Color(0xFF22C55E) else Color(0xFFF59E0B))
                CloudInfoRow("Objects", "${reconcile.b2Objects}")
            } ?: Text(
                "Run a reconciliation from the Overview to compare the provider with the database.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))
            CloudSecondaryButton("Reconcile now", Icons.Default.Refresh, { vm.reconcile() })
        }

        CyberPanel(title = "Encryption", subtitle = "what is encrypted, where, and what is not") {
            CloudInfoRow("Transport", "HTTPS only (TLS in transit)")
            CloudInfoRow("At rest", "AES-256 by the storage provider")
            CloudInfoRow(
                "Backups",
                if (state.vaultUnlocked) "AES-256-GCM on device before upload" else "Provider-side only (unlock the vault in Settings)",
                if (state.vaultUnlocked) Color(0xFF22C55E) else Color(0xFFF59E0B)
            )
            CloudInfoRow(
                "Large files",
                "Provider-side only — a resumable multipart upload cannot seek inside an AES-GCM stream",
                MaterialTheme.colorScheme.onSurfaceVariant
            )
            CloudInfoRow("Credentials in APK", "None — only public project URLs and a Turnstile site key", Color(0xFF22C55E))
        }

        CyberPanel(title = "Session and limits", subtitle = "verified against Supabase and the API's own counters") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CloudSecondaryButton("Verify session", Icons.Default.Lock, {
                    verifying = true
                    scope.launch {
                        sessionValid = runCatching { vm.verifySession() }.getOrDefault(false)
                        verifying = false
                    }
                })
                if (verifying) CloudSmallSpinner()
                sessionValid?.let { valid ->
                    CloudBadge(
                        text = if (valid) "session valid" else "session rejected",
                        icon = if (valid) Icons.Default.CheckCircle else Icons.Default.Error,
                        color = if (valid) Color(0xFF22C55E) else Color(0xFFEF4444)
                    )
                }
            }
            CloudInfoRow("API requests (24 h)", "${state.account?.apiRequestsLast24h ?: 0}")
            CloudInfoRow("Hourly ceiling", "${state.account?.limits?.hourlyRequests ?: 0} (429 beyond it)")
            CloudInfoRow("Signed URLs", "download and share links expire in minutes")
            CloudInfoRow("Malware refusals", "${quarantine.size} file(s)")
        }

        CyberPanel(title = "Audit log", subtitle = "every mutating action recorded server-side") {
            if (state.audit.isEmpty()) {
                Text("No entries yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            } else {
                state.audit.take(20).forEach { entry ->
                    CloudInfoRow("${entry.action} · ${entry.detail ?: ""}".take(46), formatDate(entry.createdAt))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsSection(
    vm: CloudViewModel,
    state: CloudUiState,
    onOpenLegacyProviders: () -> Unit
) {
    var api by remember(state.endpoints) { mutableStateOf(state.endpoints.apiBaseUrl) }
    var supabase by remember(state.endpoints) { mutableStateOf(state.endpoints.supabaseUrl) }
    var anon by remember(state.endpoints) { mutableStateOf(state.endpoints.supabaseAnonKey) }
    var turnstile by remember(state.endpoints) { mutableStateOf(state.endpoints.turnstileSiteKey) }
    var email by remember { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    var vaultPass by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CyberPanel(
            title = "Backend endpoints",
            subtitle = "free tier: Cloudflare Worker + D1 (no card) and Backblaze B2 10 GB (no card)"
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = api, onValueChange = { api = it }, label = { Text("API base URL", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = supabase, onValueChange = { supabase = it }, label = { Text("Supabase project URL", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = anon, onValueChange = { anon = it }, label = { Text("Supabase anon key", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = turnstile, onValueChange = { turnstile = it }, label = { Text("Turnstile site key (optional)", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudActionButton("Save", Icons.Default.CheckCircle, { vm.saveEndpoints(api, supabase, anon, turnstile) })
                CloudSecondaryButton("Test connection", Icons.Default.Refresh, { vm.testConnection() })
            }
            state.health?.let { health ->
                Spacer(Modifier.height(6.dp))
                CloudInfoRow("Provider", health.provider)
                CloudInfoRow("Mode", health.mode)
                CloudInfoRow("Free storage", formatCloudBytes(health.freeTier.storageBytes))
            }
        }

        if (!state.signedIn) {
            CyberPanel(title = "Account", subtitle = "Supabase email + password, 50 000 MAU on the free plan") {
                androidx.compose.material3.OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("Email", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloudActionButton("Sign in", Icons.Default.Cloud, { vm.signIn(email.trim(), password) })
                    CloudSecondaryButton("Create account", Icons.Default.Add, { vm.signUp(email.trim(), password) })
                }
                TextButton(onClick = { vm.resetPassword(email.trim()) }) {
                    Text("Send a password recovery email", fontSize = 11.sp)
                }
            }
        } else {
            CyberPanel(title = "Account", subtitle = "signed in") {
                CloudInfoRow("Email", state.email)
                CloudInfoRow("Quota", formatCloudBytes(state.usage?.quotaBytes ?: 0L))
                CloudInfoRow("Used", formatCloudBytes(state.usage?.usedBytes ?: 0L))
                CloudInfoRow("Trash", "${state.usage?.trashedFiles ?: 0} item(s) · ${formatCloudBytes(state.usage?.trashedBytes ?: 0L)}")
                Spacer(Modifier.height(6.dp))
                CloudSecondaryButton("Sign out", Icons.Default.Lock, { vm.signOut() })
            }
        }

        CyberPanel(title = "Client-side vault", subtitle = "optional AES-256-GCM encryption for configuration backups") {
            Text(
                "The passphrase is never stored. If you forget it, encrypted backups cannot be recovered.",
                color = Color(0xFFF59E0B),
                fontSize = 10.sp
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = vaultPass, onValueChange = { vaultPass = it }, label = { Text("Passphrase", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.vaultConfigured) {
                    CloudActionButton("Create vault", Icons.Default.Lock, {
                        vm.setupVault(vaultPass.toCharArray()); vaultPass = ""
                    })
                } else if (!state.vaultUnlocked) {
                    CloudActionButton("Unlock", Icons.Default.Lock, {
                        vm.unlockVault(vaultPass.toCharArray()); vaultPass = ""
                    })
                } else {
                    CloudSecondaryButton("Lock vault", Icons.Default.Lock, { vm.lockVault() })
                }
            }
            CloudInfoRow(
                "Status",
                when {
                    state.vaultUnlocked -> "Unlocked — new backups are encrypted on device"
                    state.vaultConfigured -> "Locked — new backups are not encrypted on device"
                    else -> "Not configured"
                },
                if (state.vaultUnlocked) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        CyberPanel(title = "Other", subtitle = "storage providers and honest limits") {
            Text(
                "Automatic backup runs at most once a day on unmetered networks. The free allowance is 10 GB on Backblaze B2; uploads beyond it are refused with the exact remaining space instead of upgrading you to a paid plan.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            CloudSecondaryButton("Legacy WebDAV providers", Icons.Default.Cloud, onOpenLegacyProviders)
        }
    }
}

// ---------------------------------------------------------------- auth

@Composable
private fun AuthSection(vm: CloudViewModel, state: CloudUiState) {
    var email by remember { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CyberPanel(
            title = "Sign in to AdShield Cloud",
            subtitle = "your files live in your own free B2 bucket — AdShield keeps no credentials"
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudActionButton("Sign in", Icons.Default.Cloud, { vm.signIn(email.trim(), password) })
                CloudSecondaryButton("Create account", Icons.Default.Add, { vm.signUp(email.trim(), password) })
            }
            TextButton(onClick = { vm.resetPassword(email.trim()) }) {
                Text("Forgot password?", fontSize = 11.sp)
            }
        }
    }
}
