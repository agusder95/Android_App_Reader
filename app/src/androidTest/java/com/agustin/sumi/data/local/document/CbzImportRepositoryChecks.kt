package com.agustin.sumi.data.local.document

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.agustin.sumi.data.repository.LocalCbzImportRepository
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal class CbzImportRepositoryChecks(private val context: Context) {
    private val documents = SafDocumentSource(context)
    private val cacheDirectory = File(context.cacheDir, "cbz-import")

    suspend fun run(): List<String> {
        val tests = listOf(
            ::returnsEquivalentPagesAndCleansTemporaries,
            ::invalidatesCachedResultsOnMissingOrRevokedSource,
            ::releasesOnlyNewGrantsOnFailure,
            ::sharesConcurrentAndRepeatedImports,
            ::cancelsBeforeReturningAnAlreadyReadArchive,
            ::cancelsBlockedReadAndCleansUpAfterRelease,
        )
        return tests.map { test ->
            try {
                test()
                test.name
            } catch (error: Exception) {
                throw IllegalStateException(test.name, error)
            }
        }
    }

    /** The host force-stops the test process once the 12-byte partial copy is visible. */
    suspend fun prepareInterruptedCopy(): Nothing = coroutineScope {
        val regular = prepare("cbz-regular")
        val gated = prepare("cbz-gated")
        val repository = LocalCbzImportRepository(documents)
        success(repository.importCbz(regular))
        check(isPersisted(regular))
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory)
        File(cacheDirectory, RESTART_CONTROL_FILE).writeText("keep")
        val copy = async { repository.importCbz(gated) }
        awaitCondition { temporaries().any { it.length() == 12L } }
        check(!copy.isCompleted)
        withTimeout(25000) { awaitCancellation() }
    }

    suspend fun verifyInterruptedCopyRecovery(): List<String> {
        val regular = CbzSourceReference("content://${SafTestProvider.AUTHORITY}/cbz-regular")
        val foreign = File(cacheDirectory, RESTART_CONTROL_FILE)
        try {
            val partial = temporaries().single()
            check(partial.length() == 12L && foreign.readText() == "keep")
            check(isPersisted(regular))
            val reopened = success(LocalCbzImportRepository(documents).importCbz(regular))
            check(reopened.source == regular && reopened.pages.size == 2)
            check(temporaries().isEmpty() && foreign.readText() == "keep")
            return listOf(
                "reopens a previously imported CBZ using its persisted URI after process restart",
                "removes the actual interrupted copy and preserves the unrelated file",
            )
        } finally {
            foreign.delete()
            command("revoke", "cbz-regular")
            command("revoke", "cbz-gated")
        }
    }

    private suspend fun returnsEquivalentPagesAndCleansTemporaries() {
        val regular = prepare("cbz-regular")
        val pipe = prepare("cbz-pipe")
        val repository = LocalCbzImportRepository(documents)
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory)
        val orphan = File(cacheDirectory, "import-98765432-1234-1234-1234-123456789abc.cbz.part")
        val foreign = File(cacheDirectory, "repository-foreign-fixture")
        orphan.writeText("interrupted")
        foreign.writeText("keep")
        try {
            val direct = success(repository.importCbz(regular))
            check(!orphan.exists() && foreign.readText() == "keep")
            val copy = success(repository.importCbz(pipe))
            check(direct.source == regular && copy.source == pipe)
            check(direct.pages == copy.pages && direct.pages.map { it.id }.toSet().size == 2)
            check(temporaries().isEmpty())
        } finally {
            foreign.delete()
            command("revoke", "cbz-regular")
            command("revoke", "cbz-pipe")
        }
    }

    private suspend fun invalidatesCachedResultsOnMissingOrRevokedSource() {
        val name = "cbz-regular"
        val source = prepare(name)
        val repository = LocalCbzImportRepository(documents)
        try {
            val first = success(repository.importCbz(source))
            command("missing", name)
            check(repository.importCbz(source) == CbzImportResult.Failure(CbzImportError.SOURCE_UNAVAILABLE))
            command("restore", name)
            val restored = success(repository.importCbz(source))
            check(first == restored && first !== restored)
            command("revoke", name)
            check(repository.importCbz(source) == CbzImportResult.Failure(CbzImportError.ACCESS_DENIED))
            command("persistable", name)
            val reselected = success(repository.importCbz(source))
            check(reselected == restored && reselected !== restored)
        } finally {
            command("restore", name)
            command("revoke", name)
        }
    }

    private suspend fun releasesOnlyNewGrantsOnFailure() {
        val name = "cbz-corrupt"
        val source = prepare(name)
        val repository = LocalCbzImportRepository(documents)
        val expected = CbzImportResult.Failure(CbzImportError.INVALID_ARCHIVE)
        try {
            check(repository.importCbz(source) == expected && !isPersisted(source))
            command("persistable", name)
            check(documents.persistReadPermission(source))
            check(repository.importCbz(source) == expected && isPersisted(source))
            check(count(name) == 2)
        } finally {
            command("revoke", name)
        }
    }

    private suspend fun sharesConcurrentAndRepeatedImports() = coroutineScope {
        val name = "cbz-gated"
        val source = prepare(name)
        val repository = LocalCbzImportRepository(documents)
        val first = async { repository.importCbz(source) }
        try {
            awaitCondition { temporaries().any { it.length() == 12L } }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { repository.importCbz(source) }
            val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { repository.importCbz(source) }
            cancelledWaiter.cancelAndJoin()
            check(!first.isCompleted && !waiting.isCompleted && count(name) == 1)
            command("release", name)
            val result = success(first.await())
            check(result === waiting.await() && count(name) == 1)
            check(result === repository.importCbz(source) && count(name) == 2)
            check(temporaries().isEmpty())
        } finally {
            command("release", name)
        }
    }

    private suspend fun cancelsBeforeReturningAnAlreadyReadArchive() = coroutineScope {
        val name = "cbz-pipe"
        val source = prepare(name)
        val repository = LocalCbzImportRepository(documents)
        val dispatcher = QueuedDispatcher()
        val returned = AtomicInteger()
        val importing = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            repository.importCbz(source).also { returned.incrementAndGet() }
        }
        try {
            dispatcher.next().run() // Resume after persisting the grant.
            val afterRead = dispatcher.next()
            check(count(name) == 1 && temporaries().isEmpty() && returned.get() == 0)
            importing.cancel()
            afterRead.run()
            while (!importing.isCompleted) dispatcher.next().run()
            check(importing.isCancelled && returned.get() == 0 && !isPersisted(source))
            command("persistable", name)
            success(repository.importCbz(source))
            check(count(name) == 2 && temporaries().isEmpty())
        } finally {
            importing.cancel()
            while (!importing.isCompleted) dispatcher.next().run()
            command("revoke", name)
        }
    }

    private suspend fun cancelsBlockedReadAndCleansUpAfterRelease() = coroutineScope {
        val name = "cbz-blocked"
        val source = prepare(name)
        val repository = LocalCbzImportRepository(documents)
        val returned = AtomicInteger()
        val owner = async { repository.importCbz(source).also { returned.incrementAndGet() } }
        try {
            awaitCondition { temporaries().any { it.length() == 12L } }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                repository.importCbz(source).also { returned.incrementAndGet() }
            }
            owner.cancel()
            check(!owner.isCompleted && !waiter.isCompleted && returned.get() == 0)
            check(temporaries().single().length() == 12L && count(name) == 1)
            command("release", name)
            owner.join()
            check(runCatching { waiter.await() }.exceptionOrNull() is CancellationException)
            awaitCondition { command("closed", name).getBoolean("closed") }
            check(returned.get() == 0 && temporaries().isEmpty() && !isPersisted(source))
            command("persistable", name)
            success(repository.importCbz(source))
            check(count(name) == 2 && temporaries().isEmpty())
        } finally {
            command("release", name)
        }
    }

    private fun success(result: CbzImportResult): CbzImportResult.Success {
        check(result is CbzImportResult.Success) { "Expected an import success, got $result" }
        return result
    }

    private fun prepare(name: String): CbzSourceReference {
        command("revoke", name)
        command("reset", name)
        command("persistable", name)
        return CbzSourceReference("content://${SafTestProvider.AUTHORITY}/$name")
    }

    private fun command(method: String, name: String): Bundle =
        checkNotNull(context.contentResolver.call(Uri.parse("content://${SafTestProvider.AUTHORITY}"), method, name, null))

    private fun count(name: String): Int = command("count", name).getInt("count")

    private fun isPersisted(source: CbzSourceReference): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == source.value }

    private fun temporaries(): List<File> =
        cacheDirectory.listFiles().orEmpty().filter { it.name.startsWith("import-") && it.name.endsWith(".cbz.part") }

    private suspend fun awaitCondition(predicate: () -> Boolean) = withTimeout(10000) {
        while (!predicate()) delay(10)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun next(): Runnable = checkNotNull(queue.poll(10, TimeUnit.SECONDS)) { "Missing coroutine continuation" }
    }

    private companion object {
        const val RESTART_CONTROL_FILE = "repository-restart-control"
    }
}
