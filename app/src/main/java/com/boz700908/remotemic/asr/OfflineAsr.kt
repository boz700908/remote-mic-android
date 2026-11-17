package com.boz700908.remotemic.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * sherpa-onnx 离线识别器封装：完全本地、不联网。
 * 采样率固定 16 kHz 单声道（与 ATVV 语音编码一致，无需重采样）。
 */
class OfflineAsr(
    private val modelDir: File,
    private val spec: AsrModelSpec,
    private val numThreads: Int = 2,
) : AutoCloseable {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    fun ensureLoaded() {
        if (recognizer != null) return
        synchronized(this) {
            if (recognizer != null) return
            recognizer = OfflineRecognizer(config = buildConfig())
        }
    }

    private fun p(rel: String) = File(modelDir, rel).absolutePath

    private fun buildConfig(): OfflineRecognizerConfig {
        val feat = FeatureConfig(sampleRate = 16_000, featureDim = 80)
        val model = when (spec.kind) {
            AsrModelKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = p("model.int8.onnx"),
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = p("tokens.txt"),
                numThreads = numThreads,
            )

            AsrModelKind.PARAFORMER -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = p("model.int8.onnx")),
                tokens = p("tokens.txt"),
                modelType = "paraformer",
                numThreads = numThreads,
            )

            AsrModelKind.QWEN3_ASR -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = p("conv_frontend.onnx"),
                    encoder = p("encoder.int8.onnx"),
                    decoder = p("decoder.int8.onnx"),
                    tokenizer = File(modelDir, "tokenizer").absolutePath,
                    maxTotalLen = 512,
                    maxNewTokens = 128,
                ),
                tokens = "",
                numThreads = numThreads,
            )
        }
        return OfflineRecognizerConfig(featConfig = feat, modelConfig = model)
    }

    /**
     * @param samples [-1, 1] 的 16 kHz 单声道浮点采样
     * @return 识别文本（可能为空串）
     */
    fun recognize(samples: FloatArray): String {
        val r = recognizer ?: return ""
        if (samples.isEmpty()) return ""
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(samples, 16_000)
            r.decode(stream)
            r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        synchronized(this) {
            recognizer?.release()
            recognizer = null
        }
    }
}
