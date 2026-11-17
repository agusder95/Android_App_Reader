package com.agustin.sumi.data.local.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.agustin.sumi.domain.model.LibraryEntryId
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class CoverCache(
    private val directory: File,
    private val diskLimit: Long = 256L * 1024 * 1024,
    private val memoryLimit: Int = 32 * 1024 * 1024,
) {
    private val memory = object : LruCache<String, Bitmap>(memoryLimit) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    val retainedBytes: Int get() = memory.size()

    fun trim() = memory.resize((memoryLimit / 4).coerceAtLeast(1))

    fun restoreMemoryBudget() = memory.resize(memoryLimit)

    // Disk operations are called under the shared CBZ work gate.
    fun read(key: String): Bitmap? {
        val file = File(directory, "$key.png")
        val cached = memory.get(key)
        if (cached != null) {
            if (file.isFile) file.setLastModified(System.currentTimeMillis())
            return cached
        }
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val bitmap = if (bounds.outWidth in 1..COVER_EDGE && bounds.outHeight in 1..COVER_EDGE) {
            BitmapFactory.decodeFile(file.path)
        } else null
        if (bitmap == null) file.delete()
        else {
            file.setLastModified(System.currentTimeMillis())
            memory.put(key, bitmap)
        }
        return bitmap
    }

    fun recover() {
        val files = ownedFiles()
        files.filter { it.extension == "part" }.forEach(File::delete)
        diskBudget().makeRoom(0)
    }

    fun remove(id: LibraryEntryId): Boolean = try {
        val prefix = "${id.value}-"
        memory.snapshot().keys.filter { it.startsWith(prefix) || LEGACY_KEY.matches(it) }.forEach(memory::remove)
        deleteFiles(listOwnedFiles().filter { it.name.startsWith(prefix) || LEGACY_KEY.matches(it.nameWithoutExtension) })
        true
    } catch (_: IOException) {
        false
    }

    suspend fun reconcile(keep: suspend (LibraryEntryId) -> Boolean) {
        val files = listOwnedFiles()
        val obsolete = mutableListOf<File>()
        for ((rawId, entries) in files.groupBy { it.name.substringBefore('-', "") }) {
            val id = rawId.toLongOrNull()
            if (id == null || !keep(LibraryEntryId(id))) obsolete.addAll(entries)
            else obsolete.addAll(entries.filter { it.extension == "part" })
        }
        obsolete.forEach { memory.remove(it.nameWithoutExtension) }
        deleteFiles(obsolete)
    }

    fun write(key: String, bitmap: Bitmap, check: () -> Unit) {
        check()
        if ((!directory.isDirectory && !directory.mkdirs()) || diskLimit <= 0) {
            memory.put(key, bitmap)
            return
        }
        val destination = File(directory, "$key.png")
        val partial = File(directory, "$key.part")
        val budget = diskBudget()
        try {
            val complete = partial.outputStream().use { output ->
                val bounded = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        check()
                        if (!budget.makeRoom(length.toLong())) throw IOException("Cover cache quota")
                        output.write(bytes, offset, length)
                        budget.used += length
                    }
                }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)
            }
            check()
            if (complete) partial.renameTo(destination)
        } catch (_: IOException) {
            // A generated bitmap remains usable when disk caching fails.
        } finally {
            partial.delete()
        }
        check()
        memory.put(key, bitmap)
    }

    private fun ownedFiles() = directory.listFiles().orEmpty().filter { it.isFile && FILE_NAME.matches(it.name) }

    private fun listOwnedFiles(): List<File> {
        val files = directory.listFiles()
        if (files == null && directory.exists()) throw IOException("Could not list the cover cache")
        return files.orEmpty().filter { it.isFile && FILE_NAME.matches(it.name) }
    }

    private fun deleteFiles(files: List<File>) {
        val failed = files.filterNot { it.delete() || !it.exists() }
        if (failed.isNotEmpty()) throw IOException("Could not remove all obsolete covers")
    }

    private fun diskBudget(): DiskBudget {
        val files = ownedFiles()
        return DiskBudget(files.sumOf(File::length), files.filter { it.extension == "png" }.sortedBy(File::lastModified).iterator())
    }

    private inner class DiskBudget(var used: Long, private val oldest: Iterator<File>) {
        fun makeRoom(bytes: Long): Boolean {
            while (used + bytes > diskLimit && oldest.hasNext()) {
                val file = oldest.next()
                val size = file.length()
                if (file.delete()) used -= size
            }
            return used + bytes <= diskLimit
        }
    }

    private companion object {
        val LEGACY_KEY = Regex("[a-f0-9]{64}")
        val FILE_NAME = Regex("(?:[1-9][0-9]*-)?[a-f0-9]{64}\\.(png|part)")
    }
}
