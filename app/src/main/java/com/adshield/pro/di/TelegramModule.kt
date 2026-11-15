package com.adshield.pro.di

import com.adshield.pro.data.telegram.AndroidKeystoreCipher
import com.adshield.pro.data.telegram.AndroidTdlibParametersFactory
import com.adshield.pro.data.telegram.AndroidTdlibPort
import com.adshield.pro.data.telegram.AndroidTelegramFileIo
import com.adshield.pro.data.telegram.SharedPreferencesTelegramStore
import com.adshield.pro.data.telegram.SystemTelegramClock
import com.adshield.pro.data.telegram.TelegramAesGcm
import com.adshield.pro.data.telegram.TelegramClock
import com.adshield.pro.data.telegram.TelegramFileIo
import com.adshield.pro.data.telegram.TelegramKeyValueStore
import com.adshield.pro.data.telegram.TelegramSecureStore
import com.adshield.pro.data.telegram.TelegramTdlibParametersFactory
import com.adshield.pro.data.telegram.TelegramTdlibPort
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wires the Telegram provider's Android adapters behind testable interfaces. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TelegramModule {

    @Binds
    @Singleton
    abstract fun bindTdlibParametersFactory(implementation: AndroidTdlibParametersFactory): TelegramTdlibParametersFactory

    @Binds
    @Singleton
    abstract fun bindTdlibPort(implementation: AndroidTdlibPort): TelegramTdlibPort

    @Binds
    @Singleton
    abstract fun bindFileIo(implementation: AndroidTelegramFileIo): TelegramFileIo

    @Binds
    @Singleton
    abstract fun bindKeyValueStore(implementation: SharedPreferencesTelegramStore): TelegramKeyValueStore

    @Binds
    @Singleton
    abstract fun bindCipher(implementation: AndroidKeystoreCipher): TelegramAesGcm

    companion object {
        @Provides
        @Singleton
        fun provideClock(): TelegramClock = SystemTelegramClock

        @Provides
        @Singleton
        fun provideSecureStore(
            kv: TelegramKeyValueStore,
            cipher: TelegramAesGcm
        ): TelegramSecureStore = TelegramSecureStore(kv, cipher)
    }
}
