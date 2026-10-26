package com.example.ftmstreadmill.data

import app.cash.turbine.test
import com.example.ftmstreadmill.ble.BleError
import com.example.ftmstreadmill.ftms.FtmsUuids
import com.example.ftmstreadmill.ftms.bytes
import com.example.ftmstreadmill.ftms.model.ControlResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TreadmillRepositoryTest {

    private val device = DeviceInfo("AA:BB:CC:DD:EE:FF", "Treadmill")
    private val ble = FakeBleClient()

    private fun TestScope.repository() =
        TreadmillRepository(ble, SettingsRepository(InMemoryPreferencesDataStore()), backgroundScope)

    @Test
    fun `connect initializes FTMS and requests control`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()

        assertEquals(ConnectionState.Ready(device, hasControl = true), repo.connectionState.value)
        val conn = ble.connections.single()
        assertTrue(conn.enabled.containsAll(listOf(FtmsUuids.TREADMILL_DATA, FtmsUuids.MACHINE_STATUS, FtmsUuids.CONTROL_POINT)))
        assertArrayEquals(bytes(0x00), conn.controlWrites.first())
        assertEquals(20.0, repo.treadmillState.value.speedRange.max, 1e-9)
    }

    @Test
    fun `treadmill data notifications update state`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()

        ble.connections.single().notify(FtmsUuids.TREADMILL_DATA, bytes(0x00, 0x00, 0xB0, 0x04))
        runCurrent()

        assertEquals(12.0, repo.treadmillState.value.data.speedKmh!!, 1e-9)
        assertTrue(repo.treadmillState.value.isBeltMoving)
    }

    @Test
    fun `setSpeed clamps to supported range and encodes`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()

        val outcome = repo.setSpeed(42.0)

        assertEquals(CommandOutcome.Success, outcome)
        assertArrayEquals(bytes(0x02, 0xD0, 0x07), ble.connections.single().controlWrites.last()) // 20.00 km/h
        assertEquals(20.0, repo.treadmillState.value.targetSpeedKmh!!, 1e-9)
    }

    @Test
    fun `rejected command is reported`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()
        ble.connections.single().defaultControlResult = ControlResult.NOT_SUPPORTED

        assertEquals(CommandOutcome.Rejected(ControlResult.NOT_SUPPORTED), repo.setIncline(5.0))
    }

    @Test
    fun `missing control point response times out`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()
        ble.connections.single().respondToControl = false

        val outcome = repo.start()

        assertEquals(CommandOutcome.Failed(BleError.Timeout("control point response")), outcome)
    }

    @Test
    fun `control not permitted re-requests control and retries`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()
        val conn = ble.connections.single()
        conn.controlResults[0x07] = ArrayDeque(listOf(ControlResult.CONTROL_NOT_PERMITTED))

        assertEquals(CommandOutcome.Success, repo.start())
        // RequestControl (initial), Start (rejected), RequestControl, Start
        assertEquals(listOf(0x00, 0x07, 0x00, 0x07), conn.controlWrites.map { it[0].toInt() })
    }

    @Test
    fun `commands while disconnected report NotConnected`() = runTest {
        assertEquals(CommandOutcome.NotConnected, repository().stop())
    }

    @Test
    fun `initial connection gives up after max attempts`() = runTest {
        val repo = repository()
        ble.failNextConnects(TreadmillRepository.MAX_INITIAL_ATTEMPTS)

        repo.connect(device)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(TreadmillRepository.MAX_INITIAL_ATTEMPTS, ble.connectCalls)
        assertEquals(ConnectionState.Failed(device, BleError.GattStatus("connect", 133)), repo.connectionState.value)
    }

    @Test
    fun `transient failure then success connects`() = runTest {
        val repo = repository()
        ble.failNextConnects(1)

        repo.connect(device)
        runCurrent()
        assertTrue(repo.connectionState.value is ConnectionState.Reconnecting)

        advanceTimeBy(TreadmillRepository.INITIAL_RETRY_DELAY_MS + 1)
        runCurrent()
        assertTrue(repo.connectionState.value is ConnectionState.Ready)
    }

    @Test
    fun `missing permission fails without retry`() = runTest {
        val repo = repository()
        ble.permissionsGranted = false

        repo.connect(device)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, ble.connectCalls)
        assertEquals(ConnectionState.Failed(device, BleError.PermissionMissing), repo.connectionState.value)
    }

    @Test
    fun `unexpected drop reconnects with events`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()
        val first = ble.connections.single()
        first.notify(FtmsUuids.TREADMILL_DATA, bytes(0x00, 0x00, 0xF4, 0x01)) // 5 km/h
        runCurrent()

        repo.events.test {
            first.dropLink()
            runCurrent()
            assertEquals(TreadmillEvent.ConnectionLost(beltWasMoving = true), awaitItem())
            assertTrue(first.closed)
            assertEquals(
                ConnectionState.Reconnecting(device, attempt = 1, delayMs = TreadmillRepository.reconnectBackoffMs(1)),
                repo.connectionState.value,
            )

            advanceTimeBy(TreadmillRepository.reconnectBackoffMs(1) + 1)
            runCurrent()
            assertEquals(TreadmillEvent.Reconnected, awaitItem())
            assertEquals(ConnectionState.Ready(device, hasControl = true), repo.connectionState.value)
            assertEquals(2, ble.connections.size)
        }
    }

    @Test
    fun `adapter off waits for adapter and reconnects`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()
        val first = ble.connections.single()

        ble.adapterEnabled.value = false
        runCurrent()
        assertTrue(first.closed)
        assertEquals(ConnectionState.BluetoothOff(device), repo.connectionState.value)

        advanceTimeBy(120_000)
        assertEquals(1, ble.connectCalls) // no connection attempts while the adapter is off

        ble.adapterEnabled.value = true
        advanceTimeBy(TreadmillRepository.ADAPTER_SETTLE_MS + 1)
        runCurrent()
        assertEquals(ConnectionState.Ready(device, hasControl = true), repo.connectionState.value)
    }

    @Test
    fun `user disconnect closes link and does not reconnect`() = runTest {
        val repo = repository()
        repo.connect(device)
        runCurrent()

        repo.disconnect()
        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(ble.connections.single().closed)
        assertEquals(1, ble.connectCalls)
        assertEquals(ConnectionState.Idle, repo.connectionState.value)
    }

    @Test
    fun `control not granted still connects in monitor mode`() = runTest {
        val repo = repository()
        ble.connectQueue.addLast {
            FakeGattConnection().apply { controlResults[0x00] = ArrayDeque(listOf(ControlResult.CONTROL_NOT_PERMITTED)) }
        }

        repo.events.test {
            repo.connect(device)
            runCurrent()
            assertEquals(TreadmillEvent.ControlNotGranted, awaitItem())
        }
        assertEquals(ConnectionState.Ready(device, hasControl = false), repo.connectionState.value)
    }

    @Test
    fun `backoff grows and caps`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map(TreadmillRepository::reconnectBackoffMs))
    }
}
