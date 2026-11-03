package com.adshield.pro.di

import android.content.Context
import androidx.room.Room
import com.adshield.pro.aether.AetherPolicy
import com.adshield.pro.aether.SharedPreferencesAetherPolicyStore
import com.adshield.pro.data.local.db.AdShieldDao
import com.adshield.pro.data.local.db.AdShieldDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAdShieldDatabase(
        @ApplicationContext context: Context
    ): AdShieldDatabase {
        return Room.databaseBuilder(
            context,
            AdShieldDatabase::class.java,
            AdShieldDatabase.DATABASE_NAME
        )
            .addMigrations(AdShieldDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    @Singleton
    fun provideAdShieldDao(database: AdShieldDatabase): AdShieldDao {
        return database.adShieldDao()
    }

    /** AETHER AI on-device policy store (user-approved allowlist + rollback snapshots). */
    @Provides
    @Singleton
    fun provideAetherPolicy(@ApplicationContext context: Context): AetherPolicy {
        return AetherPolicy(SharedPreferencesAetherPolicyStore(context))
    }
}
