#ifndef AUDIO_ENGINE_H
#define AUDIO_ENGINE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* native audio engine: decodes, buffers and outputs audio; config can change while active */
typedef struct AudioEngine AudioEngine;

/* native -> UI log channel, opaque here (C++ type) */
typedef struct LogSink LogSink;

/* report device's native output sample rate + frames-per-burst (from AudioManager) to
 * oboe. its OpenSL ES backend (pre-AAudio devices) can't discover these itself and
 * needs them to size low-latency buffers; unused by AAudio backend. global, call before
 * creating engine.
 * see: https://github.com/google/oboe/blob/main/docs/GettingStarted.md#obtaining-optimal-latency */
void audio_engine_set_default_stream_values(int sampleRate, int framesPerBurst);

/* change engine config; any thread */
bool audio_engine_configure(AudioEngine *engine, int cushionMs, int percentilePct,
                            int oboeBufferFrames, bool forceSwAlac, bool realtimePriority,
                            bool lowLatency, bool benchmarkLog);

/* sender is switching audio formats */
void audio_engine_on_format(AudioEngine *engine, int ct, int spf);

/* begin or resume playout; false if stream fails to open; idempotent */
bool audio_engine_start(AudioEngine *engine);

/* pause playout, releases audio output devices while paused; idempotent */
void audio_engine_pause(AudioEngine *engine);

/* refresh + copy packed debug struct into dst; false if engine NULL or dstLen too small */
bool audio_engine_get_debug(AudioEngine *engine, void *dst, size_t dstLen);

/* stop + destroy engine; no concurrent or later audio_engine_decode calls allowed */
void audio_engine_destroy(AudioEngine *engine);

/* decode + play one encoded airplay packet; not concurrent with audio_engine_destroy */
void audio_engine_decode(AudioEngine *engine, const uint8_t *data, int len, int ct, int64_t pts_ns);

/* headless field probe: standalone engine, sine PCM through timeline into oboe.
 * writes a key=val report; returns 0 when the output stream opened. */
int audio_engine_test_run(int sample_rate, int channels, int seconds, char *report, int cap);

/* live-session counters as key=val string; -1 if engine NULL */
int audio_engine_debug_string(AudioEngine *engine, char *report, int cap);

/* headless AAC path probe: raw-ADTS test file -> engine -> counters */
int audio_engine_test_aac_file(const char *path, int ct, int spf, int sample_rate,
                               int channels, char *report, int cap);

#ifdef __cplusplus
}

#include <memory>

/* create engine; handle or NULL */
extern "C++" AudioEngine *audio_engine_create(std::shared_ptr<LogSink> log, int sampleRate,
                                              int channels);
#endif

#endif  // AUDIO_ENGINE_H
