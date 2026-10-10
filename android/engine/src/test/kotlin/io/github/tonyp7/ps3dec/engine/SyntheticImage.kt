package io.github.tonyp7.ps3dec.engine

/**
 * Deterministic PRNG with a trivial definition, so the same bytes can be produced by the
 * fixture-generation script and by these tests (SplitMix64, little-endian output).
 */
class SplitMix64(seed: Long) {
    private var state = seed

    private fun next(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    fun bytes(count: Int): ByteArray {
        val out = ByteArray(count)
        var i = 0
        while (i < count) {
            var word = next()
            var k = 0
            while (k < 8 && i < count) {
                out[i++] = word.toByte()
                word = word ushr 8
                k++
            }
        }
        return out
    }
}

/**
 * Synthetic PS3-format images, laid out as `PS3Dec.c` and `backend/tests/synth.py` describe them:
 * regions alternate plain, converted, plain, ... and end on a plain region; a plain region ends at
 * its table entry inclusive, a converted region stops one sector before its entry.
 */
object SyntheticImage {
    val ENCRYPTED_MARKER = "Encrypted 3K ISO".toByteArray(Charsets.US_ASCII)
    val DECRYPTED_MARKER = "Decrypted 3K ISO".toByteArray(Charsets.US_ASCII)
    val DEFAULT_D1: ByteArray = ByteArray(16) { (it * 0x11).toByte() }

    /** Region end sectors for regions of the given sector counts (plain, converted, plain, ...). */
    fun tableEntries(regions: List<Int>): List<Long> {
        require(regions.size % 2 == 1 && regions[0] >= 2) { "need plain/enc/.../plain, first plain >= 2" }
        val table = ArrayList<Long>()
        var start = 0L
        regions.forEachIndexed { i, count ->
            table += if (i % 2 == 0) start + count - 1 else start + count
            start += count
        }
        return table
    }

    /** A 2-sector header holding a valid region table. */
    fun headerOnly(regions: List<Int>): ByteArray {
        val head = ByteArray(HEADER_SIZE)
        putU32(head, 0, ((regions.size + 1) / 2).toLong())
        tableEntries(regions).forEachIndexed { i, entry -> putU32(head, TABLE_OFFSET + 4 * i, entry) }
        return head
    }

    /** A decrypted image; [marker] turns it into a 3k3y image in that state. */
    fun decrypted(
        regions: List<Int> = listOf(4, 6, 4),
        seed: Long = 0,
        marker: ByteArray? = null,
        d1: ByteArray = DEFAULT_D1,
    ): ByteArray {
        val data = SplitMix64(seed).bytes(regions.sum() * SECTOR_SIZE)
        headerOnly(regions).copyInto(data)
        if (marker != null) {
            require(marker.size == 16)
            marker.copyInto(data, MARKER_OFFSET)
            d1.copyInto(data, D1_OFFSET)
        } else {
            data[MARKER_OFFSET] = 0
        }
        return data
    }

    fun putU32(into: ByteArray, offset: Int, value: Long) {
        into[offset] = (value ushr 24).toByte()
        into[offset + 1] = (value ushr 16).toByte()
        into[offset + 2] = (value ushr 8).toByte()
        into[offset + 3] = value.toByte()
    }
}
