# Third-party software and models

Private AI is built on the following open-source projects. Each keeps its own license.

## Code libraries
| Project | Use | License |
|---|---|---|
| llama.cpp / ggml (ggml-org) | Runs the chat language models on the phone | MIT |
| whisper.cpp (ggml-org) | Offline speech recognition | MIT |
| ONNX Runtime (Microsoft) | Runs the embedding model for PDF search | MIT |
| PdfBox-Android (Tom Roush) | Reads text from PDFs | Apache-2.0 |
| Google ML Kit Text Recognition | On-device OCR for scanned pages | Google ML Kit terms |
| AndroidX (Compose, Room, WorkManager, DataStore, Lifecycle, Activity) | App framework | Apache-2.0 |
| Kotlin and kotlinx.coroutines | Language and concurrency | Apache-2.0 |

## Models (downloaded by the user, not bundled in the repository)
| Model | License | Notes |
|---|---|---|
| all-MiniLM-L6-v2 (sentence-transformers) | Apache-2.0 | Bundled into the APK at build time (int8 ONNX, about 23 MB) |
| OpenAI Whisper (ggml conversions by whisper.cpp) | MIT | Optional voice models |
| Llama 3.2 1B / 3B Instruct (Meta), GGUF by bartowski | Llama 3.2 Community License | **Built with Llama.** See https://www.llama.com/llama3_2/license/ |
| Qwen2.5 1.5B Instruct (Alibaba), GGUF by bartowski | Apache-2.0 | |
| Qwen2.5 3B Instruct (Alibaba), GGUF by bartowski | Qwen Research License | Non-commercial use only |

Models you import yourself keep their own licenses. Check them before using a model commercially.

## Your own code
The application source code in this repository is released under the MIT License (see LICENSE). Change it if you prefer another license.
