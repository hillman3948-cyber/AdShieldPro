package com.adshield.pro.ui.viewmodel

import android.content.Context
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adshield.pro.data.cloud.AccountResponseDto
import com.adshield.pro.data.cloud.AuditEntryDto
import com.adshield.pro.data.cloud.BackupRowDto
import com.adshield.pro.data.cloud.CloudBackupManager
import com.adshield.pro.data.cloud.CloudEndpoints
import com.adshield.pro.data.cloud.CloudFileKind
import com.adshield.pro.data.cloud.CloudNodeDto
import com.adshield.pro.data.cloud.CloudQuotaWarningDto
import com.adshield.pro.data.cloud.CloudShareDto
import com.adshield.pro.data.cloud.CloudUploadEngine
import com.adshield.pro.data.cloud.CloudUsageDto
import com.adshield.pro.data.cloud.DuplicateGroupDto
import com.adshield.pro.data.cloud.HealthDto
import com.adshield.pro.data.cloud.KindCountsDto
import com.adshield.pro.data.cloud.ReconcileResponseDto
import com.adshield.pro.data.cloud.ShareRowDto
import com.adshield.pro.data.cloud.UploadJob
import com.adshield.pro.data.cloud.UploadState
import com.adshield.pro.data.cloud.UriUploadSource
import com.adshield.pro.data.repository.CloudRepository
import com.adshield.pro.data.repository.QuarantineEntry
import com.adshield.pro.worker.CloudBackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.net.Uri
import javax.inject.Inject

enum class CloudSection {
    OVERVIEW, FILES, PHOTOS, VIDEOS, DOCUMENTS, BACKUPS, SHARED, UPLOADS, SECURITY, SETTINGS, TELEGRAM
}

/** The live working status of the cloud module: derived from real activity, never a fixed string. */
data class CloudWorkingStatus(
    val headline: String,
    val detail: String,
    val active: Boolean,
    val level: CloudStatusLevel
)

enum class CloudStatusLevel { OFFLINE, IDLE, WORKING, ERROR }

data class CloudUiState(
    val endpoints: CloudEndpoints = CloudEndpoints(),
    val signedIn: Boolean = false,
    val email: String = "",
    val health: HealthDto? = null,
    val account: AccountResponseDto? = null,
    val usage: CloudUsageDto? = null,
    val warning: CloudQuotaWarningDto? = null,
    val kinds: KindCountsDto? = null,
    val path: List<CloudNodeDto> = emptyList(),
    val nodes: List<CloudNodeDto> = emptyList(),
    val trashed: List<CloudNodeDto> = emptyList(),
    val showingTrash: Boolean = false,
    val search: String = "",
    val shares: List<ShareRowDto> = emptyList(),
    val backups: List<BackupRowDto> = emptyList(),
    val audit: List<AuditEntryDto> = emptyList(),
    val duplicates: List<DuplicateGroupDto> = emptyList(),
    val reconcile: ReconcileResponseDto? = null,
    val autoBackup: Boolean = false,
    val lastAutoBackupEpochMs: Long = 0L,
    val vaultConfigured: Boolean = false,
    val vaultUnlocked: Boolean = false,
    val lastShare: CloudShareDto? = null,
    val lastBackupVerification: Pair<String, Boolean>? = null,
    val lastRestoreReport: CloudBackupManager.RestoreReport? = null,
    val pendingOpenUrl: String? = null,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class CloudViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CloudRepository,
    private val engine: CloudUploadEngine
) : ViewModel() {

    private val mutableState = MutableStateFlow(CloudUiState(endpoints = repository.endpoints()))
    val state: StateFlow<CloudUiState> = mutableState

    private val activeOperations = MutableStateFlow<List<String>>(emptyList())

    val jobs: StateFlow<List<UploadJob>> = engine.jobsFlow
    val progress = engine.progressFlow
    val quarantine: StateFlow<List<QuarantineEntry>> = repository.quarantine

    val workingStatus: StateFlow<CloudWorkingStatus> = combine(
        activeOperations,
        engine.jobsFlow,
        mutableState
    ) { ops, jobs, state ->
        when {
            state.error != null -> CloudWorkingStatus(
                headline = "ACTION FAILED",
                detail = state.error.orEmpty(),
                active = false,
                level = CloudStatusLevel.ERROR
            )
            ops.isNotEmpty() -> CloudWorkingStatus(
                headline = ops.first().uppercase(),
                detail = if (ops.size > 1) "${ops.size} operations in flight" else "working…",
                active = true,
                level = CloudStatusLevel.WORKING
            )
            jobs.any { it.state == UploadState.UPLOADING || it.state == UploadState.RETRYING } -> {
                val running = jobs.filter { it.state == UploadState.UPLOADING || it.state == UploadState.RETRYING }
                val done = running.sumOf { it.uploadedBytes }
                val total = running.sumOf { it.sizeBytes }
                val percent = if (total > 0) (done * 100 / total) else 0
                CloudWorkingStatus(
                    headline = if (running.size == 1) "UPLOADING ${running.first().name}" else "UPLOADING ${running.size} FILES",
                    detail = "$percent% · ${com.adshield.pro.data.cloud.formatCloudBytes(done)} of ${com.adshield.pro.data.cloud.formatCloudBytes(total)}",
                    active = true,
                    level = CloudStatusLevel.WORKING
                )
            }
            jobs.any { it.state == UploadState.PAUSED } -> CloudWorkingStatus(
                headline = "UPLOADS PAUSED",
                detail = "${jobs.count { it.state == UploadState.PAUSED }} job(s) waiting to resume",
                active = false,
                level = CloudStatusLevel.IDLE
            )
            jobs.any { it.state == UploadState.FAILED } -> CloudWorkingStatus(
                headline = "UPLOAD NEEDS ATTENTION",
                detail = jobs.first { it.state == UploadState.FAILED }.lastError ?: "a transfer failed",
                active = false,
                level = CloudStatusLevel.ERROR
            )
            !repository.isConfigured() -> CloudWorkingStatus(
                headline = "NOT CONNECTED",
                detail = "Point AdShield Pro at your free backend in Settings",
                active = false,
                level = CloudStatusLevel.OFFLINE
            )
            !state.signedIn -> CloudWorkingStatus(
                headline = "SIGNED OUT",
                detail = "Sign in to sync files and backups",
                active = false,
                level = CloudStatusLevel.OFFLINE
            )
            state.message != null -> CloudWorkingStatus(
                headline = "READY",
                detail = state.message.orEmpty(),
                active = false,
                level = CloudStatusLevel.IDLE
            )
            else -> CloudWorkingStatus(
                headline = "SYNCED",
                detail = "${state.nodes.size} item(s) in this folder · ${state.usage?.remainingBytes?.let { com.adshield.pro.data.cloud.formatCloudBytes(it) } ?: "—"} free",
                active = false,
                level = CloudStatusLevel.IDLE
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudWorkingStatus("STARTING", "connecting…", false, CloudStatusLevel.IDLE))

    init {
        viewModelScope.launch {
            repository.restoreJobs()
        }
        refresh()
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun <T> track(label: String, block: suspend () -> T): T? {
        activeOperations.value = activeOperations.value + label
        return try {
            block()
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(
                error = e.message ?: "operation failed",
                message = null
            )
            null
        } finally {
            activeOperations.value = activeOperations.value.filterNot { it == label }
        }
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    fun consumePendingUrl() {
        mutableState.value = mutableState.value.copy(pendingOpenUrl = null)
    }

    // ---------------------------------------------------------------- setup + auth

    fun saveEndpoints(apiBaseUrl: String, supabaseUrl: String, anonKey: String, turnstileSiteKey: String) {
        repository.saveEndpoints(
            CloudEndpoints(
                apiBaseUrl = apiBaseUrl.trim(),
                supabaseUrl = supabaseUrl.trim(),
                supabaseAnonKey = anonKey.trim(),
                turnstileSiteKey = turnstileSiteKey.trim()
            )
        )
        mutableState.value = mutableState.value.copy(endpoints = repository.endpoints())
    }

    fun testConnection() {
        viewModelScope.launch {
            val health = track("testing connection") { repository.health() }
            if (health != null) {
                mutableState.value = mutableState.value.copy(
                    health = health,
                    message = "Connected to ${health.provider} (${health.mode} mode)"
                )
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            val session = track("signing in") { repository.signIn(email, password) }
            if (session != null) {
                mutableState.value = mutableState.value.copy(signedIn = true, email = session.email, message = "Signed in as ${session.email}")
                refresh()
            }
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            val session = track("creating account") { repository.signUp(email, password) }
            if (session != null) {
                mutableState.value = mutableState.value.copy(signedIn = true, email = session.email, message = "Account created for ${session.email}")
                refresh()
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            track("signing out") { repository.signOut() }
            mutableState.value = CloudUiState(endpoints = repository.endpoints())
        }
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            val sent = track("requesting password reset") { repository.resetPassword(email) }
            if (sent == true) {
                mutableState.value = mutableState.value.copy(message = "Recovery email sent to $email")
            }
        }
    }

    // ---------------------------------------------------------------- data

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                endpoints = repository.endpoints(),
                signedIn = repository.session.value?.isSignedIn == true,
                email = repository.session.value?.email.orEmpty(),
                autoBackup = repository.autoBackupEnabled(),
                lastAutoBackupEpochMs = repository.lastAutoBackupEpochMs(),
                vaultConfigured = repository.vaultConfigured(),
                vaultUnlocked = repository.vaultUnlocked()
            )
            if (!repository.isConfigured() || repository.session.value == null) return@launch
            track("refreshing") {
                val account = repository.account()
                val kinds = repository.kinds()
                val nodes = repository.nodes(parentId = mutableState.value.path.lastOrNull()?.id)
                val shares = repository.shares()
                val backups = repository.listBackups()
                val audit = repository.audit()
                mutableState.value = mutableState.value.copy(
                    account = account,
                    usage = account.usage,
                    warning = account.warning,
                    kinds = kinds,
                    nodes = nodes,
                    shares = shares,
                    backups = backups,
                    audit = audit
                )
            }
        }
    }

    fun openFolder(node: CloudNodeDto?) {
        val path = when {
            node == null -> emptyList()
            mutableState.value.path.any { it.id == node.id } -> mutableState.value.path.takeWhile { it.id != node.id } + node
            else -> mutableState.value.path + node
        }
        mutableState.value = mutableState.value.copy(path = path, showingTrash = false)
        viewModelScope.launch {
            val nodes = track("opening folder") { repository.nodes(parentId = node?.id) }
            if (nodes != null) mutableState.value = mutableState.value.copy(nodes = nodes)
        }
    }

    fun navigateUp() {
        val path = mutableState.value.path.dropLast(1)
        mutableState.value = mutableState.value.copy(path = path)
        viewModelScope.launch {
            val nodes = track("loading files") { repository.nodes(parentId = path.lastOrNull()?.id) }
            if (nodes != null) mutableState.value = mutableState.value.copy(nodes = nodes)
        }
    }

    fun setSearch(query: String) {
        mutableState.value = mutableState.value.copy(search = query)
        viewModelScope.launch {
            val nodes = if (query.isBlank()) {
                track("loading files") { repository.nodes(parentId = mutableState.value.path.lastOrNull()?.id) }
            } else {
                track("searching") { repository.nodes(search = query.trim()) }
            }
            if (nodes != null) mutableState.value = mutableState.value.copy(nodes = nodes)
        }
    }

    fun showTrash(show: Boolean) {
        mutableState.value = mutableState.value.copy(showingTrash = show)
        viewModelScope.launch {
            val nodes = track("loading trash") { repository.nodes(trashed = show) }
            if (nodes != null) {
                mutableState.value = mutableState.value.copy(
                    nodes = if (show) nodes else emptyList(),
                    trashed = if (show) nodes else emptyList()
                )
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val node = track("creating folder") {
                repository.createFolder(name, mutableState.value.path.lastOrNull()?.id)
            }
            if (node != null) {
                mutableState.value = mutableState.value.copy(message = "Folder “${node.name}” created")
                refreshFolder()
            }
        }
    }

    private fun refreshFolder() {
        viewModelScope.launch {
            val nodes = track("loading files") { repository.nodes(parentId = mutableState.value.path.lastOrNull()?.id) }
            if (nodes != null) mutableState.value = mutableState.value.copy(nodes = nodes)
        }
    }

    fun rename(node: CloudNodeDto, name: String) {
        viewModelScope.launch {
            if (track("renaming") { repository.rename(node.id, name) } != null) {
                mutableState.value = mutableState.value.copy(message = "Renamed to $name")
                refreshFolder()
            }
        }
    }

    fun move(node: CloudNodeDto, parentId: String?) {
        viewModelScope.launch {
            if (track("moving") { repository.move(node.id, parentId) } != null) {
                mutableState.value = mutableState.value.copy(message = "Moved ${node.name}")
                refreshFolder()
            }
        }
    }

    fun copy(node: CloudNodeDto) {
        viewModelScope.launch {
            if (track("copying") { repository.copy(node.id) } != null) {
                mutableState.value = mutableState.value.copy(message = "Copied ${node.name}")
                refreshFolder()
            }
        }
    }

    fun trash(node: CloudNodeDto) {
        viewModelScope.launch {
            if (track("moving to trash") { repository.trash(node.id) } != null) {
                mutableState.value = mutableState.value.copy(message = "“${node.name}” moved to trash")
                refreshFolder()
                refresh()
            }
        }
    }

    fun restore(node: CloudNodeDto) {
        viewModelScope.launch {
            if (track("restoring") { repository.restore(node.id) } != null) {
                mutableState.value = mutableState.value.copy(message = "“${node.name}” restored")
                showTrash(mutableState.value.showingTrash)
                refresh()
            }
        }
    }

    fun purge(node: CloudNodeDto) {
        viewModelScope.launch {
            if (track("deleting permanently") { repository.purge(node.id) } != null) {
                mutableState.value = mutableState.value.copy(message = "“${node.name}” deleted and its storage freed")
                showTrash(mutableState.value.showingTrash)
                refresh()
            }
        }
    }

    fun download(node: CloudNodeDto) {
        viewModelScope.launch {
            val url = track("preparing download") { repository.downloadUrl(node.id) }
            if (url != null) {
                mutableState.value = mutableState.value.copy(
                    pendingOpenUrl = url.url,
                    message = "Signed link for ${node.name} is valid for ${url.expiresInSeconds / 60} minutes"
                )
            }
        }
    }

    /** A short-lived signed URL for previews and external players. */
    suspend fun urlFor(nodeId: String): String? = runCatching { repository.downloadUrl(nodeId) }.getOrNull()?.url

    /** Lists every file of a media kind, across folders. */
    suspend fun media(kind: CloudFileKind): List<CloudNodeDto> {
        val kindParam = when (kind) {
            CloudFileKind.PHOTO -> "photo"
            CloudFileKind.VIDEO -> "video"
            CloudFileKind.AUDIO -> "audio"
            CloudFileKind.DOCUMENT -> "document"
            else -> "file"
        }
        return runCatching { repository.nodes(kind = kindParam, recursive = true) }.getOrDefault(emptyList())
    }

    fun endpointsBaseUrl(): String = repository.endpoints().apiBaseUrl

    suspend fun verifySession(): Boolean = repository.verifySession()

    fun loadVersions(node: CloudNodeDto) {
        viewModelScope.launch {
            val versions = track("loading versions") { repository.versions(node.id) }
            if (versions != null) {
                mutableState.value = mutableState.value.copy(
                    message = "${node.name}: ${versions.size} stored version(s)"
                )
            }
        }
    }

    fun loadDuplicates() {
        viewModelScope.launch {
            val groups = track("scanning for duplicates") { repository.duplicates() }
            if (groups != null) mutableState.value = mutableState.value.copy(duplicates = groups)
        }
    }

    fun reconcile() {
        viewModelScope.launch {
            val result = track("reconciling storage") { repository.reconcile() }
            if (result != null) {
                mutableState.value = mutableState.value.copy(
                    reconcile = result,
                    message = "Provider stores ${com.adshield.pro.data.cloud.formatCloudBytes(result.b2Bytes)}; database reports ${com.adshield.pro.data.cloud.formatCloudBytes(result.databaseBytes)}"
                )
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------- uploads

    fun upload(uris: List<Uri>, background: Boolean = false) {
        viewModelScope.launch {
            val parentId = mutableState.value.path.lastOrNull()?.id
            var started = 0
            var refused = 0
            uris.forEach { uri ->
                val name = displayName(uri)
                val request = track("scanning $name") {
                    repository.enqueueUpload(uri, name, mimeType(uri), parentId, background)
                }
                if (request == null) refused++ else started++
            }
            mutableState.value = mutableState.value.copy(
                message = when {
                    started > 0 && refused > 0 -> "$started upload(s) started, $refused refused by the malware scanner"
                    started > 0 -> "$started upload(s) started"
                    refused > 0 -> "$refused file(s) were refused by the malware scanner — see Cloud Security"
                    else -> null
                }
            )
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) {
                val value = cursor.getString(index)
                if (!value.isNullOrBlank()) return value
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun mimeType(uri: Uri): String? = context.contentResolver.getType(uri)

    fun pauseUpload(jobId: String) = engine.pause(jobId)

    fun resumeUpload(jobId: String) = engine.resume(jobId)

    fun cancelUpload(jobId: String) {
        engine.cancel(jobId)
        viewModelScope.launch { repository.forgetUpload(jobId) }
    }

    // ---------------------------------------------------------------- sharing

    fun createShare(node: CloudNodeDto, expiresInSeconds: Long) {
        viewModelScope.launch {
            val share = track("creating share link") { repository.createShare(node.id, expiresInSeconds) }
            if (share != null) {
                mutableState.value = mutableState.value.copy(
                    lastShare = share,
                    message = if (share.expiresAt == null) "Share link created (no expiry)" else "Share link created"
                )
                val shares = track("loading shares") { repository.shares() }
                if (shares != null) mutableState.value = mutableState.value.copy(shares = shares)
            }
        }
    }

    fun revokeShare(shareId: String) {
        viewModelScope.launch {
            if (track("revoking share") { repository.revokeShare(shareId) } == true) {
                mutableState.value = mutableState.value.copy(
                    shares = mutableState.value.shares.filterNot { it.id == shareId },
                    message = "Share link revoked — the link stops working immediately"
                )
            }
        }
    }

    // ---------------------------------------------------------------- backups

    fun backupNow(label: String = "Manual backup") {
        viewModelScope.launch {
            val payload = track("reading configuration") { repository.captureBackup() }
            if (payload == null) return@launch
            val row = track("uploading backup") { repository.uploadBackup(payload, label) }
            if (row != null) {
                mutableState.value = mutableState.value.copy(
                    message = "Backup stored and verified (${com.adshield.pro.data.cloud.formatCloudBytes(row.sizeBytes)})"
                )
                val backups = track("loading backups") { repository.listBackups() }
                if (backups != null) mutableState.value = mutableState.value.copy(backups = backups)
                refresh()
            }
        }
    }

    fun verifyBackup(backupId: String) {
        viewModelScope.launch {
            val ok = track("verifying backup") { repository.verifyBackup(backupId) }
            if (ok != null) {
                mutableState.value = mutableState.value.copy(
                    lastBackupVerification = backupId to ok,
                    message = if (ok) "Backup verified: the stored bytes still hash to the recorded value" else "Backup failed verification"
                )
            }
        }
    }

    fun restoreBackup(backupId: String) {
        viewModelScope.launch {
            val report = track("restoring configuration") { repository.restoreBackup(backupId) }
            if (report != null) {
                mutableState.value = mutableState.value.copy(
                    lastRestoreReport = report,
                    message = "Restored ${report.ruleCount} rules and ${report.sourceCount} lists"
                )
            }
        }
    }

    fun deleteBackup(backupId: String) {
        viewModelScope.launch {
            if (track("deleting backup") { repository.deleteBackup(backupId) } == true) {
                mutableState.value = mutableState.value.copy(
                    backups = mutableState.value.backups.filterNot { it.id == backupId },
                    message = "Backup deleted and its storage freed"
                )
                refresh()
            }
        }
    }

    fun setAutoBackup(enabled: Boolean) {
        repository.setAutoBackupEnabled(enabled)
        CloudBackupWorker.scheduleOrCancel(context, enabled)
        mutableState.value = mutableState.value.copy(
            autoBackup = enabled,
            message = if (enabled) "Daily backup scheduled on unmetered networks" else "Automatic backup cancelled"
        )
    }

    // ---------------------------------------------------------------- vault

    fun setupVault(passphrase: CharArray) {
        val ok = repository.setupVault(passphrase)
        mutableState.value = mutableState.value.copy(
            vaultConfigured = repository.vaultConfigured(),
            vaultUnlocked = repository.vaultUnlocked(),
            message = if (ok) "Vault created — backups are now encrypted before they leave the device" else "Vault could not be created"
        )
    }

    fun unlockVault(passphrase: CharArray): Boolean {
        val ok = repository.unlockVault(passphrase)
        mutableState.value = mutableState.value.copy(
            vaultUnlocked = repository.vaultUnlocked(),
            message = if (ok) "Vault unlocked" else "That passphrase did not unlock the vault"
        )
        return ok
    }

    fun lockVault() {
        repository.lockVault()
        mutableState.value = mutableState.value.copy(
            vaultUnlocked = false,
            message = "Vault locked — new backups are stored unencrypted"
        )
    }

    fun fileSize(uri: Uri): Long = UriUploadSource.of(context.contentResolver, uri)?.sizeBytes ?: 0L
}
