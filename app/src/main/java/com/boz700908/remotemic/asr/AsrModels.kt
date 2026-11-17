package com.boz700908.remotemic.asr

/** 支持的三类离线识别模型（均由 sherpa-onnx 推理，全部本地运行）。 */
enum class AsrModelKind { SENSE_VOICE, QWEN3_ASR, PARAFORMER }

data class ModelFileSpec(val relativePath: String, val remoteUrl: String)

/**
 * 一个可下载的离线模型。所有文件都按 [ModelFileSpec.relativePath] 落在
 * filesDir/models/<id>/ 下，识别器只认本地路径。
 */
data class AsrModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val approxSizeMb: Int,
    val kind: AsrModelKind,
    val files: List<ModelFileSpec>,
)

object AsrModels {
    private const val SENSE_VOICE_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    /** 千问 Qwen3-ASR 0.6B（int8 导出），体积大但识别能力最强。 */
    private const val QWEN3_BASE =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main"

    private const val PARAFORMER_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main"

    /**
     * 默认模型：阿里 FunAudioLLM 的 SenseVoice-Small（中英日韩粤 + 情感/事件），
     * 228 MB，手机 CPU 上延迟低，适合语音输入这种短句场景。
     */
    val SENSE_VOICE = AsrModelSpec(
        id = "sense-voice-zh-en-ja-ko-yue-int8",
        displayName = "SenseVoice Small（默认）",
        description = "阿里 FunAudioLLM，中英日韩粤，228 MB，速度快",
        approxSizeMb = 229,
        kind = AsrModelKind.SENSE_VOICE,
        files = listOf(
            ModelFileSpec("model.int8.onnx", "$SENSE_VOICE_BASE/model.int8.onnx"),
            ModelFileSpec("tokens.txt", "$SENSE_VOICE_BASE/tokens.txt"),
        ),
    )

    /**
     * 千问 Qwen3-ASR 0.6B，约 938 MB。走同一套 sherpa-onnx 离线推理，
     * 支持中文、多语种、方言与中英混说，代价是内存与首次加载时间。
     */
    val QWEN3_ASR = AsrModelSpec(
        id = "qwen3-asr-0.6B-int8",
        displayName = "千问 Qwen3-ASR 0.6B",
        description = "通义千问语音识别，多语种/方言/中英混说，938 MB",
        approxSizeMb = 938,
        kind = AsrModelKind.QWEN3_ASR,
        files = listOf(
            ModelFileSpec("conv_frontend.onnx", "$QWEN3_BASE/conv_frontend.onnx"),
            ModelFileSpec("encoder.int8.onnx", "$QWEN3_BASE/encoder.int8.onnx"),
            ModelFileSpec("decoder.int8.onnx", "$QWEN3_BASE/decoder.int8.onnx"),
            ModelFileSpec("tokenizer/vocab.json", "$QWEN3_BASE/tokenizer/vocab.json"),
            ModelFileSpec("tokenizer/merges.txt", "$QWEN3_BASE/tokenizer/merges.txt"),
            ModelFileSpec(
                "tokenizer/tokenizer_config.json",
                "$QWEN3_BASE/tokenizer/tokenizer_config.json",
            ),
        ),
    )

    /** Paraformer 中文（老模型，作为轻量备选），约 230 MB。 */
    val PARAFORMER_ZH = AsrModelSpec(
        id = "paraformer-zh-int8",
        displayName = "Paraformer 中文（备选）",
        description = "阿里 FunASR 中文 Paraformer，230 MB",
        approxSizeMb = 230,
        kind = AsrModelKind.PARAFORMER,
        files = listOf(
            ModelFileSpec("model.int8.onnx", "$PARAFORMER_BASE/model.int8.onnx"),
            ModelFileSpec("tokens.txt", "$PARAFORMER_BASE/tokens.txt"),
        ),
    )

    val ALL = listOf(SENSE_VOICE, QWEN3_ASR, PARAFORMER_ZH)

    fun byId(id: String): AsrModelSpec = ALL.firstOrNull { it.id == id } ?: SENSE_VOICE
}
