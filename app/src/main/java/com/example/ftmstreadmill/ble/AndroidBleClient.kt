package com.example.ftmstreadmill.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.ftmstreadmill.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SuppressLint("MissingPermission") // Every entry point checks hasPermissions() first.
class AndroidBleClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bluetoothManager: BluetoothManager,
    @ApplicationScope scope: CoroutineScope,
) : BleClient {

    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    override val adapterEnabled: StateFlow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                trySend(state == BluetoothAdapter.STATE_ON)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED, // System broadcast.
        )
        trySend(adapter?.isEnabled == true)
        awaitClose { context.unregisterReceiver(receiver) }
    }.stateIn(scope, SharingStarted.Eagerly, adapter?.isEnabled == true)

    override fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun scan(serviceUuid: UUID): Flow<ScannedDevice> = callbackFlow {
        ensureReady()
        val scanner = adapter?.bluetoothLeScanner ?: throw BleException(BleError.AdapterOff)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(ScannedDevice(result.device.address, result.scanRecord?.deviceName ?: result.device.name, result.rssi))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it)
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException(BleError.ScanFailed(errorCode)))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        // stopScan throws if the adapter was switched off mid-scan; nothing left to clean up then.
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    override suspend fun connect(address: String): GattConnection {
        ensureReady()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw BleException(BleError.DeviceNotFound)
        val device = adapter?.getRemoteDevice(address) ?: throw BleException(BleError.AdapterOff)
        return AndroidGattConnection.connect(context, device)
    }

    private fun ensureReady() {
        if (!hasPermissions()) throw BleException(BleError.PermissionMissing)
        if (adapter?.isEnabled != true) throw BleException(BleError.AdapterOff)
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
