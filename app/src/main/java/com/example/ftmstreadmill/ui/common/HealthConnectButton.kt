package com.example.ftmstreadmill.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.health.ExportResult
import com.example.ftmstreadmill.health.HealthConnectAvailability
import com.example.ftmstreadmill.health.HealthConnectExporter
import kotlinx.coroutines.launch

/**
 * "Send to Health Connect" with everything it can need on the way: installing Health Connect,
 * granting permission, and retrying. Shows a check once the session has been sent.
 */
@Composable
fun HealthConnectButton(
    exported: Boolean,
    availability: HealthConnectAvailability,
    export: suspend () -> ExportResult,
    modifier: Modifier = Modifier,
) {
    if (availability == HealthConnectAvailability.UNAVAILABLE) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun run() {
        busy = true
        error = null
        scope.launch {
            val result = export()
            busy = false
            when (result) {
                ExportResult.Success -> done = true
                ExportResult.NotAvailable -> error = resources.getString(R.string.hc_not_available)
                ExportResult.PermissionMissing -> error = resources.getString(R.string.hc_permission_denied)
                is ExportResult.Failed -> error = resources.getString(R.string.hc_failed)
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HealthConnectExporter.PERMISSIONS)) run() else error = resources.getString(R.string.hc_permission_denied)
    }

    if (exported || done) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.hc_sent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
        return
    }
    Column(modifier) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                when (availability) {
                    HealthConnectAvailability.NEEDS_INSTALL_OR_UPDATE -> openHealthConnectInstall(context)
                    else -> scope.launch {
                        busy = true
                        val result = export()
                        busy = false
                        when (result) {
                            ExportResult.Success -> done = true
                            ExportResult.PermissionMissing -> permissions.launch(HealthConnectExporter.PERMISSIONS)
                            ExportResult.NotAvailable -> error = resources.getString(R.string.hc_not_available)
                            is ExportResult.Failed -> error = resources.getString(R.string.hc_failed)
                        }
                    }
                }
            },
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                stringResource(
                    if (availability == HealthConnectAvailability.NEEDS_INSTALL_OR_UPDATE) R.string.hc_install else R.string.hc_send,
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Opens the Play Store page for Health Connect (needed on Android 13 and older). */
fun openHealthConnectInstall(context: Context) {
    val uri = ("market://details?id=${HealthConnectExporter.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding").toUri()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${HealthConnectExporter.PROVIDER_PACKAGE}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
