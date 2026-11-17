package com.agustin.sumi.data.local.archive

import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzPageDescriptor
import com.agustin.sumi.domain.model.CbzPageFormat
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.CbzPagePathOrder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun main() {
    val cases = listOf(
        ::readsStoredAndDeflatedZip,
        ::readsArchivesFromZipOutputStream,
        ::acceptsAnEndSignatureInTheComment,
        ::acceptsSeveralEndCandidatesInAMaximumLengthComment,
        ::skipsAnInvalidEndCandidateBeforeTheRealRecord,
        ::rejectsARealCorruptEndWithCommentCandidates,
        ::verifiesCrcWithAnEndCandidateInTheComment,
        ::validatesZip64WithAnEndCandidateInTheComment,
        ::preservesLegacyNameBytes,
        ::acceptsAuxiliaryOnlyArchiveForPageClassification,
        ::distinguishesEmptyInvalidAndCorrupt,
        ::rejectsLocalEntriesWithoutCentralDirectory,
        ::rejectsBrokenCentralOffsetsAndLengths,
        ::rejectsMismatchedEntryCounts,
        ::rejectsInvalidLocalReferencesAndNames,
        ::rejectsMalformedUtf8EntryNames,
        ::rejectsUnsafePaths,
        ::rejectsEncryptedAndUnsupportedMethods,
        ::validatesCrcOnBothAccessPaths,
        ::rejectsTrailingBytesInDeflatedPayload,
        ::rejectsTruncatedDeflatedPayload,
        ::acceptsAndVerifiesBothDescriptorLayouts,
        ::acceptsZip64EndRecordsAndEntryFields,
        ::acceptsZip64WithUnsignedDescriptorsAndClassicEnd,
        ::rejectsMalformedZip64LocatorAndEnd,
        ::rejectsMalformedZip64ExtraFieldsAndDescriptor,
        ::enforcesWideExpandedSizeWithoutIntegerWrap,
        ::enforcesContainerLimit,
        ::enforcesActualEntryCountBeforeDecoding,
        ::readsLongFieldsWithBoundedIndex,
        ::enforcesImageLimitUsingContentHook,
        ::enforcesTotalIncludingAuxiliaryFiles,
        ::enforcesLimitsWhenSizeMetadataUnderstatesExpansion,
        ::propagatesCancellationDuringStructureAndPayload,
        ::identifiesPagesByContentAndOrdersMixedArchive,
        ::rejectsShortAndForeignSignatures,
        ::ordersSegmentsAndLongDigitRunsIndependentlyOfLocale,
        ::keepsDistinctStablePageIdsAcrossFoldersAndCase,
        ::normalizesPathAliasesWithoutChangingIdentity,
        ::rejectsDuplicateNormalizedPathsAcrossAllEntries,
        ::rejectsPathsMadeUnsafeByNormalization,
        ::matchesLegacyAndUtf8NamesForIdentity,
        ::reportsNoSupportedImages,
        ::appliesImageBudgetToRecognizedContent,
    )
    cases.forEach { test ->
        test()
        println("PASS ${test.name}")
    }
    println("${cases.size} tests passed")
}

private val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
private val classifyImage: (ByteArray) -> Boolean = { it.take(3) == image.toList() }

private fun identifiesPagesByContentAndOrdersMixedArchive() {
    val jpeg = Base64.getDecoder().decode(
        ("/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/" +
            "2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAABAAEDASIAAhEBAxEB/" +
            "8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAk" +
            "M2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztL" +
            "W2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgEC" +
            "BAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpj" +
            "ZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+" +
            "Pn6/9oADAMBAAIRAxEAPwD8qqKKKAP/2Q=="),
    )
    val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAALSURBVBhXY2AAAgAABQABqtXIUQAAAABJRU5ErkJggg==",
    )
    val webp = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA")
    val zip = fixture(
        Item("page10.jpg", jpeg, method = ZipEntry.DEFLATED),
        Item("page02.jpg", jpeg),
        Item("extra/", byteArrayOf()),
        Item("extra/scan.PNG", png),
        Item("extra/cover.JPEG", jpeg),
        Item("extra/back.webp", webp),
        Item("fake.jpg", "not an image".encodeToByteArray()),
        Item("notes.txt", "notes".encodeToByteArray()),
        Item("page2.jpg", jpeg),
        Item("renamed.bin", png),
    )
    check(pagesOnBothPaths(zip.bytes) == listOf(
        page("extra/back.webp", CbzPageFormat.WEBP),
        page("extra/cover.JPEG", CbzPageFormat.JPEG),
        page("extra/scan.PNG", CbzPageFormat.PNG),
        page("page2.jpg"),
        page("page02.jpg"),
        page("page10.jpg"),
        page("renamed.bin", CbzPageFormat.PNG),
    ))
}

private fun rejectsShortAndForeignSignatures() {
    check(identifyCbzPageFormat(byteArrayOf(0xff.toByte(), 0xd8.toByte())) == null)
    check(identifyCbzPageFormat("RIFF1234WAVE".encodeToByteArray()) == null)
    check(identifyCbzPageFormat("RIFF1234WEB".encodeToByteArray()) == null)
    check(identifyCbzPageFormat(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) == null)
}

private fun ordersSegmentsAndLongDigitRunsIndependentlyOfLocale() {
    val expected = listOf(
        "chapter2/Page2.jpg",
        "chapter2/page2.jpg",
        "chapter2/page02.jpg",
        "chapter2/page10.jpg",
        "chapter2/page" + "9".repeat(90) + ".jpg",
        "chapter2/page1" + "0".repeat(90) + ".jpg",
        "chapter02/page1.jpg",
        "chapter10/page1.jpg",
        "chapter10/part2/page1.jpg",
        "chapter10/part11/page1.jpg",
        "I/page1.jpg",
        "i/page1.jpg",
    )
    val originalLocale = Locale.getDefault()
    try {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        check(expected.reversed().sortedWith(CbzPagePathOrder) == expected)
        Locale.setDefault(Locale.US)
        check(expected.sortedWith(CbzPagePathOrder) == expected)
    } finally {
        Locale.setDefault(originalLocale)
    }
}

private fun keepsDistinctStablePageIdsAcrossFoldersAndCase() {
    val paths = listOf("A/page1.jpg", "a/page1.jpg", "b/page1.jpg")
    val first = pagesOnBothPaths(fixture(*paths.map { Item(it, image) }.toTypedArray()).bytes)
    val second = pagesOnBothPaths(fixture(*paths.reversed().map { Item(it, image) }.toTypedArray()).bytes)
    check(first == paths.map { page(it) } && first == second)
    check(first.map { it.id }.toSet().size == paths.size)
    check(first.none { "page1.jpg" in it.toString() })
}

private fun normalizesPathAliasesWithoutChangingIdentity() {
    val alias = fixture(Item("./chapter//./page.jpg", image))
    val literal = fixture(Item("chapter/page.jpg", image))
    check(pagesOnBothPaths(alias.bytes) == pagesOnBothPaths(literal.bytes))
}

private fun rejectsDuplicateNormalizedPathsAcrossAllEntries() {
    rejectPages(fixture(Item("x.jpg", image), Item("x.jpg", image)), CbzImportError.DUPLICATE_ENTRY_PATH)
    rejectPages(fixture(Item("a/p.jpg", image), Item("./a//p.jpg", image)), CbzImportError.DUPLICATE_ENTRY_PATH)
    rejectPages(
        fixture(Item("x.jpg", image), Item("notes.txt", byteArrayOf()), Item("./notes.txt", byteArrayOf())),
        CbzImportError.DUPLICATE_ENTRY_PATH,
    )
}

private fun rejectsPathsMadeUnsafeByNormalization() {
    rejectPages(fixture(Item(".", image)), CbzImportError.UNSAFE_ENTRY_PATH)
    rejectPages(fixture(Item("./C:/page.jpg", image)), CbzImportError.UNSAFE_ENTRY_PATH)
}

private fun matchesLegacyAndUtf8NamesForIdentity() {
    val zip = fixture(Item("x.jpg", image))
    val legacy = zip.bytes.patched {
        putShort(6, 0)
        putShort(zip.central + 8, 0)
        put(30, 0x82.toByte())
        put(zip.central + 46, 0x82.toByte())
    }
    check(pagesOnBothPaths(legacy) == listOf(page("é.jpg")))
    check(pagesOnBothPaths(legacy) == pagesOnBothPaths(fixture(Item("é.jpg", image)).bytes))
}

private fun reportsNoSupportedImages() {
    rejectPages(fixture(Item("fake.jpg", "notes".encodeToByteArray())), CbzImportError.NO_SUPPORTED_IMAGES)
    rejectPages(fixture(), CbzImportError.EMPTY_ARCHIVE)
}

private fun appliesImageBudgetToRecognizedContent() {
    val zip = fixture(Item("page.bin", image + ByteArray(20)))
    rejectPages(zip, CbzImportError.RESOURCE_LIMIT_EXCEEDED, ZipValidationLimits(imageBytes = 22))
    check(pagesOnBothPaths(zip.bytes, ZipValidationLimits(imageBytes = 23)) == listOf(page("page.bin")))
}

private fun page(path: String, format: CbzPageFormat = CbzPageFormat.JPEG): CbzPageDescriptor {
    val id = MessageDigest.getInstance("SHA-256").digest(path.encodeToByteArray()).toHexString()
    return CbzPageDescriptor(CbzPageId(id), format)
}

private fun pagesOnBothPaths(
    bytes: ByteArray,
    limits: ZipValidationLimits = ZipValidationLimits(),
): List<CbzPageDescriptor> {
    var pages = emptyList<CbzPageDescriptor>()
    withFixture(bytes) { file ->
        val reader = CbzArchiveReader(limits)
        pages = file.inputStream().use { reader.readPages(it.channel) {} }
        check(reader.readPages(file) {} == pages)
    }
    return pages
}

private fun rejectPages(zip: Fixture, error: CbzImportError, limits: ZipValidationLimits = ZipValidationLimits()) {
    withFixture(zip.bytes) { file ->
        val reader = CbzArchiveReader(limits)
        val channelError = runCatching { file.inputStream().use { reader.readPages(it.channel) {} } }.exceptionOrNull()
        val fileError = runCatching { reader.readPages(file) {} }.exceptionOrNull()
        check(channelError is ZipValidationException && channelError.error == error)
        check(fileError is ZipValidationException && fileError.error == error)
    }
}

private fun readsStoredAndDeflatedZip() {
    val bytes = fixture(
        Item("nested/", byteArrayOf()),
        Item("nested/page.jpg", image + byteArrayOf(1, 2), method = ZipEntry.DEFLATED),
        Item("notes.txt", "text".toByteArray()),
    ).bytes
    validateBoth(bytes) { entries ->
        check(entries.size == 3 && entries.first().directory)
        check(classifyImage(entries[1].prefix) && entries[1].name.utf8)
        check(entries[2].prefix.contentEquals("text".toByteArray()))
    }
    validateBoth(fixture(Item("stored", image)).bytes) {
        check(it.single().prefix.contentEquals(image))
    }
}

private fun readsArchivesFromZipOutputStream() {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.setComment("generated ZIP")
        zip.putNextEntry(ZipEntry("nested/"))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("nested/página.jpg"))
        zip.write(image)
        zip.closeEntry()
    }
    validateBoth(bytes.toByteArray()) { check(it.size == 2 && it[0].directory && classifyImage(it[1].prefix)) }
}

private fun acceptsAnEndSignatureInTheComment() {
    val zip = fixture(Item("page.jpg", image))
    validateBoth(zip.withComment(endCandidate())) {
        check(it.single().prefix.contentEquals(image))
    }
}

private fun acceptsSeveralEndCandidatesInAMaximumLengthComment() {
    val zip = fixture(Item("page.jpg", image))
    val comment = ByteArray(65535) { 'a'.code.toByte() }
    val firstFakeOffset = zip.bytes.size
    endCandidate(
        remaining = comment.size - 22,
        centralSize = firstFakeOffset - zip.central,
        centralOffset = zip.central,
    ).copyInto(comment)
    endCandidate().copyInto(comment, comment.size - 22)
    validateBoth(zip.withComment(comment)) { check(it.single().prefix.contentEquals(image)) }
}

private fun skipsAnInvalidEndCandidateBeforeTheRealRecord() {
    val central = ByteArray(46).patched { putInt(0, 0x02014b50) }
    val placeholder = fixture(Item("x", ByteArray(central.size + 22)))
    val payloadOffset = 31
    val candidate = endCandidate(
        remaining = placeholder.bytes.size - payloadOffset - central.size - 22,
        centralSize = central.size,
        centralOffset = payloadOffset,
    )
    val zip = fixture(Item("x", central + candidate))
    validateBoth(zip.bytes) { check(it.single().prefix.contentEquals(central.copyOf(12))) }
}

private fun rejectsARealCorruptEndWithCommentCandidates() {
    val zip = fixture(Item("page.jpg", image))
    val commented = zip.withComment(endCandidate())
    reject(commented.patched { putInt(zip.end + 16, Int.MAX_VALUE) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(commented.patched { putShort(zip.end + 20, 21) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun verifiesCrcWithAnEndCandidateInTheComment() {
    val zip = fixture(Item("x", image + byteArrayOf(42)))
    val commented = zip.withComment(endCandidate())
    reject(commented.patched { put(34, 43) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun validatesZip64WithAnEndCandidateInTheComment() {
    val zip = fixture(Item("x", image), wideEntries = true, wideEnd = true)
    val commented = zip.withComment(endCandidate())
    validateBoth(commented) { check(it.single().prefix.contentEquals(image)) }
    reject(commented.patched { putLong(zip.end - 12, Long.MAX_VALUE) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun endCandidate(remaining: Int = 0, centralSize: Int = 0, centralOffset: Int = 0): ByteArray {
    val record = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
    record.putInt(0x06054b50)
    record.putShort(0)
    record.putShort(0)
    record.putShort(1)
    record.putShort(1)
    record.putInt(centralSize)
    record.putInt(centralOffset)
    record.putShort(remaining.toShort())
    return record.array()
}

private fun acceptsAuxiliaryOnlyArchiveForPageClassification() {
    validateBoth(fixture(Item("fake.jpg", "text".toByteArray())).bytes) {
        check(!classifyImage(it.single().prefix))
    }
}

private fun preservesLegacyNameBytes() {
    val zip = fixture(Item("x", image))
    val name = 0x82.toByte()
    val legacyZip = zip.bytes.patched {
        putShort(6, 0)
        putShort(zip.central + 8, 0)
        put(30, name)
        put(zip.central + 46, name)
    }
    withFixture(legacyZip) { file ->
        val entry = CbzArchiveReader().validate(file, classifyImage) {}.single()
        check(!entry.name.utf8)
        file.inputStream().use { check(entry.name.readBytes(it.channel).contentEquals(byteArrayOf(name))) }
    }
}

private fun distinguishesEmptyInvalidAndCorrupt() {
    reject(byteArrayOf(), CbzImportError.EMPTY_FILE)
    reject(fixture().bytes, CbzImportError.EMPTY_ARCHIVE)
    reject("this is not ZIP".toByteArray(), CbzImportError.INVALID_ARCHIVE)
    reject(fixture(Item("x", image)).bytes.copyOf(15), CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsLocalEntriesWithoutCentralDirectory() {
    val zip = fixture(Item("x", image))
    val localOnly = zip.bytes.copyOf(zip.central)
    ZipInputStream(localOnly.inputStream()).use {
        check(it.nextEntry != null && it.readBytes().contentEquals(image))
    }
    reject(localOnly, CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsBrokenCentralOffsetsAndLengths() {
    val zip = fixture(Item("x", image))
    reject(zip.bytes.patched { putInt(zip.end + 16, Int.MAX_VALUE) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putInt(zip.end + 12, 0) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putShort(zip.central + 30, -1) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsMismatchedEntryCounts() {
    val zip = fixture(Item("a", image), Item("b", image))
    reject(zip.withCount(1), CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.withCount(3), CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putShort(zip.end + 8, 1) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsInvalidLocalReferencesAndNames() {
    val zip = fixture(Item("a", image), Item("b", image))
    reject(zip.bytes.patched { putInt(zip.central + 42, zip.central) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putInt(zip.central + 47 + 42, 0) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { put(30, 'x'.code.toByte()) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsUnsafePaths() {
    for (name in listOf("../page.jpg", "a/../page.jpg", "/page.jpg", "C:/page.jpg", "a\\..\\page.jpg", "a\u0000b")) {
        reject(fixture(Item(name, image)).bytes, CbzImportError.UNSAFE_ENTRY_PATH)
    }
}

private fun rejectsMalformedUtf8EntryNames() {
    val zip = fixture(Item("xx", image))
    reject(zip.bytes.patched {
        put(30, 0xc0.toByte())
        put(zip.central + 46, 0xc0.toByte())
    }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsEncryptedAndUnsupportedMethods() {
    reject(fixture(Item("a", image, flags = 1)).bytes, CbzImportError.UNSUPPORTED_ARCHIVE)
    reject(fixture(Item("a", image, method = 99)).bytes, CbzImportError.UNSUPPORTED_ARCHIVE)
    val zip = fixture(Item("a", image))
    reject(zip.bytes.patched { putShort(zip.end + 4, 1) }, CbzImportError.UNSUPPORTED_ARCHIVE)
}

private fun validatesCrcOnBothAccessPaths() {
    val zip = fixture(Item("x", image + byteArrayOf(42)))
    reject(zip.bytes.patched { put(34, 43) }, CbzImportError.CORRUPT_ARCHIVE)
    val auxiliary = fixture(Item("x", byteArrayOf(1, 2)))
    reject(auxiliary.bytes.patched { put(31, 7) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsTrailingBytesInDeflatedPayload() {
    val zip = fixture(Item("x", image, method = ZipEntry.DEFLATED, packedSuffix = byteArrayOf(99)))
    reject(zip.bytes, CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsTruncatedDeflatedPayload() {
    val zip = fixture(
        Item("x", image + ByteArray(64), method = ZipEntry.DEFLATED, truncatePackedBy = 2),
    )
    reject(zip.bytes, CbzImportError.CORRUPT_ARCHIVE)
}

private fun acceptsAndVerifiesBothDescriptorLayouts() {
    for (signed in listOf(false, true)) {
        val zip = fixture(Item("x", image, method = ZipEntry.DEFLATED, descriptor = signed))
        validateBoth(zip.bytes) { check(it.single().prefix.contentEquals(image)) }
        reject(zip.bytes.patched { putInt(zip.central - 4, 4) }, CbzImportError.CORRUPT_ARCHIVE)
    }
    validateBoth(fixture(Item("x", image, descriptor = false)).bytes) { check(it.size == 1) }
}

private fun acceptsZip64EndRecordsAndEntryFields() {
    for (item in listOf(Item("x", image), Item("x", image, method = ZipEntry.DEFLATED, descriptor = true))) {
        validateBoth(fixture(item, wideEntries = true, wideEnd = true).bytes) {
            check(it.single().prefix.contentEquals(image))
        }
    }
}

private fun acceptsZip64WithUnsignedDescriptorsAndClassicEnd() {
    val zip = fixture(Item("x", image, method = ZipEntry.DEFLATED, descriptor = false), wideEntries = true)
    validateBoth(zip.bytes) { check(it.single().prefix.contentEquals(image)) }
}

private fun rejectsMalformedZip64LocatorAndEnd() {
    val zip = fixture(Item("x", image), wideEntries = true, wideEnd = true)
    reject(zip.bytes.copyOf(zip.bytes.size - 1), CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putLong(zip.end - 12, Long.MAX_VALUE) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putLong(zip.end - 76 + 32, 2) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putLong(zip.end - 76 + 40, Long.MAX_VALUE) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putLong(zip.end - 76 + 4, 43) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun rejectsMalformedZip64ExtraFieldsAndDescriptor() {
    val zip = fixture(Item("x", image, method = ZipEntry.DEFLATED, descriptor = true), wideEntries = true)
    reject(zip.bytes.patched { putShort(zip.central + 47 + 2, 8) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putLong(zip.central + 47 + 20, -1) }, CbzImportError.CORRUPT_ARCHIVE)
    reject(zip.bytes.patched { putLong(zip.central - 8, 4) }, CbzImportError.CORRUPT_ARCHIVE)
}

private fun enforcesWideExpandedSizeWithoutIntegerWrap() {
    val zip = fixture(Item("aux", image, method = ZipEntry.DEFLATED), wideEntries = true)
    val expandedLimit = 4L * 1024 * 1024 * 1024
    val overLimit = zip.bytes.patched {
        putLong(37, expandedLimit + 1)
        putLong(zip.central + 53, expandedLimit + 1)
    }
    reject(overLimit, CbzImportError.RESOURCE_LIMIT_EXCEEDED)
    val exactLimit = overLimit.patched {
        putLong(37, expandedLimit)
        putLong(zip.central + 53, expandedLimit)
    }
    reject(exactLimit, CbzImportError.CORRUPT_ARCHIVE)
}

private fun enforcesContainerLimit() {
    val zip = fixture(Item("x", image)).bytes
    validateBoth(zip, ZipValidationLimits(archiveBytes = zip.size.toLong())) { check(it.size == 1) }
    reject(zip, CbzImportError.RESOURCE_LIMIT_EXCEEDED, ZipValidationLimits(archiveBytes = zip.size - 1L))
}

private fun enforcesActualEntryCountBeforeDecoding() {
    val zip = fixture(Item("dir/", byteArrayOf()), Item("aux", image), Item("extra", image))
    validateBoth(zip.bytes, ZipValidationLimits(entries = 3)) { check(it.size == 3) }
    val limits = ZipValidationLimits(entries = 2)
    reject(zip.bytes, CbzImportError.RESOURCE_LIMIT_EXCEEDED, limits)
    reject(zip.withCount(2), CbzImportError.RESOURCE_LIMIT_EXCEEDED, limits, isImage = { error("Started decoding") })
}

private fun readsLongFieldsWithBoundedIndex() {
    val extra = LittleEndian().apply {
        u16(0xcafe)
        u16(60000)
        bytes(ByteArray(60000))
    }.result()
    val entries = Array(12) {
        Item("file$it" + "a".repeat(29000), image, extra = extra, comment = ByteArray(30000))
    }
    val zip = fixture(*entries)
    check(zip.end - zip.central > 1024 * 1024)
    validateBoth(zip.bytes, ZipValidationLimits(entries = 12)) { check(it.size == 12) }
    reject(
        zip.withCount(11), CbzImportError.RESOURCE_LIMIT_EXCEEDED, ZipValidationLimits(entries = 11),
        isImage = { error("Started decoding") },
    )
}

private fun enforcesImageLimitUsingContentHook() {
    val zip = fixture(Item("image.txt", image + ByteArray(9), method = ZipEntry.DEFLATED)).bytes
    validateBoth(zip, ZipValidationLimits(imageBytes = 12)) { check(classifyImage(it.single().prefix)) }
    reject(zip, CbzImportError.RESOURCE_LIMIT_EXCEEDED, ZipValidationLimits(imageBytes = 11))
}

private fun enforcesTotalIncludingAuxiliaryFiles() {
    val zip = fixture(Item("page", image), Item("aux", ByteArray(13), method = ZipEntry.DEFLATED)).bytes
    validateBoth(zip, ZipValidationLimits(imageBytes = 3, expandedBytes = 16)) { check(it.size == 2) }
    reject(zip, CbzImportError.RESOURCE_LIMIT_EXCEEDED, ZipValidationLimits(imageBytes = 3, expandedBytes = 15))
}

private fun enforcesLimitsWhenSizeMetadataUnderstatesExpansion() {
    val bytes = image + ByteArray(128 * 1024)
    val zip = fixture(Item("image", bytes, method = ZipEntry.DEFLATED, claimedSize = 12)).bytes
    reject(zip, CbzImportError.RESOURCE_LIMIT_EXCEEDED, ZipValidationLimits(imageBytes = 32))
    reject(zip, CbzImportError.RESOURCE_LIMIT_EXCEEDED, ZipValidationLimits(expandedBytes = 32))
    reject(zip, CbzImportError.CORRUPT_ARCHIVE)
}

private fun propagatesCancellationDuringStructureAndPayload() {
    val zip = fixture(Item("x", image + ByteArray(128 * 1024), method = ZipEntry.DEFLATED)).bytes
    var checks = 0
    val cancelled = CancellationException()
    val preflight = runCatching {
        validateBoth(zip, checkCancellation = { if (++checks == 3) throw cancelled }) {}
    }.exceptionOrNull()
    check(preflight === cancelled)
    var reading = false
    val payload = runCatching {
        validateBoth(zip, isImage = { reading = true; false }, checkCancellation = {
            if (reading) throw cancelled
        }) {}
    }.exceptionOrNull()
    check(payload === cancelled)
}

private fun validateBoth(
    bytes: ByteArray,
    limits: ZipValidationLimits = ZipValidationLimits(),
    isImage: (ByteArray) -> Boolean = classifyImage,
    checkCancellation: () -> Unit = {},
    checkResult: (List<ValidatedZipEntry>) -> Unit,
) = withFixture(bytes) { file ->
    val reader = CbzArchiveReader(limits)
    file.inputStream().use {
        checkResult(reader.validate(it.channel, isImage, checkCancellation))
        check(it.channel.isOpen)
    }
    checkResult(reader.validate(file, isImage, checkCancellation))
}

private fun reject(
    bytes: ByteArray,
    error: CbzImportError,
    limits: ZipValidationLimits = ZipValidationLimits(),
    isImage: (ByteArray) -> Boolean = classifyImage,
) = withFixture(bytes) { file ->
    val reader = CbzArchiveReader(limits)
    val channelError = runCatching {
        file.inputStream().use { reader.validate(it.channel, isImage) {} }
    }.exceptionOrNull()
    val copyError = runCatching { reader.validate(file, isImage) {} }.exceptionOrNull()
    check(channelError is ZipValidationException && channelError.error == error) {
        "Expected $error on channel, got $channelError"
    }
    check(copyError is ZipValidationException && copyError.error == error) {
        "Expected $error on private copy, got $copyError"
    }
}

private fun withFixture(bytes: ByteArray, block: (java.io.File) -> Unit) {
    val path = Files.createTempFile("sumi-zip-test-", ".cbz")
    try {
        Files.write(path, bytes)
        block(path.toFile())
    } finally {
        Files.delete(path)
    }
}

private class Item(
    val name: String,
    val bytes: ByteArray,
    val method: Int = ZipEntry.STORED,
    val flags: Int = 0,
    val descriptor: Boolean? = null,
    val claimedSize: Int = bytes.size,
    val packedSuffix: ByteArray = byteArrayOf(),
    val truncatePackedBy: Int = 0,
    val extra: ByteArray = byteArrayOf(),
    val comment: ByteArray = byteArrayOf(),
)

private class Fixture(val bytes: ByteArray, val central: Int, val end: Int) {
    fun withComment(comment: ByteArray): ByteArray =
        bytes.patched { putShort(end + 20, comment.size.toShort()) } + comment

    fun withCount(count: Short): ByteArray = bytes.patched {
        putShort(end + 8, count)
        putShort(end + 10, count)
    }
}

private fun fixture(vararg items: Item, wideEntries: Boolean = false, wideEnd: Boolean = false): Fixture {
    val local = LittleEndian()
    val central = LittleEndian()
    for (item in items) {
        val name = item.name.toByteArray()
        val payload = if (item.method == ZipEntry.DEFLATED) deflate(item.bytes) else item.bytes
        val packed = payload.copyOf(payload.size - item.truncatePackedBy) + item.packedSuffix
        val crc = CRC32().apply { update(item.bytes) }.value
        val offset = local.size.toLong()
        val flags = 0x800 or item.flags or (if (item.descriptor != null) 8 else 0)
        val localExtra = if (wideEntries) zip64Extra(
            if (item.descriptor != null) 0 else item.claimedSize.toLong(),
            if (item.descriptor != null) 0 else packed.size.toLong(),
        ) + item.extra else item.extra
        val centralExtra = if (wideEntries) {
            zip64Extra(item.claimedSize.toLong(), packed.size.toLong(), offset) + item.extra
        } else {
            item.extra
        }
        val version = if (wideEntries) 45 else 20
        local.u32(0x04034b50)
        local.u16(version)
        local.u16(flags)
        local.u16(item.method)
        local.u32(0)
        local.u32(if (item.descriptor != null) 0 else crc)
        local.u32(if (wideEntries) -1 else if (item.descriptor != null) 0 else packed.size.toLong())
        local.u32(if (wideEntries) -1 else if (item.descriptor != null) 0 else item.claimedSize.toLong())
        local.u16(name.size)
        local.u16(localExtra.size)
        local.bytes(name)
        local.bytes(localExtra)
        local.bytes(packed)
        if (item.descriptor != null) {
            if (item.descriptor) local.u32(0x08074b50)
            local.u32(crc)
            if (wideEntries) {
                local.u64(packed.size.toLong())
                local.u64(item.claimedSize.toLong())
            } else {
                local.u32(packed.size.toLong())
                local.u32(item.claimedSize.toLong())
            }
        }
        central.u32(0x02014b50)
        central.u16(version)
        central.u16(version)
        central.u16(flags)
        central.u16(item.method)
        central.u32(0)
        central.u32(crc)
        central.u32(if (wideEntries) -1 else packed.size.toLong())
        central.u32(if (wideEntries) -1 else item.claimedSize.toLong())
        central.u16(name.size)
        central.u16(centralExtra.size)
        central.u16(item.comment.size)
        central.u16(0)
        central.u16(0)
        central.u32(0)
        central.u32(if (wideEntries) -1 else offset)
        central.bytes(name)
        central.bytes(centralExtra)
        central.bytes(item.comment)
    }
    val centralStart = local.size
    local.bytes(central.result())
    if (wideEnd) {
        val zip64End = local.size
        local.u32(0x06064b50)
        local.u64(44)
        local.u16(45)
        local.u16(45)
        local.u32(0)
        local.u32(0)
        local.u64(items.size.toLong())
        local.u64(items.size.toLong())
        local.u64(central.size.toLong())
        local.u64(centralStart.toLong())
        local.u32(0x07064b50)
        local.u32(0)
        local.u64(zip64End.toLong())
        local.u32(1)
    }
    val end = local.size
    local.u32(0x06054b50)
    local.u16(0)
    local.u16(0)
    local.u16(if (wideEnd) 0xffff else items.size)
    local.u16(if (wideEnd) 0xffff else items.size)
    local.u32(if (wideEnd) -1 else central.size.toLong())
    local.u32(if (wideEnd) -1 else centralStart.toLong())
    local.u16(0)
    return Fixture(local.result(), centralStart, end)
}

private fun zip64Extra(vararg values: Long): ByteArray = LittleEndian().apply {
    u16(1)
    u16(values.size * 8)
    values.forEach(::u64)
}.result()

private fun deflate(bytes: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        DeflaterOutputStream(output, deflater).use { it.write(bytes) }
    } finally {
        deflater.end()
    }
    return output.toByteArray()
}

private fun ByteArray.patched(block: ByteBuffer.() -> Unit): ByteArray =
    copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).block() }

private class LittleEndian {
    private val output = ByteArrayOutputStream()
    val size: Int get() = output.size()

    fun u16(value: Int) = write(value.toLong(), 2)
    fun u32(value: Long) = write(value, 4)
    fun u64(value: Long) = write(value, 8)
    fun bytes(value: ByteArray) = output.write(value)
    fun result(): ByteArray = output.toByteArray()

    private fun write(value: Long, count: Int) {
        repeat(count) { index -> output.write((value ushr (8 * index)).toInt()) }
    }
}
