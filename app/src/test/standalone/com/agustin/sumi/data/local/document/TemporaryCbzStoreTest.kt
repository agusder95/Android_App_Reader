package com.agustin.sumi.data.local.document

import com.agustin.sumi.domain.model.CbzImportError
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

// Run on the JVM with the Kotlin compiler and standard library already used by the build.
fun main() {
    val cases = listOf(
        ::copiesAtTheLimitAndDeletesOnSuccess,
        ::rejectsTheFirstByteOverTheLimit,
        ::rejectsNoSpaceBeforeCopy,
        ::rejectsKnownLengthLargerThanAvailableSpace,
        ::rejectsSpaceLossDuringCopy,
        ::closesAndCleansUpAfterReadFailure,
        ::closesAndCleansUpAfterCancellation,
        ::cleansUpAfterConsumerFailure,
        ::recoversOnlyInactiveImportTemporaries,
    )
    cases.forEach { test ->
        withTestDirectory(test)
        println("PASS ${test.name}")
    }
    println("${cases.size} tests passed")
}

private fun copiesAtTheLimitAndDeletesOnSuccess(directory: File) {
    val bytes = byteArrayOf(1, 2, 3)
    val input = TrackingInput(bytes)
    var temporary: File? = null
    store(directory).withCopy(input, {}) { file, length ->
        check(length == 3L && file.readBytes().contentEquals(bytes))
        check(input.closed)
        temporary = file
    }
    check(temporary?.exists() == false)
}

private fun rejectsTheFirstByteOverTheLimit(directory: File) {
    val input = TrackingInput(byteArrayOf(1, 2, 3, 4, 5))
    expectError(CbzImportError.RESOURCE_LIMIT_EXCEEDED) {
        store(directory).withCopy(input, {}) { _, _ -> error("Oversized copy was returned") }
    }
    check(input.closed && input.available() == 1)
    check(directory.listFiles()!!.isEmpty())
}

private fun rejectsNoSpaceBeforeCopy(directory: File) {
    val input = TrackingInput(byteArrayOf(1))
    expectError(CbzImportError.INSUFFICIENT_SPACE) {
        store(directory) { 0L }.withCopy(input, {}) { _, _ -> error("Copy was returned") }
    }
    check(input.closed && input.available() == 1)
    check(directory.listFiles()!!.isEmpty())
}

private fun rejectsSpaceLossDuringCopy(directory: File) {
    var checks = 0
    val input = TrackingInput(byteArrayOf(1, 2, 3))
    expectError(CbzImportError.INSUFFICIENT_SPACE) {
        store(directory) { if (checks++ == 0) 3L else 2L }
            .withCopy(input, {}) { _, _ -> error("Copy was returned") }
    }
    check(input.closed && checks == 2)
    check(directory.listFiles()!!.isEmpty())
}

private fun rejectsKnownLengthLargerThanAvailableSpace(directory: File) {
    val input = TrackingInput(byteArrayOf(1, 2, 3))
    expectError(CbzImportError.INSUFFICIENT_SPACE) {
        store(directory) { 2L }.withCopy(input, {}, expectedLength = 3) { _, _ ->
            error("Copy was returned")
        }
    }
    check(input.closed && input.available() == 3)
    check(directory.listFiles()!!.isEmpty())
}

private fun closesAndCleansUpAfterReadFailure(directory: File) {
    val failure = IOException("Simulated read failure")
    val input = object : TrackingInput(byteArrayOf(1)) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
    }
    val thrown = runCatching {
        store(directory).withCopy(input, {}) { _, _ -> error("Copy was returned") }
    }.exceptionOrNull()
    check(thrown === failure && input.closed)
    check(directory.listFiles()!!.isEmpty())
}

private fun closesAndCleansUpAfterCancellation(directory: File) {
    var cancelled = false
    val input = object : TrackingInput(byteArrayOf(1)) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            cancelled = true
            return super.read(buffer, offset, length)
        }
    }
    val thrown = runCatching {
        store(directory).withCopy(input, {
            if (cancelled) throw CancellationException()
        }) { _, _ -> error("Cancelled copy was returned") }
    }.exceptionOrNull()
    check(thrown is CancellationException && input.closed)
    check(directory.listFiles()!!.isEmpty())
}

private fun cleansUpAfterConsumerFailure(directory: File) {
    val failure = IOException("Simulated consumer failure")
    val input = TrackingInput(byteArrayOf(1))
    val thrown = runCatching {
        store(directory).withCopy(input, {}) { _, _ -> throw failure }
    }.exceptionOrNull()
    check(thrown === failure && input.closed)
    check(directory.listFiles()!!.isEmpty())
}

private fun recoversOnlyInactiveImportTemporaries(directory: File) {
    val orphan = File(directory, "import-01234567-89ab-cdef-0123-456789abcdef.cbz.part")
    val unrelated = File(directory, "import-personal.cbz.part")
    orphan.writeText("orphan")
    unrelated.writeText("keep")
    store(directory).withCopy(TrackingInput(byteArrayOf(1)), {}) { active, _ ->
        store(directory).recoverOrphans()
        check(!orphan.exists() && unrelated.readText() == "keep" && active.exists())
    }
    check(directory.listFiles()!!.toList() == listOf(unrelated))
}

private fun store(directory: File, available: (File) -> Long = { 100L }) =
    TemporaryCbzStore(directory, maxBytes = 3, availableBytes = available) {
        error("Temporary cleanup failed")
    }

private fun expectError(error: CbzImportError, block: () -> Unit) {
    val thrown = runCatching(block).exceptionOrNull()
    check(thrown is DocumentAccessException && thrown.error == error)
}

private fun withTestDirectory(test: (File) -> Unit) {
    val directory = Files.createTempDirectory("sumi-cbz-test-").toFile()
    try {
        test(directory)
    } finally {
        directory.listFiles()?.forEach { Files.delete(it.toPath()) }
        Files.delete(directory.toPath())
    }
}

private open class TrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var closed = false
        private set

    override fun close() {
        closed = true
        super.close()
    }
}
