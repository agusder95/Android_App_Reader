package com.agustin.sumi.data.local.document

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.net.toUri
import com.agustin.sumi.domain.model.CbzImportError
import com.agustin.sumi.domain.model.CbzSourceReference
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface OpenedCbzDocument {
    val length: Long

    class Direct(val channel: FileChannel, override val length: Long) : OpenedCbzDocument
    class Temporary(val file: File, override val length: Long) : OpenedCbzDocument
}

internal class SafDocumentSource(context: Context) {
    private val resolver = context.contentResolver
    private val temporaryStore = TemporaryCbzStore(
        directory = File(context.cacheDir, "cbz-import"),
        availableBytes = { directory ->
            try {
                StatFs(directory.path).availableBytes
            } catch (_: IllegalArgumentException) {
                throw DocumentAccessException(CbzImportError.IO_FAILURE)
            }
        },
    ) {
        Log.w("SafDocumentSource", "Could not delete an import temporary")
    }

    /** Returns true for a new read grant. The caller serializes acquisitions per URI. */
    suspend fun persistReadPermission(source: CbzSourceReference): Boolean {
        var acquired: Uri? = null
        try {
            return documentIo {
                val uri = documentUri(source)
                // The library owns acquisition and cleanup of the shared grant.
                if (treeGrantUri(uri) != null) {
                    requirePersistedReadPermission(uri)
                    return@documentIo false
                }
                if (!hasPersistedReadPermission(uri)) {
                    try {
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                        throw DocumentAccessException(CbzImportError.PERSISTENT_PERMISSION_UNAVAILABLE)
                    }
                    acquired = uri
                }
                acquired != null
            }
        } catch (cancelled: CancellationException) {
            acquired?.let { uri ->
                try {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { releaseReadGrant(uri) }
                    }
                } catch (_: SecurityException) {
                    Log.w("SafDocumentSource", "Could not release a cancelled import grant")
                }
            }
            throw cancelled
        }
    }

    /** Release only a newly acquired grant that no other import or result uses. */
    suspend fun releaseReadPermission(source: CbzSourceReference) = documentIo {
        releaseReadGrant(documentUri(source))
    }

    suspend fun persistedReadTime(source: CbzSourceReference): Long? = documentIo {
        val uri = documentUri(source)
        resolver.persistedUriPermissions.firstOrNull { it.isReadPermission && it.uri == uri }?.persistedTime
    }

    suspend fun verifyAvailable(source: CbzSourceReference): Unit = documentIo {
        val uri = documentUri(source)
        requirePersistedReadPermission(uri)
        val descriptor = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw DocumentAccessException(CbzImportError.IO_FAILURE)
        descriptor.close()
    }

    /**
     * Reopens the original URI on each operation. The channel or temporary is valid
     * only inside [read]. Use [allowDirectAccess] only when the caller can validate a
     * seekable channel, and retry with a copy if direct access proves insufficient.
     */
    suspend fun <T> withDocument(
        source: CbzSourceReference,
        allowDirectAccess: Boolean = true,
        read: (OpenedCbzDocument) -> T,
    ): T = documentIo {
        val cancellation = currentCoroutineContext()
        val uri = documentUri(source)
        temporaryStore.recoverOrphans()
        cancellation.ensureActive()
        requirePersistedReadPermission(uri)

        val descriptor = try {
            resolver.openFileDescriptor(uri, "r")
                ?: throw DocumentAccessException(CbzImportError.IO_FAILURE)
        } catch (_: FileNotFoundException) {
            // Providers may serve a sub-section through an asset instead of a raw descriptor.
            null
        }
        if (descriptor == null) {
            val input = resolver.openInputStream(uri)
                ?: throw DocumentAccessException(CbzImportError.IO_FAILURE)
            temporaryStore.withCopy(input, cancellation::ensureActive) { file, length ->
                read(OpenedCbzDocument.Temporary(file, length))
            }
        } else {
            descriptor.use {
                cancellation.ensureActive()
                val length = seekableLength(descriptor)
                if (length != null && length > MAX_CBZ_BYTES) {
                    throw DocumentAccessException(CbzImportError.RESOURCE_LIMIT_EXCEEDED)
                }
                if (allowDirectAccess && length != null) {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        read(OpenedCbzDocument.Direct(input.channel, length))
                    }
                } else {
                    val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    temporaryStore.withCopy(input, cancellation::ensureActive, length) { file, copied ->
                        read(OpenedCbzDocument.Temporary(file, copied))
                    }
                }
            }
        }.also { cancellation.ensureActive() }
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }

    private fun requirePersistedReadPermission(uri: Uri) {
        val grant = if (isTreePath(uri)) {
            treeGrantUri(uri) ?: throw DocumentAccessException(CbzImportError.ACCESS_DENIED)
        } else {
            uri
        }
        if (!hasPersistedReadPermission(grant)) {
            throw DocumentAccessException(CbzImportError.ACCESS_DENIED)
        }
        // Opening the tree-scoped document URI makes DocumentsProvider check isChildDocument.
    }

    private fun releaseReadGrant(uri: Uri) =
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun documentUri(source: CbzSourceReference): Uri =
        source.value.toUri().also {
            if (it.scheme != ContentResolver.SCHEME_CONTENT || it.authority.isNullOrEmpty()) {
                throw DocumentAccessException(CbzImportError.SOURCE_UNAVAILABLE)
            }
        }

    private fun seekableLength(descriptor: ParcelFileDescriptor): Long? {
        val length = descriptor.statSize
        if (length < 0) return null
        return try {
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            length
        } catch (_: ErrnoException) {
            null
        }
    }

    private suspend fun <T> documentIo(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: DocumentAccessException) {
            ensureActive()
            throw error
        } catch (_: SecurityException) {
            ensureActive()
            throw DocumentAccessException(CbzImportError.ACCESS_DENIED)
        } catch (_: FileNotFoundException) {
            ensureActive()
            throw DocumentAccessException(CbzImportError.SOURCE_UNAVAILABLE)
        } catch (error: IOException) {
            ensureActive()
            val noSpace = generateSequence<Throwable>(error) { it.cause }.any {
                it is ErrnoException && (it.errno == OsConstants.ENOSPC || it.errno == OsConstants.EDQUOT)
            }
            throw DocumentAccessException(
                if (noSpace) CbzImportError.INSUFFICIENT_SPACE else CbzImportError.IO_FAILURE,
            )
        }
    }
}
