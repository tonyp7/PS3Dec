package io.github.tonyp7.ps3dec.catalog

import io.github.tonyp7.ps3dec.engine.ImageKind
import io.github.tonyp7.ps3dec.engine.Mode
import io.github.tonyp7.ps3dec.engine.SyntheticImage
import io.github.tonyp7.ps3dec.storage.Entry
import io.github.tonyp7.ps3dec.storage.Folder
import io.github.tonyp7.ps3dec.storage.MemoryFolder
import java.io.IOException
import java.nio.channels.ReadableByteChannel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Listing, classification and key suggestion, as `backend/tests/test_api_lists.py` exercised them. */
class CatalogTest {
    private val hex = "00112233445566778899AABBCCDDEEFF"
    private val images = MemoryFolder()
    private val keyFolder = MemoryFolder()

    private fun std(seed: Long = 1) = SyntheticImage.decrypted(listOf(4, 6, 4), seed)

    @Test
    fun `images are listed with classification, size and the suggested key`() {
        images.put("Game.iso", std())
        images.put("k.ISO", SyntheticImage.decrypted(marker = SyntheticImage.ENCRYPTED_MARKER))
        images.put("notes.txt")
        keyFolder.put("game.dkey", hex.toByteArray())

        val keys = Catalog.keyFiles(keyFolder)
        val listed = Catalog.images(images, keys.items)

        assertTrue(listed.available)
        assertEquals(listOf("Game.iso", "k.ISO"), listed.items.map { it.name })
        val game = listed.items[0]
        assertEquals(ImageKind.STANDARD, game.kind)
        assertEquals(std().size.toLong(), game.size)
        assertEquals("game.dkey", game.suggestedKey?.name)
        val k = listed.items[1]
        assertEquals(ImageKind.THREE_K3Y_ENCRYPTED, k.kind)
        assertNull(k.suggestedKey) // 3k3y images need no key
    }

    @Test
    fun `invalid images are listed with their reason`() {
        images.put("junk.iso", ByteArray(5000))
        val item = Catalog.images(images, emptyList()).items.single()
        assertFalse(item.valid)
        assertContains(item.info.reason!!, "region table")
        assertNull(item.suggestedKey)
    }

    @Test
    fun `keys are listed by name without reading any of them`() {
        keyFolder.put("Game.dkey", hex.toByteArray())
        keyFolder.put("Other.DKEY", "nope".toByteArray())
        keyFolder.put("readme.txt")
        keyFolder.put("dir.dkey", directory = true)
        val reads = ArrayList<String>()
        val counting = object : Folder by keyFolder {
            override fun openRead(entry: Entry): ReadableByteChannel {
                reads += entry.name
                return keyFolder.openRead(entry)
            }
        }
        val keys = Catalog.keyFiles(counting)
        assertTrue(keys.available)
        assertEquals(listOf("Game.dkey", "Other.DKEY"), keys.items.map { it.name })
        assertEquals(emptyList(), reads) // a folder of thousands must not cost thousands of reads
    }

    @Test
    fun `a key is read and checked when it is inspected and never exposes the value`() {
        val good = keyFolder.put("Game.dkey", hex.toByteArray())
        val bad = keyFolder.put("Other.DKEY", "nope".toByteArray())
        val a = Catalog.inspectKey(keyFolder, good)
        val b = Catalog.inspectKey(keyFolder, bad)
        assertTrue(a.valid)
        assertFalse(b.valid)
        assertNotNull(b.info.reason)
        assertFalse(a.toString().contains(hex))
    }

    @Test
    fun `an unreadable key is reported as invalid when inspected`() {
        val entry = keyFolder.put("Game.dkey", hex.toByteArray())
        val flaky = object : Folder by keyFolder {
            override fun openRead(entry: Entry): ReadableByteChannel = throw IOException("read failed: EIO")
        }
        val item = Catalog.inspectKey(flaky, entry)
        assertFalse(item.valid)
        assertContains(item.info.reason!!, "unreadable")
    }

    @Test
    fun `a key is suggested by name before it has been checked`() {
        images.put("bad.iso", std())
        keyFolder.put("bad.dkey", "zz".toByteArray())
        val item = Catalog.images(images, Catalog.keyFiles(keyFolder).items).items.single()
        assertEquals("bad.dkey", item.suggestedKey?.name) // validity is only known once it is inspected
    }

    @Test
    fun `an unavailable folder is reported, not listed as empty`() {
        images.unavailable = true
        val listed = Catalog.images(images, emptyList())
        assertFalse(listed.available)
        assertNotNull(listed.problem)
        assertEquals(emptyList(), listed.items)
        assertTrue(Catalog.images(MemoryFolder(), emptyList()).available) // an empty folder is available
    }

    @Test
    fun `an image that cannot be read is invalid instead of failing the whole listing`() {
        images.put("a.iso", std())
        images.put("b.iso", std(2))
        val flaky = object : Folder by images {
            override fun openRead(entry: Entry): ReadableByteChannel =
                if (entry.name == "a.iso") throw IOException("read failed: EIO") else images.openRead(entry)
        }
        val items = Catalog.images(flaky, emptyList()).items
        assertFalse(items[0].valid)
        assertContains(items[0].info.reason!!, "unreadable")
        assertTrue(items[1].valid)
    }

    // -- selection rules -----------------------------------------------------------------------

    private fun item(kind: ImageKind, valid: Boolean = true): ImageItem {
        val data = when (kind) {
            ImageKind.THREE_K3Y_ENCRYPTED -> SyntheticImage.decrypted(marker = SyntheticImage.ENCRYPTED_MARKER)
            ImageKind.THREE_K3Y_DECRYPTED -> SyntheticImage.decrypted(marker = SyntheticImage.DECRYPTED_MARKER)
            else -> if (valid) std() else ByteArray(5000)
        }
        val folder = MemoryFolder().also { it.put("x.iso", data) }
        return Catalog.images(folder, emptyList()).items.single()
    }

    private fun key(valid: Boolean = true): KeyItem {
        val folder = MemoryFolder()
        val entry = folder.put("x.dkey", (if (valid) hex else "no").toByteArray())
        return Catalog.inspectKey(folder, entry)
    }

    @Test
    fun `mode defaults follow the image state`() {
        assertEquals(Mode.DECRYPT, SelectionRules.defaultMode(ImageKind.STANDARD))
        assertEquals(Mode.DECRYPT, SelectionRules.defaultMode(ImageKind.THREE_K3Y_ENCRYPTED))
        assertEquals(Mode.ENCRYPT, SelectionRules.defaultMode(ImageKind.THREE_K3Y_DECRYPTED))
    }

    @Test
    fun `the key control applies to standard images only and says why otherwise`() {
        assertTrue(SelectionRules.usesKey(ImageKind.STANDARD))
        assertNull(SelectionRules.keyDisabledReason(ImageKind.STANDARD))
        assertContains(SelectionRules.keyDisabledReason(ImageKind.THREE_K3Y_ENCRYPTED)!!, "own key")
    }

    @Test
    fun `start is allowed only when nothing blocks it`() {
        val standard = item(ImageKind.STANDARD)
        assertNull(SelectionRules.startBlockedReason(standard, Mode.DECRYPT, key(), jobRunning = false))
        assertNull(SelectionRules.startBlockedReason(standard, Mode.ENCRYPT, key(), jobRunning = false))
        assertNull(SelectionRules.startBlockedReason(item(ImageKind.THREE_K3Y_ENCRYPTED), Mode.DECRYPT, null, false))
    }

    @Test
    fun `each blocking condition gives its own visible reason`() {
        val standard = item(ImageKind.STANDARD)
        assertContains(SelectionRules.startBlockedReason(null, Mode.DECRYPT, null, false)!!, "Choose an image")
        assertContains(SelectionRules.startBlockedReason(standard, Mode.DECRYPT, null, false)!!, "Choose a key")
        assertContains(SelectionRules.startBlockedReason(standard, Mode.DECRYPT, key(valid = false), false)!!, "not valid")
        assertContains(SelectionRules.startBlockedReason(standard, Mode.DECRYPT, key(), jobRunning = true)!!, "already running")
        assertContains(
            SelectionRules.startBlockedReason(standard, Mode.DECRYPT, null, false, keyPending = true)!!,
            "Checking the key",
        )
        assertContains(
            SelectionRules.startBlockedReason(item(ImageKind.THREE_K3Y_DECRYPTED), Mode.DECRYPT, null, false)!!,
            "already decrypted",
        )
        assertContains(
            SelectionRules.startBlockedReason(item(ImageKind.THREE_K3Y_ENCRYPTED), Mode.ENCRYPT, null, false)!!,
            "already encrypted",
        )
        assertContains(
            SelectionRules.startBlockedReason(item(ImageKind.INVALID, valid = false), Mode.DECRYPT, key(), false)!!,
            "cannot be used",
        )
    }
}
