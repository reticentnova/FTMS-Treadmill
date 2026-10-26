package com.example.ftmstreadmill.ftms.model

/** Events reported via the Fitness Machine Status characteristic. */
sealed interface MachineStatus {
    data object Reset : MachineStatus
    data object StoppedByUser : MachineStatus
    data object PausedByUser : MachineStatus
    data object StoppedBySafetyKey : MachineStatus
    data object StartedOrResumedByUser : MachineStatus
    data class TargetSpeedChanged(val speedKmh: Double) : MachineStatus
    data class TargetInclineChanged(val inclinePercent: Double) : MachineStatus
    data object ControlPermissionLost : MachineStatus
    data class Other(val opCode: Int) : MachineStatus
}

/** Training Status characteristic values. */
enum class TrainingStatus(val code: Int) {
    OTHER(0x00),
    IDLE(0x01),
    WARMING_UP(0x02),
    LOW_INTENSITY_INTERVAL(0x03),
    HIGH_INTENSITY_INTERVAL(0x04),
    RECOVERY_INTERVAL(0x05),
    ISOMETRIC(0x06),
    HEART_RATE_CONTROL(0x07),
    FITNESS_TEST(0x08),
    SPEED_TOO_LOW(0x09),
    SPEED_TOO_HIGH(0x0A),
    COOL_DOWN(0x0B),
    WATT_CONTROL(0x0C),
    MANUAL_MODE(0x0D),
    PRE_WORKOUT(0x0E),
    POST_WORKOUT(0x0F);

    companion object {
        fun fromCode(code: Int): TrainingStatus = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/** Result codes from a Fitness Machine Control Point response (op code 0x80). */
enum class ControlResult(val code: Int) {
    SUCCESS(0x01),
    NOT_SUPPORTED(0x02),
    INVALID_PARAMETER(0x03),
    OPERATION_FAILED(0x04),
    CONTROL_NOT_PERMITTED(0x05),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): ControlResult = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
