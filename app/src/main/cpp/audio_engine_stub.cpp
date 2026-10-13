/*
 * M1 build-stage stub for the vendored audio engine (audio_engine.h ABI).
 * Mirroring video needs no audio; the real audio_engine.cpp (oboe +
 * ffmpeg ALAC, vendored from jqssun/android-airplay-server) returns in
 * milestone M4 unchanged — swap this file out of BRIDGE_SOURCES then.
 */
#include "audio_engine.h"
#include "log_sink.h"
#include <memory>

struct AudioEngine { int unused; };

void audio_engine_set_default_stream_values(int sampleRate, int framesPerBurst) {
    (void)sampleRate; (void)framesPerBurst;
}

AudioEngine *audio_engine_create(std::shared_ptr<LogSink> log, int sampleRate, int channels) {
    (void)log; (void)sampleRate; (void)channels;
    return NULL;
}

bool audio_engine_configure(AudioEngine *engine, int cushionMs, int percentilePct,
                            int oboeBufferFrames, bool forceSwAlac, bool realtimePriority,
                            bool lowLatency, bool benchmarkLog) {
    (void)engine; (void)cushionMs; (void)percentilePct; (void)oboeBufferFrames;
    (void)forceSwAlac; (void)realtimePriority; (void)lowLatency; (void)benchmarkLog;
    return false;
}

void audio_engine_on_format(AudioEngine *engine, int ct, int spf) {
    (void)engine; (void)ct; (void)spf;
}

bool audio_engine_start(AudioEngine *engine) { (void)engine; return false; }

void audio_engine_pause(AudioEngine *engine) { (void)engine; }

bool audio_engine_get_debug(AudioEngine *engine, void *dst, size_t dstLen) {
    (void)engine; (void)dst; (void)dstLen;
    return false;
}

void audio_engine_destroy(AudioEngine *engine) { (void)engine; }

void audio_engine_decode(AudioEngine *engine, const uint8_t *data, int len, int ct, int64_t pts_ns) {
    (void)engine; (void)data; (void)len; (void)ct; (void)pts_ns;
}
