package com.agustin.sumi.domain.usecase

import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.CbzImportRepository
import com.agustin.sumi.domain.repository.LibraryDocumentMetadata
import com.agustin.sumi.domain.repository.LibraryDocumentMetadataSource
import com.agustin.sumi.domain.repository.LibraryRepository
import com.agustin.sumi.domain.repository.LibraryWriteResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ImportIntoLibrary(
    private val newImporter: () -> CbzImportRepository,
    private val documents: LibraryDocumentMetadataSource,
    private val library: LibraryRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val work: CbzWorkGate = CbzWorkGate(),
    private val withAccess: suspend (
        CbzSourceReference,
        LibraryEntryId?,
        suspend () -> Result,
    ) -> Result = { _, _, operation -> operation() },
) {
    sealed interface Result {
        data class Saved(val title: LibraryTitle, val pageCount: Int) : Result
        data class Rejected(val error: CbzImportError) : Result
        data object InvalidIdentity : Result
        data object Duplicate : Result
        data object StorageFailure : Result
        data object Recovered : Result
        data object EntryNotFound : Result
    }

    suspend operator fun invoke(
        source: CbzSourceReference,
        fallback: LibraryTitle,
        onCommitted: () -> Unit = {},
    ): Result = validate(source) { metadata, result, _ ->
        val title = LibraryTitle.fromDisplayName(metadata.displayName, fallback)
        val addedAt = nowMillis()
        currentCoroutineContext().ensureActive()
        val write = library.insertForImport(
            LibrarySource(metadata.identity, result.source), title, addedAt, result.pages.firstOrNull()?.id, onCommitted,
        )
        when (write) {
            LibraryWriteResult.SUCCESS -> Result.Saved(title, result.pages.size)
            LibraryWriteResult.DUPLICATE_DOCUMENT -> Result.Duplicate
            LibraryWriteResult.ENTRY_NOT_FOUND, LibraryWriteResult.STORAGE_FAILURE -> Result.StorageFailure
        }
    }

    suspend fun replace(id: LibraryEntryId, source: CbzSourceReference): Result = replaceMatching(source) { id }

    suspend fun replaceMatching(
        source: CbzSourceReference,
        find: suspend (LibraryDocumentIdentity) -> LibraryEntryId?,
    ): Result = validate(source, { find(it.identity) }) { metadata, result, id ->
        when (library.replaceSource(requireNotNull(id), LibrarySource(metadata.identity, result.source), result.pages.firstOrNull()?.id)) {
            LibraryWriteResult.SUCCESS -> Result.Recovered
            LibraryWriteResult.DUPLICATE_DOCUMENT -> Result.Duplicate
            LibraryWriteResult.ENTRY_NOT_FOUND -> Result.EntryNotFound
            LibraryWriteResult.STORAGE_FAILURE -> Result.StorageFailure
        }
    }

    private suspend fun validate(
        source: CbzSourceReference,
        findReplacement: (suspend (LibraryDocumentMetadata) -> LibraryEntryId?)? = null,
        write: suspend (LibraryDocumentMetadata, CbzImportResult.Success, LibraryEntryId?) -> Result,
    ): Result = work.run {
        val metadata = documents.resolve(source) ?: return@run Result.InvalidIdentity
        val replacedId = findReplacement?.let { it(metadata) ?: return@run Result.EntryNotFound }
        withAccess(source, replacedId) access@{
            // Each selection is validated against the current archive, including retries after a failed write.
            val result = when (val imported = newImporter().importCbz(source)) {
                is CbzImportResult.Success -> imported
                is CbzImportResult.Failure -> return@access Result.Rejected(imported.error)
            }
            currentCoroutineContext().ensureActive()
            write(metadata, result, replacedId)
        }
    }
}
