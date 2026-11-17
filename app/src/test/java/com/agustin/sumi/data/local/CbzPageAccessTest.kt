package com.agustin.sumi.data.local

import com.agustin.sumi.data.local.archive.CbzArchiveReader
import com.agustin.sumi.data.local.archive.CbzPageProbe
import com.agustin.sumi.data.local.archive.ZipValidationException
import com.agustin.sumi.data.local.archive.ZipValidationLimits
import com.agustin.sumi.data.local.document.TemporaryCbzStore
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzPageFormat
import java.io.File
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CbzPageAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    private val archives = CbzArchiveReader()
    private val jpeg = byteArrayOf(-1, -40, -1) + ByteArray(100_000) { it.toByte() }

    @Test
    fun signatureIndexCountsExpandedAndPackedBytesSeparately() {
        val random = ByteArray(128 * 1024).also { java.util.Random(19).nextBytes(it) }
        val large = byteArrayOf(-1, -40, -1) + random
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val webp = "RIFF0000WEBP".encodeToByteArray()
        val entries = arrayOf(
            "nested/page10" to webp, "nested/page2.bin" to large, "nested/page3.PNG" to png,
            "nested/" to byteArrayOf(), "fake.jpg" to "not a jpeg".encodeToByteArray(),
        )
        for (stored in listOf(false, true)) {
            archive(*entries, stored = stored).inputStream().use { input ->
                val index = archives.index(input.channel) {}
                val probes = mutableListOf<CbzPageProbe>()
                val pages = archives.identifyPages(input.channel, index, {}, probes::add)
                assertEquals(archives.readPages(input.channel) {}, pages)
                assertEquals(listOf(CbzPageFormat.JPEG, CbzPageFormat.PNG, CbzPageFormat.WEBP), pages.map { it.format })
                assertEquals(listOf(10, 12, 8, 12), probes.map { it.expandedBytes })
                val largeProbe = probes.single { it.id == pages.first().id }
                val largeEntry = index.entries.single { it.id == largeProbe.id }.structure
                println("T08 ${if (stored) "STORED" else "DEFLATE"}: expanded=${probes.map { it.expandedBytes }}, " +
                    "packed=${probes.map { it.packedBytesRead }}, largeEntryPacked=${largeEntry.packed}")
                assertTrue(largeProbe.packedBytesRead < largeEntry.packed)
                if (stored) assertEquals(12L, largeProbe.packedBytesRead)
                else assertTrue(largeProbe.packedBytesRead > largeProbe.expandedBytes)
            }
        }
    }

    @Test
    fun indexRejectsMalformedDeflatePrefixWithoutReadingOtherPages() {
        val file = archive("page.jpg" to jpeg)
        val index = file.inputStream().use { archives.index(it.channel) {} }
        RandomAccessFile(file, "rw").use {
            it.seek(index.entries.single().structure.dataOffset)
            it.write(7) // Final DEFLATE block with reserved block type 3.
        }
        file.inputStream().use { input ->
            val failure = assertThrows(ZipValidationException::class.java) {
                archives.identifyPages(input.channel, index, {})
            }
            assertEquals(CbzImportError.CORRUPT_ARCHIVE, failure.error)
        }
    }

    @Test
    fun indexRejectsDeflateThatEndsBeforeItsDeclaredPrefix() {
        val file = archive("page.jpg" to jpeg.copyOf(3))
        val structure = file.inputStream().use { archives.index(it.channel) {}.entries.single().structure }
        RandomAccessFile(file, "rw").use {
            val expanded = Integer.reverseBytes(12)
            it.seek(structure.dataOffset + structure.packed + 12) // Signed data descriptor.
            it.writeInt(expanded)
            it.seek(structure.name.offset - 46 + 24) // Central directory expanded size.
            it.writeInt(expanded)
        }
        file.inputStream().use { input ->
            val index = archives.index(input.channel) {}
            val failure = assertThrows(ZipValidationException::class.java) {
                archives.identifyPages(input.channel, index, {})
            }
            assertEquals(CbzImportError.CORRUPT_ARCHIVE, failure.error)
        }
    }

    @Test
    fun cancellationStopsSignatureIndexBeforeNextEntry() {
        val file = archive("a.jpg" to jpeg, "b.jpg" to jpeg)
        file.inputStream().use { input ->
            val index = archives.index(input.channel) {}
            var probed = 0
            assertThrows(CancellationException::class.java) {
                archives.identifyPages(input.channel, index, { if (probed > 0) throw CancellationException() }) {
                    probed++
                }
            }
            assertEquals(1, probed)
        }
    }

    @Test
    fun retainedCopySharesTheOnlySlotAndStaysProtectedFromRecovery() {
        val directory = temporary.newFolder()
        val store = TemporaryCbzStore(directory, maxBytes = 8, totalBudget = 8, availableBytes = { 100 }) {
            error("Temporary cleanup failed")
        }
        val input = byteArrayOf(1, 2, 3, 4, 5, 6)
        val copy = store.copy(ByteArrayInputStream(input), {})
        copy.use {
            store.recoverOrphans()
            assertArrayEquals(input, it.file.readBytes())
            val failure = assertThrows(java.io.IOException::class.java) {
                store.withCopy(ByteArrayInputStream(byteArrayOf(1)), {}) { _, _ -> error("Second copy admitted") }
            }
            assertEquals(CbzImportError.RESOURCE_LIMIT_EXCEEDED.name, failure.message)
        }
        copy.close()
        assertFalse(copy.file.exists())
        store.withCopy(ByteArrayInputStream(input), {}) { file, _ -> assertTrue(file.exists()) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun structuralIndexSharesNaturalOrderAndPageIdsWithImport() {
        val file = archive("p10.jpg" to jpeg, "p2.bin" to jpeg, "p1.jpg" to jpeg)
        file.inputStream().use { input ->
            val index = archives.index(input.channel) {}
            assertEquals(archives.readPages(input.channel) {}.map { it.id }, index.entries.map { it.id })
            assertEquals(3, index.entries.size)
        }
    }

    @Test
    fun badNeighborPayloadDoesNotPreventIndexOrIndividualDeflatedRead() {
        val file = archive("p2.jpg" to jpeg, "p1.jpg" to jpeg, stored = true)
        val index = file.inputStream().use { archives.index(it.channel) {} }
        // Mutate payload only. The central directory and local headers stay intact.
        file.outputStreamAt(index.entries[1].structure.dataOffset + jpeg.lastIndex)
        file.inputStream().use { input ->
            val current = archives.index(input.channel) {}
            archives.openPage(input.channel, current.entries[0]) {}.use { page ->
                assertEquals(CbzPageFormat.JPEG, page.format)
                val output = ByteArray(jpeg.size)
                var cursor = 0
                while (cursor < output.size) cursor += page.read(output, cursor, output.size - cursor)
                page.verifyComplete()
                assertArrayEquals(jpeg, output)
            }
            archives.openPage(input.channel, current.entries[1]) {}.use { page ->
                // No caller reads: verification must still detect the bad CRC.
                assertThrows(ZipException::class.java) { page.verifyComplete() }
            }
        }
        val deflated = archive("page.jpg" to jpeg)
        deflated.inputStream().use { input ->
            val page = archives.index(input.channel) {}.entries.single()
            archives.openPage(input.channel, page) {}.use { it.verifyComplete() }
        }
    }

    @Test
    fun signatureIsCheckedEvenWhenExtensionLooksValid() {
        val file = archive("page.jpg" to "not an image".encodeToByteArray())
        file.inputStream().use { input ->
            val entry = archives.index(input.channel) {}.entries.single()
            val failure = assertThrows(ZipValidationException::class.java) { archives.openPage(input.channel, entry) {} }
            assertEquals(CbzImportError.NO_SUPPORTED_IMAGES, failure.error)
        }
    }

    @Test
    fun sizeAndEntryLimitsApplyBeforePublishingData() {
        val file = archive("page1.jpg" to jpeg, "page2.jpg" to jpeg)
        file.inputStream().use { input ->
            for (limits in listOf(ZipValidationLimits(entries = 1), ZipValidationLimits(expandedBytes = jpeg.size.toLong()))) {
                val failure = assertThrows(ZipValidationException::class.java) {
                    CbzArchiveReader(limits).index(input.channel) {}
                }
                assertEquals(CbzImportError.RESOURCE_LIMIT_EXCEEDED, failure.error)
            }
            val limited = CbzArchiveReader(ZipValidationLimits(imageBytes = 12))
            val entry = limited.index(input.channel) {}.entries.first()
            val failure = assertThrows(ZipValidationException::class.java) { limited.openPage(input.channel, entry) {} }
            assertEquals(CbzImportError.RESOURCE_LIMIT_EXCEEDED, failure.error)
        }
    }

    @Test
    fun changedCentralCrcInvalidatesTheSnapshot() {
        val first = archive("page.jpg" to jpeg, stored = true)
        val replacement = archive("page.jpg" to (jpeg.copyOf().also { it[it.lastIndex] = 23 }), stored = true)
        val old = first.inputStream().use { archives.index(it.channel) {} }
        val updated = replacement.inputStream().use { archives.index(it.channel) {} }
        assertEquals(old.length, updated.length)
        assertEquals(old.entries.single().id, updated.entries.single().id)
        assertFalse(old.matches(updated))
    }

    @Test
    fun cancellationIsObservedWhileDrainingAnEntry() {
        val file = archive("page.jpg" to jpeg)
        file.inputStream().use { input ->
            val entry = archives.index(input.channel) {}.entries.single()
            var cancelled = false
            archives.openPage(input.channel, entry) { if (cancelled) throw CancellationException() }.use { page ->
                cancelled = true
                assertThrows(CancellationException::class.java) { page.verifyComplete() }
            }
        }
    }

    private fun archive(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): File =
        temporary.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, content) in entries) {
                    val entry = ZipEntry(name)
                    if (stored) {
                        entry.method = ZipEntry.STORED
                        entry.size = content.size.toLong()
                        entry.crc = CRC32().apply { update(content) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }

    private fun File.outputStreamAt(offset: Long) = java.io.RandomAccessFile(this, "rw").use { file ->
        val original = ByteBuffer.allocate(1)
        check(file.channel.read(original, offset) == 1)
        file.seek(offset)
        file.write(original.array()[0].toInt() xor 1)
    }
}
