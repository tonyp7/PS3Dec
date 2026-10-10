package io.github.tonyp7.ps3dec.storage

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderRelationTest {
    private val auth = "com.android.externalstorage.documents"
    private fun tree(id: String, authority: String = auth) = DocumentTree(authority, id)

    @Test
    fun `the same folder overlaps itself`() {
        assertTrue(FolderRelation.overlaps(tree("primary:PS3/iso"), tree("primary:PS3/iso")))
        assertTrue(FolderRelation.overlaps(tree("primary:PS3/iso"), tree("primary:PS3/iso/")))
    }

    @Test
    fun `a folder inside or containing another overlaps it`() {
        assertTrue(FolderRelation.overlaps(tree("primary:PS3"), tree("primary:PS3/iso")))
        assertTrue(FolderRelation.overlaps(tree("primary:PS3/iso"), tree("primary:PS3")))
        assertTrue(FolderRelation.overlaps(tree("primary:"), tree("primary:PS3/iso")))
    }

    @Test
    fun `siblings and name prefixes do not overlap`() {
        assertFalse(FolderRelation.overlaps(tree("primary:PS3/iso"), tree("primary:PS3/out")))
        assertFalse(FolderRelation.overlaps(tree("primary:PS3/iso"), tree("primary:PS3/iso2")))
        assertFalse(FolderRelation.overlaps(tree("primary:PS3"), tree("primary:PS3Dec")))
    }

    @Test
    fun `different volumes or providers do not overlap`() {
        assertFalse(FolderRelation.overlaps(tree("primary:PS3"), tree("1234-ABCD:PS3")))
        assertFalse(FolderRelation.overlaps(tree("primary:PS3"), tree("primary:PS3", "com.example.docs")))
    }
}

class ListingTest {
    private fun e(name: String, dir: Boolean = false, size: Long = 10) = Entry("id-$name", name, size, dir)

    @Test
    fun `only regular files with the extension are listed, case-insensitively, sorted by name`() {
        val entries = listOf(
            e("b.ISO"), e("A.iso"), e("notes.txt"), e("dir.iso", dir = true), e("c.iso.bak"), e("a.iso"),
        )
        assertEquals(
            listOf("A.iso", "a.iso", "b.ISO"),
            Listing.withExtension(entries, Listing.IMAGE_EXTENSION).map { it.name },
        )
    }

    @Test
    fun `keys are listed by their own extension`() {
        val entries = listOf(e("Game.dkey"), e("Other.DKEY"), e("readme.txt"), e("dir.dkey", dir = true))
        assertEquals(
            listOf("Game.dkey", "Other.DKEY"),
            Listing.withExtension(entries, Listing.KEY_EXTENSION).map { it.name },
        )
    }

    @Test
    fun `an empty folder lists nothing`() {
        assertEquals(emptyList(), Listing.withExtension(emptyList(), Listing.IMAGE_EXTENSION))
    }

    @Test
    fun `an item is resolved only by an id from the current listing`() {
        val listing = listOf(e("Game.iso"))
        assertEquals("Game.iso", Listing.resolve(listing, "id-Game.iso", "image").name)
        for (bad in listOf("../../etc/passwd", "sub/game.iso", "..", "", "Game.iso", "missing")) {
            val ex = assertFailsWith<StorageException>("id $bad") { Listing.resolve(listing, bad, "image") }
            assertEquals(StorageException.Code.NOT_FOUND, ex.code)
        }
    }

    @Test
    fun `listing reflects changes without restarting`() {
        val folder = MemoryFolder()
        assertEquals(emptyList(), Listing.withExtension(folder.list(), ".iso"))
        folder.put("x.iso")
        assertEquals(listOf("x.iso"), Listing.withExtension(folder.list(), ".iso").map { it.name })
        folder.delete(folder.list().first())
        assertEquals(emptyList(), Listing.withExtension(folder.list(), ".iso"))
    }
}

class PreFlightTest {
    private val listing = listOf(Entry("id-1", "Game.iso", 4096))

    @Test
    fun `an unchanged input passes`() {
        assertEquals("Game.iso", PreFlight.checkInput(listing, "id-1", 4096).name)
    }

    @Test
    fun `an input that changed size is refused with both sizes`() {
        val e = assertFailsWith<StorageException> { PreFlight.checkInput(listing, "id-1", 8192) }
        assertEquals(StorageException.Code.INPUT_CHANGED, e.code)
        assertContains(e.message!!, "8.0 KiB")
        assertContains(e.message!!, "4.0 KiB")
    }

    @Test
    fun `an input that disappeared is refused`() {
        val e = assertFailsWith<StorageException> { PreFlight.checkInput(emptyList(), "id-1", 4096) }
        assertEquals(StorageException.Code.NOT_FOUND, e.code)
    }

    @Test
    fun `insufficient free space is refused and says how much is needed`() {
        val e = assertFailsWith<StorageException> { PreFlight.checkSpace(5L shl 30, 2L shl 30) }
        assertEquals(StorageException.Code.INSUFFICIENT_SPACE, e.code)
        assertContains(e.message!!, "need 5.0 GiB, have 2.0 GiB")
    }

    @Test
    fun `enough or unknown free space passes`() {
        PreFlight.checkSpace(100, 100)
        PreFlight.checkSpace(100, null)
    }

    @Test
    fun `file too large is explained as a filesystem limit`() {
        val failure = IOException("write failed: EFBIG (File too large)")
        val e = assertNotNull(PreFlight.explain(RuntimeException("wrapped", failure)))
        assertEquals(StorageException.Code.FILE_TOO_LARGE, e.code)
        assertContains(e.message!!, "maximum file size")
        assertContains(e.message!!, "FAT32")
    }

    @Test
    fun `out of space and other storage failures are explained`() {
        assertEquals(
            StorageException.Code.INSUFFICIENT_SPACE,
            PreFlight.explain(IOException("write failed: ENOSPC (No space left on device)"))!!.code,
        )
        assertEquals(
            StorageException.Code.NOT_WRITABLE,
            PreFlight.explain(IOException("open failed: EROFS (Read-only file system)"))!!.code,
        )
        assertEquals(
            StorageException.Code.UNAVAILABLE,
            PreFlight.explain(IOException("read failed: ENODEV (No such device)"))!!.code,
        )
    }

    @Test
    fun `unrecognised failures are not explained`() {
        assertNull(PreFlight.explain(IOException("something odd")))
        assertNull(PreFlight.explain(IllegalStateException("not io")))
    }
}
