package com.agustin.sumi.data.local.document

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.MainActivity
import com.agustin.sumi.data.local.archive.CbzArchiveReader
import com.agustin.sumi.data.local.database.LibraryDatabase
import com.agustin.sumi.data.local.database.toEntity
import com.agustin.sumi.data.local.reader.NativeReaderRenderer
import com.agustin.sumi.domain.repository.ReaderViewport
import com.agustin.sumi.data.repository.LocalReaderRepository
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.data.repository.RoomReaderProgressRepository
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntry
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.model.ReaderDirection
import com.agustin.sumi.domain.model.ReaderProgress
import com.agustin.sumi.domain.model.ReaderSession
import com.agustin.sumi.domain.model.ReaderZoom
import com.agustin.sumi.domain.repository.ReaderError
import com.agustin.sumi.domain.repository.ReaderRepository
import com.agustin.sumi.domain.repository.ReaderResult
import com.agustin.sumi.domain.usecase.CbzWorkGate
import com.agustin.sumi.feature.reader.ReaderBook
import com.agustin.sumi.feature.reader.ReaderCleanup
import com.agustin.sumi.feature.reader.ReaderState
import com.agustin.sumi.feature.reader.ReaderViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafCbzPageAccessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val documents = SafDocumentSource(context)
    private val archives = CbzArchiveReader()
    private val work = CbzWorkGate()

    @Test
    fun readerClosesBlockedSafOnActualProviderRelease() = runBlocking {
        val name = "cbz-blocked"
        val source = prepare(name)
        val repo = repository(source)
        val dbName = "reader-blocked-${UUID.randomUUID()}.db"
        val database = LibraryDatabase.open(context, dbName)
        val store = ViewModelStore()
        try {
            insertFixture(database, source, explicitId = bookId.value)
            val model = withContext(Dispatchers.Main) {
                val model = ViewModelProvider.create(store, viewModelFactory {
                    initializer { readerModel(SavedStateHandle(), repo, RoomReaderProgressRepository(database.readerProgressDao())) }
                })[ReaderViewModel::class]
                model.enterForeground()
                model.open(bookId, viewport)
                model
            }
            awaitCondition { temporaries().any { it.length() == 12L } }
            withContext(Dispatchers.Main) { model.close() }
            assertEquals(ReaderState.Cleaning(ReaderCleanup.CLOSE), model.state.value)
            assertEquals(12L, temporaries().single().length())
            command("release", name)
            model.await<ReaderState.Closed>()
            awaitCondition { command("closed", name).getBoolean("closed") }
            assertTrue(temporaries().isEmpty())
            withTimeout(5_000) { work.run {} }
        } finally {
            command("release", name)
            withContext(Dispatchers.Main) { store.clear() }
            database.close()
            context.deleteDatabase(dbName)
            command("revoke", name)
        }
    }

    @Test
    fun retainsSessionDuringActivityRecreationAndReopensAfterBackground() = runBlocking {
        val name = "cover-pipe"
        val source = prepare(name)
        setArchive(name, archive(pngFixture()))
        val dbName = "reader-lifecycle-${UUID.randomUUID()}.db"
        val database = LibraryDatabase.open(context, dbName)
        try {
            val id = insertFixture(database, source)
            val delegate = LocalReaderRepository(RoomLibraryRepository(database.libraryDao())::findById, documents, work)
            val progress = RoomReaderProgressRepository(database.readerProgressDao())
            val repo = object : ReaderRepository by delegate {
                var opens = 0
                override suspend fun openSession(bookId: LibraryEntryId, direction: ReaderDirection): ReaderResult<ReaderSession> {
                    opens++
                    return delegate.openSession(bookId, direction)
                }
            }
            val factory = viewModelFactory { initializer { readerModel(createSavedStateHandle(), repo, progress) } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: ReaderViewModel
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity, factory)[ReaderViewModel::class.java]
                    model.bindTo(activity) { activity.isChangingConfigurations }
                    model.open(id, viewport)
                }
                val first = model.await<ReaderState.Available>()
                scenario.onActivity { model.confirmDisplayed(first.frame) }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val retained = ViewModelProvider(activity, factory)[ReaderViewModel::class.java]
                    assertSame(model, retained)
                    retained.bindTo(activity) { activity.isChangingConfigurations }
                    retained.restore(viewport)
                }
                assertEquals(first, model.await<ReaderState.Available>())
                assertEquals(1, repo.opens)
                scenario.moveToState(Lifecycle.State.CREATED)
                model.await<ReaderState.Suspended>()
                assertTrue(temporaries().isEmpty())
                scenario.moveToState(Lifecycle.State.RESUMED)
                val returned = model.await<ReaderState.Available>()
                assertEquals(2, repo.opens)
                assertEquals(first.position, returned.position)
                assertEquals(ReaderResult.Success(ReaderProgress(first.position.pageId)), progress.load(id))
                command("revoke", name)
                scenario.moveToState(Lifecycle.State.CREATED)
                model.await<ReaderState.Suspended>()
                scenario.moveToState(Lifecycle.State.RESUMED)
                val denied = model.await<ReaderState.Failed>()
                assertEquals(ReaderError.ACCESS_DENIED, denied.error)
                assertTrue(denied.canReselectDocument)
                scenario.moveToState(Lifecycle.State.DESTROYED)
                model.await<ReaderState.Closed>()
                assertTrue(temporaries().isEmpty())
            }
        } finally {
            database.close()
            context.deleteDatabase(dbName)
            command("revoke", name)
        }
    }

    @Test
    fun restoresProgressAndRecoversOrphanOnReaderRelaunch() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("readerRelaunchPhase")
        val fixture = args.getString("readerRelaunchFixture")?.let(UUID::fromString) ?: UUID.randomUUID()
        val name = "cover-pipe"
        val source = if (phase == "reopen") CbzSourceReference("content://${SafTestProvider.AUTHORITY}/$name") else prepare(name)
        setArchive(name, archive(pngFixture()))
        val dbName = "reader-relaunch-$fixture.db"
        val database = LibraryDatabase.open(context, dbName)
        val orphan = File(context.cacheDir, "cbz-import/import-$fixture.cbz.part")
        var keep = false
        try {
            val library = RoomLibraryRepository(database.libraryDao())
            val progress = RoomReaderProgressRepository(database.readerProgressDao())
            val repo = LocalReaderRepository(library::findById, documents, work)
            val id = if (phase == "reopen") library.observeEntries(LibraryQuery()).first().single().id
                else insertFixture(database, source)
            if (phase != "reopen") {
                val session = success(repo.openSession(id, ReaderDirection.RTL))
                assertEquals(ReaderResult.Success(Unit), progress.confirm(id, ReaderProgress(session.firstPosition.pageId, ReaderDirection.RTL)).save())
                repo.closeSession(session)
                check(orphan.parentFile?.mkdirs() == true || orphan.parentFile?.isDirectory == true)
                orphan.writeText("interrupted private copy")
            }
            if (phase == "prepare") keep = true else {
                assertTrue(orphan.isFile)
                val store = ViewModelStore()
                val model = withContext(Dispatchers.Main) {
                    val handle = SavedStateHandle(mapOf("readerBookId" to id.value))
                    val factory = viewModelFactory { initializer { readerModel(handle, repo, progress) } }
                    ViewModelProvider.create(store, factory)[ReaderViewModel::class].also {
                        it.restore(viewport)
                        it.enterForeground()
                    }
                }
                try {
                    val ready = model.await<ReaderState.Available>()
                    assertEquals(id, ready.bookId)
                    assertEquals(ReaderDirection.RTL, ready.direction)
                    assertEquals(ReaderResult.Success(ReaderProgress(ready.position.pageId, ready.direction)), progress.load(id))
                    assertFalse(orphan.exists())
                } finally {
                    withContext(Dispatchers.Main) { store.clear() }
                    model.await<ReaderState.Closed>()
                }
            }
        } finally {
            database.close()
            if (!keep) {
                context.deleteDatabase(dbName)
                orphan.delete()
                command("revoke", name)
            }
        }
        Unit
    }

    @Test
    fun nativeRendererReadsVerifiedPngFromSafSession() = runBlocking {
        val name = "cover-direct"
        val source = prepare(name)
        val repo = repository(source)
        val renderer = NativeReaderRenderer(repo) { 0 }
        val image = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally {
            image.recycle()
        }
        setArchive(name, archive(bytes))
        val session = success(repo.openSession(bookId, ReaderDirection.LTR))
        try {
            val viewport = ReaderViewport(128, 128)
            assertEquals(ReaderResult.Success(Unit), renderer.load(session, session.pages.single().id, viewport))
            assertEquals(128 to 64, renderer.withFrame(viewport, ReaderZoom.FIT_PAGE) { bitmap, _ ->
                bitmap.width to bitmap.height
            })
        } finally {
            renderer.clear()
            repo.closeSession(session)
            command("revoke", name)
        }
    }

    @Test
    fun sessionReadsIndividualEntryAndRejectsReplacementsForBothSafModes() = runBlocking {
        for (name in listOf("cover-direct", "cover-pipe")) {
            val sequential = name == "cover-pipe"
            val source = prepare(name)
            val page = byteArrayOf(-1, -40, -1) + ByteArray(1024) { it.toByte() }
            setArchive(name, archive(page))
            val repo = repository(source)
            val session = success(repo.openSession(bookId, ReaderDirection.RTL))
            try {
                assertEquals(1, session.pages.size)
                assertEquals(ReaderDirection.RTL, session.options.direction)
                assertEquals(if (sequential) 1 else 0, temporaries().size)
                // A retained copy uses the quota, but the shared work slot is available between requests.
                withTimeout(2_000) { work.run {} }
                val pageId = session.pages.single().id
                val read = success(repo.readPage(session, pageId) { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(256)
                    while (true) {
                        val count = input.read(buffer, 0, buffer.size)
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toByteArray()
                })
                assertArrayEquals(page, read.content)
                setArchive(name, archive(page.copyOf().also { it[it.lastIndex] = 42 }))
                assertEquals(ReaderResult.Failure(ReaderError.INVALID_ARCHIVE), repo.readPage(session, pageId) {
                    error("A replaced archive must not reach the consumer")
                })
                assertTrue(temporaries().isEmpty())
            } finally {
                repo.closeSession(session)
                command("revoke", name)
            }
        }
    }

    @Test
    fun corruptPageIsRejectedAfterPrefixEvenIfConsumerReadsNothing() = runBlocking {
        val name = "cover-direct"
        val source = prepare(name)
        val page = byteArrayOf(-1, -40, -1) + ByteArray(1024)
        val payload = archive(page).also { it[30 + PAGE_NAME.length + page.lastIndex] = 1 }
        setArchive(name, payload)
        val repo = repository(source)
        val session = success(repo.openSession(bookId, ReaderDirection.LTR))
        try {
            assertEquals(ReaderResult.Failure(ReaderError.CORRUPT_PAGE), repo.readPage(session, session.pages.single().id) { Unit })
        } finally {
            repo.closeSession(session)
            command("revoke", name)
        }
    }

    @Test
    fun closeCancelsAnActiveConsumerAndReleasesTheSessionCopy() = runBlocking {
        val name = "cbz-pipe"
        val source = prepare(name)
        val repo = repository(source)
        val session = success(repo.openSession(bookId, ReaderDirection.LTR))
        val entered = CompletableDeferred<Unit>()
        val pageId = session.pages.first().id
        val read = async {
            repo.readPage(session, pageId) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            withTimeout(10_000) { entered.await() }
            assertEquals(1, temporaries().size)
            withTimeout(10_000) { repo.closeSession(session) }
            read.join()
            assertTrue(read.isCancelled)
            assertTrue(temporaries().isEmpty())
            assertEquals(ReaderResult.Failure(ReaderError.PAGE_NOT_FOUND), repo.readPage(session, pageId) { Unit })
        } finally {
            read.cancel()
            repo.closeSession(session)
            command("revoke", name)
        }
    }

    @Test
    fun revokedAccessInvalidatesSequentialSessionAndCleansCopy() = runBlocking {
        val name = "cbz-pipe"
        val source = prepare(name)
        val repo = repository(source)
        val session = success(repo.openSession(bookId, ReaderDirection.LTR))
        try {
            command("revoke", name)
            assertEquals(ReaderResult.Failure(ReaderError.ACCESS_DENIED), repo.readPage(session, session.pages.first().id) {
                error("Expected to validate access before using the copy")
            })
            assertTrue(temporaries().isEmpty())
        } finally {
            repo.closeSession(session)
            command("revoke", name)
        }
    }

    @Test
    fun readsSameVerifiedPageFromSeekableAndSequentialSaf() = runBlocking {
        val direct = prepare("cbz-regular")
        val pipe = prepare("cbz-pipe")
        try {
            val expected = readPage(direct, temporary = false)
            val actual = readPage(pipe, temporary = true)
            assertTrue(expected.isNotEmpty())
            assertArrayEquals(expected, actual)
            assertTrue(temporaries().isEmpty())
        } finally {
            command("revoke", "cbz-regular")
            command("revoke", "cbz-pipe")
        }
    }

    @Test
    fun blockedSequentialReadKeepsWorkSlotUntilCleanupAndReturnsNoPage() = runBlocking {
        val name = "cbz-blocked"
        val blocked = prepare(name)
        val repo = repository(blocked)
        var published = false
        val operation = async { repo.openSession(bookId, ReaderDirection.LTR).also { published = true } }
        try {
            awaitCondition { temporaries().any { it.length() == 12L } }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { work.run { temporaries().isEmpty() } }
            operation.cancel()
            assertFalse(operation.isCompleted)
            assertFalse(queued.isCompleted)
            command("release", name)
            operation.join()
            assertTrue(operation.isCancelled && queued.await())
            assertFalse(published)
            awaitCondition { command("closed", name).getBoolean("closed") }
        } finally {
            command("release", name)
            operation.cancel()
            operation.join()
            command("revoke", name)
        }
    }

    private suspend fun readPage(source: CbzSourceReference, temporary: Boolean): ByteArray = work.run {
        val caller = currentCoroutineContext()
        documents.withDocument(source) { document ->
            assertEquals(temporary, document is OpenedCbzDocument.Temporary)
            when (document) {
                is OpenedCbzDocument.Direct -> readPage(document.channel, caller::ensureActive)
                is OpenedCbzDocument.Temporary -> document.file.inputStream().use {
                    readPage(it.channel, caller::ensureActive)
                }
            }
        }
    }

    private fun readPage(channel: FileChannel, checkCancellation: () -> Unit): ByteArray {
        val index = archives.index(channel, checkCancellation)
        assertEquals(2, index.entries.size)
        return archives.openPage(channel, index.entries.last(), checkCancellation).use { page ->
            val sample = ByteArray(64)
            val read = page.read(sample, 0, sample.size)
            page.verifyComplete()
            sample.copyOf(read)
        }
    }

    private suspend fun prepare(name: String): CbzSourceReference {
        command("revoke", name)
        command("reset", name)
        command("persistable", name)
        return CbzSourceReference("content://${SafTestProvider.AUTHORITY}/$name").also {
            assertTrue(documents.persistReadPermission(it))
        }
    }

    private val bookId = LibraryEntryId(123)
    private val viewport = ReaderViewport(128, 128)

    private suspend fun insertFixture(database: LibraryDatabase, source: CbzSourceReference, explicitId: Long = 0): LibraryEntryId {
        val stored = LibrarySource(LibraryDocumentIdentity(SafTestProvider.AUTHORITY, "fixture"), source)
        database.libraryDao().insert(stored.toEntity(LibraryTitle("Fixture"), 0L, null).copy(id = explicitId))
        return RoomLibraryRepository(database.libraryDao()).observeEntries(LibraryQuery()).first().single().id
    }

    private fun readerModel(handle: SavedStateHandle, repo: ReaderRepository, progress: RoomReaderProgressRepository) =
        ReaderViewModel(handle, repo, progress, NativeReaderRenderer(repo) { 0 },
            { ReaderResult.Success(ReaderBook(LibraryTitle("Fixture"))) },
            { _, _, _ -> error("Reselection is not needed by this fixture") })

    private suspend inline fun <reified T : ReaderState> ReaderViewModel.await(): T = withTimeout(10_000) {
        state.filterIsInstance<T>().first()
    }

    private fun pngFixture(): ByteArray {
        val image = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally {
            image.recycle()
        }
    }

    private fun repository(source: CbzSourceReference): LocalReaderRepository {
        val entry = LibraryEntry(
            bookId, LibrarySource(LibraryDocumentIdentity(SafTestProvider.AUTHORITY, "fixture"), source),
            LibraryTitle("Fixture"), 0L,
        )
        return LocalReaderRepository({ id -> entry.takeIf { it.id == id } }, documents, work)
    }

    private fun <T> success(result: ReaderResult<T>): T = when (result) {
        is ReaderResult.Success -> result.value
        is ReaderResult.Failure -> error("Expected a reader result, got ${result.error}")
    }

    private fun setArchive(name: String, bytes: ByteArray) {
        val extras = Bundle().apply { putByteArray("archive", bytes) }
        context.contentResolver.call(Uri.parse("content://${SafTestProvider.AUTHORITY}"), "set-cover", name, extras)
    }

    private fun archive(page: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val image = ZipEntry(PAGE_NAME).apply {
                method = ZipEntry.STORED
                size = page.size.toLong()
                crc = CRC32().apply { update(page) }.value
            }
            zip.putNextEntry(image)
            zip.write(page)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("fake.png"))
            zip.write("not an image".encodeToByteArray())
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun command(method: String, name: String) = checkNotNull(
        context.contentResolver.call(Uri.parse("content://${SafTestProvider.AUTHORITY}"), method, name, null),
    )

    private fun temporaries() = File(context.cacheDir, "cbz-import").listFiles().orEmpty()
        .filter { it.name.endsWith(".cbz.part") }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(20)
    }

    private companion object {
        const val PAGE_NAME = "page.bin"
    }
}
