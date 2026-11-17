package com.agustin.sumi.data.local.preferences

import android.content.SharedPreferences
import com.agustin.sumi.domain.model.LibrarySortOrder
import com.agustin.sumi.domain.repository.LibrarySortPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalLibrarySortPreference(private val preferences: SharedPreferences) : LibrarySortPreference {
    override suspend fun read(): LibrarySortOrder = withContext(Dispatchers.IO) {
        val stored = preferences.all[KEY]
        LibrarySortOrder.entries.firstOrNull { it.name == stored } ?: LibrarySortOrder.RECENTLY_ADDED
    }

    override suspend fun save(order: LibrarySortOrder): Boolean = withContext(Dispatchers.IO) {
        val editor = preferences.edit()
        editor.putString(KEY, order.name)
        editor.commit()
    }

    private companion object {
        const val KEY = "library_sort_order"
    }
}
