package com.adshield.pro.ui.screens.cloud

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adshield.pro.data.telegram.TelegramAccountStatus
import com.adshield.pro.data.telegram.TelegramAuthStep
import com.adshield.pro.data.telegram.TelegramCloudSection
import com.adshield.pro.data.telegram.TelegramPolicy
import com.adshield.pro.data.telegram.TelegramStoredFile
import com.adshield.pro.data.telegram.TelegramTargetKind
import com.adshield.pro.data.telegram.TelegramTransfer
import com.adshield.pro.data.telegram.TelegramTransferKind
import com.adshield.pro.data.telegram.TelegramTransferState
import com.adshield.pro.ui.components.StatusPill
import com.adshield.pro.ui.theme.ElectricBlue
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber
import com.adshield.pro.ui.viewmodel.TelegramCloudViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Telegram provider inside Cloud Storage: Upload, Download, Files and Storage Management, plus a
 * persistent connected-account status card. Every action calls the real TDLib-backed repository;
 * no capacity, progress value or file entry is invented here.
 */
@Composable
fun TelegramCloudScreen(
    viewModel: TelegramCloudViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val allFiles by viewModel.allFiles.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val section by viewModel.section.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val labelFilter by viewModel.labelFilter.collectAsStateWithLifecycle()
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val duplicate by viewModel.duplicate.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var pendingDownload by remember { mutableStateOf<TelegramStoredFile?>(null) }
    var labelTarget by remember { mutableStateOf<TelegramStoredFile?>(null) }
    var deleteTarget by remember { mutableStateOf<TelegramStoredFile?>(null) }
    var showDisconnect by remember { mutableStateOf(false) }
    var uploadLabel by remember { mutableStateOf("") }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val metadata = queryDocumentMetadata(context, uri)
        viewModel.upload(uri, metadata.first, metadata.second, uploadLabel)
    }
    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val target = pendingDownload
        if (uri != null && target != null) viewModel.download(target, uri)
        pendingDownload = null
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "telegram-back") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Cloud Storage")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Telegram Cloud Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Official TDLib · direct account authorization · no backend server", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item(key = "telegram-status") {
                TelegramStatusCard(status = status, viewModel = viewModel, onDisconnect = { showDisconnect = true })
            }
            item(key = "telegram-sections") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TelegramCloudSection.entries, key = { it.name }) { value ->
                        FilterChip(
                            selected = section == value,
                            onClick = { viewModel.setSection(value) },
                            label = { Text(value.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            item(key = "telegram-limits") {
                Text(
                    "Telegram allows up to 2 GB per file for non-Premium accounts (4 GB for Premium); " +
                        "this build enforces the 2 GB ceiling. Telegram is cloud storage, not a permanent " +
                        "backup service: it publishes no retention guarantee, so keep an independent copy " +
                        "of anything irreplaceable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (section) {
                TelegramCloudSection.UPLOAD -> {
                    item(key = "telegram-upload-actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = uploadLabel,
                                onValueChange = { uploadLabel = it },
                                label = { Text("Label / virtual folder (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { runCatching { uploadPicker.launch(arrayOf("*/*")) } },
                                enabled = status.connected,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Choose a file and upload to Telegram")
                            }
                            Text(
                                "Files are hashed (SHA-256) before uploading; an identical file already " +
                                    "in your storage chat is reported instead of uploaded twice.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    transferItems(
                        transfers = transfers.filter { it.kind == TelegramTransferKind.UPLOAD },
                        viewModel = viewModel,
                        emptyText = "No uploads yet."
                    )
                }
                TelegramCloudSection.DOWNLOAD -> {
                    item(key = "telegram-download-hint") {
                        Text(
                            "Select a stored file to save it to your device. Downloads resume from the " +
                                "last TDLib offset and are verified against the recorded SHA-256 hash.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    items(
                        files,
                        key = { "download-" + it.id },
                        contentType = { "telegram-download-row" }
                    ) { file ->
                        TelegramFileRow(
                            file = file,
                            compact = true,
                            onDownload = {
                                pendingDownload = file
                                runCatching { destinationPicker.launch(file.name) }
                                    .onFailure { Toast.makeText(context, "No file picker available", Toast.LENGTH_SHORT).show() }
                            }
                        )
                    }
                    transferItems(
                        transfers = transfers.filter { it.kind == TelegramTransferKind.DOWNLOAD },
                        viewModel = viewModel,
                        emptyText = "No downloads yet."
                    )
                }
                TelegramCloudSection.FILES -> {
                    item(key = "telegram-search") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = viewModel::setQuery,
                                label = { Text("Search by name or label") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = viewModel::refresh) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp)); Text("Refresh from Telegram")
                                }
                                if (labelFilter.isNotBlank()) {
                                    TextButton(onClick = { viewModel.setLabelFilter("") }) { Text("All labels") }
                                }
                            }
                            if (labels.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(labels, key = { it }) { label ->
                                        FilterChip(
                                            selected = labelFilter == label,
                                            onClick = { viewModel.setLabelFilter(if (labelFilter == label) "" else label) },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (files.isEmpty()) {
                        item(key = "telegram-empty-files") {
                            Text(
                                "No stored files match. Upload one, or refresh to read the designated Telegram chat.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(
                        files,
                        key = { "file-" + it.id },
                        contentType = { "telegram-file-row" }
                    ) { file ->
                        TelegramFileRow(
                            file = file,
                            compact = false,
                            onDownload = {
                                pendingDownload = file
                                runCatching { destinationPicker.launch(file.name) }
                            },
                            onLabel = { labelTarget = file },
                            onDelete = { deleteTarget = file }
                        )
                    }
                }
                TelegramCloudSection.MANAGEMENT -> {
                    item(key = "telegram-management") {
                        TelegramManagementCard(
                            status = status,
                            allFiles = allFiles,
                            onClearCache = viewModel::clearLocalCache,
                            onDisconnect = { showDisconnect = true }
                        )
                    }
                }
            }
        }
    }

    duplicate?.let { existing ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text("Duplicate file detected") },
            text = {
                Text("${existing.name} has the same SHA-256 hash and is already stored in Telegram. Upload another copy anyway?")
            },
            confirmButton = { TextButton(onClick = viewModel::uploadAnyway) { Text("Upload anyway") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDuplicate) { Text("Skip") } }
        )
    }
    labelTarget?.let { file ->
        LabelDialog(initial = file.label, onDismiss = { labelTarget = null }) { value ->
            viewModel.setLabel(file, value)
            labelTarget = null
        }
    }
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${file.name}?") },
            text = {
                Text(
                    "This deletes the document message from your designated Telegram chat. " +
                        "AdShield Pro cannot undo it."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(file); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
    if (showDisconnect) {
        AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text("Disconnect Telegram account") },
            text = {
                Text(
                    "This logs out through TDLib (ending the server-side session) and deletes the " +
                        "encrypted session, TDLib database, cached files and staged copies on this " +
                        "device. Files already in Telegram are not deleted. If the device is offline, " +
                        "Telegram cannot confirm the logout; check Active Sessions in Telegram."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.disconnect(true); showDisconnect = false }) {
                    Text("Disconnect & clear local data")
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { viewModel.disconnect(false); showDisconnect = false }) {
                        Text("Clear local data only (offline)")
                    }
                    TextButton(onClick = { showDisconnect = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun TelegramStatusCard(
    status: TelegramAccountStatus,
    viewModel: TelegramCloudViewModel,
    onDisconnect: () -> Unit
) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(TelegramTargetKind.SAVED_MESSAGES) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (status.connected) Icons.Default.CheckCircle else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (status.connected) EmeraldPrimary else WarningAmber
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (status.connected) "Connected: ${status.displayName}" else "Telegram account not connected",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (status.connected) "${status.maskedPhone} · ${status.target?.label ?: ""} · ${status.targetTitle}"
                        else "Connect with your own Telegram API credentials",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                StatusPill(
                    if (status.connected) "CONNECTED" else status.step.name,
                    if (status.connected) EmeraldPrimary else WarningAmber
                )
            }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (status.connected) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Disconnect & clear local session data")
                }
            } else {
                if (status.step == TelegramAuthStep.DISCONNECTED || status.step == TelegramAuthStep.CLOSED) {
                    OutlinedTextField(
                        apiId, { apiId = it }, label = { Text("api_id (from my.telegram.org)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        apiHash, { apiHash = it }, label = { Text("api_hash (from my.telegram.org)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TelegramTargetKind.entries.forEach { value ->
                            FilterChip(
                                selected = target == value,
                                onClick = { target = value },
                                label = { Text(if (value == TelegramTargetKind.SAVED_MESSAGES) "Saved Messages" else "Private channel", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.connect(apiId, apiHash, target) },
                        enabled = apiId.isNotBlank() && apiHash.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Connect Telegram account") }
                }
                if (status.step == TelegramAuthStep.WAIT_PHONE_NUMBER) {
                    OutlinedTextField(
                        phone, { phone = it }, label = { Text("Phone number (international format)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { viewModel.submitPhoneNumber(phone) }, enabled = phone.isNotBlank()) {
                        Text("Send login code")
                    }
                }
                if (status.step == TelegramAuthStep.WAIT_CODE) {
                    OutlinedTextField(
                        code, { code = it }, label = { Text("Telegram login code") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { viewModel.submitCode(code) }, enabled = code.isNotBlank()) { Text("Verify code") }
                }
                if (status.step == TelegramAuthStep.WAIT_PASSWORD) {
                    OutlinedTextField(
                        password, { password = it }, label = { Text("2-step verification password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { viewModel.submitPassword(password); password = "" }, enabled = password.isNotBlank()) {
                        Text("Submit to TDLib")
                    }
                }
                Text(
                    "Authorization uses TDLib's official flow. Your api_id/api_hash are encrypted with " +
                        "Android Keystore and never leave the device except to Telegram. Phone numbers, " +
                        "login codes and 2-step passwords are passed only to TDLib and are never stored " +
                        "or logged by AdShield Pro. The app reads only the storage chat you select.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TelegramFileRow(
    file: TelegramStoredFile,
    compact: Boolean,
    onDownload: () -> Unit,
    onLabel: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, contentDescription = null, tint = ElectricBlue)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${TelegramPolicy.formatTelegramBytes(file.sizeBytes)} · ${formatTelegramDate(file.uploadedAtEpochSec)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Text(
                "Status: ${file.downloadStatus.label}${if (file.label.isNotBlank()) " · Label: ${file.label}" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (file.sha256.isNotBlank()) {
                Text("SHA-256 ${file.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onDownload) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Download")
                }
                if (!compact) {
                    TextButton(onClick = onLabel) {
                        Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("Label")
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramManagementCard(
    status: TelegramAccountStatus,
    allFiles: List<TelegramStoredFile>,
    onClearCache: () -> Unit,
    onDisconnect: () -> Unit
) {
    val totalBytes = allFiles.sumOf { it.sizeBytes }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Storage Management", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Account: ${status.displayName.ifBlank { "not connected" }} (${status.maskedPhone})", style = MaterialTheme.typography.bodySmall)
            Text("Storage chat: ${status.targetTitle.ifBlank { "none" }}", style = MaterialTheme.typography.bodySmall)
            Text("Indexed files: ${allFiles.size} · ${TelegramPolicy.formatTelegramBytes(totalBytes)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Telegram does not publish a per-account quota or a retention guarantee, so no " +
                    "capacity or backup promise is shown here. The figures above are the files this " +
                    "app uploaded, as read back from Telegram.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onClearCache, enabled = status.connected, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("Clear local TDLib cache & staged copies")
            }
            OutlinedButton(onClick = onDisconnect, enabled = status.connected, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("Disconnect & clear local session data")
            }
        }
    }
}

@Composable
private fun LabelDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Organize file") },
        text = {
            Column {
                Text("A label is a local virtual folder and is also written to the Telegram document caption.")
                OutlinedTextField(
                    value, { value = it }, label = { Text("Label") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun LazyListScope.transferItems(
    transfers: List<TelegramTransfer>,
    viewModel: TelegramCloudViewModel,
    emptyText: String
) {
    if (transfers.isEmpty()) {
        item(key = "telegram-empty-transfers-$emptyText") {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    items(transfers, key = { "transfer-" + it.id }, contentType = { "telegram-transfer-row" }) { transfer ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (transfer.kind == TelegramTransferKind.UPLOAD) Icons.Default.CloudUpload else Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = ElectricBlue
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(transfer.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${transfer.state.label} · ${TelegramPolicy.formatTelegramBytes(transfer.bytesDone)} / " +
                                TelegramPolicy.formatTelegramBytes(transfer.bytesTotal) +
                                (transfer.lastError?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (transfer.state == TelegramTransferState.RUNNING ||
                    transfer.state == TelegramTransferState.RETRYING
                ) {
                    LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (transfer.state == TelegramTransferState.INTERRUPTED ||
                        transfer.state == TelegramTransferState.FAILED ||
                        transfer.state == TelegramTransferState.RETRYING ||
                        transfer.state == TelegramTransferState.CANCELLED
                    ) {
                        TextButton(onClick = { viewModel.retry(transfer.id) }) { Text("Retry") }
                    }
                    if (!transfer.state.isTerminal &&
                        transfer.state != TelegramTransferState.INTERRUPTED &&
                        transfer.state != TelegramTransferState.CANCELLED
                    ) {
                        TextButton(onClick = { viewModel.cancel(transfer.id) }) { Text("Cancel") }
                    }
                    Text("Attempt ${transfer.attempts}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatTelegramDate(epochSeconds: Int): String =
    if (epochSeconds <= 0) "—" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        .format(Date(epochSeconds.toLong() * 1000L))

/** Only display name and MIME are needed here; size is measured exactly while staging the copy. */
private fun queryDocumentMetadata(context: Context, uri: Uri): Pair<String, String> {
    var name = "upload-${System.currentTimeMillis()}"
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
        }
    }
    return name to (context.contentResolver.getType(uri) ?: "application/octet-stream")
}
