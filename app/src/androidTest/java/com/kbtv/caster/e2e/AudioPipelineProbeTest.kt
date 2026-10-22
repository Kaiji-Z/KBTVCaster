package com.kbtv.caster.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headless silent-audio triage on the field TV: builds a standalone audio
 * engine and pushes sine PCM through timeline -> oboe. started=0 isolates the
 * output layer; healthy counters with started=1 point upstream (decoder /
 * RAOP callbacks). See audio_engine_test_run().
 */
@RunWith(AndroidJUnit4::class)
class AudioPipelineProbeTest {

    @Test
    fun outputLayerOpensAndDrains() {
        val report = io.github.jqssun.airplay.bridge.NativeBridge
            .nativeTestAudioPipeline(44100, 2, 3)
        android.util.Log.i("AudioProbe", "REPORT: " + report)
        assertTrue("audio probe: " + report, report.contains("started=1"))
    }

    /**
     * Software AAC decoder path: raw ADTS frames stripped to
     * AirPlay-shaped raw packets through the real engine decode(). The asset
     * is pushed by the test driver to /data/local/tmp/kbtv-aac-test.aac.
     */
    @Test
    fun aacSoftwareDecoderConsumesFrames() {
        val f = java.io.File("/data/local/tmp/kbtv-aac-test.aac")
        org.junit.Assume.assumeTrue("test asset missing (push it first)", f.isFile)
        val report = io.github.jqssun.airplay.bridge.NativeBridge
            .nativeTestAacFile(f.absolutePath, 4, 1024, 44100, 2) // AAC-LC
        android.util.Log.i("AudioProbe", "AAC REPORT: " + report)
        assertTrue("aac probe: " + report,
            report.contains("fed=") && !report.contains("fed=0") && report.contains("decodeErr=0"))
    }
}
