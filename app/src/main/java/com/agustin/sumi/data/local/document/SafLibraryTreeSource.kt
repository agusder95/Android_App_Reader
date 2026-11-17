package com.agustin.sumi.data.local.document

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.repository.LibraryTreeSource
import com.agustin.sumi.domain.repository.TreeDiscovery
import com.agustin.sumi.domain.usecase.BATCH_CANDIDATE_LIMIT
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

internal class SafLibraryTreeSource(
    private val resolver: ContentResolver,
    private val maintenance: SafLibraryMaintenance,
) : LibraryTreeSource {
    override suspend fun <T> withTree(
        tree: CbzSourceReference,
        consume: suspend (Flow<TreeDiscovery>) -> T,
    ): T {
        val uri = tree.value.toUri()
        val rootId = try {
            requireTreeRoot(uri)
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid tree selection")
        }
        return maintenance.withTreeGrant(tree) {
            consume(flow {
                coroutineScope {
                    val signal = CancellationSignal()
                    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            signal.cancel()
                        }
                    }
                    try {
                        walk(uri, rootId, signal)
                    } finally {
                        cancellation.cancel()
                    }
                }
            })
        }
    }

    private suspend fun FlowCollector<TreeDiscovery>.walk(tree: Uri, rootId: String, signal: CancellationSignal) {
        val visited = mutableSetOf(rootId)
        val pending = ArrayDeque<Directory>()
        pending.addLast(Directory(rootId, 0))
        var nodes = 0
        var candidates = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeFirst()
            val children = openChildren(tree, directory.id, signal)
            if (children == null) {
                if (directory.depth == 0) throw IOException("Cannot enumerate the selected tree")
                emit(TreeDiscovery.Omitted(incomplete = true))
                continue
            }
            // Keep only one provider cursor open, including when downstream applies backpressure.
            children.use { cursor ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val hasRow = providerCall { cursor.moveToNext() }
                    if (hasRow != true) {
                        if (hasRow == null) emit(TreeDiscovery.Omitted(incomplete = true))
                        break
                    }
                    if (++nodes > MAX_TREE_NODES) {
                        emit(TreeDiscovery.Omitted(incomplete = true, limited = true))
                        return
                    }
                    val row = providerCall { Child(cursor.getString(0), cursor.getString(1), cursor.getString(2)) }
                    val id = row?.id
                    val name = row?.name
                    when {
                        row == null || id.isNullOrEmpty() || name.isNullOrEmpty() ->
                            emit(TreeDiscovery.Omitted(incomplete = true))
                        name.startsWith('.') -> emit(TreeDiscovery.Omitted(incomplete = false))
                        row.mime == Document.MIME_TYPE_DIR -> {
                            val tooDeep = directory.depth == MAX_TREE_DEPTH
                            if (!tooDeep && visited.add(id)) pending.addLast(Directory(id, directory.depth + 1))
                            else emit(TreeDiscovery.Omitted(incomplete = true, limited = tooDeep))
                        }
                        name.endsWith(".cbz", ignoreCase = true) || name.endsWith(".zip", ignoreCase = true) -> {
                            if (id == rootId) {
                                emit(TreeDiscovery.Omitted(incomplete = true))
                                continue
                            }
                            if (++candidates > BATCH_CANDIDATE_LIMIT) {
                                emit(TreeDiscovery.Omitted(incomplete = true, limited = true))
                                return
                            }
                            emit(TreeDiscovery.Candidate(treeDocumentReference(tree, id)))
                        }
                        else -> emit(TreeDiscovery.Omitted(incomplete = false))
                    }
                }
            }
        }
    }

    private suspend fun openChildren(tree: Uri, parent: String, signal: CancellationSignal): Cursor? =
        providerCall {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(uri, columns, null, null, null, signal)?.let { cursor ->
                try {
                    val extras = cursor.extras
                    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                        extras.containsKey(DocumentsContract.EXTRA_ERROR)
                    ) {
                        cursor.close()
                        null
                    } else cursor
                } catch (failure: RuntimeException) {
                    cursor.close()
                    throw failure
                }
            }
        }

    private suspend fun <T> providerCall(block: () -> T): T? = try {
        block()
    } catch (_: RuntimeException) {
        currentCoroutineContext().ensureActive()
        null
    }

    private data class Child(val id: String?, val name: String?, val mime: String?)
    private data class Directory(val id: String, val depth: Int)

    private companion object {
        const val MAX_TREE_DEPTH = 10
        const val MAX_TREE_NODES = 10_000
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    }
}
