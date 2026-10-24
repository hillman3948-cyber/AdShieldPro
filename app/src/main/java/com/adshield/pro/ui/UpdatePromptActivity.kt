package com.adshield.pro.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.adshield.pro.update.GitHubApkDownload
import com.adshield.pro.update.GitHubRelease
import com.adshield.pro.ui.theme.AdShieldProTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdatePromptActivity : ComponentActivity() {
    private var release: GitHubRelease? = null
    private var downloading by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val item = release
        if (item != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) download(item)
        else if (item != null) status = "Allow AdShield Pro to install apps from this source in Settings, then try again."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        release = read(intent)
        if (release == null) { finish(); return }
        render()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        release = read(intent)
        render()
    }
    private fun render() {
        val item = release ?: return
        setContent {
            AdShieldProTheme {
                AlertDialog(
                    onDismissRequest = { if (!downloading) finish() },
                    icon = { Icon(Icons.Default.SystemUpdateAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) },
                    title = { Text("Update available") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AdShield Pro " + item.name)
                            Text(item.notes, style = MaterialTheme.typography.bodyMedium)
                            status?.let { Spacer(Modifier.height(4.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                            if (downloading) { CircularProgressIndicator(modifier = Modifier.size(28.dp)); Text("Downloading and verifying…") }
                        }
                    },
                    confirmButton = { Button(enabled = !downloading, onClick = { requestInstall(item) }) { Text("Update Now") } },
                    dismissButton = { TextButton(enabled = !downloading, onClick = { finish() }) { Text("Later") } },
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
    }
    private fun requestInstall(item: GitHubRelease) {
        status = null
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
        } else download(item)
    }
    private fun download(item: GitHubRelease) {
        if (downloading) return
        downloading = true
        status = "Connecting to GitHub…"
        lifecycleScope.launch {
            try {
                val file = GitHubApkDownload.get(this@UpdatePromptActivity, item)
                val uri = FileProvider.getUriForFile(this@UpdatePromptActivity, packageName + ".fileprovider", file)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) { startActivity(install); finish() }
            } catch (error: Exception) {
                status = error.message ?: "Could not download the update. Please try again."
                downloading = false
            }
        }
    }
    private fun read(intent: Intent?): GitHubRelease? {
        intent ?: return null
        val tag = intent.getStringExtra(TAG) ?: return null
        val name = intent.getStringExtra(NAME) ?: return null
        val apk = intent.getStringExtra(APK) ?: return null
        return GitHubRelease(tag, name, intent.getIntExtra(CODE, 0), apk, intent.getStringExtra(SHA), intent.getStringExtra(NOTES).orEmpty())
    }
    companion object {
        const val TAG = "update_tag"
        const val NAME = "update_name"
        const val CODE = "update_code"
        const val APK = "update_apk"
        const val SHA = "update_sha"
        const val NOTES = "update_notes"
    }
}