package com.kbtv.caster.service.protocol

import com.kbtv.caster.BuildConfig

/**
 * Runtime-visible feature flags, baked at build time from Gradle properties.
 *
 * Flag discipline (VERIFICATION.md §4/§5): every new behavior ships behind a
 * flag defaulting to false — false must reproduce the pre-change behavior.
 * Run the same suite both ways:
 *
 *   gradle test                                   # flag off (baseline)
 *   gradle test -Pkbtv.flags.playbackPlan=true    # flag on
 */
object FeatureFlags {

    /** Route playback via [PlaybackPlan.forUri] instead of the legacy inline branch in handlePlay. */
    val playbackPlanRouting: Boolean get() = BuildConfig.FLAG_PLAYBACK_PLAN_ROUTING

    /** Register the AirPlay protocol in the coordinator's protocol list (push subset first). */
    val airplayProtocol: Boolean get() = BuildConfig.FLAG_AIRPLAY_PROTOCOL
}
