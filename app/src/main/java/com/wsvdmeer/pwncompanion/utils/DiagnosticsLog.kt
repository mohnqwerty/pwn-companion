package com.wsvdmeer.pwncompanion.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling, on-device diagnostics log. Every significant app event — Bluetooth tether
 * up/down, WebSocket connect/disconnect, incoming device messages, errors, crashes —
 * is appended here with an ISO timestamp so the operator can export and analyze it:
 * spot where the pwnagotchi reboots, where Bluetooth drops, and what the device is
 * doing while it idles in "listening" mode.
 *
 * Thread-safe and crash-proof: a failed write is swallowed so logging never breaks the
 * app. The file rotates once it passes [MAX_BYTES] (keeping one older file).
 */
object DiagnosticsLog {
    private const val LOG_DIR = "logs"
    private const val FILE_NAME = "pwncompanion.log"
    private const val OLD_NAME = "pwncompanion.old.log"
    private const val MAX_BYTES = 4L * 1024 * 1024   // rotate at 4 MB

    @Volatile private var appContext: Context? = null

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Bind the app context (idempotent). Called from NetworkService.initialize() / MainActivity. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun file(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, LOG_DIR).apply { if (!exists()) mkdirs() }
        return File(dir, FILE_NAME)
    }

    /** Append a timestamped line. Thread-safe; silently no-ops if not yet initialized. */
    fun log(tag: String, message: String) {
        val f = file() ?: return
        try {
            synchronized(this) {
                if (f.length() >= MAX_BYTES) rotate(f)
                FileWriter(f, true).use { w ->
                    w.append(fmt.format(Date()))
                        .append(" [").append(tag).append("] ")
                        .append(message).append('\n')
                }
            }
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }

    private fun rotate(f: File) {
        val old = File(f.parentFile, OLD_NAME)
        if (old.exists()) old.delete()
        f.renameTo(old)
    }

    /** Full chronological log text (may be large — prefer [readTail] for the UI). */
    fun readAll(): String {
        val f = file() ?: return "(log not initialized)"
        return try { if (f.exists()) f.readText() else "(empty log)" }
        catch (e: Exception) { "(read failed: ${e.message})" }
    }

    /** Last [maxLines] lines, oldest-first, for the in-app viewer. */
    fun readTail(maxLines: Int = 3000): String {
        val f = file() ?: return ""
        if (!f.exists()) return ""
        return try { f.readLines().takeLast(maxLines).joinToString("\n") }
        catch (_: Exception) { "" }
    }

    /** Delete both the live and rotated log files. */
    fun clear() {
        val f = file() ?: return
        synchronized(this) {
            f.delete()
            File(f.parentFile, OLD_NAME).delete()
        }
    }

    /** Combined size of the live + rotated log files, for the "share" hint. */
    fun sizeBytes(): Long {
        val f = file() ?: return 0
        val old = File(f.parentFile, OLD_NAME)
        return (if (f.exists()) f.length() else 0L) + (if (old.exists()) old.length() else 0L)
    }

    /** An ACTION_SEND intent attaching the live log via FileProvider, or null if empty/unavailable. */
    fun shareIntent(): Intent? {
        val ctx = appContext ?: return null
        val f = file() ?: return null
        if (!f.exists() || f.length() == 0L) return null
        return try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "pwncompanion diagnostics log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) { null }
    }
}
