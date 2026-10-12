package com.kbtv.caster.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/**
 * Unit tests for [TraceRecorder] — the structured JSONL run record
 * (VERIFICATION.md criterion 3). The inline executor makes writes
 * synchronous so assertions run after the "append" returns.
 */
class TraceRecorderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRecorder(dir: File, maxBytes: Long = 1L shl 20): TraceRecorder =
        TraceRecorder(dir, maxBytes, Executor { it.run() })

    private fun traceFile(dir: File): File = File(dir, TraceRecorder.FILE_NAME)

    @Test
    fun `events should append as one JSON object per line with ts sid and event`() {
        val dir = tmp.newFolder()
        val r = newRecorder(dir)
        r.event("command", "cmd" to "Play")
        r.event("playback_state", "state" to 4, "isPlaying" to false)
        val lines = traceFile(dir).readLines()
        assertEquals(2, lines.size)
        lines.forEach { line ->
            assertTrue("line must be a JSON object: $line", line.startsWith("{") && line.endsWith("}"))
            assertTrue("\"ts\":" in line)
            assertTrue("\"sid\":" in line)
            assertTrue("\"event\":" in line)
        }
        assertTrue("\"cmd\":\"Play\"" in lines[0])
        assertTrue("\"state\":4" in lines[1])
        assertTrue("\"isPlaying\":false" in lines[1])
    }

    @Test
    fun `string values should be json-escaped`() {
        val dir = tmp.newFolder()
        val r = newRecorder(dir)
        r.event("play", "uri" to "http://a/b\"c\\d\ne")
        val line = traceFile(dir).readText()
        assertTrue(line.contains("\"uri\":\"http://a/b\\\"c\\\\d\\ne\""))
    }

    @Test
    fun `null-valued fields should be omitted`() {
        val dir = tmp.newFolder()
        val r = newRecorder(dir)
        r.event("routing", "hls" to true, "ua" to null)
        val line = traceFile(dir).readText()
        assertTrue("\"hls\":true" in line)
        assertFalse("\"ua\"" in line)
    }

    @Test
    fun `file should roll over when maxFileBytes is exceeded`() {
        val dir = tmp.newFolder()
        val r = newRecorder(dir, maxBytes = 48)
        repeat(30) { r.event("tick", "i" to it) }
        val names = dir.list()!!.toList()
        assertTrue(TraceRecorder.FILE_NAME in names)
        assertTrue("${TraceRecorder.FILE_NAME}.1" in names)
    }

    @Test
    fun `disabled recorder via init should record nothing`() {
        val dir = tmp.newFolder()
        TraceRecorder.init(dir, enabled = false)
        TraceRecorder.log("anything", "k" to "v")
        assertFalse(File(dir, "trace/${TraceRecorder.FILE_NAME}").exists())
    }

    @Test
    fun `jsonString should escape control chars as unicode escapes`() {
        assertEquals("\"\\u0001\"", jsonString(1.toChar().toString()))
    }
}
