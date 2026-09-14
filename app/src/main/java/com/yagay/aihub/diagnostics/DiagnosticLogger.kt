package com.yagay.aihub.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.webkit.WebView
import com.yagay.aihub.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticLogger {
    private const val TAG_PREFIX = "AIHub"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_LOGCAT_LINES = 4000
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
                ensureDir()
            }
        }
        i("APP", "diagnostic_logger_initialized version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT}")
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)
    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    fun suggestedFileName(): String = synchronized(lock) {
        "AIHub-diagnostic-${fileNameFormat.format(Date())}.zip"
    }

    fun clear() {
        val ctx = appContext ?: return
        synchronized(lock) {
            File(ctx.filesDir, "diagnostics/aihub.log").delete()
            File(ctx.filesDir, "diagnostics/aihub.log.1").delete()
        }
        i("APP", "diagnostic_log_cleared")
    }

    fun export(context: Context, destination: Uri): Result<Unit> = runCatching {
        init(context)
        i("EXPORT", "diagnostic_export_started")
        val resolver = context.contentResolver
        val output = resolver.openOutputStream(destination, "w")
            ?: error("Unable to open export destination")

        output.use { stream ->
            ZipOutputStream(stream.buffered()).use { zip ->
                putText(zip, "diagnostic-info.txt", buildDiagnosticInfo(context))
                val dir = File(context.filesDir, "diagnostics")
                listOf("aihub.log.1", "aihub.log").forEach { name ->
                    val file = File(dir, name)
                    if (file.isFile) putFile(zip, file, name)
                }
                putText(zip, "logcat.txt", collectOwnProcessLogcat())
                putText(
                    zip,
                    "README.txt",
                    "AIHub diagnostic bundle.\n" +
                        "It intentionally does not export conversations, cookies, passwords, or authentication tokens.\n" +
                        "URLs are stripped of query strings/fragments where AIHub records them.\n"
                )
            }
        }
        i("EXPORT", "diagnostic_export_completed")
    }.onFailure {
        e("EXPORT", "diagnostic_export_failed type=${it.javaClass.simpleName} message=${scrub(it.message.orEmpty())}", it)
    }

    fun scrub(value: String, maxLength: Int = 1200): String {
        var out = value
        out = out.replace(
            Regex("(?i)(authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|id[_-]?token|token|code)=([^&\\s]+)")
        ) { match -> "${match.groupValues[1]}=<redacted>" }
        out = out.replace(
            Regex("(https?://[^\\s?#]+)(?:\\?[^\\s#]*)?(?:#[^\\s]*)?")
        ) { match -> "${match.groupValues[1]}?<redacted>" }
        if (out.length > maxLength) out = out.take(maxLength) + "…"
        return out
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val ctx = appContext ?: return
        val safeMessage = scrub(message.replace('\u0000', ' '))
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40)
        val line = buildString {
            append(synchronized(lock) { timestampFormat.format(Date()) })
            append(' ')
            append(level)
            append('/')
            append(safeTag)
            append(" [")
            append(Thread.currentThread().name)
            append("] ")
            append(safeMessage)
            if (throwable != null) {
                append('\n')
                append(scrub(Log.getStackTraceString(throwable), 12000))
            }
            append('\n')
        }

        runCatching {
            synchronized(lock) {
                val file = File(ctx.filesDir, "diagnostics/aihub.log")
                rotateIfNeeded(file)
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(line)
                }
            }
        }

        when (level) {
            "E" -> Log.e("$TAG_PREFIX/$safeTag", safeMessage, throwable)
            "W" -> Log.w("$TAG_PREFIX/$safeTag", safeMessage, throwable)
            "I" -> Log.i("$TAG_PREFIX/$safeTag", safeMessage)
            else -> Log.d("$TAG_PREFIX/$safeTag", safeMessage)
        }
    }

    private fun ensureDir(): File {
        val ctx = appContext ?: error("DiagnosticLogger not initialized")
        return File(ctx.filesDir, "diagnostics").apply { mkdirs() }
    }

    private fun rotateIfNeeded(file: File) {
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() >= MAX_LOG_BYTES) {
            val previous = File(file.parentFile, "${file.name}.1")
            previous.delete()
            file.renameTo(previous)
        }
    }

    private fun buildDiagnosticInfo(context: Context): String = buildString {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val webViewPackage = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val versionCode = if (packageInfo == null) {
            BuildConfig.VERSION_CODE.toLong()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        appendLine("generated=${synchronized(lock) { timestampFormat.format(Date()) }}")
        appendLine("package=${context.packageName}")
        appendLine("versionName=${packageInfo?.versionName ?: BuildConfig.VERSION_NAME}")
        appendLine("versionCode=$versionCode")
        appendLine("buildType=${BuildConfig.BUILD_TYPE}")
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("brand=${Build.BRAND}")
        appendLine("model=${Build.MODEL}")
        appendLine("device=${Build.DEVICE}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("android=${Build.VERSION.RELEASE}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
        appendLine("webViewPackage=${webViewPackage?.packageName ?: "unknown"}")
        appendLine("webViewVersion=${webViewPackage?.versionName ?: "unknown"}")
        appendLine("pid=${Process.myPid()}")
    }

    private fun collectOwnProcessLogcat(): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid=${Process.myPid()}", "-v", "threadtime")
            )
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            process.errorStream.close()
            process.destroy()
            lines.takeLast(MAX_LOGCAT_LINES).joinToString("\n") { scrub(it) } + "\n"
        }.getOrElse {
            "Unable to capture own-process logcat: ${it.javaClass.simpleName}: ${scrub(it.message.orEmpty())}\n"
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }
}
