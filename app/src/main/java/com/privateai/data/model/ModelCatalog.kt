package com.privateai.data.model

enum class ModelKind { LLM, WHISPER }

data class CatalogModel(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,   // approximate; the real size is read from the server while downloading
    val minRamGb: Int,       // recommendation, not a guarantee
    val license: String,
    val magic: String = "GGUF",        // first 4 bytes of a valid file
    val kind: ModelKind = ModelKind.LLM,
)

object ModelCatalog {
    val all = listOf(
        CatalogModel(
            id = "llama32-1b",
            title = "Llama 3.2 1B Instruct",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            approxBytes = 808_000_000L, minRamGb = 6,
            license = "Llama 3.2 Community License (Meta) · Built with Llama · GGUF by bartowski",
        ),
        CatalogModel(
            id = "qwen25-15b",
            title = "Qwen2.5 1.5B Instruct",
            fileName = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            approxBytes = 940_000_000L, minRamGb = 6,
            license = "Apache 2.0 (Alibaba Qwen) · GGUF by bartowski",
        ),
        CatalogModel(
            id = "llama32-3b",
            title = "Llama 3.2 3B Instruct",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            approxBytes = 2_020_000_000L, minRamGb = 8,
            license = "Llama 3.2 Community License (Meta) · Built with Llama · GGUF by bartowski",
        ),
        CatalogModel(
            id = "qwen25-3b",
            title = "Qwen2.5 3B Instruct",
            fileName = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            approxBytes = 1_930_000_000L, minRamGb = 8,
            license = "Qwen Research License — non-commercial use only · GGUF by bartowski",
        ),
    )

    private const val WHISPER_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    /** Offline speech-to-text models for the Mic button (whisper.cpp ggml format). */
    val whisper = listOf(
        CatalogModel(
            id = "whisper-tiny-en", title = "Whisper Tiny (English only)", fileName = "ggml-tiny.en.bin",
            url = WHISPER_BASE + "ggml-tiny.en.bin", approxBytes = 77_700_000L, minRamGb = 3,
            license = "MIT (OpenAI Whisper) · ggml conversion by whisper.cpp", magic = "lmgg", kind = ModelKind.WHISPER,
        ),
        CatalogModel(
            id = "whisper-tiny", title = "Whisper Tiny (multilingual)", fileName = "ggml-tiny.bin",
            url = WHISPER_BASE + "ggml-tiny.bin", approxBytes = 77_700_000L, minRamGb = 3,
            license = "MIT (OpenAI Whisper) · ggml conversion by whisper.cpp", magic = "lmgg", kind = ModelKind.WHISPER,
        ),
        CatalogModel(
            id = "whisper-base", title = "Whisper Base (multilingual, more accurate)", fileName = "ggml-base.bin",
            url = WHISPER_BASE + "ggml-base.bin", approxBytes = 148_000_000L, minRamGb = 4,
            license = "MIT (OpenAI Whisper) · ggml conversion by whisper.cpp", magic = "lmgg", kind = ModelKind.WHISPER,
        ),
    )

    val everything: List<CatalogModel> get() = all + whisper
}

enum class Compat { OK, TIGHT, TOO_LARGE }

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Queued(val done: Long) : ModelState
    data class Downloading(val done: Long, val total: Long, val verifying: Boolean) : ModelState
    data class Paused(val done: Long) : ModelState
    data class Failed(val message: String, val done: Long) : ModelState
    data object Installed : ModelState
}

data class ModelItem(
    val key: String,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val sizeApprox: Boolean,
    val quant: String,
    val compat: Compat,
    val state: ModelState,
    val license: String?,
    val catalogId: String?,
    val imported: Boolean,
    val active: Boolean,
    val kind: ModelKind = ModelKind.LLM,
)

data class ModelsUi(
    val items: List<ModelItem> = emptyList(),
    val totalRamBytes: Long = 0,
    val freeStorageBytes: Long = 0,
    val whisperItems: List<ModelItem> = emptyList(),
)
