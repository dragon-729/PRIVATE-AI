# Test plan (run on a real phone)

I (the assistant that wrote this code) could not run any of these tests: no Android device or internet was available.
Treat every line below as **not yet verified** until you tick it.

## Automatic tests (run by GitHub on every build)
`gradle testDebugUnitTest` checks the pure logic: text chunking, UTF-8 token joining, WordPiece tokenizer,
RAG chunk overlap and the page-citation checker. The report is uploaded as the `unit-test-report` artifact.
Not included: Android instrumentation tests and native C++ tests (they need a device or emulator).

## Manual checklist
| # | Test | How | Expected | Done |
|---|---|---|---|---|
| 1 | First launch without internet | Install, turn on airplane mode, open the app | Home screen opens; “No model loaded”; no crash | ☐ |
| 2 | Model download | Wi-Fi on → Models → Download Llama 3.2 1B | Progress, then “Installed”; notification shows progress | ☐ |
| 3 | Model import | Models → Import GGUF file | File is copied, header-checked and loaded | ☐ |
| 4 | Load / unload | Load a model; Settings → Free memory now | “Model is out of RAM”; next message reloads it automatically | ☐ |
| 5 | Streaming | Send a message | Text appears word by word | ☐ |
| 6 | Stop | Press Stop mid-answer | Stops within about a second; partial answer is kept | ☐ |
| 7 | Reopen conversations | Close the app, reopen, History → open a chat | Messages are all there | ☐ |
| 8 | Summarize notes | Notes → New → paste text → AI tools → Summarize | Summary appears; long text shows “Part x of y” | ☐ |
| 9 | Import a PDF | PDF → Import PDF | Pages/sections counted; scanned PDF shows OCR pages | ☐ |
| 10 | Ask PDF questions | Open the PDF → ask something in it | Answer with [Page N] | ☐ |
| 11 | Verify citations | Open the cited page in a PDF reader | Statement is really on that page | ☐ |
| 12 | Airplane mode | After models are installed, airplane mode on → chat, notes, PDF, Mic | All work | ☐ |
| 13 | Not enough RAM | Try a 3B model on a 4 GB phone | Compatibility says “Too large” and loading is blocked | ☐ |
| 14 | Corrupted model | Rename any file to .gguf and import it | “This file is not a valid GGUF model” | ☐ |
| 15 | Interrupted download | Start a download, switch Wi-Fi off, then on | It waits, then resumes from the same byte | ☐ |
| 16 | Overheating | Run Performance → 3 runs × 256 tokens in a warm place | Fewer threads when warm; it stops with a message if too hot | ☐ |

## Things to measure and send back to me
- First-token latency and tokens per second for your model (Performance screen).
- App memory shown while a 1B and a 3B model are loaded.
- Whether 3K or 4K context is stable on your phone.
