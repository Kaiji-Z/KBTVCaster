package com.kbtv.caster.service.protocol

import android.content.Context
import android.net.Uri

/**
 * A casting protocol that the coordinator service can start and stop.
 *
 * Each protocol discovers itself on the network, receives play/control
 * commands from a sender device, and translates them into [PlaybackCommand]s
 * forwarded to the shared ExoPlayer owned by CastCoordinatorService.
 *
 * Implementations: [DlnaProtocol], plus the flag-gated AirPlayMirrorProtocol.
 * New protocols plug in here without the service knowing their transport
 * details.
 */
interface CastProtocol {

    /** Human-readable name for logging and status, e.g. "DLNA". */
    val name: String

    /**
     * Bind/start the protocol on the network. Called once during service start.
     * Must not throw — failures are logged and the protocol is left stopped.
     *
     * @param context  application context
     * @param sink     where to forward play/control commands (the coordinator service)
     */
    fun start(context: Context, sink: PlaybackSink)

    /** Release all resources (sockets, service bindings, mDNS registrations). Idempotent. */
    fun stop()

    /** Commands a protocol can ask the shared player to perform. */
    sealed class PlaybackCommand {
        data class Play(val uri: Uri) : PlaybackCommand()
        data object Pause : PlaybackCommand()
        data object Resume : PlaybackCommand()
        data object Stop : PlaybackCommand()
        data class Seek(val positionMs: Long) : PlaybackCommand()
    }

    /** The sink receives [PlaybackCommand]s; the protocol never touches ExoPlayer directly. */
    fun interface PlaybackSink {
        fun onCommand(command: PlaybackCommand)
    }
}
