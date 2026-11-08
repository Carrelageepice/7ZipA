package org.openarchive.sevenzip

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * A "Manage all files" row with a switch. Android does not allow apps to grant
 * themselves this permission, so flipping the switch opens the system settings
 * page for this app; the switch updates when the user comes back.
 *
 * Shows nothing on Android 10 and older, where the permission does not exist.
 */
@Composable
fun AllFilesAccessToggle(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val context = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        granted = Environment.isExternalStorageManager()
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Manage all files", style = MaterialTheme.typography.titleSmall)
            Text(
                if (granted) "On. The app can reach files anywhere on your storage."
                else "Off. The app only uses folders you pick.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = granted,
            onCheckedChange = {
                val appIntent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                )
                try {
                    launcher.launch(appIntent)
                } catch (_: ActivityNotFoundException) {
                    // Some devices only have the general list.
                    try {
                        launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: ActivityNotFoundException) {
                        // Nothing more we can do.
                    }
                }
            }
        )
    }
}
