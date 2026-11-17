package com.boz700908.remotemic.protocol

/** IMA / DVI ADPCM 解码器（与参考实现同表、同顺序）。 */
class ImaAdpcmDecoder {
    var predictor: Int = 0
        private set
    var stepIndex: Int = 0
        private set

    /** RC001/RC003 高半字节优先；ARN9 固件低半字节优先。 */
    var lowNibbleFirst: Boolean = false

    fun reset(predictor: Int = 0, stepIndex: Int = 0) {
        this.predictor = predictor.coerceIn(-32768, 32767)
        this.stepIndex = stepIndex.coerceIn(0, 88)
    }

    fun decode(data: ByteArray): ShortArray {
        val out = ShortArray(data.size * 2)
        var i = 0
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (lowNibbleFirst) {
                out[i++] = decodeNibble(v and 0x0F)
                out[i++] = decodeNibble(v shr 4)
            } else {
                out[i++] = decodeNibble(v shr 4)
                out[i++] = decodeNibble(v and 0x0F)
            }
        }
        return out
    }

    private fun decodeNibble(nibble: Int): Short {
        val step = STEP_TABLE[stepIndex]
        var diff = step shr 3
        if (nibble and 1 != 0) diff += step shr 2
        if (nibble and 2 != 0) diff += step shr 1
        if (nibble and 4 != 0) diff += step
        predictor += if (nibble and 8 != 0) -diff else diff
        predictor = predictor.coerceIn(-32768, 32767)
        stepIndex = (stepIndex + INDEX_TABLE[nibble and 7]).coerceIn(0, 88)
        return predictor.toShort()
    }

    companion object {
        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130,
            143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449,
            494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411,
            1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026,
            4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
            11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
            27086, 29794, 32767,
        )
        private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    }
}

/** 把零散通知拼成定长音频帧；每帧独立解码需要各自保留 predictor 状态（参考实现按整段连续解码）。 */
class FrameAccumulator {
    private val pending = ArrayList<Byte>(512)

    fun append(data: ByteArray, frameSize: Int): List<ByteArray> {
        if (frameSize <= 0) return emptyList()
        for (b in data) pending.add(b)
        val frames = ArrayList<ByteArray>()
        while (pending.size >= frameSize) {
            val frame = ByteArray(frameSize)
            for (i in 0 until frameSize) frame[i] = pending[i]
            repeat(frameSize) { pending.removeAt(0) }
            frames.add(frame)
        }
        return frames
    }

    fun reset() = pending.clear()
}

object PcmPostprocessor {
    /** 三点平滑 + 增益（±24 dB 限额），与参考实现一致。 */
    fun process(input: ShortArray, gainDb: Double): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val filtered = IntArray(input.size) { input[it].toInt() }
        if (input.size >= 3) {
            for (i in 1 until input.size - 1) {
                filtered[i] = (input[i - 1] + 2 * input[i] + input[i + 1]) shr 2
            }
        }
        val gain = Math.pow(10.0, gainDb.coerceIn(-24.0, 24.0) / 20.0)
        return ShortArray(input.size) { i ->
            (filtered[i] * gain).toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }
    }

    fun peak(samples: ShortArray): Int {
        var peak = 0
        for (s in samples) {
            val v = Math.abs(s.toInt())
            if (v > peak) peak = v
        }
        return peak
    }
}
