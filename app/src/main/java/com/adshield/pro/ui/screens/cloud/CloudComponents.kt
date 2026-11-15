package com.adshield.pro.ui.screens.cloud

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.data.cloud.CloudNodeDto
import com.adshield.pro.data.cloud.CloudQuotaWarningDto
import com.adshield.pro.data.cloud.CloudUsageDto
import com.adshield.pro.data.cloud.UploadJob
import com.adshield.pro.data.cloud.UploadProgress
import com.adshield.pro.data.cloud.UploadState
import com.adshield.pro.data.cloud.fileKind
import com.adshield.pro.data.cloud.formatCloudBytes
import com.adshield.pro.ui.components.CyberPanel
import com.adshield.pro.ui.components.StatusPill
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.ElectricBlue
import com.adshield.pro.ui.theme.NavyBorder
import com.adshield.pro.ui.theme.SkyBlueGlow
import com.adshield.pro.ui.viewmodel.CloudSection
import com.adshield.pro.ui.viewmodel.CloudStatusLevel
import com.adshield.pro.ui.viewmodel.CloudWorkingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val CloudPanelBackground = Color(0xFF061525)
private val CloudCardBackground = Color(0xFF08182B)

private val thumbnailClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

// ---------------------------------------------------------------- chrome

@Composable
fun CloudSectionTabs(
    current: CloudSection,
    onSelect: (CloudSection) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(CloudSection.entries.size) { index ->
            val section = CloudSection.entries[index]
            val selected = section == current
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelect(section) },
                color = if (selected) ElectricBlue.copy(alpha = 0.22f) else CloudCardBackground,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) SkyBlueGlow else NavyBorder.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = sectionLabel(section),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = if (selected) SkyBlueGlow else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

fun sectionLabel(section: CloudSection): String = when (section) {
    CloudSection.OVERVIEW -> "Overview"
    CloudSection.FILES -> "My Files"
    CloudSection.PHOTOS -> "Photos"
    CloudSection.VIDEOS -> "Videos"
    CloudSection.DOCUMENTS -> "Documents"
    CloudSection.BACKUPS -> "Backups"
    CloudSection.SHARED -> "Shared"
    CloudSection.UPLOADS -> "Upload Manager"
    CloudSection.SECURITY -> "Cloud Security"
    CloudSection.SETTINGS -> "Settings"
    CloudSection.TELEGRAM -> "Telegram"
}

@Composable
fun CloudStatusHeader(
    status: CloudWorkingStatus,
    usage: CloudUsageDto?,
    modifier: Modifier = Modifier
) {
    val color = when (status.level) {
        CloudStatusLevel.WORKING -> CyberCyan
        CloudStatusLevel.ERROR -> Color(0xFFEF4444)
        CloudStatusLevel.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
        CloudStatusLevel.IDLE -> Color(0xFF22C55E)
    }
    val pulse by rememberInfiniteTransition(label = "cloud-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "cloud-pulse-alpha"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CloudPanelBackground)
            .border(1.dp, NavyBorder.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (status.active) pulse else 0.85f))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                status.headline,
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            if (usage != null) {
                Text(
                    "${formatCloudBytes(usage.usedBytes)} / ${formatCloudBytes(usage.quotaBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            status.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (usage != null) {
            Spacer(Modifier.height(10.dp))
            UsageMeter(usage)
        }
    }
}

@Composable
private fun UsageMeter(usage: CloudUsageDto) {
    val fraction = if (usage.quotaBytes <= 0) 0f else (usage.usedBytes.toFloat() / usage.quotaBytes.toFloat()).coerceIn(0f, 1f)
    val color = when {
        fraction >= 0.9f -> Color(0xFFEF4444)
        fraction >= 0.75f -> Color(0xFFF59E0B)
        else -> CyberCyan
    }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF10233B))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${usage.percentUsed}% of the free allowance used",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Text(
                "${formatCloudBytes(usage.remainingBytes)} free",
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

fun quotaColor(warning: CloudQuotaWarningDto?): Color = when (warning?.level) {
    "blocked", "critical" -> Color(0xFFEF4444)
    "warn" -> Color(0xFFF59E0B)
    else -> Color(0xFF22C55E)
}

// ---------------------------------------------------------------- rows

@Composable
fun CloudNodeRow(
    node: CloudNodeDto,
    onOpen: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when {
        node.isFolder -> Icons.Default.Folder
        else -> when (node.fileKind()) {
            com.adshield.pro.data.cloud.CloudFileKind.PHOTO -> Icons.Default.Image
            com.adshield.pro.data.cloud.CloudFileKind.VIDEO -> Icons.Default.PlayArrow
            com.adshield.pro.data.cloud.CloudFileKind.AUDIO -> Icons.Default.PlayArrow
            com.adshield.pro.data.cloud.CloudFileKind.DOCUMENT -> Icons.Default.Description
            com.adshield.pro.data.cloud.CloudFileKind.ARCHIVE -> Icons.Default.InsertDriveFile
            else -> Icons.Default.InsertDriveFile
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CloudCardBackground)
            .border(1.dp, NavyBorder.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = SkyBlueGlow, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (node.isFolder) "Folder" else "${formatCloudBytes(node.size)} · ${node.contentType ?: "file"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onDownload != null) {
            IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Download, contentDescription = "Download", tint = CyberCyan, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Actions", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun UploadJobRow(
    job: UploadJob,
    progress: UploadProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = progress?.fraction ?: job.progress
    val state = progress?.state ?: job.state
    val color = when (state) {
        UploadState.COMPLETED -> Color(0xFF22C55E)
        UploadState.FAILED -> Color(0xFFEF4444)
        UploadState.PAUSED -> Color(0xFFF59E0B)
        UploadState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> CyberCyan
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CloudCardBackground)
            .border(1.dp, NavyBorder.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                job.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${(fraction * 100).toInt()}%",
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = color,
            trackColor = Color(0xFF10233B)
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatCloudBytes(progress?.uploadedBytes ?: job.uploadedBytes)} of ${formatCloudBytes(job.sizeBytes)} · ${state.name.lowercase()}" +
                    (progress?.lastError?.let { " · $it" } ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            when (state) {
                UploadState.UPLOADING, UploadState.RETRYING, UploadState.QUEUED, UploadState.VERIFYING -> {
                    IconButton(onClick = onPause, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = SkyBlueGlow, modifier = Modifier.size(18.dp))
                    }
                }
                UploadState.PAUSED, UploadState.FAILED -> {
                    IconButton(onClick = onResume, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color(0xFF22C55E), modifier = Modifier.size(18.dp))
                    }
                }
                else -> Unit
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Loads a real thumbnail over the signed URL the API issued. Nothing is faked: no URL, no image. */
@Composable
fun rememberRemoteThumbnail(url: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) {
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).get().build()
                thumbnailClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val stream = response.body?.byteStream() ?: return@runCatching null
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
fun CloudPhotoTile(
    node: CloudNodeDto,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = rememberRemoteThumbnail(thumbnailUrl)
    Box(
        modifier = modifier
            .size(104.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CloudCardBackground)
            .border(1.dp, NavyBorder.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = node.name, contentScale = ContentScale.Crop, modifier = Modifier.size(104.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Image, contentDescription = null, tint = NavyBorder, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    node.name,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

@Composable
fun CloudEmptyState(message: String, icon: ImageVector = Icons.Default.CloudOff, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = NavyBorder, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CloudActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ElectricBlue.copy(alpha = 0.85f),
            contentColor = Color.White
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun CloudSecondaryButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(38.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun CloudStatTile(label: String, value: String, caption: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CloudCardBackground)
            .border(1.dp, NavyBorder.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, letterSpacing = 0.6.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CloudInfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun CloudTextInputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmLabel: String = "Save",
    secret: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label, fontSize = 11.sp) },
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CloudConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = { Text(message, fontSize = 12.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CloudNodeMenu(
    node: CloudNodeDto,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onVersions: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename", fontSize = 12.sp) },
            onClick = { onDismiss(); onRename() },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
        DropdownMenuItem(
            text = { Text("Copy", fontSize = 12.sp) },
            onClick = { onDismiss(); onCopy() },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
        DropdownMenuItem(
            text = { Text("Move to…", fontSize = 12.sp) },
            onClick = { onDismiss(); onMove() },
            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
        if (!node.isFolder) {
            DropdownMenuItem(
                text = { Text("Share link", fontSize = 12.sp) },
                onClick = { onDismiss(); onShare() },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            DropdownMenuItem(
                text = { Text("Version history", fontSize = 12.sp) },
                onClick = { onDismiss(); onVersions() },
                leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
        if (node.deletedAt == null) {
            DropdownMenuItem(
                text = { Text("Move to trash", fontSize = 12.sp) },
                onClick = { onDismiss(); onTrash() },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        } else {
            DropdownMenuItem(
                text = { Text("Restore", fontSize = 12.sp) },
                onClick = { onDismiss(); onRestore() },
                leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            DropdownMenuItem(
                text = { Text("Delete permanently", fontSize = 12.sp) },
                onClick = { onDismiss(); onPurge() },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

@Composable
fun CloudSmallSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(16.dp), strokeWidth = 2.dp, color = CyberCyan)
}

@Composable
fun CloudBadge(text: String, icon: ImageVector, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
