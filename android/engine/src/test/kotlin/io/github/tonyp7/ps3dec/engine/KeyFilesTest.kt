package io.github.tonyp7.ps3dec.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports of the parsing and suggestion cases in `backend/tests/test_keys.py`. */
class KeyFilesTest {
    private val hex = "00112233445566778899AABBCCDDEEFF"

    @Test
    fun `valid content parses to canonical upper case`() {
        val contents = listOf("$hex\n", hex, "  $hex  \r\n", "0x${hex.lowercase()}", "0X$hex", hex.lowercase())
        for (content in contents) {
            assertEquals(hex, KeyFiles.parseKey(content.toByteArray()), "content: ${content.trim()}")
        }
    }

    @Test
    fun `invalid content is rejected with a specific reason`() {
        val cases: List<Pair<ByteArray, String>> = listOf(
            hex.dropLast(1).toByteArray() to "found 31",
            "${hex}0".toByteArray() to "found 33",
            ("G" + hex.drop(1)).toByteArray() to "not hexadecimal",
            ByteArray(0) to "empty",
            "  \n".toByteArray() to "empty",
            "0x".toByteArray() to "empty",
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + hex.toByteArray() to "ASCII",
            ByteArray(1000) { 'A'.code.toByte() } to "larger",
        )
        for ((raw, fragment) in cases) {
            val e = assertFailsWith<KeyFormatException> { KeyFiles.parseKey(raw) }
            assertContains(e.message!!, fragment)
        }
    }

    @Test
    fun `a key file of exactly the size limit is still parsed`() {
        val padded = hex + " ".repeat(KeyFiles.MAX_KEY_FILE_BYTES - hex.length)
        assertEquals(KeyFiles.MAX_KEY_FILE_BYTES, padded.length)
        assertEquals(hex, KeyFiles.parseKey(padded.toByteArray()))
    }

    @Test
    fun `inspect reports validity and a reason but never the key`() {
        val good = KeyFiles.inspect("Game.dkey", hex.toByteArray())
        assertEquals(KeyInfo("Game.dkey", valid = true), good)
        val bad = KeyFiles.inspect("Other.DKEY", "nope".toByteArray())
        assertFalse(bad.valid)
        assertContains(bad.reason!!, "found 4")
        assertFalse(good.toString().contains(hex))
    }

    @Test
    fun `an unreadable key file is invalid with its reason`() {
        val info = KeyFiles.unreadable("a.dkey", "permission denied")
        assertFalse(info.valid)
        assertEquals("unreadable: permission denied", info.reason)
    }

    @Test
    fun `suggest matches the stem case-insensitively`() {
        val keys = listOf(KeyInfo("game.dkey", true))
        assertEquals("game.dkey", KeyFiles.suggest("Game.iso", keys))
        assertEquals("game.dkey", KeyFiles.suggest("Game.ISO", keys))
    }

    @Test
    fun `suggest prefers exact case and skips invalid and missing keys`() {
        val keys = listOf(
            KeyInfo("Game.dkey", true),
            KeyInfo("bad.dkey", false, "zz"),
            KeyInfo("game.dkey", true),
        )
        assertEquals("Game.dkey", KeyFiles.suggest("Game.iso", keys))
        assertNull(KeyFiles.suggest("bad.iso", keys))
        assertNull(KeyFiles.suggest("other.iso", keys))
    }

    @Test
    fun `suggest uses everything before the last dot as the stem`() {
        val keys = listOf(KeyInfo("Game.v1.dkey", true), KeyInfo("Game.dkey", true))
        assertEquals("Game.v1.dkey", KeyFiles.suggest("Game.v1.iso", keys))
        assertEquals("Game.dkey", KeyFiles.suggest("Game.iso", keys))
    }

    @Test
    fun `stem keeps names with a leading or trailing dot whole`() {
        assertEquals(".iso", KeyFiles.stem(".iso"))
        assertEquals("a.", KeyFiles.stem("a."))
        assertEquals("a", KeyFiles.stem("a.iso"))
        assertTrue(KeyFiles.stem("noext") == "noext")
    }
}
