package io.github.tonyp7.ps3dec.engine

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Direction of a conversion. */
enum class Mode { DECRYPT, ENCRYPT }

internal object DiscCrypto {
    const val KEY_SIZE = 16

    private const val TRANSFORMATION = "AES/CBC/NoPadding"

    /*
     * The fixed key and IV that turn a D1 value into the disc key (the reference tool's `key_d1`
     * and `iv_d1`). Written in hex; the C source mixes decimal and hex literals
     * (11 = 0x0B, 60 = 0x3C, 120 = 0x78, 170 = 0xAA).
     */
    private val D1_KEY = intArrayOf(
        0x38, 0x0B, 0xCF, 0x0B, 0x53, 0x45, 0x5B, 0x3C, 0x78, 0x17, 0xAB, 0x4F, 0xA3, 0xBA, 0x90, 0xED,
    ).toBytes()
    private val D1_IV = intArrayOf(
        0x69, 0x47, 0x47, 0x72, 0xAF, 0x6F, 0xDA, 0xB3, 0x42, 0x74, 0x3A, 0xEF, 0xAA, 0x18, 0x62, 0x87,
    ).toBytes()

    private fun IntArray.toBytes() = ByteArray(size) { this[it].toByte() }

    /** The disc key for a 16-byte D1: one AES-128-CBC encryption block under the fixed key and IV. */
    fun discKeyFor(d1: ByteArray): ByteArray {
        require(d1.size == KEY_SIZE) { "D1 must be $KEY_SIZE bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(D1_KEY, "AES"), IvParameterSpec(D1_IV))
        return cipher.doFinal(d1)
    }
}

/**
 * AES-128-CBC over whole sectors, each with its own IV (12 zero bytes followed by the sector's
 * absolute number, big-endian). Instances are not thread-safe: use one per worker.
 */
internal class SectorCipher(discKey: ByteArray, mode: Mode) {
    private val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    private val key = SecretKeySpec(discKey, "AES")
    private val opmode = if (mode == Mode.ENCRYPT) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
    private val iv = ByteArray(16)

    /** Convert [sectors] sectors in place, starting at [offset], the first being sector [firstLba]. */
    fun process(buffer: ByteArray, offset: Int, sectors: Int, firstLba: Long) {
        for (k in 0 until sectors) {
            val lba = firstLba + k
            iv[12] = (lba ushr 24).toByte()
            iv[13] = (lba ushr 16).toByte()
            iv[14] = (lba ushr 8).toByte()
            iv[15] = lba.toByte()
            cipher.init(opmode, key, IvParameterSpec(iv))
            val at = offset + k * SECTOR_SIZE
            cipher.doFinal(buffer, at, SECTOR_SIZE, buffer, at)
        }
    }
}
