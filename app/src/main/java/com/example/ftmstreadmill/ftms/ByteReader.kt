package com.example.ftmstreadmill.ftms

/** Sequential little-endian reader for GATT payloads. Throws [FtmsParseException] on underflow. */
internal class ByteReader(private val bytes: ByteArray) {
    var offset = 0
        private set

    val remaining: Int get() = bytes.size - offset

    fun uint8(): Int {
        require(1)
        return bytes[offset++].toInt() and 0xFF
    }

    fun uint16(): Int {
        require(2)
        val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
        return value
    }

    fun sint16(): Int = uint16().toShort().toInt()

    fun uint24(): Int {
        require(3)
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
        offset += 3
        return value
    }

    fun uint32(): Long {
        val low = uint16().toLong()
        val high = uint16().toLong()
        return low or (high shl 16)
    }

    private fun require(count: Int) {
        if (remaining < count) {
            throw FtmsParseException("Need $count byte(s) at offset $offset but only $remaining remain")
        }
    }
}

class FtmsParseException(message: String) : IllegalArgumentException(message)
