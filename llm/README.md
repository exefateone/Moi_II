# ИИ Паук — локальные LLM-модели

This directory is a documented model-import point for developers. Model weights are deliberately not committed into Git and are not bundled inside the source archive.

## Android runtime location

The installed app scans its app-specific folder:

`/storage/emulated/0/Android/data/com.iipauk.spider/files/llm/`

The exact external-storage path can vary by device. The built-in **Local LLM** screen also offers an import picker: select a local `.gguf` model and it is copied into the managed `llm` folder atomically. Models must be GGUF files built for a supported architecture. A filename extension alone is not enough; the first four bytes must be `GGUF`.

When Android restricts direct access to `Android/data`, use the in-app importer instead of changing permissions or using shell workarounds.

## Hardware and compatibility

The local inference engine is llama.cpp pinned to v0.6.0 through CMake FetchContent. Android builds target `arm64-v8a` and `x86_64`. The first implementation is CPU-first. Start with a small, quantized instruction-tuned model compatible with the phone's available RAM and whose licence permits your intended use. Model files may be multiple gigabytes; choose carefully and keep free device storage for temporary copies.

No model weights are included in this repository. The application reports local inference unavailable until the native engine is compiled and the user loads a valid model.

## What “self-learning” means here

ИИ Паук keeps a separate, on-device SQLite episodic memory of task outcomes and explicit feedback. Retrieved, scoped observations can influence future planning. This is behavioral adaptation via memory/retrieval; it is **not** online training or fine-tuning of a GGUF model's weights. The user can pause or erase the episodic memory.


## Optional first local smoke model

To download the compact Qwen2.5-0.5B-Instruct Q4_K_M model (about 491 MB), run from the project root on a network-enabled machine:

```bash
./scripts/download_qwen_smoke_model.sh
```

The script uses the official Hugging Face file URL and checks the expected SHA-256 before the download is considered complete. It puts the large `.gguf` file in ignored `llm/models/`; the weights are not part of the source archive. Copy the verified model to the phone, then use **ИИ Паук → Local LLM → Импортировать GGUF**. The in-app importer manages the Android-specific path. The 0.5B model is for checking the load/generate pipeline; it has limited instruction-following quality and should not be treated as final production model.
