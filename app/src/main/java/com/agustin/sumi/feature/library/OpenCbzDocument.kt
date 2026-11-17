package com.agustin.sumi.feature.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

internal object OpenCbzDocument : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.comicbook+zip",
                    "application/zip",
                    "application/x-cbz",
                    "application/x-zip-compressed",
                ),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Multi-select variant; the single-document picker remains available for import and recovery. */
internal object OpenCbzDocuments : ActivityResultContract<Unit, List<Uri>?>() {
    override fun createIntent(context: Context, input: Unit): Intent = OpenCbzDocument.createIntent(context, input).apply {
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri>? {
        if (resultCode != Activity.RESULT_OK) return null
        intent ?: return emptyList()
        val selected = buildList {
            val clip = intent.clipData
            if (clip != null) {
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
            }
            intent.data?.let { data -> if (clip == null || (0 until clip.itemCount).none { clip.getItemAt(it).uri == data }) add(data) }
        }
        return selected
    }
}
