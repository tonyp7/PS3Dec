package io.github.tonyp7.ps3dec.lab

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.tonyp7.ps3dec.engine.ConversionCallbacks
import io.github.tonyp7.ps3dec.engine.HEADER_SIZE
import io.github.tonyp7.ps3dec.engine.ImageClassifier
import io.github.tonyp7.ps3dec.engine.ImageConverter
import io.github.tonyp7.ps3dec.engine.ImageKind
import io.github.tonyp7.ps3dec.engine.KeyFiles
import io.github.tonyp7.ps3dec.engine.Mode
import io.github.tonyp7.ps3dec.engine.SECTOR_SIZE
import io.github.tonyp7.ps3dec.job.JobRejectedException
import io.github.tonyp7.ps3dec.job.JobState
import io.github.tonyp7.ps3dec.job.StartRequest
import io.github.tonyp7.ps3dec.runtime.ConversionRuntime
import io.github.tonyp7.ps3dec.storage.DocumentTree
import io.github.tonyp7.ps3dec.storage.Entry
import io.github.tonyp7.ps3dec.storage.FolderConfig
import io.github.tonyp7.ps3dec.storage.FolderRelation
import io.github.tonyp7.ps3dec.storage.FolderRole
import io.github.tonyp7.ps3dec.storage.Listing
import io.github.tonyp7.ps3dec.storage.OLD_SUFFIX
import io.github.tonyp7.ps3dec.storage.Output
import io.github.tonyp7.ps3dec.storage.PART_SUFFIX
import io.github.tonyp7.ps3dec.storage.PreFlight
import io.github.tonyp7.ps3dec.storage.SafFolder
import io.github.tonyp7.ps3dec.storage.StorageException
import io.github.tonyp7.ps3dec.storage.humanSize
import java.nio.ByteBuffer
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only harness that exercises the storage layer against whatever provider the user picks a
 * folder in (the verification vehicle for the `android-storage` tasks). Choose an empty folder: the
 * lab creates `iso`, `keys` and `out` inside it and removes what it creates when it is done.
 *
 * Every step is logged under the tag `StorageLab` and shown on screen.
 */
class StorageLabActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private lateinit var config: FolderConfig
    private var failures = 0
    private var largeMiB = 256

    private fun say(text: String) {
        Log.i(TAG, text)
        runOnUiThread { lines += text }
    }

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        say("${if (ok) "PASS" else "FAIL"}  $name${if (detail.isNotEmpty()) "  ($detail)" else ""}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = FolderConfig(applicationContext)
        largeMiB = intent.getIntExtra("mib", 256)
        setContent {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) lifecycleScope.launch { onPicked(uri) }
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { picker.launch(null) }) { Text("Choose lab folder") }
                            Button(onClick = { lifecycleScope.launch { runSaved() } }) { Text("Run checks") }
                            Button(onClick = { lifecycleScope.launch { revoke() } }) { Text("Revoke grant (test)") }
                            Button(onClick = { lifecycleScope.launch { startLongJob() } }) { Text("Start long job") }
                        }
                        LazyColumn { items(lines) { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
        lifecycleScope.launch {
            ConversionRuntime.interruption.collect { n ->
                if (n != null) say("NOTICE  the previous job (${n.image}) was interrupted: ${n.reason}")
            }
        }
        lifecycleScope.launch {
            var lastLogged = -1
            ConversionRuntime.job.collect { snap ->
                if (snap == null) return@collect
                val pct = (snap.fraction * 100).toInt() / 10 * 10
                if (snap.state != JobState.RUNNING || pct != lastLogged) {
                    lastLogged = pct
                    say("job ${snap.id} ${snap.state} ${snap.image} ${(snap.fraction * 100).toInt()}% " +
                        "${snap.bytesDone shr 20}/${snap.bytesTotal shr 20} MiB" +
                        (snap.etaSeconds?.let { " eta ${it.toInt()}s" } ?: "") +
                        (snap.error?.let { " error: $it" } ?: ""))
                }
            }
        }
        if (intent.getBooleanExtra("run", true)) lifecycleScope.launch { runSaved(auto = true) }
        if (intent.getBooleanExtra("longjob", false)) lifecycleScope.launch { startLongJob() }
    }

    /** Seed a large image in the lab folder and run it through the real runtime and service. */
    private suspend fun startLongJob() = withContext(Dispatchers.IO) {
        val tree = config.get(FolderRole.OUTPUT) ?: return@withContext say("no folder chosen yet")
        labTree = tree
        val mib = intent.getIntExtra("bigmib", 4096)
        val root = SafFolder(applicationContext, tree)
        val iso = subfolder(root, "iso")
        val keys = subfolder(root, "keys")
        val out = subfolder(root, "out")
        val name = "Long.iso"
        val sectors = mib * 512
        val want = sectors.toLong() * SECTOR_SIZE
        if (iso.list().none { it.name == name && it.size == want }) {
            iso.list().filter { it.name == name }.forEach(iso::delete)
            say("seeding $name ($mib MiB)")
            val src = iso.create(name)
            val header = image(listOf(2, sectors - 4, 2), 9, null, fill = false)
            val block = ByteArray(8 shl 20).also { Random(11).nextBytes(it) }
            val firstBlock = block.copyOf().also { header.copyInto(it) }
            iso.openWrite(src).use { ch ->
                var remaining = want
                var first = true
                while (remaining > 0) {
                    val n = minOf(remaining, block.size.toLong()).toInt()
                    val bb = ByteBuffer.wrap(if (first) firstBlock else block, 0, n)
                    while (bb.hasRemaining()) ch.write(bb)
                    first = false
                    remaining -= n
                }
            }
        }
        if (keys.list().none { it.name == "Long.dkey" }) put(keys, "Long.dkey", "00112233445566778899AABBCCDDEEFF".toByteArray())
        ConversionRuntime.configure(iso, keys, out)
        if (intent.getBooleanExtra("recover", false)) {
            val before = out.list().map { it.name }
            ConversionRuntime.recoverInterrupted(applicationContext, out)
            say("recovery: output folder before=$before after=${out.list().map { it.name }}")
            return@withContext
        }
        val image = iso.list().first { it.name == name }
        val key = keys.list().first { it.name == "Long.dkey" }
        try {
            val snap = ConversionRuntime.start(
                applicationContext,
                StartRequest(image.id, Mode.DECRYPT, key.id, overwrite = true, expectedSize = image.size),
            )
            say("started job ${snap.id} for ${snap.image}")
        } catch (e: JobRejectedException) {
            say("FAIL  start refused: ${e.code} ${e.message}")
        }
    }

    private suspend fun onPicked(uri: Uri) {
        try {
            config.set(FolderRole.OUTPUT, uri)
            say("chose $uri")
        } catch (e: Exception) {
            say("FAIL  could not use the folder: ${e.message}")
            return
        }
        runSaved()
    }

    private suspend fun runSaved(auto: Boolean = false) {
        val uri = config.get(FolderRole.OUTPUT)
        if (uri == null) {
            if (!auto) say("no folder chosen yet")
            else say("No folder chosen. Tap 'Choose lab folder' and pick an empty folder (not a storage root).")
            return
        }
        say("persisted folder: $uri")
        check("persisted grant is still held", config.hasGrant(uri))
        withContext(Dispatchers.IO) { runChecks(uri) }
    }

    /** Release the persisted grant and report how the app sees the folder afterwards. */
    private suspend fun revoke() = withContext(Dispatchers.IO) {
        val uri = config.get(FolderRole.OUTPUT) ?: return@withContext say("no folder to revoke")
        say("-- revoking the grant for $uri")
        contentResolver.releasePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val state = config.state(FolderRole.OUTPUT)
        say("state after revoking: $state")
        check("a revoked grant is reported as unavailable", state is io.github.tonyp7.ps3dec.storage.FolderState.Unavailable)
        val listing = runCatching { SafFolder(applicationContext, uri).list() }.exceptionOrNull()
        check(
            "listing a revoked folder fails cleanly as UNAVAILABLE",
            (listing as? StorageException)?.code == StorageException.Code.UNAVAILABLE,
            listing?.javaClass?.simpleName + ": " + listing?.message,
        )
        say("choose the folder again to continue")
    }

    // -- the checks -----------------------------------------------------------------------------

    private fun runChecks(tree: Uri) {
        failures = 0
        labTree = tree
        try {
            val root = SafFolder(applicationContext, tree)
            say("provider ${tree.authority}, tree document ${DocumentsContract.getTreeDocumentId(tree)}")

            val iso = subfolder(root, "iso")
            val keys = subfolder(root, "keys")
            val out = subfolder(root, "out")
            listingChecks(iso, keys)
            outputChecks(out)
            relationChecks(tree)
            throughputCheck(iso, out)
            cleanup(root)
        } catch (e: Throwable) {
            failures++
            say("FAIL  unexpected ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "lab crashed", e)
        }
        say(if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
    }

    /** The lab folder's grant; subfolders are created and addressed through it. */
    private var labTree: Uri? = null

    private fun subfolder(parent: SafFolder, name: String): SafFolder {
        val existing = parent.list().firstOrNull { it.name == name && it.isDirectory }
        if (existing != null) return parent.child(existing.id)
        val tree = labTree!!
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val created = DocumentsContract.createDocument(
            contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: error("could not create folder $name")
        return parent.child(DocumentsContract.getDocumentId(created))
    }

    private fun listingChecks(iso: SafFolder, keys: SafFolder) {
        say("-- listing and classification")
        val files = mapOf(
            "Alpha.iso" to image(listOf(2, 6, 2), 1, null),
            "beta.ISO" to image(listOf(3, 5, 2), 2, "Decrypted 3K ISO"),
            "bad.iso" to ByteArray(5000).also { Random(3).nextBytes(it) },
            "notes.txt" to "hello".toByteArray(),
        )
        for ((name, data) in files) put(iso, name, data)
        val tree = labTree!!
        val sub = DocumentsContract.createDocument(
            contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, iso.rootId()),
            DocumentsContract.Document.MIME_TYPE_DIR,
            "sub.iso", // a folder that even looks like an image
        )!!
        put(iso.child(DocumentsContract.getDocumentId(sub)), "inner.iso", image(listOf(2, 2, 2), 5, null))
        val names = iso.list().filter { !it.isDirectory }.map { it.name }
        check("created names are kept exactly", names.toSet() == files.keys, names.sorted().toString())
        check("a subfolder is seen as a directory", iso.list().any { it.name == "sub.iso" && it.isDirectory })

        val listed = Listing.withExtension(iso.list(), Listing.IMAGE_EXTENSION)
        check(
            "only .iso files listed, sorted",
            listed.map { it.name } == listOf("Alpha.iso", "bad.iso", "beta.ISO"),
            "${listed.map { it.name }} (sub.iso folder and its inner.iso ignored)",
        )
        for (entry in listed) {
            val expected = files.getValue(entry.name).size.toLong()
            check("size of ${entry.name} comes from the listing", entry.size == expected, "${entry.size} vs $expected")
        }

        val kinds = listed.associate { it.name to classify(iso, it) }
        check("Alpha.iso is standard", kinds["Alpha.iso"] == ImageKind.STANDARD, kinds["Alpha.iso"].toString())
        check("beta.ISO is 3k3y-decrypted", kinds["beta.ISO"] == ImageKind.THREE_K3Y_DECRYPTED, kinds["beta.ISO"].toString())
        check("bad.iso is invalid", kinds["bad.iso"] == ImageKind.INVALID, kinds["bad.iso"].toString())

        put(keys, "Alpha.dkey", "00112233445566778899AABBCCDDEEFF\n".toByteArray())
        put(keys, "broken.dkey", "nope".toByteArray())
        val keyInfos = Listing.withExtension(keys.list(), Listing.KEY_EXTENSION).map { entry ->
            val raw = keys.openRead(entry).use { ch ->
                val bb = ByteBuffer.allocate(KeyFiles.MAX_KEY_FILE_BYTES + 1)
                while (bb.hasRemaining() && ch.read(bb) >= 0) { /* read */ }
                bb.array().copyOf(bb.position())
            }
            KeyFiles.inspect(entry.name, raw)
        }
        check("keys validated", keyInfos.map { it.valid } == listOf(true, false), keyInfos.toString())
        check("key suggested by name", KeyFiles.suggest("Alpha.iso", keyInfos) == "Alpha.dkey")

        check("an id outside the listing is refused", runCatching { Listing.resolve(listed, "primary:x/../etc", "image") }.exceptionOrNull() is StorageException)
        val stale = PreFlight.checkInput(iso.list(), listed.first().id, listed.first().size)
        check("unchanged input passes the pre-flight", stale.name == "Alpha.iso")
        val changed = runCatching { PreFlight.checkInput(iso.list(), listed.first().id, listed.first().size + 1) }
        check("changed input is refused", (changed.exceptionOrNull() as? StorageException)?.code == StorageException.Code.INPUT_CHANGED)
    }

    private fun outputChecks(out: SafFolder) {
        say("-- output protocol")
        val name = "Alpha.iso"

        val s1 = Output.begin(out, name, overwrite = false)
        val partName = out.list().single().name
        check("temporary file keeps its exact name", partName == name + PART_SUFFIX, partName)
        say("free space reported through the provider: ${s1.freeBytes?.let { humanSize(it) } ?: "unknown"}")
        val statfs = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        say("free space by StatFs on external storage: ${humanSize(statfs)}")
        check("free space is available", s1.freeBytes != null)
        s1.channel.write(ByteBuffer.wrap(ByteArray(1 shl 20) { 1 }))
        check("part size visible while writing", true, "${s1.partSize()} bytes")
        val done = s1.commit()
        check("commit gives the final name", done.name == name, done.name)
        check("only the final file remains", out.list().map { it.name } == listOf(name), out.list().map { it.name }.toString())
        check("final size is correct", out.list().single().size == (1L shl 20), "${out.list().single().size}")

        val refused = runCatching { Output.begin(out, name, overwrite = false) }.exceptionOrNull() as? StorageException
        check("existing output refused without overwrite", refused?.code == StorageException.Code.OUTPUT_EXISTS)
        check("nothing left behind by the refusal", out.list().map { it.name } == listOf(name))

        val s2 = Output.begin(out, name, overwrite = true)
        check("original untouched while the new one is written", out.list().first { it.name == name }.size == (1L shl 20))
        s2.channel.write(ByteBuffer.wrap(ByteArray(2 shl 20) { 2 }))
        s2.commit()
        val after = out.list()
        check("replacement leaves no .old behind", after.map { it.name } == listOf(name), after.map { it.name }.toString())
        check("replacement has the new content size", after.single().size == (2L shl 20), "${after.single().size}")

        // A failure at the last step against the real provider: the original must come back.
        val faulty = FaultyFolder(out) { from, to -> from.endsWith(PART_SUFFIX) && to == name }
        val s4 = Output.begin(faulty, name, overwrite = true)
        s4.channel.write(ByteBuffer.wrap(ByteArray(3 shl 20) { 4 }))
        val failure = runCatching { s4.commit() }.exceptionOrNull() as? StorageException
        check("a failed final rename is reported", failure != null, failure?.message.orEmpty())
        val afterFail = out.list()
        check("the original is back under its name", afterFail.map { it.name } == listOf(name), afterFail.map { it.name }.toString())
        check("the original content is intact", afterFail.single().size == (2L shl 20), "${afterFail.single().size}")

        val s3 = Output.begin(out, "Other.iso", overwrite = false)
        s3.abort()
        check("abort removes the temporary file", out.list().map { it.name } == listOf(name))

        put(out, "Lost.iso$OLD_SUFFIX", "orig".toByteArray())
        put(out, "Stale.iso$PART_SUFFIX", "half".toByteArray())
        val sweep = Output.sweep(out)
        val names = out.list().map { it.name }.sorted()
        check("sweep removes parts and restores interrupted replacements", names == listOf("Alpha.iso", "Lost.iso"), "$sweep $names")
        out.list().filter { it.name == "Lost.iso" }.forEach(out::delete)
        out.list().filter { it.name == name }.forEach(out::delete)
    }

    private fun relationChecks(tree: Uri) {
        say("-- folder relation with real document ids")
        val id = DocumentsContract.getTreeDocumentId(tree)
        val a = DocumentTree(tree.authority.orEmpty(), id)
        val inside = DocumentTree(a.authority, "$id/out")
        val sibling = DocumentTree(a.authority, "${id}-other")
        check("a folder overlaps itself", FolderRelation.overlaps(a, a))
        check("a subfolder overlaps its parent", FolderRelation.overlaps(a, inside))
        check("a different folder does not overlap", !FolderRelation.overlaps(a, sibling))

        // The same real folder cannot also be the image folder while it is the output folder.
        val rejected = runBlockingCatching { config.set(FolderRole.IMAGES, tree) }
        check(
            "choosing the output folder as the image folder is rejected",
            rejected is io.github.tonyp7.ps3dec.storage.FolderChoiceException,
            rejected?.message.orEmpty(),
        )
        check("the rejected choice was not stored", kotlinx.coroutines.runBlocking { config.get(FolderRole.IMAGES) } == null)
    }

    private fun throughputCheck(iso: SafFolder, out: SafFolder) {
        say("-- conversion through the provider ($largeMiB MiB)")
        val sectors = largeMiB * 512
        val name = "Big.iso"
        val data = image(listOf(2, sectors - 4, 2), 9, null, fill = false)

        val t0 = System.nanoTime()
        val src = iso.create(name)
        iso.openWrite(src).use { ch ->
            val block = ByteArray(8 shl 20).also { Random(11).nextBytes(it) }
            val firstBlock = block.copyOf().also { data.copyInto(it) } // header in the first block
            var remaining = sectors.toLong() * SECTOR_SIZE
            var first = true
            while (remaining > 0) {
                val n = minOf(remaining, block.size.toLong()).toInt()
                val bb = ByteBuffer.wrap(if (first) firstBlock else block, 0, n)
                while (bb.hasRemaining()) ch.write(bb)
                first = false
                remaining -= n
            }
        }
        val writeSecs = (System.nanoTime() - t0) / 1e9
        say("wrote the source through the provider: %.1f s, %.0f MiB/s".format(writeSecs, largeMiB / writeSecs))

        val entry = iso.list().first { it.name == name }
        check("source has the expected size", entry.size == sectors.toLong() * SECTOR_SIZE, "${entry.size}")
        val session = Output.begin(out, name, overwrite = false)
        PreFlight.checkSpace(entry.size, session.freeBytes)
        val c0 = System.nanoTime()
        iso.openRead(entry).use { input ->
            ImageConverter.convert(
                input, entry.size, session.channel, Mode.DECRYPT, ByteArray(16) { it.toByte() },
                ConversionCallbacks(),
            )
        }
        val convSecs = (System.nanoTime() - c0) / 1e9
        check("temporary output is the full size", session.partSize() == entry.size, "${session.partSize()}")
        val done = session.commit()
        say("converted through the provider: %.1f s, %.0f MiB/s".format(convSecs, largeMiB / convSecs))
        check("converted file in place with the right size", done.size == entry.size, "${done.size}")

        out.list().filter { it.name == name }.forEach(out::delete)
        iso.list().filter { it.name == name }.forEach(iso::delete)
    }

    private fun cleanup(root: SafFolder) {
        for (dir in root.list().filter { it.isDirectory && it.name in setOf("iso", "keys", "out") }) {
            val sub = root.child(dir.id)
            for (e in sub.list()) {
                if (e.isDirectory) sub.child(e.id).let { inner -> inner.list().forEach(inner::delete) }
                sub.delete(e)
            }
            root.delete(dir)
        }
        check("lab files removed", root.list().none { it.name in setOf("iso", "keys", "out") })
    }

    // -- helpers --------------------------------------------------------------------------------

    /** Wraps a real folder and refuses selected renames, to test failure handling against real storage. */
    private class FaultyFolder(
        private val inner: SafFolder,
        private val refuse: (from: String, to: String) -> Boolean,
    ) : io.github.tonyp7.ps3dec.storage.Folder by inner {
        override fun rename(entry: Entry, newName: String): Entry {
            if (refuse(entry.name, newName)) throw java.io.IOException("rename refused (injected)")
            return inner.rename(entry, newName)
        }
    }

    private fun runBlockingCatching(block: suspend () -> Unit): Throwable? =
        try {
            kotlinx.coroutines.runBlocking { block() }
            null
        } catch (e: Throwable) {
            e
        }

    private fun put(folder: SafFolder, name: String, data: ByteArray): Entry {
        val entry = folder.create(name)
        folder.openWrite(entry).use { ch ->
            val bb = ByteBuffer.wrap(data)
            while (bb.hasRemaining()) ch.write(bb)
        }
        return entry
    }

    private fun classify(folder: SafFolder, entry: Entry): ImageKind {
        val header = folder.openRead(entry).use { ch ->
            val bb = ByteBuffer.allocate(HEADER_SIZE)
            while (bb.hasRemaining() && ch.read(bb) >= 0) { /* read */ }
            bb.array().copyOf(bb.position())
        }
        return ImageClassifier.classify(entry.size, header).kind
    }

    /** A small synthetic image: the header for [regions] and pseudo-random sectors. */
    private fun image(regions: List<Int>, seed: Long, marker: String?, fill: Boolean = true): ByteArray {
        val total = regions.sum() * SECTOR_SIZE
        val data = if (fill) ByteArray(total).also { Random(seed).nextBytes(it) } else ByteArray(HEADER_SIZE)
        val header = data.copyOf(HEADER_SIZE).also { it.fill(0, 0, HEADER_SIZE) }
        putU32(header, 0, ((regions.size + 1) / 2).toLong())
        var start = 0L
        regions.forEachIndexed { i, count ->
            putU32(header, 12 + 4 * i, if (i % 2 == 0) start + count - 1 else start + count)
            start += count
        }
        if (marker != null) {
            marker.toByteArray().copyInto(header, 0xF70)
            ByteArray(16) { (it * 0x11).toByte() }.copyInto(header, 0xF80)
        }
        header.copyInto(data)
        return data
    }

    private fun putU32(into: ByteArray, at: Int, v: Long) {
        into[at] = (v ushr 24).toByte(); into[at + 1] = (v ushr 16).toByte()
        into[at + 2] = (v ushr 8).toByte(); into[at + 3] = v.toByte()
    }

    private companion object {
        const val TAG = "StorageLab"
    }
}
