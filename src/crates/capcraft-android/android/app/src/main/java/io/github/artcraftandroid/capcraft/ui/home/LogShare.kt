package io.github.artcraftandroid.capcraft.ui.home

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The most of the engine's own log file that goes into a report, in bytes (its end: the newest lines). */
private const val ENGINE_TAIL = 300_000L

/** How many of the app's latest logcat lines go into a report. */
private const val LOGCAT_LINES = 3000

/**
 * A text file to send when something goes wrong: the phone and app, the app's own logcat lines (the preview's
 * frame rate among them) and the end of the engine's log file. It holds file names and folders as the log wrote
 * them, never a video's contents, and goes nowhere until the person picks where to share it. Only the latest
 * report is kept.
 */
internal fun buildLogReport(context: Context): File {
    val folder = File(context.cacheDir, "logs").apply { mkdirs() }
    folder.listFiles().orEmpty().forEach { it.delete() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val out = File(folder, "capcraft-log-$stamp.txt")
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    out.bufferedWriter().use { w ->
        w.appendLine("CapCraft $version")
        w.appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        w.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        w.appendLine("Made: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        w.appendLine()
        w.appendLine("===== App log (logcat, latest $LOGCAT_LINES lines) =====")
        w.appendLine(appLogcat())
        w.appendLine()
        w.appendLine("===== Engine log file (the end of it) =====")
        w.appendLine(engineLogTail(context))
    }
    return out
}

/** This app's own latest logcat lines; it may read its own process's log without any permission. */
private fun appLogcat(): String = runCatching {
    val process = ProcessBuilder("logcat", "-d", "-t", LOGCAT_LINES.toString(), "--pid=${android.os.Process.myPid()}")
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
    text.ifBlank { "(logcat gave nothing)" }
}.getOrElse { "(logcat could not be read: $it)" }

/** The end of the newest engine log file, wherever the engine keeps its logs folder under the app's files. */
private fun engineLogTail(context: Context): String = runCatching {
    val newest = context.filesDir.walkTopDown()
        .maxDepth(4)
        .onEnter { it.name != "projects" }
        .filter { it.isFile && it.name.startsWith("concat-") && it.name.endsWith(".log") }
        .maxByOrNull { it.name }
        ?: return@runCatching "(no engine log file found)"
    RandomAccessFile(newest, "r").use { file ->
        val length = file.length()
        val start = (length - ENGINE_TAIL).coerceAtLeast(0L)
        file.seek(start)
        val bytes = ByteArray((length - start).toInt())
        file.readFully(bytes)
        "(${newest.name}${if (start > 0) ", from byte $start" else ""})\n" + String(bytes, Charsets.UTF_8)
    }
}.getOrElse { "(engine log could not be read: $it)" }

/** Opens the phone's share sheet for the report. */
internal fun shareLogReport(context: Context, report: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", report)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "CapCraft log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
