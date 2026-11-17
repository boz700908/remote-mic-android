package com.boz700908.remotemic.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** 把解出来的 16 kHz 单声道 PCM 直接播到扬声器（可选功能，方便确认音频链路通）。 */
class PcmPlayer {

    private var track: AudioTrack? = null

    val isRunning: Boolean get() = track != null

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = if (minBuf > 0) minBuf * 2 else 8192
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
                .also { it.play() }
        }.getOrNull()
    }

    fun write(samples: ShortArray) {
        val t = track ?: return
        if (samples.isEmpty()) return
        runCatching { t.write(samples, 0, samples.size) }
    }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
    }
}

/** 音量键：无 root 时只能改媒体音量，够用。 */
class VolumeController(private val manager: AudioManager) {
    fun up() = adjust(1)
    fun down() = adjust(-1)

    private fun adjust(dir: Int) {
        runCatching {
            manager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }
    }
}
