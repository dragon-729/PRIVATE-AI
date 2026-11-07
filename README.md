# Private AI

**Your AI. Your Device. Your Privacy.**
An offline AI assistant for Android: chat with small language models that run entirely on your phone — no account, no subscription, no cloud AI.

Built entirely on GitHub: no Android Studio needed.
Developer: [GitHub dragon-729](https://github.com/dragon-729) · Telegram [@YogeshBhai729](https://t.me/YogeshBhai729)

## Features
- **Chat** with streaming answers, Stop, edit, regenerate, copy, history, markdown and syntax-highlighted code.
- **Model manager**: download (resumable, integrity-checked) or import GGUF models; RAM/storage checks; voice models.
- **Smart Notes**: 9 AI tools (summarize, key points, action items, rewrite, grammar, translate, questions, study notes, explain simply); long notes handled in parts.
- **Chat with PDF**: local search (MiniLM embeddings + ONNX Runtime), optional OCR, answers with checked `[Page N]` citations, summaries, key-info extraction.
- **Personas**: 7 built-in + your own.
- **Voice input** (Whisper via whisper.cpp), fully offline once a voice model is downloaded.
- **Performance screen**: first-token latency, tokens/s, RAM, temperature, history.
- **Settings & Privacy Center**: theme, context length (2K–4K), CPU threads, thermal protection, automatic RAM release, storage tools.

## Build the APK on GitHub
1. Create a new GitHub repository and upload everything in this folder (keep the hidden `.github` folder).
2. Open **Actions → Build APK → Run workflow**. The first build takes 20–40 minutes (it compiles llama.cpp and whisper.cpp).
3. When it turns green, open the run and download **PrivateAI-debug-apk** from Artifacts. Unzip and install the APK.
4. If a step is red, open it and send the error text to whoever is helping you.

The debug APK is fully functional. Native code is always compiled with optimisations, even in the debug APK.

## Signed release APK (optional)
1. Make your repository **private**.
2. Run **Actions → Create signing key (run once)**, download the `signing-secrets` artifact, and copy its four values into
   **Settings → Secrets and variables → Actions** as secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Then delete the artifact.
3. Run **Build APK** again: it now also produces **PrivateAI-release-apk**. Keep a backup of those four values; without them you cannot update the app later.
4. Push a tag such as `v1.0.0` to publish the APK on the repository's Releases page.
5. Optional: add a repository *variable* `ENABLE_R8` = `true` to shrink the release APK. Test it carefully on your phone first.

## Using the app
Home → **Models** → download a model (for 6 GB phones: Llama 3.2 1B or Qwen2.5 1.5B; 8 GB+: the 3B models) → **Load** → chat.
After the model is on the phone, everything works in airplane mode. For PDFs, the search model is already inside the APK.

## Known limits
- Works best in English. The PDF search model and OCR are English/Latin-script; chat and voice (Whisper Base) can handle other languages, including Hindi, with varying quality.
- Small models can be wrong. Check important answers.
- One AI task runs at a time (chat, notes, PDF, voice and benchmark share the same engine).
- Untested on real hardware: see `TESTING.md`.

## Licenses
See `LICENSE` and `THIRD_PARTY_NOTICES.md`.
