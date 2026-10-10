package io.github.tonyp7.ps3dec.engine

/** Size of one disc sector in bytes. */
const val SECTOR_SIZE = 2048

/** The header every operation looks at first: sectors 0 and 1. */
const val HEADER_SIZE = 2 * SECTOR_SIZE

/** Offset of the 16-byte 3k3y state marker (`Encrypted 3K ISO` / `Decrypted 3K ISO`). */
const val MARKER_OFFSET = 0xF70

/** Offset of the 16-byte D1 value that 3k3y images carry. */
const val D1_OFFSET = 0xF80

/** Offset of the first region end sector in the header. */
const val TABLE_OFFSET = 12

/** Big-endian unsigned 32-bit value at [offset]. */
internal fun ByteArray.u32(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)
