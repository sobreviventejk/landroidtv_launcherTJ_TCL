package com.tvlauncher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small, privacy-friendly event log kept inside the app's private storage.
 *
 * The TV has no easy way to read logcat, so the launcher records what matters for support
 * (start, settings changed, Home service, backups, memory cleaning, errors) and lets the user
 * export everything as a TXT file from "Configurações avançadas".
 *
 * Nothing here ever reads other apps' content: it only stores messages written by this launcher.
 */
object CrashLog {

    private const val CRASH_FILE = "last_crash.txt"
    private const val EVENTS_FILE = "events.log"
    private const val MAX_CRASH_CHARS = 6000
    private const val MAX_EVENTS_BYTES = 128 * 1024
    private const val MAX_EVENTS_READ = 40_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stamp = timestamp()
                val text = "$stamp  [${thread.name}]\n" + throwable.stackTraceToString()
                File(appContext.filesDir, CRASH_FILE).writeText(text.take(MAX_CRASH_CHARS))
                log(appContext, "ERRO FATAL em ${thread.name}: ${throwable.javaClass.simpleName}")
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Appends one line to the event log. Safe to call from any thread. */
    @Synchronized
    fun log(context: Context, message: String) {
        try {
            val file = File(context.applicationContext.filesDir, EVENTS_FILE)
            if (file.exists() && file.length() > MAX_EVENTS_BYTES) {
                // Keep the newest half instead of letting the file grow forever.
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                file.writeText(lines.takeLast(1000).joinToString("\n") + "\n")
            }
            file.appendText("${timestamp()}  $message\n")
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String? {
        return try {
            val file = File(context.filesDir, CRASH_FILE)
            if (file.exists()) file.readText().ifBlank { null } else null
        } catch (_: Exception) {
            null
        }
    }

    /** Whole event log, newest last, capped so the dialog stays responsive. */
    fun readEvents(context: Context): String {
        return try {
            val file = File(context.filesDir, EVENTS_FILE)
            if (!file.exists()) return ""
            val text = file.readText()
            if (text.length > MAX_EVENTS_READ) text.takeLast(MAX_EVENTS_READ) else text
        } catch (_: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, CRASH_FILE).delete()
            File(context.filesDir, EVENTS_FILE).delete()
        } catch (_: Exception) {
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
