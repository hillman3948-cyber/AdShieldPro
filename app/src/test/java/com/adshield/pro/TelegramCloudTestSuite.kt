package com.adshield.pro

import com.adshield.pro.data.telegram.CAPTION_MARKER
import com.adshield.pro.data.telegram.TelegramAuthStep
import com.adshield.pro.data.telegram.TelegramClock
import com.adshield.pro.data.telegram.TelegramConnectionState
import com.adshield.pro.data.telegram.TelegramFileIo
import com.adshield.pro.data.telegram.TelegramHashResult
import com.adshield.pro.data.telegram.TelegramKeyUnavailable
import com.adshield.pro.data.telegram.TelegramKeyValueStore
import com.adshield.pro.data.telegram.TelegramLimits
import com.adshield.pro.data.telegram.TelegramPolicy
import com.adshield.pro.data.telegram.TelegramSecureStore
import com.adshield.pro.data.telegram.TelegramSession
import com.adshield.pro.data.telegram.TelegramStagedFile
import com.adshield.pro.data.telegram.TelegramStorageRepository
import com.adshield.pro.data.telegram.TelegramTargetKind
import com.adshield.pro.data.telegram.TelegramTdlibException
import com.adshield.pro.data.telegram.TelegramTdlibParameters
import com.adshield.pro.data.telegram.TelegramTdlibParametersFactory
import com.adshield.pro.data.telegram.TelegramTdlibPaths
import com.adshield.pro.data.telegram.TelegramTdlibPort
import com.adshield.pro.data.telegram.TelegramTransferKind
import com.adshield.pro.data.telegram.TelegramTransferState
import com.adshield.pro.data.telegram.mapAuthorizationState
import com.adshield.pro.data.telegram.toTdApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * Telegram Cloud Storage verification without a device, an emulator, libtdjni or a Telegram
 * account. [ScriptedTdlibPort] answers the *real* TdApi request objects with *real* TdApi
 * response objects; assertions therefore exercise the same repository path that talks to
 * Telegram in the APK. Values are scripted, never simulated network behaviour in the app.
 */
class TelegramCloudTestSuite {

    // ---------------------------------------------------------------- test doubles

    private class MemoryKv : TelegramKeyValueStore {
        val values = linkedMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
        override fun keys(): Set<String> = values.keys.toSet()
    }

    /** Real AES-GCM with a test key; the Android implementation uses Android Keystore. */
    private class TestCipher : com.adshield.pro.data.telegram.TelegramAesGcm {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var deleted = false
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plaintext)
        }
        override fun decrypt(envelope: ByteArray): ByteArray {
            if (deleted) throw TelegramKeyUnavailable("key deleted")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, envelope.copyOfRange(0, 12))
            )
            return cipher.doFinal(envelope.copyOfRange(12, envelope.size))
        }
        override fun deleteKey() { deleted = true }
    }

    private class MemoryFiles : TelegramFileIo {
        val staged = linkedMapOf<String, ByteArray>()
        val destinations = linkedMapOf<String, ByteArray>()
        val localTdlibFiles = linkedMapOf<String, ByteArray>()
        var wiped = false
        var stagingCounter = 0
        override fun pathsFor(accountDir: String) = TelegramTdlibPaths("/data/$accountDir/db", "/data/$accountDir/files")
        override fun exists(path: String): Boolean = staged.containsKey(path) || localTdlibFiles.containsKey(path)
        override suspend fun stage(sourceUri: String, name: String, mimeType: String): TelegramStagedFile {
            val bytes = requireNotNull(staged[sourceUri]) { "unknown source" }
            val path = "/staged/${++stagingCounter}/$name"
            staged[path] = bytes
            return TelegramStagedFile(path, name, mimeType, bytes.size.toLong(), sha256(bytes))
        }
        override suspend fun writeToDestination(localPath: String, destinationUri: String): TelegramHashResult {
            val bytes = requireNotNull(localTdlibFiles[localPath]) { "No TDLib local file" }
            destinations[destinationUri] = bytes
            return TelegramHashResult(bytes.size.toLong(), sha256(bytes))
        }
        override suspend fun delete(path: String) { staged.remove(path) }
        override suspend fun wipe() {
            staged.clear(); localTdlibFiles.clear(); wiped = true
        }
    }

    private class FixedClock : TelegramClock {
        var value = 1_700_000_000_000L
        override fun now() = value
    }

    private class TestParamsFactory : TelegramTdlibParametersFactory {
        override fun create(session: TelegramSession, paths: TelegramTdlibPaths, key: ByteArray) =
            TelegramTdlibParameters(
                databaseDirectory = paths.databaseDirectory,
                filesDirectory = paths.filesDirectory,
                databaseEncryptionKey = key,
                apiId = session.apiId,
                apiHash = session.apiHash,
                systemLanguageCode = "en-US",
                deviceModel = "Test device",
                systemVersion = "14",
                applicationVersion = "2.10.2"
            )
    }

    /** Scripted responses for every TdApi function the repository sends. */
    private class ScriptedTdlibPort : TelegramTdlibPort {
        override val authState = MutableStateFlow(TelegramAuthStep.INITIALIZING)
        override val connectionState = MutableStateFlow(TelegramConnectionState.OFFLINE)
        override val fileStates = MutableStateFlow<Map<Int, TdApi.File>>(emptyMap())
        private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 32)
        override val updates: SharedFlow<TdApi.Object> = _updates

        val sent = mutableListOf<TdApi.Function<*>>()
        var loggedOut = false
        var closed = false
        var startCount = 0
        var lastParameters: TelegramTdlibParameters? = null
        var lastSession: TelegramSession? = null
        var me = TdApi.User().apply { id = 991L; firstName = "Ada"; lastName = "Lovelace"; phoneNumber = "+639171234567" }
        var chat = TdApi.Chat().apply { id = 991L; title = "Saved Messages" }
        var nextMessageId = 1000L
        var nextFileId = 10
        var uploadFileBytes: ByteArray = byteArrayOf()
        var uploadComplete = true
        var failUpload = false
        var downloadComplete = true
        var downloadBytes: ByteArray = byteArrayOf()
        var downloadOffset = 0L
        var historyMessages: MutableList<TdApi.Message> = mutableListOf()
        var searchResults: MutableList<TdApi.Message> = mutableListOf()
        var startThrows: TelegramTdlibException? = null
        var failDownloadWith: TelegramTdlibException? = null
        var autoCompleteDownload = true

        override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
            sent.add(query)
            @Suppress("UNCHECKED_CAST")
            return when (query) {
                is TdApi.SetTdlibParameters -> TdApi.Ok() as T
                is TdApi.SetAuthenticationPhoneNumber -> {
                    authState.value = TelegramAuthStep.WAIT_CODE
                    TdApi.Ok() as T
                }
                is TdApi.CheckAuthenticationCode -> {
                    authState.value = TelegramAuthStep.WAIT_PASSWORD
                    TdApi.Ok() as T
                }
                is TdApi.CheckAuthenticationPassword -> {
                    authState.value = TelegramAuthStep.READY
                    TdApi.Ok() as T
                }
                is TdApi.GetMe -> me as T
                is TdApi.CreatePrivateChat, is TdApi.CreateNewSupergroupChat -> chat as T
                is TdApi.GetChatHistory -> {
                    val messages = historyMessages.toTypedArray()
                    TdApi.Messages(messages.size, messages) as T
                }
                is TdApi.SearchChatMessages -> {
                    // The scripted port returns everything TDLib knows about this chat; the
                    // repository must filter untagged documents out itself.
                    val messages = (if (searchResults.isEmpty()) historyMessages else searchResults).toTypedArray()
                    TdApi.FoundChatMessages(messages.size, messages, 0L) as T
                }
                is TdApi.SendMessage -> buildSentMessage(query) as T
                is TdApi.GetMessage -> {
                    (historyMessages.firstOrNull { it.id == query.messageId }
                        ?: TdApi.Message().apply { id = query.messageId; content = TdApi.MessageDocument() }) as T
                }
                is TdApi.GetFile -> {
                    // TDLib's GetFile reports the current local download offset. The scripted
                    // port must reflect a resumed/interrupted local file just as TDLib does.
                    val state = file(query.fileId)
                    state.local?.downloadOffset = downloadOffset
                    state.local?.downloadedSize = downloadOffset
                    state as T
                }
                is TdApi.DownloadFile -> {
                    failDownloadWith?.let { throw it }
                    downloadOffset = query.offset
                    if (autoCompleteDownload) seedFile(completedDownloadFile(query.fileId))
                    file(query.fileId) as T
                }
                is TdApi.DeleteMessages -> {
                    val ids = query.messageIds.toSet()
                    historyMessages.removeAll { it.id in ids }
                    TdApi.Ok() as T
                }
                is TdApi.DeleteFile -> TdApi.Ok() as T
                is TdApi.EditMessageCaption -> {
                    historyMessages.firstOrNull { it.id == query.messageId }?.let { message ->
                        (message.content as? TdApi.MessageDocument)?.caption = query.caption
                    }
                    TdApi.Ok() as T
                }
                is TdApi.CancelDownloadFile -> TdApi.Ok() as T
                else -> TdApi.Ok() as T
            }
        }

        private fun buildSentMessage(query: TdApi.SendMessage): TdApi.Message {
            val content = query.inputMessageContent as TdApi.InputMessageDocument
            val id = ++nextMessageId
            val fileId = ++nextFileId
            val state = file(fileId)
            val message = TdApi.Message().apply {
                this.id = id
                chatId = query.chatId
                date = 1_700_000_000
                this.content = TdApi.MessageDocument().apply {
                    document = TdApi.Document().apply {
                        fileName = (content.document.document as? TdApi.InputFileLocal)?.path?.substringAfterLast('/')
                            ?: "upload-$id"
                        mimeType = "application/octet-stream"
                        this.document = state
                    }
                    caption = content.caption
                }
                if (failUpload) {
                    sendingState = TdApi.MessageSendingStateFailed().apply {
                        error = TdApi.Error(429, "FLOOD_WAIT_10")
                        canRetry = true
                    }
                }
            }
            historyMessages.add(0, message)
            return message
        }

        private fun file(id: Int): TdApi.File {
            val uploaded = if (uploadComplete) uploadFileBytes.size.toLong() else 0L
            val downloaded = if (downloadComplete) downloadBytes.size.toLong() else downloadOffset
            val existing = fileStates.value[id]
            return existing ?: TdApi.File().apply {
                this.id = id
                size = maxOf(uploaded, downloaded, 1L)
                expectedSize = size
                remote = TdApi.RemoteFile().apply {
                    this.id = "remote-$id"
                    uniqueId = "unique-$id"
                    isUploadingActive = !uploadComplete && !failUpload
                    isUploadingCompleted = uploadComplete
                    uploadedSize = uploaded
                }
                local = TdApi.LocalFile().apply {
                    path = "/tdlib/$id"
                    canBeDownloaded = true
                    canBeDeleted = true
                    isDownloadingActive = !downloadComplete
                    isDownloadingCompleted = downloadComplete
                    downloadOffset = this@ScriptedTdlibPort.downloadOffset
                    downloadedPrefixSize = downloaded
                    downloadedSize = downloaded
                }
            }
        }

        private fun completedDownloadFile(id: Int): TdApi.File = TdApi.File().apply {
            this.id = id
            size = downloadBytes.size.toLong().coerceAtLeast(1L)
            expectedSize = size
            remote = TdApi.RemoteFile().apply {
                this.id = "remote-$id"
                uniqueId = "unique-$id"
                isUploadingCompleted = true
                uploadedSize = size
            }
            local = TdApi.LocalFile().apply {
                path = "/tdlib/$id"
                canBeDownloaded = true
                canBeDeleted = true
                isDownloadingActive = false
                isDownloadingCompleted = true
                downloadOffset = this@ScriptedTdlibPort.downloadOffset
                downloadedPrefixSize = downloadBytes.size.toLong()
                downloadedSize = downloadBytes.size.toLong()
            }
        }

        override fun seedFile(file: TdApi.File) {
            fileStates.value = fileStates.value + (file.id to file)
        }

        override suspend fun start(session: TelegramSession, params: TelegramTdlibParameters) {
            startCount++
            lastSession = session
            lastParameters = params
            startThrows?.let { throw it }
            if (authState.value == TelegramAuthStep.INITIALIZING ||
                authState.value == TelegramAuthStep.DISCONNECTED
            ) {
                authState.value = TelegramAuthStep.WAIT_PHONE_NUMBER
            }
            connectionState.value = TelegramConnectionState.READY
        }

        override suspend fun logout(): Boolean {
            loggedOut = true
            authState.value = TelegramAuthStep.CLOSED
            return true
        }

        override suspend fun close() { closed = true }

        fun emitUpdateFile(file: TdApi.File) {
            fileStates.value = fileStates.value + (file.id to file)
            _updates.tryEmit(TdApi.UpdateFile(file))
        }
    }

    private class Fixture {
        val kv = MemoryKv()
        val cipher = TestCipher()
        val store = TelegramSecureStore(kv, cipher)
        val files = MemoryFiles()
        val port = ScriptedTdlibPort()
        val clock = FixedClock()
        val repo = TelegramStorageRepository(port, store, files, TestParamsFactory(), clock)
    }

    private fun Fixture.source(name: String, bytes: ByteArray): String {
        val uri = "content://documents/$name"
        files.staged[uri] = bytes
        return uri
    }

    /** Complete the official auth flow (phone → code → password → ready). */
    private suspend fun Fixture.connectFully(target: TelegramTargetKind = TelegramTargetKind.SAVED_MESSAGES) {
        repo.connect(123456, "0123456789abcdef0123456789abcdef", target)
        assertEquals(TelegramAuthStep.WAIT_PHONE_NUMBER, port.authState.value)
        repo.submitPhoneNumber("+639171234567")
        assertEquals(TelegramAuthStep.WAIT_CODE, port.authState.value)
        repo.submitCode("12345")
        assertEquals(TelegramAuthStep.WAIT_PASSWORD, port.authState.value)
        repo.submitPassword("correct horse battery staple")
        assertTrue(repo.status.value.connected)
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun testOfficialAuthorizationFlowUsesRealTdlibRequestsAndNeverPersistsSecrets() = runBlocking {
        val f = Fixture()
        f.repo.connect(123456, "0123456789abcdef0123456789abcdef", TelegramTargetKind.SAVED_MESSAGES)
        val params = f.port.lastParameters!!
        assertEquals(123456, params.apiId)
        assertEquals("0123456789abcdef0123456789abcdef", params.apiHash)
        assertEquals(false, params.useTestDc)
        assertEquals(true, params.useMessageDatabase)
        assertEquals(false, params.useSecretChats)
        assertEquals(32, params.databaseEncryptionKey.size)
        val tdApi = params.toTdApi()
        assertEquals(params.apiId, tdApi.apiId)
        assertEquals(params.filesDirectory, tdApi.filesDirectory)
        assertEquals(false, tdApi.useSecretChats)

        f.repo.submitPhoneNumber("+639171234567")
        val phoneQuery = f.port.sent.filterIsInstance<TdApi.SetAuthenticationPhoneNumber>().single()
        assertEquals("+639171234567", phoneQuery.phoneNumber)
        assertFalse(phoneQuery.settings.allowFlashCall)
        assertFalse(phoneQuery.settings.allowSmsRetrieverApi)
        f.repo.submitCode("99887")
        f.repo.submitPassword("correct horse battery staple")
        val passwordQuery = f.port.sent.filterIsInstance<TdApi.CheckAuthenticationPassword>().single()
        assertEquals("correct horse battery staple", passwordQuery.password)

        assertEquals("Ada Lovelace", f.repo.status.value.displayName)
        assertEquals("+••••••4567", f.repo.status.value.maskedPhone)
        assertEquals(991L, f.repo.status.value.targetChatId)
        assertTrue(f.port.sent.any { it is TdApi.CreatePrivateChat })
        assertFalse(f.port.sent.any { it is TdApi.CreateNewSupergroupChat })

        // Nothing that could be a password, phone or raw credential is stored in plaintext.
        val blobs = f.kv.values.values.joinToString(" ")
        assertFalse(blobs.contains("correct horse battery staple"))
        assertFalse(blobs.contains("+639171234567"))
        assertFalse(blobs.contains("0123456789abcdef"))
        assertFalse(blobs.contains("apiHash"))
        // The encrypted session round-trips to the exact stored credentials.
        val loaded = f.store.loadSession()!!
        assertEquals(123456, loaded.apiId)
        assertEquals("0123456789abcdef0123456789abcdef", loaded.apiHash)
        assertTrue(loaded.isComplete)
    }

    @Test
    fun testUnsupportedAuthorizationStatesAreReportedNeverSimulated() = runBlocking {
        val f = Fixture()
        f.repo.connect(123456, "0123456789abcdef0123456789abcdef", TelegramTargetKind.SAVED_MESSAGES)
        // A state the app cannot complete is shown as-is. The registration/premium purchase flows
        // require the official Telegram app; this build does not fake them.
        assertEquals(
            TelegramAuthStep.WAIT_OTHER_DEVICE_CONFIRMATION,
            mapAuthorizationState(TdApi.AuthorizationStateWaitOtherDeviceConfirmation("https://t.me/login"))
        )
        f.port.authState.value = TelegramAuthStep.WAIT_REGISTRATION
        f.repo.initialize()
        assertEquals(TelegramAuthStep.WAIT_REGISTRATION, f.repo.status.value.step)
        assertTrue(f.repo.status.value.error!!.contains("not registered"))
        assertTrue(f.port.sent.filterIsInstance<TdApi.RegisterUser>().isEmpty())
        assertEquals(TelegramAuthStep.WAIT_REGISTRATION, mapAuthorizationState(TdApi.AuthorizationStateWaitRegistration()))
        assertEquals(TelegramAuthStep.WAIT_PREMIUM_PURCHASE, mapAuthorizationState(TdApi.AuthorizationStateWaitPremiumPurchase()))
    }

    @Test
    fun testUploadSendsRealDocumentRecordsMetadataAndProgress() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = "definitely real bytes".toByteArray()
        f.port.uploadFileBytes = bytes
        val source = f.source("report.txt", bytes)
        val stored = f.repo.upload(source, "report.txt", "text/plain", label = "Invoices")!!
        val sent = f.port.sent.filterIsInstance<TdApi.SendMessage>().last()
        assertEquals(991L, sent.chatId)
        val doc = sent.inputMessageContent as TdApi.InputMessageDocument
        assertEquals(991L, f.repo.status.value.targetChatId)
        assertTrue((doc.document.document as TdApi.InputFileLocal).path.startsWith("/staged/"))
        assertTrue(doc.caption.text.startsWith(CAPTION_MARKER))
        val caption = TelegramPolicy.decodeCaption(doc.caption.text)!!
        assertEquals(sha256(bytes), caption.sha256)
        assertEquals(bytes.size.toLong(), caption.size)
        assertEquals("Invoices", caption.label)

        assertEquals("report.txt", stored.name)
        assertEquals(bytes.size.toLong(), stored.sizeBytes)
        assertEquals(1_700_000_000, stored.uploadedAtEpochSec)
        assertEquals(TelegramTargetKind.SAVED_MESSAGES, f.repo.status.value.target)
        val transfer = f.repo.transfers.value.single()
        assertEquals(TelegramTransferState.COMPLETED, transfer.state)
        assertEquals(bytes.size.toLong(), transfer.bytesDone)
        assertEquals(1f, transfer.fraction)
        assertEquals(TelegramTransferKind.UPLOAD, transfer.kind)
        assertEquals(sha256(bytes), f.store.loadFiles().single().sha256)
    }

    @Test
    fun testDuplicateSha256IsRejectedBeforeAnyUploadRequest() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = "same content".toByteArray()
        f.port.uploadFileBytes = bytes
        val a = f.source("a.bin", bytes)
        val b = f.source("b.bin", bytes)
        f.repo.upload(a, "a.bin", "application/octet-stream")
        val before = f.port.sent.count { it is TdApi.SendMessage }
        try {
            f.repo.upload(b, "b.bin", "application/octet-stream")
            fail("duplicate upload should be refused")
        } catch (duplicate: com.adshield.pro.data.telegram.TelegramDuplicateFile) {
            assertEquals("a.bin", duplicate.existing.name)
        }
        assertEquals(before, f.port.sent.count { it is TdApi.SendMessage })
        assertEquals(1, f.repo.storedFiles.value.size)
        // The explicit escape hatch really sends a second document.
        val forced = f.repo.upload(b, "b.bin", "application/octet-stream", forceDuplicate = true)!!
        assertNotNull(forced)
        assertEquals(2, f.repo.storedFiles.value.size)
    }

    @Test
    fun testPrivateChannelConnectionCreatesChannelAndOnlyDesignatedChatIsQueried() = runBlocking {
        val f = Fixture()
        f.port.chat = TdApi.Chat().apply { id = -1001234567890L; title = TelegramLimits.CHANNEL_TITLE }
        f.connectFully(TelegramTargetKind.PRIVATE_CHANNEL)
        val create = f.port.sent.filterIsInstance<TdApi.CreateNewSupergroupChat>().single()
        assertTrue(create.isChannel)
        assertFalse(create.isForum)
        assertEquals(TelegramLimits.CHANNEL_TITLE, create.title)
        assertEquals(f.port.chat.id, f.repo.status.value.targetChatId)
        f.repo.refreshFiles(null)
        val history = f.port.sent.filterIsInstance<TdApi.GetChatHistory>()
        assertTrue(history.isNotEmpty())
        assertTrue(history.all { it.chatId == f.port.chat.id })
        assertFalse(f.port.sent.any { it is TdApi.GetChats })
        assertFalse(f.port.sent.any { it is TdApi.LoadChats })
    }

    @Test
    fun testInterruptedUploadIsPersistedAndRetryReusesUploadedFileId() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = "interrupted payload".toByteArray()
        f.port.uploadFileBytes = bytes
        val source = f.source("movie.mp4", bytes)
        f.port.failUpload = true
        // The bytes reached Telegram's servers, but the message send failed (e.g. FLOOD_WAIT).
        f.port.uploadComplete = true
        f.repo.upload(source, "movie.mp4", "video/mp4")
        val interrupted = f.repo.transfers.value.single()
        assertEquals(TelegramTransferState.INTERRUPTED, interrupted.state)
        assertTrue(interrupted.lastError!!.contains("FLOOD_WAIT") || interrupted.lastError!!.contains("TDLib error 429"))
        assertEquals(TelegramTransferState.INTERRUPTED, f.store.loadTransfers().single().state)

        f.port.failUpload = false
        f.port.uploadComplete = true
        f.repo.retry(interrupted.id)
        val second = f.port.sent.filterIsInstance<TdApi.SendMessage>().last()
        val input = ((second.inputMessageContent as TdApi.InputMessageDocument).document.document)
        assertTrue(input is TdApi.InputFileId) // bytes already uploaded: no second data transfer
        assertEquals(TelegramTransferState.COMPLETED, f.repo.transfers.value.first { it.id == interrupted.id }.state)
        assertEquals(2, f.repo.transfers.value.first { it.id == interrupted.id }.attempts)
    }

    @Test
    fun testAppRestartMarksRunningTransferInterruptedInsteadOfFakingCompletion() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val transfer = com.adshield.pro.data.telegram.TelegramTransfer(
            id = "t1", kind = TelegramTransferKind.UPLOAD,
            state = TelegramTransferState.RUNNING, name = "half.bin",
            bytesDone = 50, bytesTotal = 100, attempts = 1, updatedAtEpochMs = 1
        )
        f.store.saveTransfers(listOf(transfer))
        f.repo.initialize()
        assertEquals(TelegramTransferState.INTERRUPTED, f.repo.transfers.value.single().state)
        assertEquals(50L, f.repo.transfers.value.single().bytesDone)
    }

    @Test
    fun testDownloadProgressResumeAndHashVerification() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = "file from telegram".toByteArray()
        f.port.uploadFileBytes = bytes
        val stored = f.repo.upload(f.source("note.pdf", bytes), "note.pdf", "application/pdf")!!
        f.port.downloadBytes = bytes
        f.files.localTdlibFiles["/tdlib/${stored.fileId}"] = bytes
        f.port.downloadComplete = true
        f.port.downloadOffset = 7L // TDLib resumes from its recorded offset
        val ok = f.repo.download(stored, "content://downloads/note.pdf")
        assertTrue(ok)
        val request = f.port.sent.filterIsInstance<TdApi.DownloadFile>().last()
        assertEquals(stored.fileId, request.fileId)
        assertEquals(7L, request.offset)
        assertEquals(0L, request.limit)
        assertEquals(TelegramLimits.PRIORITY, request.priority)
        assertFalse(request.synchronous)
        val saved = f.repo.storedFiles.value.single()
        assertEquals(com.adshield.pro.data.telegram.TelegramDownloadStatus.VERIFIED, saved.downloadStatus)
        assertEquals(bytes.size.toLong(), f.repo.transfers.value.first { it.kind == TelegramTransferKind.DOWNLOAD }.bytesDone)
    }

    @Test
    fun testHashMismatchDoesNotClaimDownloadSuccess() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = "original".toByteArray()
        f.port.uploadFileBytes = bytes
        val stored = f.repo.upload(f.source("data.bin", bytes), "data.bin", "application/octet-stream")!!
        f.port.downloadBytes = "corrupted".toByteArray()
        f.port.downloadComplete = true
        f.files.localTdlibFiles["/tdlib/${stored.fileId}"] = "tampered bytes".toByteArray()
        val ok = f.repo.download(stored, "content://downloads/data.bin")
        assertFalse(ok)
        assertEquals(
            com.adshield.pro.data.telegram.TelegramDownloadStatus.FAILED,
            f.repo.storedFiles.value.single().downloadStatus
        )
        assertEquals(TelegramTransferState.FAILED, f.repo.transfers.value.first { it.kind == TelegramTransferKind.DOWNLOAD }.state)
    }

    @Test
    fun testFailedDownloadCanRetryFromRecordedOffset() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = ByteArray(64) { it.toByte() }
        f.port.uploadFileBytes = bytes
        val stored = f.repo.upload(f.source("chunk.bin", bytes), "chunk.bin", "application/octet-stream")!!
        f.files.localTdlibFiles["/tdlib/${stored.fileId}"] = byteArrayOf()
        f.port.downloadComplete = false
        f.port.downloadOffset = 20L
        f.port.downloadBytes = bytes
        f.port.failDownloadWith = TelegramTdlibException(500, "connection lost")
        f.repo.download(stored, "content://downloads/chunk.bin")
        val failed = f.repo.transfers.value.first { it.kind == TelegramTransferKind.DOWNLOAD }
        assertEquals(TelegramTransferState.INTERRUPTED, failed.state)
        assertTrue(failed.lastError!!.contains("TDLib error 500"))

        f.port.failDownloadWith = null
        f.files.localTdlibFiles["/tdlib/${stored.fileId}"] = bytes
        f.repo.retry(failed.id)
        assertEquals(TelegramTransferState.COMPLETED, f.repo.transfers.value.first { it.id == failed.id }.state)
        assertEquals(2, f.repo.transfers.value.first { it.id == failed.id }.attempts)
        assertEquals(2, f.port.sent.filterIsInstance<TdApi.DownloadFile>().size)
        assertEquals(20L, f.port.sent.filterIsInstance<TdApi.DownloadFile>().last().offset)
    }

    @Test
    fun testDisconnectLogsOutWipesEncryptedSessionKeyAndAllLocalSessionData() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = "secret session payload".toByteArray()
        f.port.uploadFileBytes = bytes
        f.repo.upload(f.source("private.doc", bytes), "private.doc", "application/msword")
        assertNotNull(f.store.loadSession())
        assertTrue(f.kv.values.isNotEmpty())

        val result = f.repo.disconnect(true)
        assertTrue(result.remoteLogoutConfirmed)
        assertTrue(f.port.loggedOut)
        assertTrue(f.port.closed)
        assertNull(f.store.loadSession())
        assertTrue(f.kv.values.isEmpty())
        assertTrue(f.cipher.deleted)
        assertTrue(f.files.wiped)
        assertTrue(f.repo.storedFiles.value.isEmpty())
        assertTrue(f.repo.transfers.value.isEmpty())
        assertFalse(f.repo.status.value.connected)
        assertEquals(TelegramAuthStep.DISCONNECTED, f.repo.status.value.step)
    }

    @Test
    fun testOfflineDisconnectWarnsThatServerSessionRemainsActive() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val result = f.repo.disconnect(false)
        assertFalse(result.remoteLogoutConfirmed)
        assertTrue(result.note!!.contains("still active on the server"))
        assertTrue(f.kv.values.isEmpty())
        assertFalse(f.port.loggedOut)
    }

    @Test
    fun testSearchReadsOnlyOurTaggedDocumentsAndLocalFilterWorks() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = "searchable".toByteArray()
        f.port.uploadFileBytes = bytes
        val stored = f.repo.upload(f.source("invoice.pdf", bytes), "invoice.pdf", "application/pdf", "Finance")!!
        val untagged = TdApi.Message().apply {
            id = 42L; chatId = 991L; date = 1_600_000_000
            content = TdApi.MessageDocument().apply {
                caption = TdApi.FormattedText("somebody else's file", arrayOf())
            }
        }
        f.port.historyMessages.add(untagged)
        f.port.searchResults.clear()
        f.repo.refreshFiles("invoice")
        val queries = f.port.sent.filterIsInstance<TdApi.SearchChatMessages>()
        assertEquals(1, queries.size)
        assertEquals(991L, queries.single().chatId)
        assertEquals("invoice", queries.single().query)
        assertTrue(queries.single().filter is TdApi.SearchMessagesFilterDocument)
        // The unrecognized document is never indexed, even though TDLib returned it.
        assertTrue(f.repo.storedFiles.value.all { it.sha256.isNotBlank() })
        assertEquals(stored.sha256, f.repo.storedFiles.value.single().sha256)
        assertEquals("Finance", f.repo.storedFiles.value.single().label)
    }

    @Test
    fun testLabelIsWrittenBackToTelegramCaptionAndDeleteTargetsOneMessage() = runBlocking {
        val f = Fixture()
        f.connectFully()
        val bytes = "organize me".toByteArray()
        f.port.uploadFileBytes = bytes
        val stored = f.repo.upload(f.source("todo.txt", bytes), "todo.txt", "text/plain")!!
        f.repo.setLabel(stored, "Projects")
        val edit = f.port.sent.filterIsInstance<TdApi.EditMessageCaption>().single()
        assertEquals(991L, edit.chatId)
        assertEquals(stored.messageId, edit.messageId)
        assertEquals("Projects", TelegramPolicy.decodeCaption(edit.caption.text)!!.label)
        assertEquals("Projects", f.store.loadFiles().single().label)
        f.repo.delete(stored)
        val deletion = f.port.sent.filterIsInstance<TdApi.DeleteMessages>().last()
        assertEquals(991L, deletion.chatId)
        assertEquals(1, deletion.messageIds.size)
        assertEquals(stored.messageId, deletion.messageIds[0])
        assertTrue(deletion.revoke)
        assertTrue(f.repo.storedFiles.value.isEmpty())
    }

    @Test
    fun testCaptionEnvelopeAndUploadLimitAreHonest() {
        val caption = TelegramPolicy.encodeCaption("abc123", 42L, "My files\nwith newline")
        assertTrue(caption.startsWith(CAPTION_MARKER))
        val decoded = TelegramPolicy.decodeCaption(caption)!!
        assertEquals("abc123", decoded.sha256)
        assertEquals(42L, decoded.size)
        assertEquals("My files with newline", decoded.label)
        assertNull(TelegramPolicy.decodeCaption("an ordinary Telegram caption"))
        assertFalse(TelegramPolicy.isOurCaption(null))
        TelegramPolicy.validateUploadSize(TelegramLimits.MAX_UPLOAD_BYTES)
        try {
            TelegramPolicy.validateUploadSize(TelegramLimits.MAX_UPLOAD_BYTES + 1)
            fail("2 GB limit must be enforced")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("2 GB"))
        }
        assertEquals("+••••••9876", TelegramPolicy.maskPhone("+63 917 000 9876"))
        assertEquals(2000L, TelegramPolicy.retryDelayMs(0))
        assertEquals(8000L, TelegramPolicy.retryDelayMs(2))
        assertEquals(30000L, TelegramPolicy.retryDelayMs(99))
        assertEquals(1, com.adshield.pro.data.telegram.CAPTION_VERSION)
    }

    @Test
    fun testHashStreamIsExactSha256AndSize() {
        val bytes = "0123456789".repeat(13).toByteArray()
        val result = TelegramPolicy.hashStream(ByteArrayInputStream(bytes))
        assertEquals(bytes.size.toLong(), result.sizeBytes)
        assertEquals(sha256(bytes), result.sha256)
        assertEquals(TelegramHashResult(bytes.size.toLong(), sha256(bytes)), result)
    }

    @Test
    fun testSecureStoreNeverWritesPlaintextAndRejectsTampering() {
        val f = Fixture()
        val session = TelegramSession(
            apiId = 7, apiHash = "super-secret-hash", databaseKeyBase64 = "abc",
            accountDir = "acct", targetKind = TelegramTargetKind.SAVED_MESSAGES
        )
        f.store.saveSession(session)
        val raw = f.kv.values[TelegramSecureStore.KEY_SESSION]!!
        assertFalse(raw.contains("super-secret-hash"))
        assertFalse(raw.contains("\"apiHash\""))
        val envelope = Base64.getDecoder().decode(raw)
        assertTrue(envelope.size > 28)
        assertEquals(session, f.store.loadSession())
        f.kv.put(TelegramSecureStore.KEY_SESSION, Base64.getEncoder().encodeToString(envelope.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }))
        try {
            f.store.loadSession()
            fail("tampered ciphertext must not be accepted")
        } catch (e: TelegramKeyUnavailable) {
            assertTrue(e.message!!.contains("cannot be decrypted"))
        }
        f.store.clearAll()
        assertTrue(f.cipher.deleted)
        assertTrue(f.kv.values.isEmpty())
    }

    @Test
    fun testEncryptedSessionThatCannotBeUnlockedIsWipedNotInvented() = runBlocking {
        val f = Fixture()
        f.connectFully()
        f.cipher.deleted = true
        f.repo.initialize()
        assertNull(f.store.loadSession())
        assertTrue(f.files.wiped)
        assertTrue(f.repo.storedFiles.value.isEmpty())
        assertFalse(f.repo.status.value.connected)
        assertTrue(f.repo.status.value.error!!.contains("session could not be unlocked"))
    }

    @Test
    fun testWrongDatabaseEncryptionKeyClearsSessionInsteadOfLooping() = runBlocking {
        val f = Fixture()
        // A real TDLib 401 on setTdlibParameters arrives through start(); emulate via port.
        f.port.startThrows = TelegramTdlibException(401, "Unauthorized")
        f.store.saveSession(
            TelegramSession(
                apiId = 1, apiHash = "0123456789abcdef", databaseKeyBase64 = "AAAA",
                accountDir = "old", targetChatId = 991L, targetKind = TelegramTargetKind.SAVED_MESSAGES
            )
        )
        f.repo.initialize()
        assertTrue(f.kv.values.isEmpty())
        assertTrue(f.files.wiped)
        assertTrue(f.repo.status.value.error!!.contains("database key"))
    }

}
