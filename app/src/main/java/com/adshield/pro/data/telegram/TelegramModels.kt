package com.adshield.pro.data.telegram

import kotlinx.serialization.Serializable

/**
 * Domain types for the Telegram Cloud Storage provider.
 *
 * This file is deliberately free of Android and TDLib imports: the whole state machine, the
 * transfer bookkeeping and the session store are exercised by JVM unit tests without an emulator,
 * without a Telegram account and without the native library. Android-specific adapters live in
 * [AndroidKeystoreCipher], [AndroidTelegramFileIo] and [AndroidTdlibPort].
 */

/** The four sections of the Telegram provider UI. */
enum class TelegramCloudSection(val label: String) {
    UPLOAD("Upload"),
    DOWNLOAD("Download"),
    FILES("Files"),
    MANAGEMENT("Storage Management")
}

/** Where files are stored. Only ever one chat is addressed; no other conversation is opened. */
enum class TelegramTargetKind(val label: String) {
    SAVED_MESSAGES("Saved Messages"),
    PRIVATE_CHANNEL("Private storage channel")
}

/** Mirrors TDLib's authorizationState* classes, reduced to what the UI needs. */
enum class TelegramAuthStep {
    DISCONNECTED,
    INITIALIZING,
    WAIT_TDLIB_PARAMETERS,
    WAIT_PHONE_NUMBER,
    WAIT_CODE,
    WAIT_PASSWORD,
    WAIT_OTHER_DEVICE_CONFIRMATION,
    WAIT_REGISTRATION,
    WAIT_EMAIL_ADDRESS,
    WAIT_EMAIL_CODE,
    WAIT_PREMIUM_PURCHASE,
    READY,
    LOGGING_OUT,
    CLOSING,
    CLOSED,
    UNSUPPORTED
}

enum class TelegramConnectionState { OFFLINE, CONNECTING, READY }

data class TelegramAccountStatus(
    val step: TelegramAuthStep = TelegramAuthStep.DISCONNECTED,
    val connection: TelegramConnectionState = TelegramConnectionState.OFFLINE,
    val displayName: String = "",
    val maskedPhone: String = "",
    val target: TelegramTargetKind? = null,
    val targetTitle: String = "",
    val targetChatId: Long = 0L,
    val message: String? = null,
    val error: String? = null
) {
    val connected: Boolean get() = step == TelegramAuthStep.READY && targetChatId != 0L
}

/** Everything secret or session-related. The JSON blob holding this is AES-GCM encrypted. */
@Serializable
data class TelegramSession(
    val apiId: Int = 0,
    val apiHash: String = "",
    val databaseKeyBase64: String = "",
    val accountDir: String = "",
    val userId: Long = 0L,
    val displayName: String = "",
    val maskedPhone: String = "",
    val targetKind: TelegramTargetKind = TelegramTargetKind.SAVED_MESSAGES,
    val targetChatId: Long = 0L,
    val targetTitle: String = "",
    val createdAtEpochMs: Long = 0L
) {
    /** True once TDLib reported authorizationStateReady and the storage chat was resolved. */
    val isComplete: Boolean get() = apiId > 0 && targetChatId != 0L && databaseKeyBase64.isNotBlank()
}

/** Parameters passed to TDLib's setTdlibParameters. Kept as data so tests can inspect them. */
data class TelegramTdlibParameters(
    val useTestDc: Boolean = false,
    val databaseDirectory: String,
    val filesDirectory: String,
    val databaseEncryptionKey: ByteArray,
    val apiId: Int,
    val apiHash: String,
    val systemLanguageCode: String,
    val deviceModel: String,
    val systemVersion: String,
    val applicationVersion: String,
    val useFileDatabase: Boolean = true,
    val useChatInfoDatabase: Boolean = true,
    val useMessageDatabase: Boolean = true,
    val useSecretChats: Boolean = false
)

/** Machine-readable metadata stored in the document caption of every file we upload. */
@Serializable
data class TelegramCaption(
    val v: Int = CAPTION_VERSION,
    val sha256: String = "",
    val size: Long = 0L,
    val label: String = ""
)

const val CAPTION_VERSION = 1
const val CAPTION_MARKER = "AdShieldPro/TGCS1:"

enum class TelegramDownloadStatus(val label: String) {
    NOT_DOWNLOADED("Not downloaded"),
    DOWNLOADING("Downloading"),
    DOWNLOADED("Downloaded"),
    VERIFIED("Downloaded · hash verified"),
    FAILED("Download failed")
}

/** One document in the designated Telegram chat, joined with our local metadata. */
@Serializable
data class TelegramStoredFile(
    val id: String,
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val remoteFileId: String = "",
    val name: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long,
    val uploadedAtEpochSec: Int,
    val sha256: String = "",
    val label: String = "",
    val downloadStatus: TelegramDownloadStatus = TelegramDownloadStatus.NOT_DOWNLOADED,
    val localDestinationUri: String? = null,
    val downloadedAtEpochMs: Long? = null
)

@Serializable
data class TelegramFileIndex(val files: List<TelegramStoredFile> = emptyList())

enum class TelegramTransferKind { UPLOAD, DOWNLOAD }

enum class TelegramTransferState(val label: String) {
    QUEUED("Queued"),
    RUNNING("Running"),
    INTERRUPTED("Interrupted"),
    RETRYING("Retrying"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED || this == FAILED
}

/** Persisted per transfer so an interrupted job can be retried after a process restart. */
@Serializable
data class TelegramTransfer(
    val id: String,
    val kind: TelegramTransferKind,
    val state: TelegramTransferState,
    val name: String,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val attempts: Int = 0,
    val lastError: String? = null,
    val sourceUri: String? = null,
    val stagedPath: String? = null,
    val destinationUri: String? = null,
    val fileId: Int? = null,
    val messageId: Long? = null,
    val sha256: String? = null,
    val label: String = "",
    val mimeType: String = "application/octet-stream",
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
}

@Serializable
data class TelegramTransferBook(val transfers: List<TelegramTransfer> = emptyList())

/** Result of hashing a local byte stream. */
data class TelegramHashResult(val sizeBytes: Long, val sha256: String)
