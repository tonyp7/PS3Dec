package io.github.tonyp7.ps3dec.storage

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputTest {
    private fun bytes(s: String) = s.toByteArray()

    private fun write(session: OutputSession, text: String) {
        session.channel.write(ByteBuffer.wrap(bytes(text)))
    }

    private fun code(block: () -> Unit): StorageException.Code =
        assertFailsWith<StorageException> { block() }.code

    @Test
    fun `output is written to a part file and moved to its name on commit`() {
        val folder = MemoryFolder()
        val session = Output.begin(folder, "Game.iso", overwrite = false)
        assertEquals(listOf("Game.iso$PART_SUFFIX"), folder.names)
        write(session, "converted")
        assertEquals(9L, session.partSize())

        val done = session.commit()

        assertEquals("Game.iso", done.name)
        assertEquals(listOf("Game.iso"), folder.names)
        assertContentEquals(bytes("converted"), folder.content("Game.iso"))
    }

    @Test
    fun `a part file never ends in iso`() {
        assertTrue(!"Game.iso$PART_SUFFIX".endsWith(".iso", ignoreCase = true))
    }

    @Test
    fun `an existing output is refused unless overwriting was confirmed`() {
        val folder = MemoryFolder()
        folder.put("Game.iso", bytes("old"))
        assertEquals(StorageException.Code.OUTPUT_EXISTS, code { Output.begin(folder, "Game.iso", overwrite = false) })
        assertEquals(listOf("Game.iso"), folder.names) // no temporary file was left
        assertContentEquals(bytes("old"), folder.content("Game.iso"))
    }

    @Test
    fun `a directory with the output name is refused even when overwriting`() {
        val folder = MemoryFolder()
        folder.put("Game.iso", directory = true)
        assertEquals(StorageException.Code.OUTPUT_EXISTS, code { Output.begin(folder, "Game.iso", overwrite = true) })
    }

    @Test
    fun `overwriting keeps the original until the new file is complete, then replaces it`() {
        val folder = MemoryFolder()
        folder.put("Game.iso", bytes("old"))
        val session = Output.begin(folder, "Game.iso", overwrite = true)
        write(session, "new content")
        assertContentEquals(bytes("old"), folder.content("Game.iso")) // untouched while writing

        session.commit()

        assertEquals(listOf("Game.iso"), folder.names) // no .old left behind
        assertContentEquals(bytes("new content"), folder.content("Game.iso"))
    }

    @Test
    fun `if moving the new file into place fails the original is restored`() {
        val folder = MemoryFolder()
        folder.put("Game.iso", bytes("old"))
        val session = Output.begin(folder, "Game.iso", overwrite = true)
        write(session, "new")
        folder.failRename = { from, to -> from.endsWith(PART_SUFFIX) && to == "Game.iso" }

        val e = assertFailsWith<StorageException> { session.commit() }

        assertContains(e.message!!, "could not rename")
        assertEquals(listOf("Game.iso"), folder.names) // original back, temp file gone
        assertContentEquals(bytes("old"), folder.content("Game.iso"))
    }

    @Test
    fun `if the original cannot be restored either, the next sweep puts it back`() {
        val folder = MemoryFolder()
        folder.put("Game.iso", bytes("old"))
        val session = Output.begin(folder, "Game.iso", overwrite = true)
        write(session, "new")
        folder.failRename = { _, to -> to == "Game.iso" } // the move and the restore both fail

        assertFailsWith<StorageException> { session.commit() }
        assertEquals(listOf("Game.iso$OLD_SUFFIX"), folder.names) // the original is still there, set aside

        folder.failRename = null
        assertEquals(1, Output.sweep(folder).oldRestored)
        assertEquals(listOf("Game.iso"), folder.names)
        assertContentEquals(bytes("old"), folder.content("Game.iso"))
    }

    @Test
    fun `if the original cannot be moved aside nothing is changed`() {
        val folder = MemoryFolder()
        folder.put("Game.iso", bytes("old"))
        val session = Output.begin(folder, "Game.iso", overwrite = true)
        folder.failRename = { _, to -> to.endsWith(OLD_SUFFIX) }

        assertFailsWith<StorageException> { session.commit() }

        assertEquals(listOf("Game.iso"), folder.names)
        assertContentEquals(bytes("old"), folder.content("Game.iso"))
    }

    @Test
    fun `a provider that renames the final file differently is detected and undone`() {
        val folder = MemoryFolder()
        folder.put("Game.iso", bytes("old"))
        val session = Output.begin(folder, "Game.iso", overwrite = true)
        write(session, "new")
        folder.alterRenamedName = { from, to -> if (from.endsWith(PART_SUFFIX) && to == "Game.iso") "Game.iso.bin" else to }

        val e = assertFailsWith<StorageException> { session.commit() }

        assertEquals(StorageException.Code.NAME_CHANGED, e.code)
        assertEquals(listOf("Game.iso"), folder.names)
        assertContentEquals(bytes("old"), folder.content("Game.iso"))
    }

    @Test
    fun `a provider that alters the temporary name is detected`() {
        val folder = MemoryFolder()
        folder.alterCreatedName = { "$it.bin" }
        assertEquals(StorageException.Code.NAME_CHANGED, code { Output.begin(folder, "Game.iso", overwrite = false) })
        assertEquals(emptyList(), folder.names) // the stray file was removed
    }

    @Test
    fun `a folder that cannot create files is reported as not writable`() {
        val folder = MemoryFolder()
        folder.failCreate = true
        val e = assertFailsWith<StorageException> { Output.begin(folder, "Game.iso", overwrite = false) }
        assertEquals(StorageException.Code.NOT_WRITABLE, e.code)
        assertContains(e.message!!, "not writable")
    }

    @Test
    fun `abort removes the temporary file and is idempotent`() {
        val folder = MemoryFolder()
        val session = Output.begin(folder, "Game.iso", overwrite = false)
        session.abort()
        session.abort()
        assertEquals(emptyList(), folder.names)
    }

    @Test
    fun `free space is reported when known and null when not`() {
        val known = MemoryFolder(free = 12345)
        assertEquals(12345L, Output.begin(known, "a.iso", overwrite = false).freeBytes)
        val unknown = MemoryFolder(free = null)
        assertNull(Output.begin(unknown, "a.iso", overwrite = false).freeBytes)
    }

    @Test
    fun `begin removes stale temporary files from earlier runs`() {
        val folder = MemoryFolder()
        folder.put("Other.iso$PART_SUFFIX", bytes("half"))
        folder.put("Game.iso$PART_SUFFIX", bytes("half"))
        folder.put("Keep.iso", bytes("fine"))

        Output.begin(folder, "Game.iso", overwrite = false)

        assertEquals(listOf("Game.iso$PART_SUFFIX", "Keep.iso"), folder.names)
    }

    @Test
    fun `sweep restores an interrupted replacement and deletes a superseded one`() {
        val folder = MemoryFolder()
        folder.put("Lost.iso$OLD_SUFFIX", bytes("original")) // crash between the two renames
        folder.put("Done.iso", bytes("new"))
        folder.put("Done.iso$OLD_SUFFIX", bytes("original")) // crash before the old one was deleted
        folder.put("Stale.iso$PART_SUFFIX", bytes("half"))

        val result = Output.sweep(folder)

        assertEquals(SweepResult(partsRemoved = 1, oldRestored = 1, oldRemoved = 1), result)
        assertEquals(listOf("Done.iso", "Lost.iso"), folder.names)
        assertContentEquals(bytes("original"), folder.content("Lost.iso"))
        assertContentEquals(bytes("new"), folder.content("Done.iso"))
    }

    @Test
    fun `sweep leaves other files and directories alone`() {
        val folder = MemoryFolder()
        folder.put("notes.txt")
        folder.put("sub.ps3dec.part", directory = true)
        Output.sweep(folder)
        assertEquals(listOf("notes.txt", "sub.ps3dec.part"), folder.names)
    }

    @Test
    fun `sweep on an unavailable folder does nothing`() {
        val folder = MemoryFolder()
        folder.unavailable = true
        assertEquals(SweepResult(0, 0, 0), Output.sweep(folder))
    }

    @Test
    fun `human sizes use binary units`() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1.5 KiB", humanSize(1536))
        assertEquals("1.0 MiB", humanSize(1L shl 20))
        assertEquals("3.2 GiB", humanSize((3.2 * (1L shl 30)).toLong()))
        assertEquals("2048.0 GiB", humanSize(2048L shl 30))
    }
}
