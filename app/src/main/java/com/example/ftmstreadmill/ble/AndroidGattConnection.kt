package com.example.ftmstreadmill.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.ftmstreadmill.ftms.FtmsUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [GattConnection] over the framework [BluetoothGatt].
 *
 * Android allows only one outstanding GATT operation per connection, so every operation takes
 * [opMutex], registers a [PendingOp], starts the framework call and awaits the matching callback
 * with a timeout. Callbacks arrive on a binder thread and only complete deferreds or emit into a
 * buffered flow, both of which are thread-safe.
 */
@SuppressLint("MissingPermission") // AndroidBleClient checks permissions before creating connections.
internal class AndroidGattConnection private constructor(
    override val address: String,
) : GattConnection {

    private lateinit var gatt: BluetoothGatt
    private val opMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val connected = CompletableDeferred<Unit>()
    private val _disconnected = CompletableDeferred<Int>()
    override val disconnected: Deferred<Int> = _disconnected

    @Volatile private var pending: PendingOp? = null

    private val _notifications = MutableSharedFlow<CharacteristicNotification>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val notifications: Flow<CharacteristicNotification> = _notifications.asSharedFlow()

    private class PendingOp(val kind: OpKind, val uuid: UUID?) {
        val result = CompletableDeferred<ByteArray>()
    }

    private enum class OpKind { DISCOVER, MTU, READ, WRITE, DESCRIPTOR_WRITE }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "$address connection state=$newState status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val error = BleException(
                    if (connected.isCompleted) BleError.Disconnected else BleError.GattStatus("connect", status),
                )
                connected.completeExceptionally(error)
                pending?.result?.completeExceptionally(BleException(BleError.Disconnected))
                _disconnected.complete(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            complete(OpKind.DISCOVER, null, status, ByteArray(0))

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
            complete(OpKind.MTU, null, status, byteArrayOf(mtu.toByte(), (mtu shr 8).toByte()))

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = complete(OpKind.READ, characteristic.uuid, status, value)

        @Deprecated("Used below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            complete(OpKind.READ, characteristic.uuid, status, characteristic.value ?: ByteArray(0))

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            complete(OpKind.WRITE, characteristic.uuid, status, ByteArray(0))

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            complete(OpKind.DESCRIPTOR_WRITE, descriptor.characteristic.uuid, status, ByteArray(0))

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            _notifications.tryEmit(CharacteristicNotification(characteristic.uuid, value.copyOf()))
        }

        @Deprecated("Used below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            _notifications.tryEmit(CharacteristicNotification(characteristic.uuid, value.copyOf()))
        }
    }

    /** Completes the pending op only if the callback matches it, so a late callback can't satisfy a newer op. */
    private fun complete(kind: OpKind, uuid: UUID?, status: Int, value: ByteArray) {
        val op = pending ?: return
        if (op.kind != kind || (op.uuid != null && op.uuid != uuid)) {
            Log.w(TAG, "Ignoring unexpected $kind callback for $uuid while waiting for ${op.kind}")
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            op.result.complete(value)
        } else {
            op.result.completeExceptionally(BleException(BleError.GattStatus(kind.name, status)))
        }
    }

    private suspend fun execute(
        kind: OpKind,
        uuid: UUID?,
        timeoutMs: Long = OP_TIMEOUT_MS,
        start: () -> Boolean,
    ): ByteArray = opMutex.withLock {
        if (closed.get() || _disconnected.isCompleted) throw BleException(BleError.Disconnected)
        val op = PendingOp(kind, uuid)
        pending = op
        try {
            if (!start()) throw BleException(BleError.OperationRejected(kind.name))
            withTimeout(timeoutMs) { op.result.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException(BleError.Timeout(kind.name))
        } finally {
            pending = null
        }
    }

    override suspend fun discoverServices() {
        execute(OpKind.DISCOVER, null, DISCOVERY_TIMEOUT_MS) { gatt.discoverServices() }
        if (gatt.getService(FtmsUuids.SERVICE) == null) throw BleException(BleError.ServiceNotFound(FtmsUuids.SERVICE))
    }

    override suspend fun requestMtu(mtu: Int): Int {
        val bytes = execute(OpKind.MTU, null) { gatt.requestMtu(mtu) }
        return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    }

    override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt.getService(service)?.getCharacteristic(characteristic) != null

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        val c = characteristic(service, characteristic)
        return execute(OpKind.READ, characteristic) { gatt.readCharacteristic(c) }
    }

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val c = characteristic(service, characteristic)
        execute(OpKind.WRITE, characteristic) {
            writeCharacteristicCompat(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID, indicate: Boolean) {
        val c = characteristic(service, characteristic)
        if (!gatt.setCharacteristicNotification(c, true)) {
            throw BleException(BleError.OperationRejected("setCharacteristicNotification"))
        }
        val descriptor = c.getDescriptor(FtmsUuids.CCCD)
            ?: throw BleException(BleError.CharacteristicNotFound(FtmsUuids.CCCD))
        val value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        execute(OpKind.DESCRIPTOR_WRITE, characteristic) { writeDescriptorCompat(descriptor, value) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending?.result?.completeExceptionally(BleException(BleError.Disconnected))
        connected.completeExceptionally(BleException(BleError.Disconnected))
        if (::gatt.isInitialized) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        // close() unregisters the callback, so no disconnect callback will follow; signal it here.
        _disconnected.complete(BluetoothGatt.GATT_SUCCESS)
    }

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic {
        val s = gatt.getService(service) ?: throw BleException(BleError.ServiceNotFound(service))
        return s.getCharacteristic(characteristic) ?: throw BleException(BleError.CharacteristicNotFound(characteristic))
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(c: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(c, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = writeType
            c.value = value
            gatt.writeCharacteristic(c)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            gatt.writeDescriptor(d)
        }

    companion object {
        /**
         * Direct (non-autoConnect) LE connection. The callback only completes deferreds and emits
         * into a buffered flow, so running it inline on the binder thread is safe.
         */
        @Suppress("DEPRECATION")
        private fun connectGattCompat(context: Context, device: BluetoothDevice, callback: BluetoothGattCallback): BluetoothGatt? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                val settings = BluetoothGattConnectionSettings.Builder()
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .setAutoConnectEnabled(false)
                    .build()
                device.connectGatt(settings, Runnable::run, callback)
            } else {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }

        private const val TAG = "GattConnection"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val OP_TIMEOUT_MS = 5_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L

        /** Connects to [device]; on any failure the GATT client is closed before the exception propagates. */
        suspend fun connect(context: Context, device: BluetoothDevice): AndroidGattConnection {
            val connection = AndroidGattConnection(device.address)
            try {
                connection.gatt = connectGattCompat(context, device, connection.callback)
                    ?: throw BleException(BleError.OperationRejected("connectGatt"))
                withTimeout(CONNECT_TIMEOUT_MS) { connection.connected.await() }
                return connection
            } catch (e: TimeoutCancellationException) {
                connection.close()
                throw BleException(BleError.Timeout("connect"))
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }
    }
}
