package com.adshield.pro.data.telegram

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi

/**
 * The seam between our storage logic and the official TDLib Java API.
 *
 * The real implementation ([AndroidTdlibPort]) is the only place that calls
 * org.drinkless.tdlib.Client. A JVM fake implements the same interface, so authentication,
 * uploads, interrupted transfers, downloads and disconnects are all exercised without the
 * native library or a Telegram account — while the production path is still 100% real TDLib.
 */
interface TelegramTdlibPort {
    val authState: StateFlow<TelegramAuthStep>
    val connectionState: StateFlow<TelegramConnectionState>
    /** Latest updateFile per file id; seeded from sendMessage/getFile responses as well. */
    val fileStates: StateFlow<Map<Int, TdApi.File>>
    /** Only non-chat-content updates (file/auth/connection/our own send results) are forwarded. */
    val updates: SharedFlow<TdApi.Object>

    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T

    fun seedFile(file: TdApi.File)

    suspend fun start(session: TelegramSession, params: TelegramTdlibParameters)

    /**
     * TDLib logOut: terminates the server-side session, then waits for authorizationStateClosed.
     * Returns true only when TDLib actually confirmed the logout (no network => false).
     */
    suspend fun logout(): Boolean

    suspend fun close()
}

/** Builds setTdlibParameters; the Android implementation supplies real device identification. */
interface TelegramTdlibParametersFactory {
    fun create(
        session: TelegramSession,
        paths: TelegramTdlibPaths,
        key: ByteArray
    ): TelegramTdlibParameters
}

/** Maps every authorizationState* class of the pinned TDLib API to an honest UI state. */
fun mapAuthorizationState(state: TdApi.AuthorizationState?): TelegramAuthStep = when (state) {
    is TdApi.AuthorizationStateWaitTdlibParameters -> TelegramAuthStep.WAIT_TDLIB_PARAMETERS
    is TdApi.AuthorizationStateWaitPhoneNumber -> TelegramAuthStep.WAIT_PHONE_NUMBER
    is TdApi.AuthorizationStateWaitCode -> TelegramAuthStep.WAIT_CODE
    is TdApi.AuthorizationStateWaitPassword -> TelegramAuthStep.WAIT_PASSWORD
    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> TelegramAuthStep.WAIT_OTHER_DEVICE_CONFIRMATION
    is TdApi.AuthorizationStateWaitRegistration -> TelegramAuthStep.WAIT_REGISTRATION
    is TdApi.AuthorizationStateWaitEmailAddress -> TelegramAuthStep.WAIT_EMAIL_ADDRESS
    is TdApi.AuthorizationStateWaitEmailCode -> TelegramAuthStep.WAIT_EMAIL_CODE
    is TdApi.AuthorizationStateWaitPremiumPurchase -> TelegramAuthStep.WAIT_PREMIUM_PURCHASE
    is TdApi.AuthorizationStateReady -> TelegramAuthStep.READY
    is TdApi.AuthorizationStateLoggingOut -> TelegramAuthStep.LOGGING_OUT
    is TdApi.AuthorizationStateClosing -> TelegramAuthStep.CLOSING
    is TdApi.AuthorizationStateClosed -> TelegramAuthStep.CLOSED
    else -> TelegramAuthStep.UNSUPPORTED
}

/** Builds the real setTdlibParameters request; kept here so tests can assert every field. */
fun TelegramTdlibParameters.toTdApi(): TdApi.SetTdlibParameters =
    TdApi.SetTdlibParameters().apply {
        useTestDc = this@toTdApi.useTestDc
        databaseDirectory = this@toTdApi.databaseDirectory
        filesDirectory = this@toTdApi.filesDirectory
        databaseEncryptionKey = this@toTdApi.databaseEncryptionKey
        useFileDatabase = this@toTdApi.useFileDatabase
        useChatInfoDatabase = this@toTdApi.useChatInfoDatabase
        useMessageDatabase = this@toTdApi.useMessageDatabase
        useSecretChats = this@toTdApi.useSecretChats
        apiId = this@toTdApi.apiId
        apiHash = this@toTdApi.apiHash
        systemLanguageCode = this@toTdApi.systemLanguageCode
        deviceModel = this@toTdApi.deviceModel
        systemVersion = this@toTdApi.systemVersion
        applicationVersion = this@toTdApi.applicationVersion
    }
