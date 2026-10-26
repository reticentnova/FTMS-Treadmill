package com.example.ftmstreadmill.ui

import com.example.ftmstreadmill.ble.ScannedDevice
import com.example.ftmstreadmill.data.CommandOutcome
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.data.DeviceInfo
import com.example.ftmstreadmill.data.InMemoryPreferencesDataStore
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.data.TreadmillEvent
import com.example.ftmstreadmill.data.TreadmillState
import com.example.ftmstreadmill.ftms.model.TreadmillData
import com.example.ftmstreadmill.ui.control.ControlViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val device = DeviceInfo("AA:BB:CC:DD:EE:FF", "Treadmill")
    private val controller = FakeController()
    private val settings = SettingsRepository(InMemoryPreferencesDataStore())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `rapid speed taps are coalesced into one command`() = runTest(dispatcher) {
        controller.connectionState.value = ConnectionState.Ready(device, hasControl = true)
        controller.treadmillState.value = TreadmillState(data = TreadmillData(speedKmh = 5.0))
        settings.setUseImperialUnits(false)
        val vm = ControlViewModel(controller, settings)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        repeat(3) { vm.adjustSpeed(1) }
        runCurrent()
        assertEquals(5.3, vm.uiState.value.pendingSpeedKmh!!, 1e-9)
        assertTrue(controller.speedCommands.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(5.3), controller.speedCommands.map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun `connection lost while moving shows safety warning until reconnected`() = runTest(dispatcher) {
        val vm = ControlViewModel(controller, settings)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        controller.connectionState.value = ConnectionState.Reconnecting(device, 1, 1_000)
        controller.events.emit(TreadmillEvent.ConnectionLost(beltWasMoving = true))
        runCurrent()
        assertTrue(vm.uiState.value.showSafetyWarning)

        controller.connectionState.value = ConnectionState.Ready(device, hasControl = true)
        controller.events.emit(TreadmillEvent.Reconnected)
        runCurrent()
        assertFalse(vm.uiState.value.showSafetyWarning)
    }

    @Test
    fun `auto-connects to last device once permissions are granted`() = runTest(dispatcher) {
        settings.setLastDevice(device)
        val vm = ControlViewModel(controller, settings)

        vm.onPermissionsGranted()
        vm.onPermissionsGranted()
        advanceUntilIdle()

        assertEquals(listOf(device), controller.connectCalls)
    }

    private class FakeController : TreadmillController {
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val treadmillState = MutableStateFlow(TreadmillState())
        override val events = MutableSharedFlow<TreadmillEvent>()
        val speedCommands = mutableListOf<Double>()
        val connectCalls = mutableListOf<DeviceInfo>()

        override fun scan(): Flow<ScannedDevice> = emptyFlow()
        override fun connect(device: DeviceInfo) {
            connectCalls += device
        }
        override fun disconnect() = Unit
        override suspend fun start() = CommandOutcome.Success
        override suspend fun pause() = CommandOutcome.Success
        override suspend fun stop() = CommandOutcome.Success
        override suspend fun setSpeed(kmh: Double): CommandOutcome {
            speedCommands += kmh
            return CommandOutcome.Success
        }
        override suspend fun setIncline(percent: Double) = CommandOutcome.Success
    }
}
