package com.agustin.sumi.data.local.document

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.agustin.sumi.domain.model.CbzSourceReference

/** Builds a reopenable reference for a candidate discovered under a selected SAF tree. */
internal fun treeDocumentReference(tree: Uri, documentId: String): CbzSourceReference {
    val rootId = requireTreeRoot(tree)
    require(documentId.isNotEmpty() && documentId != rootId)
    return CbzSourceReference(DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString())
}

internal fun requireTreeRoot(tree: Uri): String {
    require(tree.scheme == ContentResolver.SCHEME_CONTENT)
    val authority = requireNotNull(tree.authority)
    require(authority.isNotEmpty())
    require(isTreePath(tree) && tree.pathSegments.size == 2)
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    require(tree == DocumentsContract.buildTreeDocumentUri(authority, rootId))
    require(rootId.isNotEmpty())
    return rootId
}

/** Identifies the exact URI that holds the shared grant. The provider enforces membership on open. */
internal fun treeGrantUri(document: Uri): Uri? {
    if (document.scheme != ContentResolver.SCHEME_CONTENT || !isTreePath(document)) return null
    val authority = document.authority ?: return null
    val path = document.pathSegments
    if (path.size != 4 || path[2] != "document") return null
    val rootId = DocumentsContract.getTreeDocumentId(document)
    val documentId = DocumentsContract.getDocumentId(document)
    if (rootId.isEmpty() || documentId.isEmpty() || rootId == documentId) return null
    val tree = DocumentsContract.buildTreeDocumentUri(authority, rootId)
    return tree.takeIf { DocumentsContract.buildDocumentUriUsingTree(it, documentId) == document }
}

internal fun CbzSourceReference.treeGrantReference(): CbzSourceReference? =
    treeGrantUri(value.toUri())?.let { CbzSourceReference(it.toString()) }

// The path selects the URI format only. DocumentsContract.isTreeUri requires API 24.
internal fun isTreePath(uri: Uri): Boolean = uri.pathSegments.firstOrNull() == "tree"
