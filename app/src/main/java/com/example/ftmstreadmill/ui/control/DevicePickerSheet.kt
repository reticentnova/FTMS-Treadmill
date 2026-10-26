package com.example.ftmstreadmill.ui.control

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.data.DeviceInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(
    scan: ScanUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelect: (DeviceInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    // Scan only while the sheet is visible.
    DisposableEffect(Unit) {
        onStartScan()
        onDispose(onStopScan)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (scan.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onStartScan) { Text(stringResource(R.string.picker_scan_again)) }
                }
            }
            when {
                scan.devices.isEmpty() && scan.isScanning -> Text(
                    stringResource(R.string.picker_scanning),
                    modifier = Modifier.padding(16.dp),
                )
                scan.devices.isEmpty() -> Text(
                    stringResource(R.string.picker_empty),
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn {
                    items(scan.devices, key = { it.address }) { device ->
                        ListItem(
                            headlineContent = { Text(device.name ?: stringResource(R.string.unknown_device)) },
                            supportingContent = { Text(device.address) },
                            trailingContent = { Text(stringResource(R.string.picker_rssi, device.rssi)) },
                            modifier = Modifier.clickable { onSelect(DeviceInfo(device.address, device.name)) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
