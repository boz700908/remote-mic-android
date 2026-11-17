package com.boz700908.remotemic.protocol

import java.util.UUID

/**
 * ATVV（Android TV Voice）协议常量。小米蓝牙遥控器 2 / 2 Pro（RC001 / RC003）走这套。
 *
 * 特征角色（与参考实现一致，别弄反）：
 *  - TRANSMIT（…0002）：**主机写**，所有命令（能力协商 / 开麦 / 关麦 / 延长）都写这里
 *  - AUDIO（…0003）：**遥控器通知**，ADPCM 编码的音频分片
 *  - CONTROL（…0004）：**遥控器通知**，控制消息（0x0B 能力 / 0x04 开流 / 0x00 停流 / 0x0A 同步 / 0x08 请求开麦）
 */
object AtvvProtocol {
    val SERVICE: UUID = UUID.fromString("AB5E0001-5A21-4F05-BC7D-AF01F617B664")
    val TRANSMIT: UUID = UUID.fromString("AB5E0002-5A21-4F05-BC7D-AF01F617B664")
    val AUDIO: UUID = UUID.fromString("AB5E0003-5A21-4F05-BC7D-AF01F617B664")
    val CONTROL: UUID = UUID.fromString("AB5E0004-5A21-4F05-BC7D-AF01F617B664")

    /** 能力协商请求：GetCapabilities v1.0 */
    val GET_CAPABILITIES_V10 = byteArrayOf(0x0A, 0x01, 0x00, 0x00, 0x03, 0x03)

    /** 16 kHz 单声道：每毫秒 16 个样本。 */
    const val REAL_TIME_SAMPLES_PER_MS = 16.0

    fun supportsAudio(sampleRate: Int): Boolean = sampleRate == 16_000

    fun microphoneOpen(version: Int, codec: Int): ByteArray =
        if (version >= 0x0100) byteArrayOf(0x0C, 0x00) else byteArrayOf(0x0C, 0x00, codec.toByte())

    fun microphoneClose(version: Int, sessionId: Int): ByteArray =
        if (version >= 0x0100) byteArrayOf(0x0D, sessionId.toByte()) else byteArrayOf(0x0D)

    fun microphoneExtend(version: Int, sessionId: Int): ByteArray? =
        if (version >= 0x0100) byteArrayOf(0x0E, sessionId.toByte()) else null

    fun expectedFrameCount(durationMs: Long, frameSamples: Int): Int {
        if (durationMs <= 0 || frameSamples <= 0) return 0
        return Math.round(durationMs / (frameSamples / REAL_TIME_SAMPLES_PER_MS)).toInt()
    }

    fun audioCoveragePercent(receivedSamples: Int, durationMs: Long): Double {
        if (durationMs <= 0) return 0.0
        return receivedSamples / REAL_TIME_SAMPLES_PER_MS / durationMs * 100.0
    }
}

/** 0x0B 能力响应。 */
data class AtvvCapabilities(
    val version: Int,
    val codecs: Int,
    val interaction: Int,
    val frameSize: Int,
    val selectedCodec: Int,
    val sampleRate: Int,
) {
    val frameSamples: Int get() = frameSize * 2
    val isVoiceV11: Boolean get() = version >= 0x0100

    companion object {
        fun parse(data: ByteArray): AtvvCapabilities? {
            if (data.size < 7 || (data[0].toInt() and 0xFF) != 0x0B) return null
            val version = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            var codecs: Int
            var interaction: Int
            if (version >= 0x0100) {
                codecs = data[3].toInt() and 0xFF
                interaction = data[4].toInt() and 0xFF
                if (codecs == 0 && data.size >= 9 && (data[4].toInt() and 0x03) != 0) {
                    codecs = data[4].toInt() and 0xFF
                    interaction = 0x03
                }
            } else {
                if (data.size < 9) return null
                codecs = data[4].toInt() and 0xFF
                interaction = 0
            }
            val frameSize = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
            val selectedCodec = if (codecs and 0x02 != 0) 0x02 else 0x01
            return AtvvCapabilities(
                version = version,
                codecs = codecs,
                interaction = interaction,
                frameSize = if (frameSize == 0) 120 else frameSize,
                selectedCodec = selectedCodec,
                sampleRate = if (selectedCodec == 0x02) 16_000 else 8_000,
            )
        }
    }
}
