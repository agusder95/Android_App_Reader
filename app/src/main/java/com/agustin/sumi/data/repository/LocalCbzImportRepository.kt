package com.agustin.sumi.data.repository

import android.util.Log
import com.agustin.sumi.data.local.archive.CbzArchiveReader
import com.agustin.sumi.data.local.archive.ZipValidationException
import com.agustin.sumi.data.local.document.DocumentAccessException
import com.agustin.sumi.data.local.document.OpenedCbzDocument
import com.agustin.sumi.data.local.document.SafDocumentSource
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.repository.CbzImportRepository
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One instance owns the successful imports and in-flight requests of a session. */
internal class LocalCbzImportRepository(
    private val documents: SafDocumentSource,
    private val archives: CbzArchiveReader = CbzArchiveReader(),
) : CbzImportRepository {
    private val lock = Any()
    private val pending = mutableMapOf<CbzSourceReference, CompletableDeferred<CbzImportResult>>()
    private val imported = mutableMapOf<CbzSourceReference, CbzImportResult.Success>()

    override suspend fun importCbz(source: CbzSourceReference): CbzImportResult {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val request = CompletableDeferred<CbzImportResult>()
        val existing = synchronized(lock) {
            pending[source].also { if (it == null) pending[source] = request }
        }
        if (existing != null) return existing.await()

        var ownsGrant = false
        try {
            val result = try {
                val cached = synchronized(lock) { imported[source] }
                if (cached != null) {
                    documents.verifyAvailable(source)
                    cached
                } else {
                    ownsGrant = documents.persistReadPermission(source)
                    readArchive(source)
                }
            } catch (failure: DocumentAccessException) {
                CbzImportResult.Failure(failure.error)
            }
            if (result is CbzImportResult.Failure && ownsGrant) {
                ownsGrant = false
                releaseGrant(source)
            }
            synchronized(lock) {
                caller.ensureActive()
                if (result is CbzImportResult.Success) imported[source] = result else imported.remove(source)
                pending.remove(source)
            }
            request.complete(result)
            return result
        } catch (failure: Throwable) {
            try {
                if (ownsGrant) releaseGrant(source)
            } finally {
                synchronized(lock) { pending.remove(source) }
                request.completeExceptionally(failure)
            }
            throw failure
        }
    }

    private suspend fun readArchive(source: CbzSourceReference): CbzImportResult {
        val cancellation = currentCoroutineContext()
        val directOrCopy = documents.withDocument(source) { document ->
            try {
                readPages(source, document, cancellation::ensureActive)
            } catch (failure: IOException) {
                // Retry an unusable positioned read via a fresh, sequential copy.
                if (document !is OpenedCbzDocument.Direct) throw failure
                null
            }
        }
        return directOrCopy ?: documents.withDocument(source, allowDirectAccess = false) { document ->
            readPages(source, document, cancellation::ensureActive)
        }
    }

    private fun readPages(
        source: CbzSourceReference,
        document: OpenedCbzDocument,
        checkCancellation: () -> Unit,
    ): CbzImportResult = try {
        val pages = when (document) {
            is OpenedCbzDocument.Direct -> archives.readPages(document.channel, checkCancellation)
            is OpenedCbzDocument.Temporary -> archives.readPages(document.file, checkCancellation)
        }
        CbzImportResult.Success(source, Collections.unmodifiableList(pages))
    } catch (failure: ZipValidationException) {
        CbzImportResult.Failure(failure.error)
    }

    private suspend fun releaseGrant(source: CbzSourceReference) = withContext(NonCancellable) {
        try {
            documents.releaseReadPermission(source)
        } catch (_: DocumentAccessException) {
            Log.w("LocalCbzImportRepository", "Could not release an unused import grant")
        }
    }
}
