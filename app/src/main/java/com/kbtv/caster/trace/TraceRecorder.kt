package com.kbtv.caster.trace

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Structured run recorder — VERIFICATION.md criterion 3.
 *
 * Appends one JSON object per line to `files/trace/cast.jsonl` (debug builds
 * only; release keeps Timber logs alone). Every workflow step worth asserting
 * on — command arrival, playback routing, player state changes, protocol
 * lifecycle, restart scheduling — lands here as a distinct event, so a run
 * leaves a machine-greppable record behind:
 *
 *   adb shell run-as com.kbtv.caster cat files/trace/cast.jsonl
 *
 * Hand-rolled JSON (no org.json) keeps this JVM-unit-testable. All writes run
 * on the injected [Executor] (a single daemon thread in production) and are
 * swallowed on failure — tracing must never break playback. Rollover keeps
 * [KEEP_GENERATIONS] generations of at most [DEFAULT_MAX_FILE_BYTES] each.
 */
class TraceRecorder(
    private val traceDir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val executor: Executor = defaultExecutor(),
) {

    /** Append an event; fields with null values are omitted. Never throws into the caller. */
    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val line = buildLine(name, fields)
        try {
            executor.execute {
                try {
                    val file = currentFile()
                    rolloverIfNeeded(file)
                    FileOutputStream(file, true).use {
                        it.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                    }
                } catch (e: Exception) {
                    // Swallowed on purpose: see class kdoc.
                }
            }
        } catch (_: Exception) {
            // Executor rejected — same policy.
        }
    }

    internal fun buildLine(name: String, fields: Array<out Pair<String, Any?>>): String {
        val sb = StringBuilder(128)
        sb.append("{\"ts\":").append(System.currentTimeMillis())
            .append(",\"sid\":").append(jsonString(sessionId))
            .append(",\"event\":").append(jsonString(name))
        for ((key, value) in fields) {
            if (value == null) continue
            sb.append(',').append(jsonString(key)).append(':').append(jsonValue(value))
        }
        return sb.append('}').toString()
    }

    private fun currentFile(): File {
        if (!traceDir.isDirectory) traceDir.mkdirs()
        return File(traceDir, FILE_NAME)
    }

    private fun rolloverIfNeeded(file: File) {
        if (!file.isFile || file.length() <= maxFileBytes) return
        for (i in KEEP_GENERATIONS - 1 downTo 1) {
            val src = File(traceDir, "$FILE_NAME.$i")
            if (src.isFile) src.renameTo(File(traceDir, "$FILE_NAME.${i + 1}"))
        }
        file.renameTo(File(traceDir, "$FILE_NAME.1"))
    }

    companion object {
        const val FILE_NAME = "cast.jsonl"
        const val DEFAULT_MAX_FILE_BYTES: Long = 1L shl 20 // 1 MiB
        const val KEEP_GENERATIONS = 3

        @Volatile private var instance: TraceRecorder? = null

        /**
         * Wire the process-wide recorder. [enabled]=false (release builds)
         * disables recording entirely — TraceRecorder.log becomes a no-op.
         */
        @JvmStatic
        fun init(traceParentDir: File, enabled: Boolean) {
            instance = if (enabled) TraceRecorder(File(traceParentDir, "trace")) else null
        }

        /** Record an event on the process-wide recorder; no-op when disabled or uninitialized. */
        @JvmStatic
        fun log(name: String, vararg fields: Pair<String, Any?>) {
            instance?.event(name, *fields)
        }

        private fun defaultExecutor(): Executor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "kbtv-trace").apply { isDaemon = true }
            }
    }

    private val sessionId: String = newSessionId()
}

/** Minimal JSON string escaping — internal so unit tests can exercise it directly. */
internal fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) {
        when {
            c == '"' || c == '\\' -> sb.append('\\').append(c)
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}

/** Scalars only; anything else degrades to an escaped string form. */
internal fun jsonValue(v: Any): String = when (v) {
    is String -> jsonString(v)
    is Boolean, is Number -> v.toString()
    else -> jsonString(v.toString())
}

private fun newSessionId(): String {
    val bytes = ByteArray(6)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
