package com.adshield.pro.data.telegram

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

interface TelegramClock {
    fun now(): Long
}

object SystemTelegramClock : TelegramClock {
    override fun now(): Long = System.currentTimeMillis()
}

data class TelegramDisconnectResult(val remoteLogoutConfirmed: Boolean, val note: String?)

/**
 * The Telegram Cloud Storage feature: official TDLib authentication, one designated chat, real
 * uploads/downloads, progress, retry, SHA-256 deduplication and a complete local wipe.
 *
 * Nothing here simulates a network call. Every remote operation is a TdApi function sent through
 * [TelegramTdlibPort]; the JVM suite substitutes a scripted port and asserts the real request
 * objects (chat ids, input files, priorities, offsets, logout, delete). Only the designated
 * Saved Messages / private storage channel is ever addressed with getChatHistory or
 * searchChatMessages — getChats/loadChats and other conversations are never used.
 */
@Singleton
class TelegramStorageRepository @Inject constructor(
    private val port: TelegramTdlibPort,
    private val store: TelegramSecureStore,
    private val files: TelegramFileIo,
    private val parametersFactory: TelegramTdlibParametersFactory,
    private val clock: TelegramClock
) {
    private val _status = MutableStateFlow(TelegramAccountStatus())
    val status: StateFlow<TelegramAccountStatus> = _status

    private val _storedFiles = MutableStateFlow<List<TelegramStoredFile>>(emptyList())
    val storedFiles: StateFlow<List<TelegramStoredFile>> = _storedFiles

    private val _transfers = MutableStateFlow<List<TelegramTransfer>>(emptyList())
    val transfers: StateFlow<List<TelegramTransfer>> = _transfers

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    @Volatile
    private var session: TelegramSession? = null

    // ---------------------------------------------------------------- lifecycle

    /** Restore encrypted state, flag transfers killed by a restart, resume the TDLib client. */
    suspend fun initialize() {
        _storedFiles.value = runCatching { store.loadFiles() }.getOrDefault(emptyList())
        val recovered = runCatching { store.loadTransfers() }.getOrDefault(emptyList()).map { transfer ->
            if (!transfer.state.isTerminal && transfer.state != TelegramTransferState.INTERRUPTED) {
                transfer.copy(
                    state = TelegramTransferState.INTERRUPTED,
                    lastError = "Interrupted by an app restart or connectivity change",
                    updatedAtEpochMs = clock.now()
                )
            } else transfer
        }
        _transfers.value = recovered
        runCatching { store.saveTransfers(recovered) }

        val saved = try {
            store.loadSession()
        } catch (failure: TelegramKeyUnavailable) {
            // A Keystore key that cannot decrypt its session is a dead session, not a reason to
            // invent a new one. Wipe and ask the user to connect again.
            runCatching { port.close() }
            store.clearAll()
            files.wipe()
            _storedFiles.value = emptyList()
            _transfers.value = emptyList()
            _status.value = TelegramAccountStatus(
                error = "The encrypted Telegram session could not be unlocked; local session data was cleared."
            )
            session = null
            return
        }
        if (saved == null) {
            _status.value = TelegramAccountStatus()
            return
        }
        session = saved
        resumeClient(saved)
    }

    private suspend fun resumeClient(saved: TelegramSession) {
        try {
            startClient(saved)
            val step = withTimeoutOrNull(30_000L) {
                port.authState.first {
                    it != TelegramAuthStep.INITIALIZING && it != TelegramAuthStep.WAIT_TDLIB_PARAMETERS
                }
            } ?: TelegramAuthStep.UNSUPPORTED
            if (step == TelegramAuthStep.READY && !saved.isComplete) {
                finalizeAuthorization(saved)
            } else {
                applyStatus(step, saved)
            }
        } catch (failure: TelegramTdlibException) {
            if (failure.code == 401) {
                runCatching { port.close() }
                store.clearAll()
                files.wipe()
                session = null
                _status.value = TelegramAccountStatus(
                    error = "TDLib rejected the local database key; the session was cleared. Connect again."
                )
            } else {
                applyStatus(port.authState.value, saved, failure.message)
            }
        } catch (failure: Exception) {
            applyStatus(port.authState.value, saved, failure.message)
        }
    }

    private suspend fun startClient(current: TelegramSession) {
        val key = Base64.getDecoder().decode(current.databaseKeyBase64)
        val paths = files.pathsFor(current.accountDir)
        port.start(current, parametersFactory.create(current, paths, key))
    }

    private fun applyStatus(
        step: TelegramAuthStep,
        current: TelegramSession? = session,
        failure: String? = null
    ) {
        _status.value = TelegramAccountStatus(
            step = step,
            connection = port.connectionState.value,
            displayName = current?.displayName.orEmpty(),
            maskedPhone = current?.maskedPhone.orEmpty(),
            target = current?.targetKind,
            targetTitle = current?.targetTitle.orEmpty(),
            targetChatId = current?.targetChatId ?: 0L,
            error = failure ?: guidanceFor(step)
        )
    }

    private fun guidanceFor(step: TelegramAuthStep): String? = when (step) {
        TelegramAuthStep.WAIT_PHONE_NUMBER -> "Enter the phone number of your Telegram account (international format)."
        TelegramAuthStep.WAIT_CODE -> "Enter the login code Telegram sent to your official app or by SMS."
        TelegramAuthStep.WAIT_PASSWORD -> "Enter your 2-step verification password. It goes only to TDLib and is never stored."
        TelegramAuthStep.WAIT_OTHER_DEVICE_CONFIRMATION ->
            "Telegram requires confirmation on another signed-in device. Confirm there, or restart sign-in."
        TelegramAuthStep.WAIT_REGISTRATION ->
            "This phone number is not registered with Telegram. Register it in an official Telegram app first."
        TelegramAuthStep.WAIT_EMAIL_ADDRESS, TelegramAuthStep.WAIT_EMAIL_CODE ->
            "Telegram requires email verification. Complete it in an official Telegram app first."
        TelegramAuthStep.WAIT_PREMIUM_PURCHASE ->
            "Telegram is asking for a Premium purchase before sign-in. Complete it in an official Telegram app."
        TelegramAuthStep.UNSUPPORTED -> "TDLib reported an authorization step this build does not handle."
        else -> null
    }

    // ---------------------------------------------------------------- connection

    /**
     * Begin a real, direct user-account authorization. api_id/api_hash are the user's own app
     * credentials from https://my.telegram.org; they are encrypted at rest and never logged.
     */
    suspend fun connect(apiId: Int, apiHash: String, target: TelegramTargetKind) {
        TelegramPolicy.validateApiCredentials(apiId, apiHash)
        check(session?.isComplete != true) {
            "Disconnect the current Telegram account before connecting another."
        }
        runCatching { port.close() }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val created = TelegramSession(
            apiId = apiId,
            apiHash = apiHash.trim(),
            databaseKeyBase64 = Base64.getEncoder().encodeToString(key),
            accountDir = UUID.randomUUID().toString(),
            targetKind = target,
            createdAtEpochMs = clock.now()
        )
        session = created
        store.saveSession(created)
        startClient(created)
        val step = withTimeoutOrNull(30_000L) {
            port.authState.first {
                it != TelegramAuthStep.INITIALIZING && it != TelegramAuthStep.WAIT_TDLIB_PARAMETERS
            }
        } ?: TelegramAuthStep.UNSUPPORTED
        applyStatus(step, created)
    }

    suspend fun submitPhoneNumber(phoneNumberInput: String) {
        require(port.authState.value == TelegramAuthStep.WAIT_PHONE_NUMBER) {
            "TDLib is not waiting for a phone number"
        }
        port.send(TdApi.SetAuthenticationPhoneNumber().apply {
            this.phoneNumber = phoneNumberInput.trim()
            settings = TdApi.PhoneNumberAuthenticationSettings(
                false, false, false, false, false, null, arrayOf<String>()
            )
        })
        advanceAuthState(TelegramAuthStep.WAIT_PHONE_NUMBER)
    }

    suspend fun submitCode(codeInput: String) {
        require(port.authState.value == TelegramAuthStep.WAIT_CODE) {
            "TDLib is not waiting for a login code"
        }
        port.send(TdApi.CheckAuthenticationCode().apply { this.code = codeInput.trim() })
        advanceAuthState(TelegramAuthStep.WAIT_CODE)
    }

    /** 2-step verification password is passed straight to TDLib and is never persisted. */
    suspend fun submitPassword(passwordInput: String) {
        require(port.authState.value == TelegramAuthStep.WAIT_PASSWORD) {
            "TDLib is not waiting for a password"
        }
        port.send(TdApi.CheckAuthenticationPassword().apply { this.password = passwordInput })
        advanceAuthState(TelegramAuthStep.WAIT_PASSWORD)
    }

    private suspend fun advanceAuthState(from: TelegramAuthStep) {
        val next = withTimeoutOrNull(60_000L) { port.authState.first { it != from } } ?: from
        if (next == TelegramAuthStep.READY) {
            session?.let { finalizeAuthorization(it) }
        } else {
            applyStatus(next)
        }
    }

    private suspend fun finalizeAuthorization(current: TelegramSession) {
        val me = port.send(TdApi.GetMe())
        val chat = if (current.targetKind == TelegramTargetKind.SAVED_MESSAGES) {
            // "Saved Messages" is the private chat whose id equals the current user id.
            port.send(TdApi.CreatePrivateChat().apply { userId = me.id; force = false })
        } else {
            port.send(TdApi.CreateNewSupergroupChat().apply {
                title = TelegramLimits.CHANNEL_TITLE
                isForum = false
                isChannel = true
                description = TelegramLimits.CHANNEL_DESCRIPTION
                location = null
                messageAutoDeleteTime = 0
                forImport = false
            })
        }
        require(chat.id != 0L) { "TDLib did not return a usable storage chat" }
        val complete = current.copy(
            userId = me.id,
            displayName = listOf(me.firstName.orEmpty(), me.lastName.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Telegram account" },
            maskedPhone = TelegramPolicy.maskPhone(me.phoneNumber.orEmpty()),
            targetChatId = chat.id,
            targetTitle = chat.title?.takeIf { it.isNotBlank() } ?: current.targetKind.label
        )
        session = complete
        store.saveSession(complete)
        applyStatus(TelegramAuthStep.READY, complete)
        _message.value = "Connected to Telegram. Storage chat: ${complete.targetTitle}."
        runCatching { refreshFiles(null) }
    }

    /**
     * Disconnect. When logOut is requested and the network is available, TDLib terminates the
     * server-side session; then every local secret, database and cached byte is removed.
     */
    suspend fun disconnect(logOutRemotely: Boolean = true): TelegramDisconnectResult {
        var confirmed = false
        var note: String? = null
        if (logOutRemotely) {
            confirmed = runCatching { port.logout() }.getOrElse {
                note = "Telegram did not confirm the remote logout (${it.message})."
                false
            }
        }
        runCatching { port.close() }
        store.clearAll()
        files.wipe()
        session = null
        _storedFiles.value = emptyList()
        _transfers.value = emptyList()
        _status.value = TelegramAccountStatus()
        if (!logOutRemotely) {
            note = "Local session data was cleared, but the Telegram session is still active on the server. " +
                "Terminate it in Telegram → Settings → Devices → Active Sessions."
        } else if (!confirmed) {
            note = (note ?: "Telegram did not confirm the remote logout.") +
                " Local data was cleared, but the server session may remain active; check Telegram → Settings → Devices."
        }
        _message.value = "Telegram account disconnected; local session data cleared."
        _error.value = note
        return TelegramDisconnectResult(confirmed, note)
    }

    // ---------------------------------------------------------------- upload

    /**
     * Copy a SAF document into app-private storage, hash it, reject duplicates, then send it as
     * a real Telegram document to the designated chat. Progress comes from TDLib updateFile.
     */
    suspend fun upload(
        sourceUri: String,
        name: String,
        mimeType: String,
        label: String = "",
        forceDuplicate: Boolean = false
    ): TelegramStoredFile? {
        val current = requireConnected()
        val staged = files.stage(sourceUri, name, mimeType)
        TelegramPolicy.validateUploadSize(staged.sizeBytes)
        if (!forceDuplicate) {
            val duplicate = _storedFiles.value.firstOrNull { it.sha256 == staged.sha256 }
            if (duplicate != null) {
                files.delete(staged.path)
                throw TelegramDuplicateFile(duplicate)
            }
        }
        val transfer = TelegramTransfer(
            id = UUID.randomUUID().toString(),
            kind = TelegramTransferKind.UPLOAD,
            state = TelegramTransferState.QUEUED,
            name = staged.name,
            bytesTotal = staged.sizeBytes,
            sourceUri = sourceUri,
            stagedPath = staged.path,
            sha256 = staged.sha256,
            label = label,
            mimeType = staged.mimeType,
            createdAtEpochMs = clock.now(),
            updatedAtEpochMs = clock.now()
        )
        putTransfer(transfer)
        return executeUpload(transfer, current, existingFileId = null)
    }

    private suspend fun executeUpload(
        initial: TelegramTransfer,
        current: TelegramSession,
        existingFileId: Int?
    ): TelegramStoredFile? {
        val stagedPath = initial.stagedPath
        // On retry with an already uploaded TDLib file id we reuse InputFileId, so the staged
        // copy is no longer required. It is required for a fresh upload.
        if (existingFileId == null && (stagedPath == null || !files.exists(stagedPath))) {
            failTransfer(initial.id, "The staged local copy is missing; select the file again.")
            return null
        }
        val running = initial.copy(
            state = TelegramTransferState.RUNNING,
            attempts = initial.attempts + 1,
            lastError = null,
            updatedAtEpochMs = clock.now()
        )
        putTransfer(running)
        _busy.value = true
        return try {
            val inputFile: TdApi.InputFile = if (existingFileId != null) {
                // Retry after the bytes were already uploaded: reuse the TDLib file id so no
                // second copy of the data is transferred.
                TdApi.InputFileId().apply { id = existingFileId }
            } else {
                TdApi.InputFileLocal().apply { path = requireNotNull(stagedPath) }
            }
            val caption = TelegramPolicy.encodeCaption(
                initial.sha256.orEmpty(), initial.bytesTotal, initial.label
            )
            val message = port.send(TdApi.SendMessage().apply {
                chatId = current.targetChatId
                topicId = null
                replyTo = null
                options = null
                replyMarkup = null
                inputMessageContent = TdApi.InputMessageDocument().apply {
                    document = TdApi.InputDocument().apply {
                        document = inputFile
                        thumbnail = null
                        disableContentTypeDetection = true
                    }
                    this.caption = TdApi.FormattedText().apply {
                        text = caption
                        entities = arrayOf<TdApi.TextEntity>()
                    }
                }
            })
            val content = message.content as? TdApi.MessageDocument
                ?: error("TDLib did not return the uploaded document")
            val uploadedFile = content.document.document
            require(uploadedFile.id != 0) { "TDLib did not return a file id for the upload" }
            port.seedFile(uploadedFile)
            putTransfer(
                running.copy(
                    fileId = uploadedFile.id,
                    messageId = message.id,
                    updatedAtEpochMs = clock.now()
                )
            )
            val finalState = awaitFileState(
                targetFileId = uploadedFile.id,
                transferId = initial.id,
                isUpload = true,
                targetChatId = current.targetChatId,
                sentMessageId = message.id
            )
            val finalMessageId = findMessageIdForFile(uploadedFile.id) ?: message.id
            val stored = TelegramStoredFile(
                id = "${current.targetChatId}:$finalMessageId",
                chatId = current.targetChatId,
                messageId = finalMessageId,
                fileId = uploadedFile.id,
                remoteFileId = finalState.remote?.id.orEmpty(),
                name = initial.name,
                mimeType = initial.mimeType,
                sizeBytes = initial.bytesTotal,
                uploadedAtEpochSec = message.date,
                sha256 = initial.sha256.orEmpty(),
                label = initial.label,
                downloadStatus = TelegramDownloadStatus.NOT_DOWNLOADED
            )
            upsertFile(stored)
            putTransfer(
                running.copy(
                    state = TelegramTransferState.COMPLETED,
                    bytesDone = initial.bytesTotal,
                    fileId = uploadedFile.id,
                    messageId = finalMessageId,
                    lastError = null,
                    updatedAtEpochMs = clock.now()
                )
            )
            _message.value = "Uploaded ${stored.name} to ${current.targetTitle}."
            stored
        } catch (interrupted: TelegramInterruptedTransfer) {
            markInterrupted(initial.id, interrupted.message ?: "Upload interrupted")
            null
        } catch (failure: TelegramTdlibException) {
            markInterrupted(initial.id, "TDLib error ${failure.code}: ${failure.message}")
            null
        } catch (failure: Exception) {
            failTransfer(initial.id, failure.message ?: "Upload failed")
            null
        } finally {
            _busy.value = false
        }
    }

    // ---------------------------------------------------------------- download

    suspend fun download(file: TelegramStoredFile, destinationUri: String): Boolean {
        requireConnected()
        val transfer = TelegramTransfer(
            id = UUID.randomUUID().toString(),
            kind = TelegramTransferKind.DOWNLOAD,
            state = TelegramTransferState.QUEUED,
            name = file.name,
            bytesTotal = file.sizeBytes,
            fileId = file.fileId,
            messageId = file.messageId,
            sha256 = file.sha256.takeIf { it.isNotBlank() },
            mimeType = file.mimeType,
            destinationUri = destinationUri,
            createdAtEpochMs = clock.now(),
            updatedAtEpochMs = clock.now()
        )
        putTransfer(transfer)
        return executeDownload(transfer, file)
    }

    private suspend fun executeDownload(
        initial: TelegramTransfer,
        file: TelegramStoredFile
    ): Boolean {
        val destination = initial.destinationUri
            ?: return failTransfer(initial.id, "No download destination was selected").let { false }
        val targetFileId = file.fileId
        val running = initial.copy(
            state = TelegramTransferState.RUNNING,
            attempts = initial.attempts + 1,
            lastError = null,
            updatedAtEpochMs = clock.now()
        )
        putTransfer(running)
        _busy.value = true
        return try {
            val current = port.send(TdApi.GetFile().apply { this.fileId = targetFileId })
            port.seedFile(current)
            // Always re-issue downloadFile: TDLib resumes at its recorded downloadOffset and
            // returns immediately if the local copy is already complete.
            val requested = port.send(TdApi.DownloadFile().apply {
                this.fileId = targetFileId
                priority = TelegramLimits.PRIORITY
                offset = current.local?.downloadOffset ?: 0L
                limit = 0L
                synchronous = false
            })
            port.seedFile(requested)
            val finalState = awaitFileState(
                targetFileId = targetFileId,
                transferId = initial.id,
                isUpload = false,
                targetChatId = file.chatId,
                sentMessageId = null
            )
            val localPath = finalState.local?.path?.takeIf { it.isNotBlank() }
                ?: error("TDLib did not report a local file path")
            val written = files.writeToDestination(localPath, destination)
            val status = when {
                file.sha256.isBlank() -> TelegramDownloadStatus.DOWNLOADED
                written.sha256.equals(file.sha256, ignoreCase = true) -> TelegramDownloadStatus.VERIFIED
                else -> TelegramDownloadStatus.FAILED
            }
            upsertFile(
                file.copy(
                    downloadStatus = status,
                    localDestinationUri = destination,
                    downloadedAtEpochMs = clock.now()
                )
            )
            putTransfer(
                running.copy(
                    state = if (status == TelegramDownloadStatus.FAILED) TelegramTransferState.FAILED
                    else TelegramTransferState.COMPLETED,
                    bytesDone = written.sizeBytes,
                    lastError = if (status == TelegramDownloadStatus.FAILED) "SHA-256 mismatch" else null,
                    updatedAtEpochMs = clock.now()
                )
            )
            if (status == TelegramDownloadStatus.FAILED) {
                _error.value = "Downloaded bytes do not match the recorded SHA-256 hash. The local copy was kept for inspection; do not trust it."
            } else {
                _message.value = "Saved ${file.name} to your device (${TelegramPolicy.formatTelegramBytes(written.sizeBytes)})."
            }
            status != TelegramDownloadStatus.FAILED
        } catch (interrupted: TelegramInterruptedTransfer) {
            markInterrupted(initial.id, interrupted.message ?: "Download interrupted")
            false
        } catch (failure: TelegramTdlibException) {
            markInterrupted(initial.id, "TDLib error ${failure.code}: ${failure.message}")
            false
        } catch (failure: Exception) {
            failTransfer(initial.id, failure.message ?: "Download failed")
            false
        } finally {
            _busy.value = false
        }
    }

    /**
     * Poll TDLib's file state for progress, completion, failure and stalls. The state map is fed
     * by real updateFile events in AndroidTdlibPort; polling also makes progress robust if an
     * update arrives between two UI subscriptions.
     */
    private suspend fun awaitFileState(
        targetFileId: Int,
        transferId: String,
        isUpload: Boolean,
        targetChatId: Long,
        sentMessageId: Long?
    ): TdApi.File {
        var seenActive = false
        var stalledPolls = 0
        val result = withTimeoutOrNull(TelegramLimits.TRANSFER_TIMEOUT_MS) {
            while (coroutineContext.isActive) {
                val state = port.fileStates.value[targetFileId]
                    ?: port.send(TdApi.GetFile().apply { this.fileId = targetFileId })
                        .also { port.seedFile(it) }
                val done = if (isUpload) {
                    state.remote?.uploadedSize ?: 0L
                } else {
                    state.local?.downloadedSize ?: 0L
                }
                updateProgress(transferId, done)
                val complete = if (isUpload) {
                    state.remote?.isUploadingCompleted == true
                } else {
                    state.local?.isDownloadingCompleted == true
                }
                val active = if (isUpload) {
                    state.remote?.isUploadingActive == true
                } else {
                    state.local?.isDownloadingActive == true
                }
                if (active) seenActive = true
                if (isUpload && sentMessageId != null && targetChatId != 0L) {
                    val failed = runCatching {
                        port.send(TdApi.GetMessage().apply {
                            this.chatId = targetChatId
                            this.messageId = sentMessageId
                        }).sendingState as? TdApi.MessageSendingStateFailed
                    }.getOrNull()
                    if (failed != null) {
                        throw TelegramTdlibException(
                            failed.error?.code ?: 0,
                            failed.error?.message ?: "Telegram rejected the message"
                        )
                    }
                }
                if (complete) return@withTimeoutOrNull state
                if (seenActive && !active && done > 0L) stalledPolls++ else stalledPolls = 0
                if (stalledPolls >= STALLED_POLL_LIMIT) {
                    throw TelegramInterruptedTransfer("TDLib paused the transfer before completion")
                }
                delay(POLL_INTERVAL_MS)
            }
            null
        }
        return result ?: throw TelegramInterruptedTransfer("Timed out waiting for TDLib")
    }

    private suspend fun findMessageIdForFile(fileId: Int): Long? {
        val storageChatId = session?.targetChatId ?: return null
        return runCatching {
            val history = port.send(TdApi.GetChatHistory().apply {
                this.chatId = storageChatId
                fromMessageId = 0L
                offset = 0
                limit = TelegramLimits.HISTORY_PAGE_SIZE
                onlyLocal = false
            })
            history.messages.orEmpty().firstOrNull { message ->
                val content = message.content as? TdApi.MessageDocument ?: return@firstOrNull false
                content.document.document.id == fileId && TelegramPolicy.isOurCaption(content.caption?.text)
            }?.id
        }.getOrNull()
    }

    // ---------------------------------------------------------------- retry / cancel

    suspend fun retry(transferId: String) {
        val transfer = _transfers.value.firstOrNull { it.id == transferId } ?: return
        require(transfer.attempts < TelegramLimits.MAX_RETRY_ATTEMPTS) {
            "Retry limit reached for ${transfer.name}"
        }
        when (transfer.kind) {
            TelegramTransferKind.UPLOAD -> {
                val current = requireConnected()
                val existing = transfer.fileId?.let { id ->
                    runCatching { port.send(TdApi.GetFile().apply { fileId = id }) }.getOrNull()
                }
                val reusableId = existing?.takeIf { it.remote?.isUploadingCompleted == true }?.id
                executeUpload(
                    transfer.copy(state = TelegramTransferState.RETRYING),
                    current,
                    reusableId
                )
            }
            TelegramTransferKind.DOWNLOAD -> {
                val stored = _storedFiles.value.firstOrNull { it.fileId == transfer.fileId }
                    ?: error("The stored file record is gone; refresh the file list first")
                executeDownload(transfer.copy(state = TelegramTransferState.RETRYING), stored)
            }
        }
    }

    suspend fun cancel(transferId: String) {
        val transfer = _transfers.value.firstOrNull { it.id == transferId } ?: return
        when (transfer.kind) {
            TelegramTransferKind.UPLOAD -> {
                // Deleting the pending message cancels TDLib's in-flight upload for that message.
                val targetChatId = session?.targetChatId ?: 0L
                val pendingMessageId = transfer.messageId
                if (targetChatId != 0L && pendingMessageId != null) {
                    runCatching {
                        port.send(TdApi.DeleteMessages().apply {
                            this.chatId = targetChatId
                            messageIds = longArrayOf(pendingMessageId)
                            revoke = true
                        })
                    }
                }
            }
            TelegramTransferKind.DOWNLOAD -> transfer.fileId?.let { id ->
                runCatching {
                    port.send(TdApi.CancelDownloadFile().apply {
                        fileId = id
                        onlyIfPending = false
                    })
                }
            }
        }
        putTransfer(
            transfer.copy(
                state = TelegramTransferState.CANCELLED,
                lastError = "Cancelled by the user",
                updatedAtEpochMs = clock.now()
            )
        )
    }

    // ---------------------------------------------------------------- browse / organise

    /** Reads only the designated storage chat, and only our own tagged document messages. */
    suspend fun refreshFiles(searchText: String?) {
        val current = requireConnected()
        val remote = listRemoteMessages(current, searchText)
        val local = _storedFiles.value
        val nowSec = clock.now() / 1000L
        val merged = remote.map { item ->
            val known = local.firstOrNull { it.id == item.id }
                ?: local.firstOrNull { it.fileId == item.fileId }
            item.copy(
                label = known?.label?.takeIf { it.isNotBlank() } ?: item.label,
                sha256 = known?.sha256?.takeIf { it.isNotBlank() } ?: item.sha256,
                downloadStatus = known?.downloadStatus ?: TelegramDownloadStatus.NOT_DOWNLOADED,
                localDestinationUri = known?.localDestinationUri,
                downloadedAtEpochMs = known?.downloadedAtEpochMs
            )
        }
        // Keep very recent local records whose temporary message id has not been reconciled yet.
        val pending = local.filter { known ->
            merged.none { it.id == known.id } && nowSec - known.uploadedAtEpochSec < 600
        }
        val result = (merged + pending)
            .distinctBy { it.id }
            .sortedByDescending { it.uploadedAtEpochSec }
        _storedFiles.value = result
        store.saveFiles(result)
    }

    private suspend fun listRemoteMessages(
        current: TelegramSession,
        searchText: String?
    ): List<TelegramStoredFile> {
        val messages = ArrayList<TdApi.Message>()
        if (searchText.isNullOrBlank()) {
            var cursorMessageId = 0L
            var pages = 0
            while (pages < MAX_HISTORY_PAGES) {
                val page = port.send(TdApi.GetChatHistory().apply {
                    this.chatId = current.targetChatId
                    this.fromMessageId = cursorMessageId
                    offset = 0
                    limit = TelegramLimits.HISTORY_PAGE_SIZE
                    onlyLocal = false
                })
                val batch = page.messages.orEmpty().toList()
                messages.addAll(batch)
                pages++
                if (batch.size < TelegramLimits.HISTORY_PAGE_SIZE) break
                val next = batch.lastOrNull()?.id ?: 0L
                if (next == 0L || next == cursorMessageId) break
                cursorMessageId = next
            }
        } else {
            val found = port.send(TdApi.SearchChatMessages().apply {
                this.chatId = current.targetChatId
                topicId = null
                this.query = searchText.orEmpty()
                senderId = null
                this.fromMessageId = 0L
                offset = 0
                limit = TelegramLimits.HISTORY_PAGE_SIZE
                filter = TdApi.SearchMessagesFilterDocument()
            })
            messages.addAll(found.messages.orEmpty().toList())
        }
        return messages.mapNotNull { toStoredFile(it, current.targetChatId) }
    }

    private fun toStoredFile(message: TdApi.Message, chatId: Long): TelegramStoredFile? {
        val content = message.content as? TdApi.MessageDocument ?: return null
        val caption = TelegramPolicy.decodeCaption(content.caption?.text) ?: return null
        val document = content.document
        val file = document.document
        return TelegramStoredFile(
            id = "$chatId:${message.id}",
            chatId = chatId,
            messageId = message.id,
            fileId = file.id,
            remoteFileId = file.remote?.id.orEmpty(),
            name = document.fileName?.takeIf { it.isNotBlank() } ?: "file-${message.id}",
            mimeType = document.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            sizeBytes = if (file.size > 0L) file.size else caption.size,
            uploadedAtEpochSec = message.date,
            sha256 = caption.sha256,
            label = caption.label,
            downloadStatus = TelegramDownloadStatus.NOT_DOWNLOADED
        )
    }

    /** Local label (virtual folder) plus a real editMessageCaption so it survives reinstall. */
    suspend fun setLabel(file: TelegramStoredFile, label: String) {
        val current = requireConnected()
        val safeLabel = label.trim().take(80)
        val caption = TelegramPolicy.encodeCaption(file.sha256, file.sizeBytes, safeLabel)
        val editedRemotely = runCatching {
            port.send(TdApi.EditMessageCaption().apply {
                chatId = current.targetChatId
                messageId = file.messageId
                replyMarkup = null
                this.caption = TdApi.FormattedText().apply {
                    text = caption
                    entities = arrayOf<TdApi.TextEntity>()
                }
                showCaptionAboveMedia = false
            })
            true
        }.getOrDefault(false)
        upsertFile(file.copy(label = safeLabel))
        _message.value = if (editedRemotely) {
            "Label saved locally and in the Telegram caption."
        } else {
            "Label saved on this device; Telegram did not accept the caption edit."
        }
    }

    suspend fun delete(file: TelegramStoredFile) {
        val current = requireConnected()
        port.send(TdApi.DeleteMessages().apply {
            chatId = current.targetChatId
            messageIds = longArrayOf(file.messageId)
            revoke = true
        })
        runCatching { port.send(TdApi.DeleteFile().apply { fileId = file.fileId }) }
        removeFile(file.id)
        _message.value = "Deleted ${file.name} from Telegram. Telegram does not guarantee a permanent backup."
    }

    /** Clears TDLib's on-device file cache and staged upload copies. Remote files are untouched. */
    suspend fun clearLocalCache() {
        _storedFiles.value.forEach { stored ->
            runCatching { port.send(TdApi.DeleteFile().apply { fileId = stored.fileId }) }
        }
        _transfers.value
            .filter { it.kind == TelegramTransferKind.UPLOAD && it.state == TelegramTransferState.COMPLETED }
            .forEach { transfer -> transfer.stagedPath?.let { files.delete(it) } }
        _message.value = "Cleared TDLib's local file cache and staged upload copies. Files remain in Telegram."
    }

    fun clearMessage() { _message.value = null }
    fun clearError() { _error.value = null }

    /** Used by the ViewModel for failures that happen before a repository operation starts. */
    fun reportError(message: String) { _error.value = message }

    // ---------------------------------------------------------------- helpers

    private fun requireConnected(): TelegramSession =
        session?.takeIf { it.isComplete && _status.value.connected }
            ?: error("Connect a Telegram account and finish authorization first")

    private fun upsertFile(file: TelegramStoredFile) {
        val updated = _storedFiles.value.filterNot { it.id == file.id } + file
        _storedFiles.value = updated.sortedByDescending { it.uploadedAtEpochSec }
        runCatching { store.saveFiles(updated) }
    }

    private fun removeFile(id: String) {
        val updated = _storedFiles.value.filterNot { it.id == id }
        _storedFiles.value = updated
        runCatching { store.saveFiles(updated) }
    }

    private fun putTransfer(transfer: TelegramTransfer) {
        val updated = _transfers.value.filterNot { it.id == transfer.id } + transfer
        _transfers.value = updated.sortedByDescending { it.updatedAtEpochMs }
        runCatching { store.saveTransfers(updated) }
    }

    private fun updateProgress(transferId: String, doneBytes: Long) {
        val existing = _transfers.value.firstOrNull { it.id == transferId } ?: return
        if (existing.bytesDone == doneBytes) return
        putTransfer(
            existing.copy(bytesDone = doneBytes, updatedAtEpochMs = clock.now())
        )
    }

    private fun markInterrupted(transferId: String, reason: String) {
        val existing = _transfers.value.firstOrNull { it.id == transferId } ?: return
        putTransfer(
            existing.copy(
                state = TelegramTransferState.INTERRUPTED,
                lastError = reason,
                updatedAtEpochMs = clock.now()
            )
        )
        _error.value = "$reason. Use Retry to resume."
    }

    private fun failTransfer(transferId: String, reason: String) {
        val existing = _transfers.value.firstOrNull { it.id == transferId } ?: return
        putTransfer(
            existing.copy(
                state = TelegramTransferState.FAILED,
                lastError = reason,
                updatedAtEpochMs = clock.now()
            )
        )
        _error.value = reason
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val STALLED_POLL_LIMIT = 4
        const val MAX_HISTORY_PAGES = 5
    }
}
