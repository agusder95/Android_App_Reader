package com.agustin.sumi.data.local.document

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.agustin.sumi.domain.model.CbzSourceReference
import com.agustin.sumi.domain.model.LibraryDocumentIdentity
import com.agustin.sumi.domain.repository.LibraryDocumentMetadata
import com.agustin.sumi.domain.repository.LibraryDocumentMetadataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SafLibraryDocumentMetadataSource(private val context: Context) : LibraryDocumentMetadataSource {
    override suspend fun resolve(source: CbzSourceReference): LibraryDocumentMetadata? = withContext(Dispatchers.IO) {
        val uri = source.value.toUri()
        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (!DocumentsContract.isDocumentUri(context, uri)) return@withContext null
        if (isTreePath(uri)) {
            if (treeGrantUri(uri) == null) return@withContext null
        } else if (uri.pathSegments.size != 2) {
            return@withContext null
        }
        val id = DocumentsContract.getDocumentId(uri).takeIf { it.isNotEmpty() } ?: return@withContext null
        LibraryDocumentMetadata(LibraryDocumentIdentity(authority, id), displayName(uri))
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
