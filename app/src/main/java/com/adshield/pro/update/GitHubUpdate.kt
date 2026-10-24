package com.adshield.pro.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.adshield.pro.BuildConfig
import com.adshield.pro.R
import com.adshield.pro.ui.UpdatePromptActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class GitHubRelease(val tag: String, val name: String, val code: Int, val apk: String, val sha256: String?, val notes: String)

object GitHubReleaseClient {
    private const val API_BASE = "https://api.github.com/repos/"
    fun latest(): GitHubRelease? {
        val repository = BuildConfig.GITHUB_UPDATE_REPOSITORY.trim()
        if (!repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return null
        val endpoint = API_BASE + repository + "/releases/latest"
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AdShieldPro-Android-Updater").get().build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string() ?: return null)
            val tag = json.optString("tag_name").trim()
            val match = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(tag) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            if (minor > 99 || patch > 99) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apk: String? = null
            var digest: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", true)) {
                    apk = asset.optString("browser_download_url")
                    digest = asset.optString("digest").takeIf { it.startsWith("sha256:", true) }?.substringAfter(":")
                    break
                }
            }
            val trustedUrl = apk?.takeIf { it.startsWith("https://github.com/") && it.contains("/releases/download/") } ?: return null
            val notes = json.optString("body").takeUnless { it == "null" }?.trim().orEmpty()
                .take(1800).ifBlank { "Performance improvements and protection updates." }
            return GitHubRelease(tag, tag.removePrefix("v"), major * 10000 + minor * 100 + patch, trustedUrl, digest, notes)
        }
    }
}

@HiltWorker
class GitHubUpdateWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val release = GitHubReleaseClient.latest() ?: return@withContext Result.retry()
            if (release.code <= BuildConfig.VERSION_CODE) return@withContext Result.success()
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return@withContext Result.success()
            val notifications = NotificationManagerCompat.from(applicationContext)
            if (!notifications.areNotificationsEnabled()) return@withContext Result.success()
            val prefs = applicationContext.getSharedPreferences("adshield_update_state", Context.MODE_PRIVATE)
            if (prefs.getInt("notified_code", 0) >= release.code) return@withContext Result.success()

            if (Build.VERSION.SDK_INT >= 26) {
                val system = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                system.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val intent = Intent(applicationContext, UpdatePromptActivity::class.java).apply {
                putExtra(UpdatePromptActivity.TAG, release.tag)
                putExtra(UpdatePromptActivity.NAME, release.name)
                putExtra(UpdatePromptActivity.CODE, release.code)
                putExtra(UpdatePromptActivity.APK, release.apk)
                putExtra(UpdatePromptActivity.SHA, release.sha256)
                putExtra(UpdatePromptActivity.NOTES, release.notes)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pending = PendingIntent.getActivity(applicationContext, release.code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notifications.notify(ID, NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield_logo)
                .setContentTitle("AdShield Pro " + release.name + " is available")
                .setContentText("Tap to review the update and release notes.")
                .setContentIntent(pending).setAutoCancel(true).build())
            prefs.edit().putInt("notified_code", release.code).apply()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        private const val CHANNEL = "app_updates"
        private const val ID = 7021
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val work = WorkManager.getInstance(context)
            work.enqueueUniquePeriodicWork("adshield_update_periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GitHubUpdateWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build())
            work.enqueueUniqueWork("adshield_update_startup", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GitHubUpdateWorker>().setConstraints(constraints).build())
        }
    }
}

object GitHubApkDownload {
    suspend fun get(context: Context, release: GitHubRelease): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(release.apk).header("User-Agent", "AdShieldPro-Android-Updater").get().build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Download failed (HTTP " + response.code + ").")
            val body = response.body ?: throw java.io.IOException("APK download was empty.")
            val limit = 250L * 1024L * 1024L
            if (body.contentLength() > limit) throw java.io.IOException("APK exceeds the download size limit.")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val part = File(dir, "adshield-" + release.code + ".apk.part")
            val apk = File(dir, "adshield-" + release.code + ".apk")
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            body.byteStream().use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > limit) throw java.io.IOException("APK exceeds the download size limit.")
                        hash.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = hash.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !actual.equals(release.sha256, true)) {
                part.delete()
                throw java.io.IOException("APK checksum verification failed; it was not installed.")
            }
            if (apk.exists()) apk.delete()
            if (!part.renameTo(apk)) { part.copyTo(apk, true); part.delete() }
            apk
        }
    }
}