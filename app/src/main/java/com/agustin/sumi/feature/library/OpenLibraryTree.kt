package com.agustin.sumi.feature.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.agustin.sumi.domain.model.CbzSourceReference

internal object OpenLibraryTree : ActivityResultContract<Unit, LibraryTreeSelection?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): LibraryTreeSelection? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val uri = intent.data ?: return null
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        return LibraryTreeSelection(CbzSourceReference(uri.toString()), intent.flags and flags == flags)
    }
}

internal data class LibraryTreeSelection(val source: CbzSourceReference, val offersPersistentRead: Boolean)
