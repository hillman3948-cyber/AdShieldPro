package com.adshield.pro.data.telegram

import android.content.Context
import android.os.Build
import com.adshield.pro.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Supplies honest device/app identification required by the Telegram API. */
@Singleton
class AndroidTdlibParametersFactory @Inject constructor(
    @ApplicationContext private val context: Context
) : TelegramTdlibParametersFactory {
    override fun create(
        session: TelegramSession,
        paths: TelegramTdlibPaths,
        key: ByteArray
    ): TelegramTdlibParameters {
        val language = context.resources.configuration.locales[0]?.toLanguageTag()
            ?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag().ifBlank { "en" }
        return TelegramTdlibParameters(
            useTestDc = false,
            databaseDirectory = paths.databaseDirectory,
            filesDirectory = paths.filesDirectory,
            databaseEncryptionKey = key,
            apiId = session.apiId,
            apiHash = session.apiHash,
            systemLanguageCode = language,
            deviceModel = Build.MODEL.ifBlank { "Android device" },
            systemVersion = Build.VERSION.RELEASE.ifBlank { "Android" },
            applicationVersion = BuildConfig.VERSION_NAME,
            useFileDatabase = true,
            useChatInfoDatabase = true,
            useMessageDatabase = true,
            // Secret chats are unrelated to cloud storage and are never enabled.
            useSecretChats = false
        )
    }
}

/**
 * Real TDLib client. This class is the only code that touches org.drinkless.tdlib.Client; it
 * deliberately forwards only file, authorization, connection and our own message-send updates.
 * Updates carrying somebody else's chat content are dropped at the boundary, so the storage
 * feature never inspects unrelated private conversations.
 */
@Singleton
class AndroidTdlibPort @Inject constructor(
    private val pathsFactory: AndroidTdlibParametersFactory
) : TelegramTdlibPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableStateFlow(TelegramAuthStep.DISCONNECTED)
    override val authState: StateFlow<TelegramAuthStep> = _authState
    private val _connectionState = MutableStateFlow(TelegramConnectionState.OFFLINE)
    override val connectionState: StateFlow<TelegramConnectionState> = _connectionState
    private val _fileStates = MutableStateFlow<Map<Int, TdApi.File>>(emptyMap())
    override val fileStates: StateFlow<Map<Int, TdApi.File>> = _fileStates
    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 256)
    override val updates: SharedFlow<TdApi.Object> = _updates

    @Volatile
    private var client: Client? = null

    @Volatile
    private var parameters: TdApi.SetTdlibParameters? = null

    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T = suspendCancellableCoroutine { cont ->
        val active = client
        if (active == null) {
            cont.resumeWithException(TelegramTdlibException(0, "TDLib client is not started"))
            return@suspendCancellableCoroutine
        }
        val handler = object : Client.ResultHandler {
            override fun onResult(result: TdApi.Object) {
                if (result is TdApi.Error) {
                    cont.resumeWithException(TelegramTdlibException(result.code, result.message))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            }
        }
        try {
            active.send(query, handler, NOOP_EXCEPTION_HANDLER)
        } catch (failure: Throwable) {
            cont.resumeWithException(failure)
        }
    }

    override fun seedFile(file: TdApi.File) {
        _fileStates.value = _fileStates.value + (file.id to file)
    }

    override suspend fun start(session: TelegramSession, params: TelegramTdlibParameters) {
        loadNativeLibrary()
        // Verbosity 1 = errors only, and no persistent log file: session data never lands on disk
        // through TDLib logging. Nothing about TdApi objects, phone numbers or tokens is logged.
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        parameters = params.toTdApi()
        _authState.value = TelegramAuthStep.INITIALIZING
        client = Client.create(
            object : Client.ResultHandler {
                override fun onResult(update: TdApi.Object) = dispatch(update)
            },
            NOOP_EXCEPTION_HANDLER,
            NOOP_EXCEPTION_HANDLER
        )
    }

    private fun dispatch(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                val step = mapAuthorizationState(update.authorizationState)
                _authState.value = step
                if (update.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
                    val query = parameters
                    val active = client
                    if (query != null && active != null) {
                        // TDLib's receiver thread must never block on a coroutine.
                        scope.launch {
                            runCatching { active.send(query, null, NOOP_EXCEPTION_HANDLER) }
                        }
                    }
                }
                _updates.tryEmit(update)
            }
            is TdApi.UpdateFile -> {
                _fileStates.value = _fileStates.value + (update.file.id to update.file)
                _updates.tryEmit(update)
            }
            is TdApi.UpdateMessageSendSucceeded,
            is TdApi.UpdateMessageSendFailed,
            is TdApi.UpdateConnectionState -> {
                _connectionState.value = when (update) {
                    is TdApi.UpdateConnectionState -> when (update.state) {
                        is TdApi.ConnectionStateReady -> TelegramConnectionState.READY
                        is TdApi.ConnectionStateConnecting,
                        is TdApi.ConnectionStateConnectingToProxy,
                        is TdApi.ConnectionStateUpdating -> TelegramConnectionState.CONNECTING
                        else -> TelegramConnectionState.OFFLINE
                    }
                    else -> _connectionState.value
                }
                _updates.tryEmit(update)
            }
            else -> Unit // Drop chat content; storage never reads unrelated conversations.
        }
    }

    override suspend fun logout(): Boolean {
        val active = client ?: return false
        // logOut destroys local TDLib data and invalidates the server-side session. It needs a
        // network connection; failure is reported rather than silently pretending to log out.
        send(TdApi.LogOut())
        val confirmed = runCatching {
            withTimeout(45_000L) {
                authState.first { it == TelegramAuthStep.CLOSED || it == TelegramAuthStep.WAIT_PHONE_NUMBER }
            }
            true
        }.getOrDefault(false)
        closeInternal(active, alreadyClosed = confirmed)
        return confirmed
    }

    override suspend fun close() {
        val active = client ?: return
        closeInternal(active, alreadyClosed = false)
    }

    private fun closeInternal(active: Client, alreadyClosed: Boolean) {
        if (!alreadyClosed) {
            // Close is fire-and-forget; the native client releases itself after the update.
            runCatching { active.send(TdApi.Close(), null, NOOP_EXCEPTION_HANDLER) }
        }
        if (client === active) client = null
        parameters = null
        _fileStates.value = emptyMap()
        _authState.value = TelegramAuthStep.DISCONNECTED
        _connectionState.value = TelegramConnectionState.OFFLINE
    }

    companion object {
        private val NOOP_EXCEPTION_HANDLER = object : Client.ExceptionHandler {
            override fun onException(e: Throwable) = Unit
        }

        private val nativeLock = Any()

        @Volatile
        private var nativeLoaded = false

        /** libtdjni.so links libsslx.so and libcryptox.so from the same jniLibs directory. */
        fun loadNativeLibrary() {
            synchronized(nativeLock) {
                if (nativeLoaded) return
                try {
                    System.loadLibrary("tdjni")
                    nativeLoaded = true
                } catch (failure: Throwable) {
                    throw TelegramTdlibException(
                        -1,
                        "TDLib native runtime is unavailable for this device ABI. " +
                            "Rebuild with ./gradlew :app:fetchTdlibNative (see docs/TELEGRAM_CLOUD_STORAGE.md). " +
                            "Cause: ${failure.message}"
                    )
                }
            }
        }
    }
}
