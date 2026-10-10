package io.github.tonyp7.ps3dec.job

import io.github.tonyp7.ps3dec.engine.HEADER_SIZE
import io.github.tonyp7.ps3dec.engine.ImageConverter
import io.github.tonyp7.ps3dec.engine.Mode
import io.github.tonyp7.ps3dec.engine.SECTOR_SIZE
import io.github.tonyp7.ps3dec.engine.SyntheticImage
import io.github.tonyp7.ps3dec.engine.convert
import io.github.tonyp7.ps3dec.storage.Entry
import io.github.tonyp7.ps3dec.storage.Folder
import io.github.tonyp7.ps3dec.storage.MemoryFolder
import io.github.tonyp7.ps3dec.storage.PART_SUFFIX
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lifecycle cases of `backend/tests/test_job.py`, run against the real engine on small images. */
class JobControllerTest {
    private val hex = SyntheticImage.DEFAULT_D1.joinToString("") { "%02X".format(it) }
    private val image = SyntheticImage.decrypted(listOf(4, 6, 4), seed = 3)

    private val images = MemoryFolder()
    private val keys = MemoryFolder()
    private val output = MemoryFolder()
    private lateinit var imageEntry: Entry
    private lateinit var keyEntry: Entry
    private val releases = ArrayList<CountDownLatch>()

    private fun standardSetup() {
        imageEntry = images.put("Game.iso", image)
        keyEntry = keys.put("Game.dkey", hex.toByteArray())
    }

    private fun controller(
        images: Folder = this.images,
        keys: Folder = this.keys,
        output: Folder = this.output,
        marker: JobMarker = JobMarker.None,
    ) = JobController(images, keys, output, parallelism = 2, marker = marker)

    private fun request(mode: Mode = Mode.DECRYPT, overwrite: Boolean = false, key: String? = keyEntry.id) =
        StartRequest(imageEntry.id, mode, key, overwrite)

    private fun code(block: () -> Unit) = assertFailsWith<JobRejectedException> { block() }.code

    @AfterTest
    fun releaseGates() = releases.forEach { it.countDown() }

    /** An images folder whose files, after the header, stall until released. */
    private fun gated(inner: MemoryFolder, afterBytes: Int = HEADER_SIZE): Pair<Folder, CountDownLatch> {
        val latch = CountDownLatch(1).also { releases += it }
        val folder = object : Folder by inner {
            override fun openRead(entry: Entry): ReadableByteChannel {
                val real = inner.openRead(entry)
                var seen = 0
                return object : ReadableByteChannel {
                    override fun read(dst: ByteBuffer): Int {
                        if (seen >= afterBytes && !latch.await(30, TimeUnit.SECONDS)) throw IOException("gate timed out")
                        val room = if (seen < afterBytes) minOf(dst.remaining(), afterBytes - seen) else dst.remaining()
                        val slice = dst.duplicate().also { it.limit(it.position() + room) }
                        val n = real.read(slice)
                        if (n > 0) {
                            dst.position(dst.position() + n)
                            seen += n
                        }
                        return n
                    }

                    override fun isOpen() = real.isOpen
                    override fun close() = real.close()
                }
            }
        }
        return folder to latch
    }

    // -- one job at a time ---------------------------------------------------------------------

    @Test
    fun `there is no job initially`() {
        standardSetup()
        assertNull(controller().snapshot())
    }

    @Test
    fun `a second start while running is rejected and the first is unaffected`() {
        standardSetup()
        val (gatedImages, release) = gated(images)
        val c = controller(images = gatedImages)
        val first = c.start(request())
        assertEquals(JobState.RUNNING, first.state)

        assertEquals(JobRejectedException.Code.JOB_RUNNING, code { c.start(request(overwrite = true)) })

        release.countDown()
        assertTrue(c.awaitFinished())
        assertEquals(first.id, c.snapshot()!!.id)
        assertEquals(JobState.SUCCEEDED, c.snapshot()!!.state)
    }

    // -- start-time validation ----------------------------------------------------------------

    @Test
    fun `rejections write nothing and leave no job`() {
        standardSetup()
        keys.put("bad.dkey", "xyz".toByteArray())
        val badKey = keys.list().first { it.name == "bad.dkey" }
        images.put("junk.iso", ByteArray(5000))
        val junk = images.list().first { it.name == "junk.iso" }
        val c = controller()

        val cases = listOf(
            request(key = null) to JobRejectedException.Code.KEY_REQUIRED,
            request(key = "") to JobRejectedException.Code.KEY_REQUIRED,
            request(key = "missing") to JobRejectedException.Code.NOT_FOUND,
            request(key = "../Game.dkey") to JobRejectedException.Code.NOT_FOUND,
            request(key = badKey.id) to JobRejectedException.Code.INVALID_KEY,
            StartRequest("missing", Mode.DECRYPT, keyEntry.id) to JobRejectedException.Code.NOT_FOUND,
            StartRequest("../Game.iso", Mode.DECRYPT, keyEntry.id) to JobRejectedException.Code.NOT_FOUND,
            StartRequest(junk.id, Mode.DECRYPT, keyEntry.id) to JobRejectedException.Code.INVALID_IMAGE,
        )
        for ((req, expected) in cases) {
            assertEquals(expected, code { c.start(req) }, "request $req")
        }
        assertEquals(emptyList(), output.names)
        assertNull(c.snapshot())
    }

    @Test
    fun `an image that changed since it was listed is refused`() {
        standardSetup()
        val e = assertFailsWith<JobRejectedException> {
            controller().start(StartRequest(imageEntry.id, Mode.DECRYPT, keyEntry.id, expectedSize = image.size + 1L))
        }
        assertEquals(JobRejectedException.Code.INPUT_CHANGED, e.code)
        assertEquals(emptyList(), output.names)
    }

    @Test
    fun `3k3y mode conflicts are refused`() {
        val dec = images.put("D.iso", SyntheticImage.decrypted(marker = SyntheticImage.DECRYPTED_MARKER))
        val enc = images.put("E.iso", SyntheticImage.decrypted(marker = SyntheticImage.ENCRYPTED_MARKER))
        val c = controller()
        val a = assertFailsWith<JobRejectedException> { c.start(StartRequest(dec.id, Mode.DECRYPT)) }
        val b = assertFailsWith<JobRejectedException> { c.start(StartRequest(enc.id, Mode.ENCRYPT)) }
        assertEquals(JobRejectedException.Code.MODE_CONFLICT, a.code)
        assertContains(a.message!!, "already decrypted")
        assertContains(b.message!!, "already encrypted")
        assertEquals(emptyList(), output.names)
    }

    @Test
    fun `3k3y needs no key and ignores a supplied one`() {
        val enc = images.put("K.iso", SyntheticImage.decrypted(marker = SyntheticImage.ENCRYPTED_MARKER))
        val c = controller()
        c.start(StartRequest(enc.id, Mode.DECRYPT, keyId = "does-not-exist"))
        assertTrue(c.awaitFinished())
        assertEquals(JobState.SUCCEEDED, c.snapshot()!!.state)
    }

    @Test
    fun `an existing output needs overwrite and is replaced only then`() {
        standardSetup()
        output.put("Game.iso", "old".toByteArray())
        val c = controller()
        assertEquals(JobRejectedException.Code.OUTPUT_EXISTS, code { c.start(request()) })
        assertContentEquals("old".toByteArray(), output.content("Game.iso"))
        assertEquals(listOf("Game.iso"), output.names)

        c.start(request(overwrite = true))
        assertTrue(c.awaitFinished())
        assertEquals(JobState.SUCCEEDED, c.snapshot()!!.state)
        assertContentEquals(convert(image, Mode.DECRYPT, d1 = null, discKey = SyntheticImage.DEFAULT_D1), output.content("Game.iso"))
        assertEquals(listOf("Game.iso"), output.names)
    }

    @Test
    fun `an output folder that is the input folder is refused and the input untouched`() {
        standardSetup()
        val c = controller(output = images)
        assertEquals(JobRejectedException.Code.OUTPUT_SAME_AS_INPUT, code { c.start(request(overwrite = true)) })
        assertContentEquals(image, images.content("Game.iso"))
    }

    @Test
    fun `insufficient space is refused with the sizes and nothing is left behind`() {
        standardSetup()
        output.free = 100
        val e = assertFailsWith<JobRejectedException> { controller().start(request()) }
        assertEquals(JobRejectedException.Code.INSUFFICIENT_SPACE, e.code)
        assertContains(e.message!!, "need")
        assertEquals(emptyList(), output.names)
    }

    @Test
    fun `an output folder that cannot be written is refused`() {
        standardSetup()
        output.failCreate = true
        assertEquals(JobRejectedException.Code.OUTPUT_NOT_WRITABLE, code { controller().start(request()) })
    }

    @Test
    fun `an unavailable folder is refused`() {
        standardSetup()
        output.unavailable = true
        assertEquals(JobRejectedException.Code.UNAVAILABLE, code { controller().start(request()) })
        output.unavailable = false
        images.unavailable = true
        assertEquals(JobRejectedException.Code.UNAVAILABLE, code { controller().start(request()) })
    }

    // -- running and completion ---------------------------------------------------------------

    @Test
    fun `success publishes the output under its final name`() {
        standardSetup()
        val c = controller()
        c.start(request(Mode.ENCRYPT))
        assertTrue(c.awaitFinished())
        val s = c.snapshot()!!
        assertEquals(JobState.SUCCEEDED, s.state)
        assertNull(s.error)
        assertEquals("Game.iso", s.output)
        assertEquals(1.0, s.fraction)
        assertEquals(listOf("Game.iso"), output.names)
        assertContentEquals(convert(image, Mode.ENCRYPT, d1 = null, discKey = SyntheticImage.DEFAULT_D1), output.content("Game.iso"))
    }

    @Test
    fun `no file with the final name exists while the job runs`() {
        standardSetup()
        val (gatedImages, release) = gated(images)
        val c = controller(images = gatedImages)
        c.start(request())
        assertEquals(listOf("Game.iso$PART_SUFFIX"), output.names)
        release.countDown()
        assertTrue(c.awaitFinished())
    }

    @Test
    fun `key material never reaches the log or the snapshot`() {
        standardSetup()
        val c = controller()
        c.start(request())
        assertTrue(c.awaitFinished())
        val s = c.snapshot()!!
        assertTrue(s.log.isNotEmpty())
        assertFalse(s.toString().contains(hex, ignoreCase = true))
        assertFalse(s.log.any { "decryption key" in it.lowercase() })
    }

    @Test
    fun `progress rises, throughput and eta appear, and the end is exactly the image size`() {
        val big = SyntheticImage.decrypted(listOf(2, 2 * ImageConverter.BUFFER_SECTORS, 2), seed = 7)
        val entry = images.put("Big.iso", big)
        keys.put("Big.dkey", hex.toByteArray())
        val seen = ArrayList<JobSnapshot>()
        var tick = 0L
        val c = JobController(images, keys, output, parallelism = 2, clock = { tick += 500_000_000L; tick })
        c.addListener { synchronized(seen) { seen += it } }
        c.start(StartRequest(entry.id, Mode.DECRYPT, keys.list().first().id))
        assertTrue(c.awaitFinished())

        val running = synchronized(seen) { seen.filter { it.state == JobState.RUNNING } }
        val done = running.map { it.bytesDone }
        assertEquals(done.sorted(), done)
        assertTrue(done.last() > done.first())
        assertTrue(running.any { it.etaSeconds != null && (it.bytesPerSecond ?: 0.0) > 0.0 })
        val last = c.snapshot()!!
        assertEquals(JobState.SUCCEEDED, last.state)
        assertEquals(big.size.toLong(), last.bytesTotal)
        assertEquals(1.0, last.fraction)
    }

    @Test
    fun `a read error fails the job, leaves nothing, and a new job is accepted`() {
        standardSetup()
        val failing = object : Folder by images {
            override fun openRead(entry: Entry): ReadableByteChannel {
                val real = images.openRead(entry)
                var seen = 0
                return object : ReadableByteChannel {
                    override fun read(dst: ByteBuffer): Int {
                        if (seen > HEADER_SIZE) throw IOException("read failed: EIO (Input/output error)")
                        val n = real.read(dst)
                        if (n > 0) seen += n
                        return n
                    }

                    override fun isOpen() = true
                    override fun close() = real.close()
                }
            }
        }
        val c = controller(images = failing)
        c.start(request())
        assertTrue(c.awaitFinished())
        val s = c.snapshot()!!
        assertEquals(JobState.FAILED, s.state)
        assertContains(s.error!!, "EIO")
        assertEquals(emptyList(), output.names)

        val c2 = controller()
        c2.start(request())
        assertTrue(c2.awaitFinished())
        assertEquals(JobState.SUCCEEDED, c2.snapshot()!!.state)
    }

    @Test
    fun `an unexpected exception fails the job rather than leaving it running`() {
        standardSetup()
        val broken = object : Folder by images {
            override fun openRead(entry: Entry): ReadableByteChannel {
                val real = images.openRead(entry)
                var first = true
                return object : ReadableByteChannel {
                    override fun read(dst: ByteBuffer): Int {
                        if (!first) throw IllegalStateException("boom")
                        first = false
                        return real.read(dst)
                    }

                    override fun isOpen() = true
                    override fun close() = real.close()
                }
            }
        }
        val c = controller(images = broken)
        c.start(request())
        assertTrue(c.awaitFinished())
        val s = c.snapshot()!!
        assertEquals(JobState.FAILED, s.state)
        assertContains(s.error!!, "boom")
        assertFalse(c.isRunning)
        assertEquals(emptyList(), output.names)
    }

    @Test
    fun `a size mismatch fails the job and removes the output`() {
        standardSetup()
        val lying = object : Folder by output {
            override fun list(): List<Entry> =
                output.list().map { if (it.name.endsWith(PART_SUFFIX)) it.copy(size = it.size - 1) else it }
        }
        val c = controller(output = lying)
        c.start(request())
        assertTrue(c.awaitFinished())
        val s = c.snapshot()!!
        assertEquals(JobState.FAILED, s.state)
        assertContains(s.error!!, "bytes")
        assertEquals(emptyList(), output.names)
    }

    @Test
    fun `an existing file survives a failed overwrite`() {
        standardSetup()
        output.put("Game.iso", "precious".toByteArray())
        val failing = object : Folder by images {
            override fun openRead(entry: Entry): ReadableByteChannel {
                val real = images.openRead(entry)
                var n = 0
                return object : ReadableByteChannel {
                    override fun read(dst: ByteBuffer): Int {
                        if (++n > 2) throw IOException("read failed: EIO (Input/output error)")
                        return real.read(dst)
                    }

                    override fun isOpen() = true
                    override fun close() = real.close()
                }
            }
        }
        val c = controller(images = failing)
        c.start(request(overwrite = true))
        assertTrue(c.awaitFinished())
        assertEquals(JobState.FAILED, c.snapshot()!!.state)
        assertEquals(listOf("Game.iso"), output.names)
        assertContentEquals("precious".toByteArray(), output.content("Game.iso"))
    }

    // -- cancellation -------------------------------------------------------------------------

    @Test
    fun `cancelling mid-job stops it, removes the output and frees the slot`() {
        standardSetup()
        val (gatedImages, _) = gated(images)
        val c = controller(images = gatedImages)
        c.start(request())
        c.cancel()
        val s = c.snapshot()!!
        assertEquals(JobState.CANCELLED, s.state)
        assertNull(s.error)
        assertEquals(emptyList(), output.names)
        assertFalse(c.isRunning)

        val c2 = controller()
        c2.start(request())
        assertTrue(c2.awaitFinished())
        assertEquals(JobState.SUCCEEDED, c2.snapshot()!!.state)
    }

    @Test
    fun `an existing file survives a cancelled overwrite`() {
        standardSetup()
        output.put("Game.iso", "precious".toByteArray())
        val (gatedImages, _) = gated(images)
        val c = controller(images = gatedImages)
        c.start(request(overwrite = true))
        c.cancel()
        assertEquals(JobState.CANCELLED, c.snapshot()!!.state)
        assertEquals(listOf("Game.iso"), output.names)
        assertContentEquals("precious".toByteArray(), output.content("Game.iso"))
    }

    @Test
    fun `cancelling when idle or after the end has no effect`() {
        standardSetup()
        val c = controller()
        c.cancel()
        assertNull(c.snapshot())
        c.start(request())
        assertTrue(c.awaitFinished())
        c.cancel()
        assertEquals(JobState.SUCCEEDED, c.snapshot()!!.state)
    }

    // -- retention and the in-progress marker -------------------------------------------------

    @Test
    fun `the last job is kept until the next one starts`() {
        standardSetup()
        val c = controller()
        c.start(request())
        assertTrue(c.awaitFinished())
        val first = c.snapshot()!!
        assertEquals(first, c.snapshot())

        c.start(request(overwrite = true))
        assertTrue(c.awaitFinished())
        assertNotNull(c.snapshot())
        assertTrue(c.snapshot()!!.id != first.id)
    }

    @Test
    fun `the in-progress marker is set at the start and cleared whenever the job ends`() {
        standardSetup()
        class Recording : JobMarker {
            val events = ArrayList<String>()
            override fun begin(image: String) { events += "begin $image" }
            override fun clear() { events += "clear" }
        }

        val ok = Recording()
        controller(marker = ok).also { it.start(request()); assertTrue(it.awaitFinished()) }
        assertEquals(listOf("begin Game.iso", "clear"), ok.events)

        val cancelled = Recording()
        val (gatedImages, _) = gated(images)
        controller(images = gatedImages, marker = cancelled).also { it.start(request(overwrite = true)); it.cancel() }
        assertEquals(listOf("begin Game.iso", "clear"), cancelled.events)

        val rejected = Recording()
        runCatching { controller(marker = rejected).start(request(key = null)) }
        assertEquals(emptyList(), rejected.events) // nothing started, nothing to clear
    }
}
