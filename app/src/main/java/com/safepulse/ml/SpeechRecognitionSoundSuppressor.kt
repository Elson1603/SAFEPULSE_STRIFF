package com.safepulse.ml

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Android's SpeechRecognizer can play a short system tone when recognition
 * starts. There is no official API flag to disable it, so this class briefly
 * mutes likely tone streams and restores their previous state immediately.
 */
class SpeechRecognitionSoundSuppressor(context: Context) {

    companion object {
        private const val TAG = "SpeechSoundSuppressor"
        private const val DEFAULT_SUPPRESS_MS = 500L
    }

    private data class StreamSnapshot(
        val stream: Int,
        val volume: Int,
        val wasMuted: Boolean
    )

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val restoreRunnable = Runnable { restore() }
    private var snapshots: List<StreamSnapshot>? = null

    private val streamsToSuppress: List<Int> = buildList {
        add(AudioManager.STREAM_SYSTEM)
        add(AudioManager.STREAM_NOTIFICATION)
    }.distinct()

    fun suppressStartTone(durationMs: Long = DEFAULT_SUPPRESS_MS) {
        handler.removeCallbacks(restoreRunnable)

        if (snapshots == null) {
            snapshots = streamsToSuppress.mapNotNull { stream ->
                try {
                    val snapshot = StreamSnapshot(
                        stream = stream,
                        volume = audioManager.getStreamVolume(stream),
                        wasMuted = audioManager.isStreamMute(stream)
                    )
                    if (!snapshot.wasMuted) {
                        audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    }
                    snapshot
                } catch (e: Exception) {
                    Log.w(TAG, "Could not suppress recognition tone stream=$stream", e)
                    null
                }
            }.takeIf { it.isNotEmpty() }
        }

        handler.postDelayed(restoreRunnable, durationMs)
    }

    fun restore() {
        handler.removeCallbacks(restoreRunnable)
        val savedSnapshots = snapshots ?: return

        savedSnapshots.forEach { snapshot ->
            try {
                if (!snapshot.wasMuted) {
                    audioManager.adjustStreamVolume(snapshot.stream, AudioManager.ADJUST_UNMUTE, 0)
                    audioManager.setStreamVolume(snapshot.stream, snapshot.volume, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore recognition tone stream=${snapshot.stream}", e)
            }
        }
        snapshots = null
    }

    fun release() {
        restore()
    }
}
