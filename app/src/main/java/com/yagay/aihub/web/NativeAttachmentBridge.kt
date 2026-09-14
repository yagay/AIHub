package com.yagay.aihub.web

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import com.yagay.aihub.diagnostics.DiagnosticLogger
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class NativeAttachmentBridge(private val context: Context) {
    data class Summary(val names: List<String>, val totalBytes: Long)

    private data class StagedFile(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val size: Long
    )

    private val lock = Any()
    private var staged = emptyList<StagedFile>()
    private var stagingDir: File? = null

    fun stage(uris: List<Uri>): Summary = synchronized(lock) {
        clearLocked()
        val dir = File(context.cacheDir, "aihub-native-files/${UUID.randomUUID()}").apply { mkdirs() }
        val files = mutableListOf<StagedFile>()

        uris.forEachIndexed { index, uri ->
            val meta = queryMeta(uri)
            val temp = File(dir, "file-$index.bin")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@forEachIndexed
            files += StagedFile(
                file = temp,
                displayName = meta.first.ifBlank { "attachment-${index + 1}" },
                mimeType = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" },
                size = temp.length()
            )
        }

        stagingDir = dir
        staged = files
        Summary(files.map { it.displayName }, files.sumOf { it.size })
    }

    fun clearNative() = synchronized(lock) { clearLocked() }

    @JavascriptInterface
    fun count(): Int = synchronized(lock) { staged.size }

    @JavascriptInterface
    fun name(index: Int): String = synchronized(lock) { staged.getOrNull(index)?.displayName.orEmpty() }

    @JavascriptInterface
    fun mime(index: Int): String = synchronized(lock) { staged.getOrNull(index)?.mimeType.orEmpty() }

    @JavascriptInterface
    fun size(index: Int): Long = synchronized(lock) { staged.getOrNull(index)?.size ?: 0L }

    @JavascriptInterface
    fun chunkCount(index: Int): Int = synchronized(lock) {
        val size = staged.getOrNull(index)?.size ?: return@synchronized 0
        ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
    }

    @JavascriptInterface
    fun chunk(index: Int, chunkIndex: Int): String = synchronized(lock) {
        val item = staged.getOrNull(index) ?: return@synchronized ""
        if (chunkIndex < 0) return@synchronized ""
        val offset = chunkIndex.toLong() * CHUNK_SIZE
        if (offset >= item.size) return@synchronized ""
        val length = minOf(CHUNK_SIZE.toLong(), item.size - offset).toInt()
        val bytes = ByteArray(length)
        RandomAccessFile(item.file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @JavascriptInterface
    fun diagnosticSnapshot(scope: String, phase: String, payload: String) {
        DiagnosticLogger.recordSnapshot(scope, phase, payload)
    }

    @JavascriptInterface
    fun diagnosticEvent(scope: String, event: String, detail: String) {
        DiagnosticLogger.d(
            "WEBTRACE",
            "scope=${DiagnosticLogger.scrub(scope, 120)} event=${DiagnosticLogger.scrub(event, 120)} detail=${DiagnosticLogger.scrub(detail, 1000)}"
        )
    }

    private fun queryMeta(uri: Uri): Pair<String, Long?> {
        var name = ""
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return name to size
    }

    private fun clearLocked() {
        staged = emptyList()
        stagingDir?.deleteRecursively()
        stagingDir = null
    }

    companion object {
        private const val CHUNK_SIZE = 256 * 1024
    }
}
