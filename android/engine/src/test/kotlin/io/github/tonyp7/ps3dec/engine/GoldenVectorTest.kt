package io.github.tonyp7.ps3dec.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine's output must be byte-identical to the reference C tool's. The expected hashes in
 * `golden-vectors.tsv` were produced by running that tool (see `android/tools/golden_vectors.py`);
 * the images are rebuilt here from their seeds.
 *
 * A failure here is a disagreement between two implementations. It must be investigated, and
 * the expected hash must not be regenerated until it is understood which one is right.
 */
class GoldenVectorTest {
    class Vector(
        val name: String,
        val regions: List<Int>,
        val seed: Long,
        val marker: ByteArray?,
        val mode: Mode,
        val d1: ByteArray?,
        val discKey: ByteArray?,
        val inputSha: String,
        val outputSha: String,
    )

    companion object {
        private fun keyOf(column: String, type: String): ByteArray? =
            column.removePrefixOrNull("$type:")?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

        private fun String.removePrefixOrNull(prefix: String): String? =
            if (startsWith(prefix)) substring(prefix.length) else null

        fun load(): List<Vector> {
            val text = GoldenVectorTest::class.java.getResourceAsStream("/golden-vectors.tsv")!!
                .bufferedReader().readText()
            return text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val f = line.split('\t')
                    require(f.size == 8) { "bad golden vector line: $line" }
                    Vector(
                        name = f[0],
                        regions = f[1].split(',').map(String::toInt),
                        seed = f[2].toLong(),
                        marker = when (f[3]) {
                            "-" -> null
                            "encrypted" -> SyntheticImage.ENCRYPTED_MARKER
                            "decrypted" -> SyntheticImage.DECRYPTED_MARKER
                            else -> error("unknown marker ${f[3]}")
                        },
                        mode = if (f[4] == "encrypt") Mode.ENCRYPT else Mode.DECRYPT,
                        d1 = keyOf(f[5], "d1"),
                        discKey = keyOf(f[5], "key"),
                        inputSha = f[6],
                        outputSha = f[7],
                    )
                }
                .toList()
        }
    }

    @Test
    fun `the fixture file is not empty and covers both directions`() {
        val vectors = load()
        assertTrue(vectors.size >= 20, "only ${vectors.size} vectors")
        assertTrue(vectors.any { it.mode == Mode.ENCRYPT && it.marker == null })
        assertTrue(vectors.any { it.mode == Mode.DECRYPT && it.marker == null })
        assertTrue(vectors.any { it.mode == Mode.ENCRYPT && it.marker != null })
        assertTrue(vectors.any { it.mode == Mode.DECRYPT && it.marker != null })
        assertTrue(vectors.any { it.discKey != null }, "no vector with a disc key")
        assertTrue(vectors.any { it.d1 != null }, "no vector with a D1")
        assertTrue(vectors.any { v -> v.regions.any { it in 4095..4097 } }, "no layout around the block size")
    }

    @Test
    fun `every vector matches the reference tool at every pool size`() {
        // The output must not depend on how many threads convert sectors.
        val poolSizes = listOf(1, 2, 3, ImageConverter.defaultParallelism()).distinct()
        val failures = ArrayList<String>()
        for (v in load()) {
            val input = SyntheticImage.decrypted(v.regions, v.seed, v.marker)
            if (sha256(input) != v.inputSha) {
                failures += "${v.name}: generated input differs from the recorded one (generator drift)"
                continue
            }
            for (pool in poolSizes) {
                val outputSha = sha256(convert(input, v.mode, d1 = v.d1, discKey = v.discKey, parallelism = pool))
                if (outputSha != v.outputSha) {
                    failures += "${v.name} [pool $pool]: output ${outputSha.take(16)} != reference ${v.outputSha.take(16)}"
                }
            }
        }
        assertEquals(emptyList(), failures, "engine disagrees with the reference tool on ${failures.size} case(s)")
    }
}
