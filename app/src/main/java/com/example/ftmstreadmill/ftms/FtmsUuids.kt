package com.example.ftmstreadmill.ftms

import java.util.UUID

/** GATT identifiers from the Bluetooth SIG Fitness Machine Service (FTMS) specification. */
object FtmsUuids {
    val SERVICE: UUID = sig(0x1826)

    val FEATURE: UUID = sig(0x2ACC)
    val TREADMILL_DATA: UUID = sig(0x2ACD)
    val TRAINING_STATUS: UUID = sig(0x2AD3)
    val SUPPORTED_SPEED_RANGE: UUID = sig(0x2AD4)
    val SUPPORTED_INCLINATION_RANGE: UUID = sig(0x2AD5)
    val CONTROL_POINT: UUID = sig(0x2AD9)
    val MACHINE_STATUS: UUID = sig(0x2ADA)

    /** Client Characteristic Configuration Descriptor, used to enable notifications/indications. */
    val CCCD: UUID = sig(0x2902)

    private fun sig(shortUuid: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(shortUuid))
}
