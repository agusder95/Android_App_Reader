package com.agustin.sumi.data.local.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agustin.sumi.data.repository.RoomLibraryRepository
import com.agustin.sumi.domain.model.CbzPageId
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.model.LibraryEntryId
import com.agustin.sumi.domain.model.LibraryGrantReference
import com.agustin.sumi.domain.model.LibraryQuery
import com.agustin.sumi.domain.model.LibrarySortOrder
import com.agustin.sumi.domain.model.LibrarySource
import com.agustin.sumi.domain.model.LibraryTitle
import com.agustin.sumi.domain.repository.LibraryWriteResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val schema = MigrationTestHelper(instrumentation, LibraryDatabase::class.java)

    @Test
    fun exportedSchemaSupportsWriteAndReopenOnDisk() = runBlocking {
        val name = "library-${UUID.randomUUID()}.db"
        val storedSource = source("book", grant = "tree")
        val cover = CbzPageId("first")
        try {
            schema.createDatabase(name, 1).close()
            open(name).useDatabase { database ->
                val repository = RoomLibraryRepository(database.libraryDao())
                assertEquals(LibraryWriteResult.SUCCESS, repository.insert(storedSource, LibraryTitle("Álbum"), 42, cover))
            }
            open(name).useDatabase { database ->
                val repository = RoomLibraryRepository(database.libraryDao())
                val entry = repository.observeEntries(LibraryQuery("ÁLBUM")).first().single()
                assertEquals(storedSource, entry.source)
                assertEquals(42L, entry.addedAtEpochMillis)
                assertEquals(cover, entry.coverPageId)
                assertEquals(LibraryWriteResult.DUPLICATE_DOCUMENT, repository.add(source("book", "other-tree")))
                assertEquals(entry.id, repository.observeEntries(LibraryQuery()).first().single().id)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test
    fun concurrentWritesEnforceCompoundIdentityButAllowIdenticalTitles() = runBlocking {
        inMemory().useDatabase { database ->
            val repository = RoomLibraryRepository(database.libraryDao())
            val outcomes = listOf(
                async { repository.add(source("book")) },
                async { repository.add(source("book", "tree")) },
            ).awaitAll()
            assertEquals(1, outcomes.count { it == LibraryWriteResult.SUCCESS })
            assertEquals(1, outcomes.count { it == LibraryWriteResult.DUPLICATE_DOCUMENT })
            assertEquals(LibraryWriteResult.SUCCESS, repository.add(source("book", authority = "other")))
            assertEquals(2, repository.observeEntries(LibraryQuery()).first().size)
        }
    }

    @Test
    fun replacementIsAtomicAndKeepsIdTitleAndDate() = runBlocking {
        inMemory().useDatabase { database ->
            val repository = RoomLibraryRepository(database.libraryDao())
            val initial = source("first", "old-tree")
            val other = source("second")
            assertEquals(LibraryWriteResult.SUCCESS, repository.add(initial))
            assertEquals(LibraryWriteResult.SUCCESS, repository.add(other))
            val original = repository.observeEntries(LibraryQuery()).first().first()
            assertEquals(initial, original.source)
            assertEquals(LibraryWriteResult.DUPLICATE_DOCUMENT, repository.replaceSource(original.id, other, null))
            assertEquals(original, repository.observeEntries(LibraryQuery()).first().first())

            val newSource = source("moved", "new-tree")
            val cover = CbzPageId("new-first-page")
            assertEquals(LibraryWriteResult.SUCCESS, repository.replaceSource(original.id, newSource, cover))
            assertEquals(
                original.copy(source = newSource, coverPageId = cover),
                repository.observeEntries(LibraryQuery()).first().first(),
            )
            assertEquals(LibraryWriteResult.SUCCESS, repository.remove(original.id))
            assertEquals(LibraryWriteResult.ENTRY_NOT_FOUND, repository.remove(original.id))
            assertEquals(LibraryWriteResult.ENTRY_NOT_FOUND, repository.replaceSource(LibraryEntryId(-1), newSource, null))
            assertEquals(other, repository.observeEntries(LibraryQuery()).first().single().source)
        }
    }

    @Test
    fun queriesMatchDomainOrderAndLiteralUnicodeAcrossWindows() = runBlocking {
        inMemory().useDatabase { database ->
            val repository = RoomLibraryRepository(database.libraryDao())
            val titles = listOf("Café %_", "CAFE\u0301 %_", "Cafe", "\uE000", "\uD83D\uDE00")
            for ((index, title) in titles.withIndex()) {
                assertEquals(
                    LibraryWriteResult.SUCCESS,
                    repository.insert(source("book-$index"), LibraryTitle(title), (index % 2).toLong(), null),
                )
            }
            val entries = repository.observeEntries(LibraryQuery()).first()
            assertEquals(titles.size, entries.size)
            for (order in LibrarySortOrder.entries) {
                for (search in listOf("", "café %_", "CAFE", "_", "missing")) {
                    val query = LibraryQuery(search, order, offset = 1, limit = 2)
                    val expected = entries.filter { query.matches(it.title) }.sortedWith(query.comparator).drop(1).take(2)
                    assertEquals(expected, repository.observeEntries(query).first())
                }
            }
        }
    }

    @Test
    fun observedQueryEmitsAfterInsert() = runBlocking {
        inMemory().useDatabase { database ->
            val repository = RoomLibraryRepository(database.libraryDao())
            val query = LibraryQuery()
            val observing = CompletableDeferred<Unit>()
            val loaded = async {
                withTimeout(5_000) {
                    repository.observeEntries(query)
                        .onEach { if (it.isEmpty()) observing.complete(Unit) }
                        .first { it.isNotEmpty() }
                }
            }
            withTimeout(5_000) { observing.await() }
            assertEquals(LibraryWriteResult.SUCCESS, repository.add(source("observed")))
            assertEquals(source("observed"), loaded.await().single().source)
        }
    }

    @Test
    fun cancellationAfterInsertInsideTransactionRollsBackBeforeCommit() = runBlocking {
        inMemory().useDatabase { database ->
            val inserted = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val entry = source("rollback").toEntity(LibraryTitle("Fixture"), 10L, null)
            val writing = async {
                val caller = coroutineContext
                var checks = 0
                withContext(NonCancellable) {
                    database.libraryDao().insertWhileActive(entry) {
                        checks++
                        if (checks == 2) {
                            inserted.complete(Unit)
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        caller.ensureActive()
                    }
                }
            }
            try {
                withTimeout(10_000) { inserted.await() }
                writing.cancel()
            } finally {
                release.countDown()
                writing.cancelAndJoin()
            }
            assertEquals(0, RoomLibraryRepository(database.libraryDao()).observeEntries(LibraryQuery()).first().size)
        }
    }

    private fun open(name: String) = LibraryDatabase.open(context, name)

    private fun inMemory() = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()

    private inline fun <T> LibraryDatabase.useDatabase(block: (LibraryDatabase) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun source(id: String, grant: String? = null, authority: String = "provider") = LibrarySource(
        identity = LibraryDocumentIdentity(authority, id),
        reference = CbzSourceReference("$authority/${grant ?: "direct"}/$id"),
        grantReference = grant?.let(::LibraryGrantReference),
    )

    private suspend fun RoomLibraryRepository.add(source: LibrarySource): LibraryWriteResult =
        insert(source, LibraryTitle("Álbum"), 10, null)
}
