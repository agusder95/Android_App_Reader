package com.agustin.sumi.data.local.document

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.data.local.cover.CoverCache
import com.agustin.sumi.data.local.database.LibraryDatabase
import com.agustin.sumi.data.repository.LocalCbzImportRepository
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzImportResult
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryAccessStatus
import com.agustin.sumi.domain.repository.LibraryRemovalResult
import com.agustin.sumi.domain.usecase.CbzWorkGate
import com.agustin.sumi.domain.usecase.ImportIntoLibrary
import com.agustin.sumi.domain.usecase.ImportLibraryBatch
import com.agustin.sumi.domain.usecase.RecoverLibraryTree
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafTreeLibraryTest {
    private val fixture = TreeLibraryChecks(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun requiresDurableTreeGrantAndRejectsOutsideDocumentEvenWithDirectGrant() = runBlocking {
        fixture.clean("tree-boundary")
        try {
            fixture.offered("picker-transient")
            fixture.withLibrary("tree-boundary") {
                check(import(first) == ImportIntoLibrary.Result.Rejected(CbzImportError.PERSISTENT_PERMISSION_UNAVAILABLE))
                check(entries().isEmpty())
                fixture.offered("picker-grant")
                check(import(first) is ImportIntoLibrary.Result.Saved)
                fixture.offered("picker-grant", PickerFixtureProvider.OUTSIDE)
                check(import(direct(PickerFixtureProvider.OUTSIDE)) is ImportIntoLibrary.Result.Saved)
                check(import(child(PickerFixtureProvider.OUTSIDE)) == ImportIntoLibrary.Result.Rejected(CbzImportError.ACCESS_DENIED))
                check(entries().size == 2)
                fixture.offered("picker-revoke")
                check(access.check(first) == LibraryAccessStatus.PERMISSION_REVOKED)
                check(entries().size == 2)
            }
        } finally {
            fixture.clean("tree-boundary")
        }
    }

    @Test
    fun deduplicatesAcrossUriShapesAndReleasesOnlyAfterFinalReferenceAndRead() = runBlocking {
        fixture.clean("tree-shared")
        try {
            fixture.offered("picker-grant")
            fixture.offered("picker-grant", PickerFixtureProvider.TREE_CBZ)
            fixture.withLibrary("tree-shared") {
                check(import(first) is ImportIntoLibrary.Result.Saved)
                val original = entries().single()
                check(import(direct(PickerFixtureProvider.TREE_CBZ)) == ImportIntoLibrary.Result.Duplicate)
                check(entries().single() == original)
                check(import(second) is ImportIntoLibrary.Result.Saved)
                val other = entries().single { it.id != original.id }
                check(other.source.grantReference?.value == tree.toString())
                check(maintenance.remove(original.id) == LibraryRemovalResult.REMOVED)
                check(documents.persistedReadTime(grant) != null)
                check(access.check(second) == LibraryAccessStatus.AVAILABLE)

                val reading = CompletableDeferred<Unit>()
                val finish = CountDownLatch(1)
                val read = async {
                    work.run {
                        documents.withDocument(second) {
                            reading.complete(Unit)
                            check(finish.await(10, TimeUnit.SECONDS))
                        }
                    }
                }
                try {
                    withTimeout(5_000) { reading.await() }
                    val removing = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { maintenance.remove(other.id) }
                    check(!removing.isCompleted)
                    check(documents.persistedReadTime(grant) != null)
                    finish.countDown()
                    read.await()
                    check(removing.await() == LibraryRemovalResult.REMOVED)
                    check(documents.persistedReadTime(grant) == null)
                } finally {
                    finish.countDown()
                }
            }
        } finally {
            fixture.clean("tree-shared")
        }
    }

    @Test
    fun reopensAfterRecreatingDatabaseAndPreservesPreexistingGrant() = runBlocking {
        fixture.clean("tree-preexisting")
        try {
            fixture.offered("picker-grant")
            fixture.withLibrary("tree-preexisting") {
                check(documents.persistReadPermission(grant))
                check(import(first) is ImportIntoLibrary.Result.Saved)
            }
            fixture.withLibrary("tree-preexisting") {
                val row = entries().single()
                check(row.source.reference == first)
                check(row.source.grantReference?.value == tree.toString())
                check(LocalCbzImportRepository(documents).importCbz(row.source.reference) is CbzImportResult.Success)
                check(maintenance.remove(row.id) == LibraryRemovalResult.REMOVED)
                check(documents.persistedReadTime(grant) != null)
            }
        } finally {
            fixture.clean("tree-preexisting")
        }
    }
}

internal class TreeLibraryChecks(private val context: Context) {
    private val treeUri = DocumentsContract.buildTreeDocumentUri(PickerFixtureProvider.AUTHORITY, PickerFixtureProvider.TREE_ROOT)

    fun clean(name: String) {
        context.deleteDatabase(name)
        File(context.cacheDir, "$name-grants.json").delete()
        for (root in listOf(PickerFixtureProvider.TREE_ROOT, PickerFixtureProvider.SCAN_ROOT,
            PickerFixtureProvider.RECOVERY_ROOT, PickerFixtureProvider.NODE_ROOT, PickerFixtureProvider.CANDIDATE_ROOT)) {
            offered("picker-revoke", root)
        }
        offered("picker-revoke", PickerFixtureProvider.TREE_CBZ)
        offered("picker-revoke", PickerFixtureProvider.OUTSIDE)
    }

    fun offered(method: String, documentId: String = PickerFixtureProvider.TREE_ROOT) {
        context.contentResolver.call(Uri.parse("content://${SafTestProvider.AUTHORITY}"), method, documentId, null)
    }

    suspend fun prepareProcessRestart(): List<String> {
        clean(RESTART_DB)
        offered("picker-grant")
        withLibrary(RESTART_DB) {
            check(import(first) is ImportIntoLibrary.Result.Saved)
            check(entries().single().source.grantReference?.value == tree.toString())
        }
        return listOf("persisted tree grant and imported descendant before process stop")
    }

    suspend fun verifyProcessRestart(): List<String> {
        try {
            withLibrary(RESTART_DB) {
                val row = entries().single()
                check(row.source.reference == first)
                check(access.check(row.source.reference) == LibraryAccessStatus.AVAILABLE)
                check(LocalCbzImportRepository(documents).importCbz(row.source.reference) is CbzImportResult.Success)
                check(maintenance.remove(row.id) == LibraryRemovalResult.REMOVED)
                check(documents.persistedReadTime(grant) == null)
            }
        } finally {
            clean(RESTART_DB)
        }
        return listOf("reopened and validated descendant in new process, then released the last managed grant")
    }

    suspend fun withLibrary(name: String, block: suspend LibraryFixture.() -> Unit) {
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            LibraryFixture(database, context, name).block()
        } finally {
            database.close()
        }
    }

    inner class LibraryFixture(database: LibraryDatabase, context: Context, name: String) {
        val tree = treeUri
        val grant = CbzSourceReference(tree.toString())
        val first = child(PickerFixtureProvider.TREE_CBZ)
        val second = child(PickerFixtureProvider.TREE_ZIP)
        val work = CbzWorkGate()
        val documents = SafDocumentSource(context)
        val access = SafLibraryDocumentAccess(documents, work)
        private val library = RoomLibraryRepository(database.libraryDao())
        val maintenance = SafLibraryMaintenance(
            library, documents, work, CoverCache(File(context.cacheDir, "$name-covers")),
            LibraryGrantRegistry(File(context.cacheDir, "$name-grants.json")),
        )
        private val importer = ImportIntoLibrary(
            newImporter = { LocalCbzImportRepository(documents) },
            documents = SafLibraryDocumentMetadataSource(context),
            library = library,
            work = work,
            withAccess = maintenance::withImportAccess,
        )
        val trees = SafLibraryTreeSource(context.contentResolver, maintenance)
        val batches = ImportLibraryBatch(importer)
        val recover = RecoverLibraryTree(trees, importer, library::countTreeEntries) { grant, identity ->
            library.findTreeEntry(grant, identity)?.id
        }

        suspend fun import(source: CbzSourceReference) = importer(source, LibraryTitle("Fixture"))
        suspend fun entries() = library.observeEntries(LibraryQuery("")).first()
        fun child(id: String) = treeDocumentReference(tree, id)
        fun direct(id: String) = CbzSourceReference(DocumentsContract.buildDocumentUri(PickerFixtureProvider.AUTHORITY, id).toString())
    }

    private companion object {
        const val RESTART_DB = "tree-process-restart"
    }
}
