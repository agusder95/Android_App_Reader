package com.agustin.sumi.data.local.document

import android.util.AtomicFile
import com.agustin.sumi.domain.model.CbzSourceReference
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** Accessed on IO under the library's shared work gate. */
internal class LibraryGrantRegistry(path: File) {
    private val file = AtomicFile(path)

    fun read(): MutableMap<CbzSourceReference, Long> {
        val bytes = try {
            file.readFully()
        } catch (failure: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw failure
            return mutableMapOf()
        }
        return try {
            val json = JSONObject(bytes.decodeToString())
            json.keys().asSequence().associateTo(mutableMapOf()) { CbzSourceReference(it) to json.getLong(it) }
        } catch (failure: JSONException) {
            throw IOException("Unreadable library grant registry", failure)
        }
    }

    fun write(grants: Map<CbzSourceReference, Long>) {
        val json = JSONObject()
        grants.forEach { (reference, time) -> json.put(reference.value, time) }
        val stream = file.startWrite()
        try {
            stream.write(json.toString().encodeToByteArray())
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: IOException) {
            file.failWrite(stream)
            throw failure
        }
        if (read() != grants) throw IOException("Library grant registry was not committed")
    }
}
